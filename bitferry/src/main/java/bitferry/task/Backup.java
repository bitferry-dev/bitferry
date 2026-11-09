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
    "default", List.of("--prune", "--no-cache", "--keep-within-hourly", "24h", "--keep-within-daily", "7d", "--keep-within-weekly", "30d", "--keep-within-monthly", "1y", "--keep-within-yearly", "100y")
  ), null);

  /// Repository checking profiles; the null key denotes the default (skip) profile.
  public static final Map<String, List<String>> CHECK = route(Map.of(
    "default", List.of("--no-cache"),
    "full", List.of("--no-cache", "--read-data")
  ), null);

  /// The repository initialization mode: null to initialize a new repository,
  /// true to wipe and recreate the existing one, false to attach to it.
  protected Boolean format;

  protected List<String> forget;
  protected List<String> check;

  private static final System.Logger LOG = System.getLogger(Backup.class.getName());

  /// Constructor for a new task instance.
  public Backup(Manager manager, Endpoint source, Endpoint destination, String password, Boolean format, String process, String forget, String check, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, password, process, PROCESS, include, exclude);
    this.format = format;
    this.forget = optional(forget, FORGET);
    this.check = optional(check, CHECK);
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
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("include") List<String> include,
    @JsonProperty("exclude") List<String> exclude,
    @JsonProperty("restic") Backend backend
  ) {
    super(manager, source, destination, tag, modified, include, exclude, backend, PROCESS);
    this.format = null;
    this.forget = backend == null ? null : backend.forget();
    this.check = backend == null ? null : backend.check();
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

  @Override
  @JsonProperty("restic")
  Backend storeBackend() {
    boolean empty = (processOptions == null || processOptions.isEmpty())
      && (forget == null || forget.isEmpty())
      && (check == null || check.isEmpty());
    return empty ? null : new Backend(orNull(processOptions), orNull(forget), orNull(check));
  }

  /// Initialize the repository upon the task creation.
  @Override
  protected void format() {
    manager.backendExecutor().format(this);
  }
}
