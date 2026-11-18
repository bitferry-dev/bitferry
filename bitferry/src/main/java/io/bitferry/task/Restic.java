package io.bitferry.task;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import io.bitferry.vault.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.*;

/// Restic-backend task base. The repository password is obtained upon the task creation,
/// obscured and stored within the vault of the volume hosting the unencrypted end of
/// the task; it is retrieved back upon the processing and never persisted in plain form.
public abstract class Restic extends Task {

  public static final String BACKEND = "restic";
  public static final String DIRECTOR = "-->";

  /// Processing options profiles; the null key denotes the default profile.
  public static final Map<String, List<String>> PROCESS =
    route(Map.of("default", List.of()), List.of());

  // The process options and the route ride the TaskBase (the hoisted plumbing)

  private final Vault vault;
  // The provider currently holding the token (resolved upon the retrieval); guides
  // the password updates back into the store the token lives in
  private Vault holding;
  private String cachedPassword;
  private boolean cached;
  private boolean conformed;

  // The transient runtime flag (the probe-conformance state): not a part of the
  // storage descriptor
  @JsonIgnore
  public boolean isConformed() {
    return conformed;
  }

  public void markConformed() {
    this.conformed = true;
  }

  /// Constructor for a new task instance; the processing options profile route is
  /// passed explicitly by the concrete classes. The password is obscured and stored
  /// with the vault provider right upon the creation.
  // The this-escape is deliberate mirroring the Task constructors: the vault provider
  // merely receives the task reference for the token storage keyed by the task tag
  @SuppressWarnings("this-escape")
  protected Restic(Manager manager, Endpoint source, Endpoint destination, String password, String process, Map<String, List<String>> processRoute, Collection<String> exclude) {
    this(manager, source, destination, password, process, processRoute, exclude, false);
  }

  /// The creation constructor with the explicit keyring storage request: the token
  /// travels to the OS keyring when the keyring is usable on the host; the per-volume
  /// vault keeps serving otherwise (the fixedness of the vault volume only hints the
  /// sensible default — see [Vault.select]). The include filters do not ride the base:
  /// the restic operations diverge on the inclusion support (the restore carries the
  /// [IncludeFilters] capability, the backup omits it), so the capable concrete
  /// classes hold their own include state.
  @SuppressWarnings("this-escape")
  protected Restic(Manager manager, Endpoint source, Endpoint destination, String password, String process, Map<String, List<String>> processRoute, Collection<String> exclude, boolean keyring) {
    super(manager, source, destination, exclude);
    this.vault = Vault.select(this, keyring);
    this.processRoute = processRoute;
    this.processOptions = optional(process, processRoute);
    setPassword(password);
  }

  /// Constructor for restoring an existing task from the descriptor's fields;
  /// serves the concrete subclasses' Jackson creators. The password is not a part
  /// of the descriptor and is retrieved from the vault lazily upon the processing.
  protected Restic(Manager manager, Endpoint source, Endpoint destination, String tag, OffsetDateTime modified, List<String> exclude, Backend backend, Map<String, List<String>> processRoute) {
    super(manager, source, destination, tag, modified, exclude);
    // The retrieval chain resolves the token regardless of the provider holding it
    this.vault = Vault.volume();
    this.processRoute = processRoute;
    this.processOptions = optional(backend == null ? null : backend.execute(), processRoute);
    this.cached = false;
  }

  /// The restic tasks keep the repository password within the vault.
  @Override
  public boolean usesVault() {
    return true;
  }

  /// The repository password rotates on the existing restic tasks.
  @Override
  public boolean supportsPasswordRotation() {
    return true;
  }

  /// Whether the task may change the repository key itself when the offered password
  /// does not match: the backup tasks own the rotation, the restore tasks merely share
  /// the repository and refuse.
  public boolean mayRotateRepositoryKey() {
    return true;
  }

  /// The restic backend descriptor section.
  public record Backend(
    @JsonProperty("execute") List<String> execute,
    @JsonProperty("forget") List<String> forget,
    @JsonProperty("check") List<String> check,
    @JsonProperty("serve") @JsonAlias("maintain") String serve
  ) {
    public Backend(List<String> execute, List<String> forget, List<String> check) {
      this(execute, forget, check, null);
    }
  }

  public void updatePassword(String newPassword) {
    setPassword(newPassword);
    touch();
  }

  /// Obscure and store the password with the vault provider caching the plain value.
  /// The target provider is the one currently holding the token (the password updates
  /// and the key rotations keep the storage kind), falling back to the creation-time
  /// selection; the stale entries in the other provider are dropped.
  private void setPassword(String password) {
    if (password == null || password.isEmpty()) throw new BitferryError("password must be specified for task " + tag);
    Vault target = holding != null ? holding : vault;
    target.storeToken(this, Passwords.encodePasswords(password, null));
    if (target != Vault.volume()) Vault.volume().deleteToken(this);
    if (target != Vault.keyring()) Vault.keyring().deleteToken(this);
    cachedPassword = password;
    cached = true;
  }

  /// Return the task's repository password, retrieving and caching it from the vault
  /// upon the first request (e.g. after the task has been restored from the storage).
  public String password() {
    if (!cached) {
      String token = retrieveToken();
      if (token == null) throw new BitferryError("no stored password token for task " + tag);
      cachedPassword = Passwords.decodeToken(token)[0];
      cached = true;
    }
    return cachedPassword;
  }

  /// Retrieve the obscured token trying the providers in turn: the creation-time
  /// selection first, then the other one — the classification drift (the volume
  /// moving between the media kinds) and the pre-keyring tasks keep resolving.
  private String retrieveToken() {
    String token = vault.retrieveToken(this);
    if (token != null) {
      holding = vault;
      return token;
    }
    for (Vault other : new Vault[]{Vault.volume(), Vault.keyring()}) {
      if (other == vault) continue;
      token = other.retrieveToken(this);
      if (token != null) {
        holding = other;
        return token;
      }
    }
    return null;
  }

  /// The provider currently holding (or, when the token is not yet resolved, the
  /// creation-time designated one).
  @Override
  public java.util.Optional<Vault> vaultProvider() {
    return java.util.Optional.ofNullable(holding != null ? holding : vault);
  }

  /// Move the stored repository token to the requested provider (the volume vault
  /// or the OS keyring): the token is read from the provider holding it, stored
  /// with the target one, and the stale entry is dropped from the other (mirroring
  /// the setPassword stale-drop). The already-there request is a no-op; migrating
  /// to the keyring requires a usable OS keyring.
  @Override
  public void migrateVault(boolean toKeyring) {
    Vault target = toKeyring ? Vault.keyring() : Vault.volume();
    if (target == vaultProvider().orElse(null)) return;
    if (target == Vault.keyring() && !KeyringVault.INSTANCE.available()) {
      throw new BitferryError("No usable OS keyring on this host - the password storage cannot be migrated");
    }
    String token = retrieveToken();
    if (token == null) throw new BitferryError("No stored password token for task " + tag);
    target.storeToken(this, token);
    if (target != Vault.volume()) Vault.volume().deleteToken(this);
    if (target != Vault.keyring()) Vault.keyring().deleteToken(this);
    holding = target;
  }

  /// Return the environment variables to be set for the task processing.
  public Map<String, String> processEnvironment() {
    return Map.of("RESTIC_PASSWORD", password());
  }

  /// Serialize the backend descriptor section; omitted when empty mirroring
  /// the reference implementation's `filter_nones` behavior.
  // The execution options are persisted explicitly: explicitly cleared options (empty or null)
  // persist as an empty execute list. Task restoration restores whatever options are read from
  // storage without falling back to @default profiles (which apply at task creation time only).
  @JsonProperty("restic")
  Backend storeBackend() {
    return new Backend(processOptions == null || processOptions.isEmpty() ? List.of() : processOptions, null, null);
  }

  @Override
  public String backend() {
    return BACKEND;
  }

  @Override
  public String director() {
    return DIRECTOR;
  }

  /// Perform repository password conformance check against the existing repository.
  @Override
  protected void conform() {
    manager.backendExecutor().conform(this);
  }

  /// The endpoint holding the restic repository (the destination of the backup tasks,
  /// the source of the restore ones).
  public abstract Endpoint repoEndpoint();

  /// Whether the two repo path strings denote the same repository: when both paths
  /// exist on disk, the file system is the authority (Files.isSameFile resolves the
  /// letter case, the symlinks and the bind mounts exactly - the case-insensitive
  /// APFS/HFS+ of the macOS hosts and the FAT/exFAT/NTFS mounts of the Linux ones
  /// must not defeat the paired-task password propagation nor the co-repository task
  /// discovery regardless of the host OS). The not-yet-created or unresolvable paths
  /// fall back to the normalized absolute textual form, folding the case on the
  /// case-insensitive host platforms (the trailing separators, the relative segments
  /// and the letter case must not split the repository identities). The non-parseable
  /// forms (the remote protocol specs) fall back to the exact string equality.
  public static boolean sameRepository(String a, String b) {
    String na = normalizeRepoPath(a);
    String nb = normalizeRepoPath(b);
    if (na == null || nb == null) return a.equals(b);
    try {
      if (java.nio.file.Files.exists(java.nio.file.Path.of(na)) && java.nio.file.Files.exists(java.nio.file.Path.of(nb))) {
        return java.nio.file.Files.isSameFile(java.nio.file.Path.of(na), java.nio.file.Path.of(nb));
      }
    } catch (java.io.IOException e) {
      // The stat failure (an unreadable parent, a broken mount): the textual form decides
    }
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    boolean caseInsensitive = os.contains("windows") || os.contains("mac os") || os.contains("darwin");
    return caseInsensitive ? na.equalsIgnoreCase(nb) : na.equals(nb);
  }

  private static String normalizeRepoPath(String path) {
    try {
      return java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
    } catch (java.nio.file.InvalidPathException e) {
      return null;
    }
  }
}
