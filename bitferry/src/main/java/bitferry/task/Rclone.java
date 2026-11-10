package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.vault.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.*;

/// Rclone-backend task base. The task processing is optionally protected with the
/// rclone crypt encryption: the [Encrypt] mode appends the crypt tail making rclone
/// write the encrypted replica into the destination while the [Decrypt] mode makes
/// rclone read the encrypted source. The crypt passwords are obscured and stored
/// within the vault of the volume hosting the unencrypted end of the task.
public abstract class Rclone extends Task {

  public static final String BACKEND = "rclone";
  public static final String DIRECTOR = "-->";

  /// Processing options profiles; the null key denotes the default profile.
  public static final Map<String, List<String>> PROCESS =
    route(Map.of("default", List.of("--metadata")), List.of("--metadata"));

  protected List<String> processOptions;
  protected Encryption encryption = NoCrypt.INSTANCE;

  /// Constructor for a new task instance.
  protected Rclone(Manager manager, Endpoint source, Endpoint destination, String process, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, include, exclude);
    this.processOptions = optional(process, PROCESS);
  }

  /// Constructor for restoring an existing task from the descriptor's fields;
  /// serves the concrete subclasses' Jackson creators. The encryption mode is
  /// restored from the backend descriptor with the passwords retrieved lazily
  /// from the vault upon the processing.
  // The this-escape is deliberate mirroring the Task constructors: the encryption mode
  // merely keeps the back-reference to its owning task for the lazy password retrieval
  @SuppressWarnings("this-escape")
  protected Rclone(Manager manager, Endpoint source, Endpoint destination, String tag, OffsetDateTime modified, List<String> include, List<String> exclude, Backend backend) {
    super(manager, source, destination, tag, modified, include, exclude);
    this.processOptions = optional(backend == null ? null : backend.execute(), PROCESS);
    this.encryption = Encryption.restore(this, backend);
  }

  /// The rclone backend descriptor section. Exactly one of the encryption
  /// descriptors may be present.
  public record Backend(List<String> execute, List<String> encrypt, List<String> decrypt) {}

  /// The crypt filename encoding profiles; the null key denotes no default profile.
  public static final Map<String, List<String>> PROFILE = route(Map.of(
    "default", List.of("--crypt-filename-encoding", "base32", "--crypt-filename-encryption", "standard"),
    "extended", List.of("--crypt-filename-encoding", "base32768", "--crypt-filename-encryption", "standard")
  ), null);

  public List<String> processOptions() {
    return processOptions == null ? List.of() : java.util.Collections.unmodifiableList(processOptions);
  }

  /// The backend operation arguments prepended to the rclone command line.
  /// The base implementation maps the operation discriminator verbatim;
  /// the concrete operations override it with their subcommands and modifiers.
  public List<String> operationArgs() {
    return List.of(operation());
  }

  /// Return the task's encryption mode.
  public Encryption encryption() {
    return encryption;
  }

  /// Configure the encrypt mode obscuring and storing the password pair with the
  /// vault provider right upon the call. The password pair is optional (e.g. the
  /// filename-only encryption).
  public Rclone encrypt(String password, String password2, List<String> options) {
    this.encryption = new Encrypt(this, options);
    this.encryption.storePasswords(password, password2);
    return this;
  }

  /// Configure the decrypt mode mirroring [encrypt].
  public Rclone decrypt(String password, String password2, List<String> options) {
    this.encryption = new Decrypt(this, options);
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
  @JsonProperty("rclone")
  Backend storeBackend() {
    List<String> execute = orNull(processOptions);
    List<String> encrypt = "encrypt".equals(encryption.operation()) ? orNull(encryption.options()) : null;
    List<String> decrypt = "decrypt".equals(encryption.operation()) ? orNull(encryption.options()) : null;
    return execute == null && encrypt == null && decrypt == null ? null : new Backend(execute, encrypt, decrypt);
  }

  @Override
  public String backend() {
    return BACKEND;
  }

  @Override
  public String director() {
    return DIRECTOR;
  }

  /// Perform candidate encryption options conformance test against existing backend data.
  @Override
  protected void conform() {
    manager.backendExecutor().conform(this);
  }

  @Override
  public List<List<String>> commands() {
    return manager.backendExecutor().commands(this);
  }

  /// The rclone crypt encryption mode base carrying the crypt options and the
  /// obscured password pair stored with the vault provider.
  public abstract static class Encryption {

    protected final List<String> options;
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
      if (backend.encrypt() != null && backend.decrypt() != null) throw new BitferryError("invalid encryption descriptor");
      if (backend.encrypt() != null) return new Encrypt(task, backend.encrypt());
      if (backend.decrypt() != null) return new Decrypt(task, backend.decrypt());
      return NoCrypt.INSTANCE;
    }

    /// Resolve the encryption profile specifier (a profile name, raw comma-separated
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

    /// Return the volume hosting the task's password vault: the volume of the
    /// unencrypted end of the task.
    /// Raises [BitferryError] for the endpoints residing outside Bitferry volumes.
    public abstract Volume vaultVolume(Rclone task);

    /// Obscure and store the password pair with the vault provider caching the plain values.
    void storePasswords(String password, String password2) {
      if (password == null || password.isEmpty()) throw new BitferryError("password must be specified for task #" + task().tag());
      vault().storeToken(task(), Passwords.encodePasswords(password, password2));
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
        String token = vault().retrieveToken(task());
        if (token == null) throw new BitferryError("no stored password token for task #" + task().tag());
        String[] passwords = Passwords.decodeToken(token);
        cachedPassword = passwords[0];
        cachedPassword2 = passwords[1];
        cached = true;
      }
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

    Vault vault() {
      return Vault.volume();
    }
  }

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

    @Override
    public Volume vaultVolume(Rclone task) {
      if (!(task.source() instanceof VolumeEndpoint sourceEndpoint)) throw new BitferryError("encryption requires the unencrypted endpoint to reside within a Bitferry volume");
      Volume volume = task.manager().volume(sourceEndpoint.vtag());
      if (volume == null) throw new BitferryError("no volume #" + sourceEndpoint.vtag() + " is registered");
      return volume;
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

    @Override
    public Volume vaultVolume(Rclone task) {
      if (!(task.destination() instanceof VolumeEndpoint destinationEndpoint)) throw new BitferryError("encryption requires the unencrypted endpoint to reside within a Bitferry volume");
      Volume volume = task.manager().volume(destinationEndpoint.vtag());
      if (volume == null) throw new BitferryError("no volume #" + destinationEndpoint.vtag() + " is registered");
      return volume;
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

    @Override
    public Volume vaultVolume(Rclone task) {
      throw new BitferryError("the directory endpoint must reside within a Bitferry volume");
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
