package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.vault.*;
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

  protected List<String> processOptions;
  protected final Map<String, List<String>> processRoute;

  private final Vault vault;
  private String cachedPassword;
  private boolean cached;
  private boolean conformed;

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
  protected Restic(Manager manager, Endpoint source, Endpoint destination, String password, String process, Map<String, List<String>> processRoute, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, include, exclude);
    this.vault = Vault.volume();
    this.processRoute = processRoute;
    this.processOptions = optional(process, processRoute);
    setPassword(password);
  }

  /// Constructor for restoring an existing task from the descriptor's fields;
  /// serves the concrete subclasses' Jackson creators. The password is not a part
  /// of the descriptor and is retrieved from the vault lazily upon the processing.
  protected Restic(Manager manager, Endpoint source, Endpoint destination, String tag, OffsetDateTime modified, List<String> include, List<String> exclude, Backend backend, Map<String, List<String>> processRoute) {
    super(manager, source, destination, tag, modified, include, exclude);
    this.vault = Vault.volume();
    this.processRoute = processRoute;
    this.processOptions = optional(backend == null ? null : backend.execute(), processRoute);
    this.cached = false;
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

  public List<String> processOptions() {
    return processOptions == null ? List.of() : java.util.Collections.unmodifiableList(processOptions);
  }

  public void setProcessOptions(String process) {
    this.processOptions = optional(process, processRoute);
    touch();
  }

  public void updatePassword(String newPassword) {
    setPassword(newPassword);
    touch();
  }

  /// Obscure and store the password with the vault provider caching the plain value.
  private void setPassword(String password) {
    if (password == null || password.isEmpty()) throw new BitferryError("password must be specified for task #" + tag);
    vault.storeToken(this, Passwords.encodePasswords(password, null));
    cachedPassword = password;
    cached = true;
  }

  /// Return the task's repository password, retrieving and caching it from the vault
  /// upon the first request (e.g. after the task has been restored from the storage).
  public String password() {
    if (!cached) {
      String token = vault.retrieveToken(this);
      if (token == null) throw new BitferryError("no stored password token for task #" + tag);
      cachedPassword = Passwords.decodeToken(token)[0];
      cached = true;
    }
    return cachedPassword;
  }

  /// Return the environment variables to be set for the task processing.
  public Map<String, String> processEnvironment() {
    return Map.of("RESTIC_PASSWORD", password());
  }

  /// Serialize the backend descriptor section; omitted when empty mirroring
  /// the reference implementation's `filter_nones` behavior.
  @JsonProperty("restic")
  Backend storeBackend() {
    return processOptions == null || processOptions.isEmpty() ? null : new Backend(processOptions, null, null);
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
}
