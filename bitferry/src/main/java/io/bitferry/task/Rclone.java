package io.bitferry.task;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import io.bitferry.vault.*;
import com.fasterxml.jackson.annotation.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.function.Consumer;
import java.util.*;

/// Rclone-backend task base. The task processing is optionally protected with the
/// rclone crypt encryption: the [Encrypt] mode appends the crypt tail making rclone
/// write the encrypted replica into the destination while the [Decrypt] mode makes
/// rclone read the encrypted source. The crypt passwords are obscured and stored
/// within the vault of the volume hosting the unencrypted end of the task.
/// The rclone operations filter by inclusion and exclusion both (the [IncludeFilters]
/// capability carrying class; the include state rides this base covering the family).
public abstract class Rclone extends Task implements IncludeFilters {

  public static final String BACKEND = "rclone";
  public static final String DIRECTOR = "-->";

  /// Processing options profiles; the null key denotes the default profile.
  public static final Map<String, List<String>> PROCESS =
    route(Map.of("default", List.of("--metadata")), List.of("--metadata"));

  protected Encryption encryption = NoCrypt.INSTANCE;

  // The [IncludeFilters] backing state: the rclone operations filter by inclusion and
  // exclusion both, the list riding this base class across the family (the interface
  // default methods carry the behavior over [includeState])
  protected final List<String> include = new ArrayList<>();

  /// Constructor for a new task instance.
  protected Rclone(Manager manager, Endpoint source, Endpoint destination, String process, Collection<String> include, Collection<String> exclude) {
    this(manager, source, destination, process, PROCESS, include, exclude);
  }

  /// Constructor for a new task instance with a custom processing options route.
  protected Rclone(Manager manager, Endpoint source, Endpoint destination, String process, Map<String, List<String>> route, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, exclude);
    this.include.addAll(include);
    this.processRoute = route != null ? route : PROCESS;
    this.processOptions = optional(process, processRoute);
  }

  /// Constructor for restoring an existing task from the descriptor's fields;
  /// serves the concrete subclasses' Jackson creators. The encryption mode is
  /// restored from the backend descriptor with the passwords retrieved lazily
  /// from the vault upon the processing.
  // The this-escape is deliberate mirroring the Task constructors: the encryption mode
  // merely keeps the back-reference to its owning task for the lazy password retrieval
  @SuppressWarnings("this-escape")
  protected Rclone(Manager manager, Endpoint source, Endpoint destination, String tag, OffsetDateTime modified, List<String> include, List<String> exclude, Backend backend) {
    this(manager, source, destination, tag, modified, include, exclude, backend, PROCESS);
  }

  /// Constructor for restoring an existing task with a custom processing options route.
  @SuppressWarnings("this-escape")
  protected Rclone(Manager manager, Endpoint source, Endpoint destination, String tag, OffsetDateTime modified, List<String> include, List<String> exclude, Backend backend, Map<String, List<String>> route) {
    super(manager, source, destination, tag, modified, exclude);
    this.include.addAll(include == null ? List.of() : include);
    this.processRoute = route != null ? route : PROCESS;
    this.processOptions = optional(backend == null ? null : backend.execute(), processRoute);
    this.encryption = Encryption.restore(this, backend);
  }

  /// The rclone backend descriptor section. Exactly one of the encryption
  /// descriptors may be present.
  public record Backend(List<String> execute, List<String> encrypt, List<String> decrypt) {}

  /// The [IncludeFilters] backing state (the behavior rides the interface defaults).
  @Override
  public List<String> includeState() {
    return include;
  }

  /// The crypt filename encoding profiles; the null key denotes no default profile.
  public static final Map<String, List<String>> PROFILE = route(Map.of(
    "default", List.of("--crypt-filename-encoding", "base32", "--crypt-filename-encryption", "standard"),
    "extended", List.of("--crypt-filename-encoding", "base32768", "--crypt-filename-encryption", "standard")
  ), null);

  /// The backend operation arguments prepended to the rclone command line.
  /// The base implementation maps the operation discriminator verbatim;
  /// the concrete operations override it with their subcommands and modifiers.
  public List<String> operationArgs() {
    return List.of(operation());
  }

  /// Pre-execution hook for concrete rclone operations (e.g. preparing working directories).
  public void prepare(List<String> endpoints) {}

  /// Return the human-readable operation label incorporating the encryption mode when active.
  @Override
  public String displayOperation() {
    String enc = encryption != null ? encryption.operation() : null;
    return enc != null ? enc + "+" + operation() : operation();
  }

  /// Return the task's encryption mode.
  public Encryption encryption() {
    return encryption;
  }

  /// The encrypted rclone tasks keep the crypt password pair within the vault.
  @Override
  public boolean usesVault() {
    return encryption != null && encryption.operation() != null;
  }

  /// The crypt token's provider (the encrypted tasks only).
  @Override
  public java.util.Optional<io.bitferry.vault.Vault> vaultProvider() {
    return usesVault() ? java.util.Optional.of(encryption.vaultProvider()) : java.util.Optional.empty();
  }

  /// Move the stored crypt token to the requested provider (the encrypted tasks only).
  @Override
  public void migrateVault(boolean toKeyring) {
    if (!usesVault()) throw new BitferryError("Task " + tag + " holds no vault token to migrate");
    encryption.migrateVault(toKeyring);
  }

  @Override
  public boolean supportsEncryption() {
    return true;
  }

  /// Configure the encryption mode on the task: the mode picks between the encrypt
  /// and the decrypt wiring.
  @Override
  public Task setEncryption(String mode, String password, String password2, List<String> options, boolean keyring) {
    if ("encrypt".equals(mode)) encrypt(password, password2, options, keyring);
    else decrypt(password, password2, options, keyring);
    return this;
  }

  /// Configure the encrypt mode obscuring and storing the password pair with the
  /// vault provider right upon the call. The password pair is optional (e.g. the
  /// filename-only encryption).
  public Rclone encrypt(String password, String password2, List<String> options) {
    return encrypt(password, password2, options, false);
  }

  /// The encrypt mode with the explicit keyring storage request: the password pair
  /// travels to the OS keyring when the keyring is usable.
  public Rclone encrypt(String password, String password2, List<String> options, boolean keyring) {
    this.encryption = new Encrypt(this, options);
    this.encryption.selectVault(keyring);
    this.encryption.storePasswords(password, password2);
    return this;
  }

  /// Configure the decrypt mode mirroring [encrypt].
  public Rclone decrypt(String password, String password2, List<String> options) {
    return decrypt(password, password2, options, false);
  }

  /// The decrypt mode with the explicit keyring storage request mirroring [encrypt]
  /// (the keyring honored when usable; the fixedness only hints the default).
  public Rclone decrypt(String password, String password2, List<String> options, boolean keyring) {
    this.encryption = new Decrypt(this, options);
    this.encryption.selectVault(keyring);
    this.encryption.storePasswords(password, password2);
    return this;
  }

  /// Return the environment variables to be set for the task processing.
  public Map<String, String> processEnvironment() {
    return encryption.processEnvironment();
  }

  /// Return the volume hosting the task's password vault: the volume of the
  /// unencrypted end of the task as resolved by its encryption mode.
  @Override
  public Volume vaultVolume() {
    return encryption.vaultVolume(this);
  }

  /// Serialize the backend descriptor section; omitted when empty mirroring
  /// the reference implementation's `filter_nones` behavior.
  // The execution options are persisted explicitly: explicitly cleared options (empty or null)
  // persist as an empty execute list. Task restoration restores whatever options are read from
  // storage without falling back to @default profiles (which apply at task creation time only).
  @JsonProperty("rclone")
  Backend storeBackend() {
    List<String> execute = processOptions == null || processOptions.isEmpty() ? List.of() : processOptions;
    List<String> encrypt = "encrypt".equals(encryption.operation()) ? orNull(encryption.options()) : null;
    List<String> decrypt = "decrypt".equals(encryption.operation()) ? orNull(encryption.options()) : null;
    return new Backend(execute, encrypt, decrypt);
  }

  @Override
  public String backend() {
    return BACKEND;
  }

  @Override
  public String director() {
    return DIRECTOR;
  }

  /// Return options to pass to the integrity check command (rclone check).
  public List<String> serveOptions() {
    return processOptions();
  }

  /// Perform candidate encryption options conformance test against existing backend data.
  @Override
  protected void conform() {
    manager.backendExecutor().conform(this);
  }

  /// The encryption summary for the breakdown listings: the crypt mode and the
  /// options from the Encryption hierarchy.
  @Override
  public java.util.Optional<String> encryptionSummary() {
    var enc = encryption;
    if (enc == null || enc.operation() == null) return java.util.Optional.of("none");
    String opts = encryption.options() == null || encryption.options().isEmpty() ? "" : " (options: " + String.join(" ", encryption.options()) + ")";
    return java.util.Optional.of(encryption.operation() + opts);
  }

  @Override
  public boolean supportsOperationChange() {
    return true;
  }

  /// Morph this rclone task into another rclone operation type (copy, update, replicate, equalize).
  @Override
  public Rclone morph(String newOperation) {
    if (operation().equals(newOperation)) return this;
    // The target operation is validated BEFORE any destructive side effect: an
    // invalid (or a rejected) operation name used to delete the equalize bisync
    // baseline (deleteWorkdirs) and only then fail, leaving the task permanently
    // un-baselined on disk
    if (!Set.of(Copy.OPERATION, Update.OPERATION, Replicate.OPERATION, Equalize.OPERATION).contains(newOperation)) {
      throw new BitferryError("Invalid rclone operation " + newOperation);
    }
    if (this instanceof Equalize) {
      deleteWorkdirs();
    }
    Rclone morphed = switch (newOperation) {
      case Copy.OPERATION -> new Copy(manager, source, destination, tag, BACKEND, mtime, include, exclude, storeBackend());
      case Update.OPERATION -> new Update(manager, source, destination, tag, BACKEND, mtime, include, exclude, storeBackend());
      case Replicate.OPERATION -> new Replicate(manager, source, destination, tag, BACKEND, mtime, include, exclude, storeBackend());
      case Equalize.OPERATION -> new Equalize(manager, source, destination, tag, BACKEND, mtime, include, exclude, storeBackend());
      default -> throw new BitferryError("Invalid rclone operation " + newOperation); // Unreachable (validated above), kept for the exhaustiveness
    };
    morphed.manual = this.manual;
    morphed.runnable = this.runnable;
    morphed.commitState = this.commitState;
    morphed.processOptions = this.processOptions;
    if (!(this.encryption instanceof NoCrypt) && !(morphed.encryption instanceof NoCrypt)) {
      morphed.encryption.copySecretsFrom(this.encryption);
    }
    morphed.touch();
    manager.replaceTask(morphed);
    return morphed;
  }

  /// Clean up any per-task hidden metadata directory (.bitferry.<task_id>) that may exist
  /// on either endpoint (e.g. from an equalize task, or a task converted from equalize).
  protected void deleteWorkdirs() {
    deleteWorkdir(source);
    deleteWorkdir(destination);
  }

  private void deleteWorkdir(Endpoint endpoint) {
    if (endpoint == null) return;
    try {
      Path dir = Path.of(endpoint.processOpts()).resolve(Bitferry.STORAGE + "." + tag());
      if (Files.isDirectory(dir)) {
        try (var stream = Files.walk(dir)) {
          stream.sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (IOException ignored) {}
          });
        }
      }
    } catch (Exception ignored) {}
  }

  @Override
  public void delete() {
    super.delete();
    deleteWorkdirs();
  }

  /// The tool dispatch (the double dispatch landing on the executor's rclone slot).
  @Override
  public boolean runWith(Executor executor, Consumer<String> output) {
    return executor.run(this, output);
  }

  @Override
  public boolean serveWith(Executor executor, Consumer<String> output) {
    return executor.serve(this, output);
  }

  @Override
  public List<List<String>> commandsWith(Executor executor) {
    return executor.commands(this);
  }

  @Override
  public List<List<String>> serveCommandsWith(Executor executor) {
    return executor.serveCommands(this);
  }

  @Override
  public List<List<String>> commands() {
    return manager.backendExecutor().commands(this);
  }

  /// The rclone crypt encryption mode base carrying the crypt options and the
  /// obscured password pair stored with the vault provider.
  public abstract static class Encryption {

    protected final List<String> options;
    // The selected (creation-time) and holding (retrieval-resolved) vault providers
    private Vault vault = Vault.volume();
    private Vault holding;
    private String cachedPassword;
    private String cachedPassword2;
    private boolean cached;

    protected Encryption(List<String> options) {
      this.options = options;
    }

    /// Restore the encryption mode from the backend descriptor dispatching
    /// over the encrypt/decrypt descriptor keys. Exactly one of them may be present.
    static Encryption restore(Rclone task, Backend backend) {
      if (backend == null) return NoCrypt.INSTANCE;
      if (backend.encrypt() != null && backend.decrypt() != null) throw new BitferryError("Invalid encryption descriptor");
      if (backend.encrypt() != null) return new Encrypt(task, backend.encrypt());
      if (backend.decrypt() != null) return new Decrypt(task, backend.decrypt());
      return NoCrypt.INSTANCE;
    }

    /// Resolve the encryption profile specifier (an @-prefixed profile name, raw comma-separated
    /// options or '-' for none) against the crypt filename encoding profiles.
    public static List<String> options(String specifier) {
      return optional(specifier, PROFILE);
    }

    /// The storage descriptor key of the mode (null for the no encryption mode).
    public abstract String operation();

    /// The crypt options to be appended to the rclone command line.
    public List<String> options() {
      return options == null ? List.of() : java.util.Collections.unmodifiableList(options);
    }

    /// The command line tail placing the ':crypt:' remote against the endpoints.
    public abstract List<String> processOpts(Rclone task);

    /// The backend-side path carrying the encrypted content (the destination of the
    /// encrypt mode, the source of the decrypt one); null for the no encryption mode
    /// (the conformance probing is then not applicable).
    public abstract String encryptedSide(Rclone task);

    /// Return the volume hosting the task's password vault: the volume of the
    /// unencrypted end of the task.
    /// Raises [BitferryError] for the endpoints residing outside Bitferry volumes.
    public abstract Volume vaultVolume(Rclone task);

    /// Obscure and store the password pair with the vault provider caching the plain
    /// values. The target provider is the one currently holding the token (the updates
    /// keep the storage kind), falling back to the creation-time selection; the stale
    /// entries in the other provider are dropped.
    void storePasswords(String password, String password2) {
      if (password == null || password.isEmpty()) throw new BitferryError("Password must be specified for task " + task().tag());
      Vault target = holding != null ? holding : vault;
      target.storeToken(task(), Passwords.encodePasswords(password, password2));
      if (target != Vault.volume()) Vault.volume().deleteToken(task());
      if (target != Vault.keyring()) Vault.keyring().deleteToken(task());
      cachedPassword = password;
      cachedPassword2 = password2;
      cached = true;
    }

    /// Return the task's crypt password, retrieving and caching it from the vault
    /// upon the first request (e.g. after the task has been restored from the storage).
    public String password() {
      cache();
      return cachedPassword;
    }

    /// Return the task's second crypt password (the filename encryption one) or null.
    public String password2() {
      cache();
      return cachedPassword2;
    }

    // Retrieve and cache the password pair from the vault upon the first request
    private void cache() {
      if (!cached) {
        String token = retrieveToken();
        if (token == null) throw new BitferryError("No stored password token for task " + task().tag());
        String[] passwords = Passwords.decodeToken(token);
        cachedPassword = passwords[0];
        cachedPassword2 = passwords[1];
        cached = true;
      }
    }

    /// Retrieve the obscured token trying the providers in turn: the creation-time
    /// selection first, then the other one — the classification drift and the
    /// pre-keyring tasks keep resolving.
    private String retrieveToken() {
      String token = vault.retrieveToken(task());
      if (token != null) {
        holding = vault;
        return token;
      }
      for (Vault other : new Vault[]{Vault.volume(), Vault.keyring()}) {
        if (other == vault) continue;
        token = other.retrieveToken(task());
        if (token != null) {
          holding = other;
          return token;
        }
      }
      return null;
    }

    /// Return the environment variables to be set for the task processing.
    /// Rclone expects the obscured (not plain) password tokens.
    public Map<String, String> processEnvironment() {
      Map<String, String> environment = new LinkedHashMap<>();
      environment.put("RCLONE_CRYPT_PASSWORD", Passwords.obscure(password()));
      if (password2() != null) environment.put("RCLONE_CRYPT_PASSWORD2", Passwords.obscure(password2()));
      return environment;
    }

    // The owning task and the vault provider
    Rclone task() {
      return task;
    }

    private Rclone task;

    Encryption bind(Rclone task) {
      this.task = task;
      return this;
    }

    /// Select the storage provider: the keyring when requested and usable (the
    /// vault volume fixed), the per-volume vault otherwise. Creation-time only.
    void selectVault(boolean keyringRequested) {
      this.vault = Vault.select(task(), keyringRequested);
    }

    void copySecretsFrom(Encryption other) {
      this.vault = other.vault;
      this.holding = other.holding;
      this.cachedPassword = other.cachedPassword;
      this.cachedPassword2 = other.cachedPassword2;
      this.cached = other.cached;
    }

    Vault vault() {
      return vault;
    }

    /// The provider currently holding (or, when the token is not yet resolved, the
    /// creation-time designated one).
    Vault vaultProvider() {
      return holding != null ? holding : vault;
    }

    /// Move the stored crypt token to the requested provider (the volume vault or
    /// the OS keyring): the token is read from the provider holding it, stored with
    /// the target one, and the stale entry is dropped from the other (mirroring the
    /// storePasswords stale-drop). The already-there request is a no-op; migrating
    /// to the keyring requires a usable OS keyring.
    void migrateVault(boolean toKeyring) {
      Vault target = toKeyring ? Vault.keyring() : Vault.volume();
      if (target == vaultProvider()) return;
      if (target == Vault.keyring() && !KeyringVault.INSTANCE.available()) {
        throw new BitferryError("No usable OS keyring on this host - the password storage cannot be migrated");
      }
      String token = retrieveToken();
      if (token == null) throw new BitferryError("No stored password token for task " + task().tag());
      target.storeToken(task(), token);
      if (target != Vault.volume()) Vault.volume().deleteToken(task());
      if (target != Vault.keyring()) Vault.keyring().deleteToken(task());
      holding = target;
    }
  }

  private static final String E = "Encryption requires the unencrypted endpoint to reside in a Bitferry volume";

  /// The encrypt mode: rclone writes the encrypted replica into the destination
  /// (moved into the ':crypt:' remote through --crypt-remote).
  public static final class Encrypt extends Encryption {

    public Encrypt(Rclone task, List<String> options) {
      super(options);
      bind(task);
    }

    @Override
    public String operation() {
      return "encrypt";
    }

    @Override
    public List<String> processOpts(Rclone task) {
      List<String> opts = new ArrayList<>(options());
      opts.add("--crypt-remote");
      opts.add(task.destination().processOpts());
      opts.add(task.source().processOpts());
      opts.add(":crypt:");
      return opts;
    }

    public String encryptedSide(Rclone task) {
      return task.destination().processOpts();
    }

    @Override
    public Volume vaultVolume(Rclone task) {
      return task.source().requireVolume(task.manager(), E);
    }
  }

  /// The decrypt mode: rclone reads the encrypted source through the ':crypt:' remote.
  public static final class Decrypt extends Encryption {

    public Decrypt(Rclone task, List<String> options) {
      super(options);
      bind(task);
    }

    @Override
    public String operation() {
      return "decrypt";
    }

    @Override
    public List<String> processOpts(Rclone task) {
      List<String> opts = new ArrayList<>(options());
      opts.add("--crypt-remote");
      opts.add(task.source().processOpts());
      opts.add(":crypt:");
      opts.add(task.destination().processOpts());
      return opts;
    }

    public String encryptedSide(Rclone task) {
      return task.source().processOpts();
    }

    @Override
    public Volume vaultVolume(Rclone task) {
      return task.destination().requireVolume(task.manager(), E);
    }
  }

  /// The no encryption mode.
  public static final class NoCrypt extends Encryption {

    public static final NoCrypt INSTANCE = new NoCrypt();

    private NoCrypt() {
      super(null);
    }

    @Override
    public String operation() {
      return null;
    }

    @Override
    public List<String> processOpts(Rclone task) {
      return List.of(task.source().processOpts(), task.destination().processOpts());
    }

    public String encryptedSide(Rclone task) {
      return null;
    }

    @Override
    public Volume vaultVolume(Rclone task) {
      throw new BitferryError("Directory endpoint must reside within a Bitferry volume");
    }

    @Override
    void storePasswords(String password, String password2) {
      // Encryption is not configured; nothing to store
    }

    @Override
    public Map<String, String> processEnvironment() {
      return Map.of();
    }
  }
}
