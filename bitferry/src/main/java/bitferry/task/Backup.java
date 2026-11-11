package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.*;

/// Incremental encrypted backup with snapshotting (Restic). The repository is initialized
/// upon the task creation unless attached to the existing one. The repository password is
/// kept within the vault of the volume hosting the source directory (the unencrypted end).
public class Backup extends Restic {

  public static final String OPERATION = "backup";

  /// Snapshot retention policy profiles; the null key denotes the default (skip) profile.
  public static final Map<String, List<String>> FORGET = route(Map.of(
    "default", List.of("--prune", "--keep-within-hourly", "24h", "--keep-within-daily", "7d", "--keep-within-weekly", "30d", "--keep-within-monthly", "1y", "--keep-within-yearly", "100y")
  ), null);

  /// Repository checking profiles; the null key denotes the default (skip) profile.
  public static final Map<String, List<String>> CHECK = route(Map.of(
    "default", List.of(),
    "full", List.of("--read-data")
  ), null);

  /// Maintenance cadence profiles; default is 7 days or 5 backups.
  public static final Map<String, Cadence> CADENCE = Cadence.PROFILES;

  /// The repository initialization mode: null for automatic creation/attachment,
  /// or true (--force) to wipe and recreate the existing repository.
  protected Boolean format;

  protected List<String> forget;
  protected List<String> check;
  protected Cadence cadence;

  private static final System.Logger LOG = System.getLogger(Backup.class.getName());

  /// Constructor for a new task instance with an explicit maintenance cadence.
  public Backup(Manager manager, Endpoint source, Endpoint destination, String password, Boolean format, String process, String forget, String check, String maintain, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, password, process, PROCESS, include, exclude);
    this.format = format;
    this.forget = optional(forget, FORGET);
    this.check = optional(check, CHECK);
    this.cadence = Cadence.parse(maintain);
  }

  /// Constructor for a new task instance (cadence defaults to @default).
  public Backup(Manager manager, Endpoint source, Endpoint destination, String password, Boolean format, String process, String forget, String check, Collection<String> include, Collection<String> exclude) {
    this(manager, source, destination, password, format, process, forget, check, null, include, exclude);
  }

  /// Jackson creator restoring an existing task from the storage descriptor;
  /// the manager is injected as it is not a part of the descriptor. The repository
  /// is not re-initialized (nor attached) upon the restoration.
  @JsonCreator
  Backup(
    @JacksonInject("manager") Manager manager,
    @JsonProperty("source") Endpoint source,
    @JsonProperty("destination") Endpoint destination,
    @JsonProperty("task") String tag,
    @JsonProperty("backend") String backend,
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("include") List<String> include,
    @JsonProperty("exclude") List<String> exclude,
    @JsonProperty("restic") Backend backendOpts
  ) {
    super(manager, source, destination, tag, modified, include, exclude, backendOpts, PROCESS);
    if (backend != null && !BACKEND.equals(backend)) {
      throw new BitferryError("invalid task backend: expected '" + BACKEND + "' but got '" + backend + "'");
    }
    this.format = null;
    this.forget = backendOpts == null ? null : backendOpts.forget();
    this.check = backendOpts == null ? null : backendOpts.check();
    this.cadence = backendOpts == null || backendOpts.maintain() == null
      ? Cadence.PROFILES.get("default")
      : Cadence.parse(backendOpts.maintain());
  }

  @Override
  public String operation() {
    return OPERATION;
  }

  public Boolean formatMode() {
    return format;
  }

  /// Return the volume hosting the task's password vault: the volume of the source
  /// directory (the unencrypted end of the backup task).
  /// Raises [BitferryError] if the source endpoint resides outside Bitferry volumes.
  @Override
  public Volume vaultVolume() {
    if (!(source instanceof VolumeEndpoint sourceEndpoint)) throw new BitferryError("backup directory endpoint must reside within a Bitferry volume");
    Volume volume = manager.volume(sourceEndpoint.vtag());
    if (volume == null) throw new BitferryError("no volume #" + sourceEndpoint.vtag() + " is registered");
    return volume;
  }

  public List<String> forget() {
    return forget == null ? null : java.util.Collections.unmodifiableList(forget);
  }

  public List<String> check() {
    return check == null ? null : java.util.Collections.unmodifiableList(check);
  }

  public Cadence cadence() {
    return cadence;
  }

  @Override
  @JsonProperty("restic")
  Backend storeBackend() {
    String m = cadence == null ? "-" : cadence.format();
    boolean empty = (processOptions == null || processOptions.isEmpty())
      && (forget == null || forget.isEmpty())
      && check == null
      && Cadence.PROFILES.get("default").equals(cadence);
    return empty ? null : new Backend(orNull(processOptions), orNull(forget), check, m);
  }

  /// Initialize the repository upon the task creation.
  @Override
  protected void format() {
    manager.backendExecutor().format(this);
  }

  @Override
  public List<List<String>> commands() {
    return manager.backendExecutor().commands(this);
  }

  @Override
  public List<List<String>> maintainCommands() {
    return manager.backendExecutor().maintainCommands(this);
  }
}
