package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.lang.System.Logger.Level;
import java.time.*;
import java.util.*;
import java.util.function.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/// Data processing task referring the source and destination endpoints. Tasks are stored
/// redundantly in the storages of all the volumes they refer to, with the newest
/// timestamp copy winning upon the conflicting restorations. The concrete task operation
/// is discriminated by the 'operation' descriptor property.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "operation")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Copy.class, name = "copy"),
  @JsonSubTypes.Type(value = Update.class, name = "update"),
  @JsonSubTypes.Type(value = Synchronize.class, name = "synchronize"),
  @JsonSubTypes.Type(value = Equalize.class, name = "equalize"),
  @JsonSubTypes.Type(value = Backup.class, name = "backup"),
  @JsonSubTypes.Type(value = Restore.class, name = "restore")
})
@JsonPropertyOrder({"task", "operation", "backend", "source", "destination", "modified", "include", "exclude", "deleted"})
public abstract class Task {

  // Tags used by the commit()'s FSM
  protected enum CommitState { PRISTINE, LIVE, DELETED, DEAD }

  protected final Manager manager;

  @JsonProperty("task")
  protected final String tag;

  @JsonProperty
  protected final Endpoint source;

  @JsonProperty
  protected final Endpoint destination;

  protected final List<String> include;
  protected final List<String> exclude;

  @JsonProperty("modified")
  protected OffsetDateTime mtime;

  protected int generation;
  protected CommitState commitState;
  private boolean live = true;

  // The lifecycle rides on the three cooperating fields above: `deleted` is the persisted
  // deletion marker (the tombstone winning the conflicting restorations), `live` is the
  // visibility flag (the physically deleted tasks and the collected tombstones are
  // dropped from the storages), and `commitState` is the commit-phase machine mirroring
  // the volume's one.

  // The transient in-memory marker of the processing in progress: guards the re-entrancy
  // (a task dispatched while its previous run is still going). Deliberately not persisted -
  // the generation/intact mechanism already encodes the processing needs and the backend
  // runs are idempotent upon the interruption. Volatile: the dispatches may come from
  // different threads (the guard must be visible across them).
  private volatile boolean processing;

  /// The deletion marker: a tombstoned task stays persisted within the storages of the
  /// currently visible volumes but is invisible for all the operations until it is
  /// collected. Null (and omitted from the descriptor) when the task is not deleted:
  /// the JSON default for the boolean fields is not emitted.
  @JsonProperty("deleted")
  protected Boolean deleted;

  private static final System.Logger LOG = System.getLogger(Task.class.getName());

  /// Constructor for a new task instance.
  // The this-escape is deliberate: the task is registered with the manager right upon its
  // creation (mirroring the reference implementation). The registration reads back only the
  // tag and the timestamp assigned above, so no uninitialized subclass state is observable.
  @SuppressWarnings("this-escape")
  protected Task(Manager manager, Endpoint source, Endpoint destination, Collection<String> include, Collection<String> exclude) {
    this.manager = manager;
    this.tag = Tags.newTag();
    this.source = source;
    this.destination = destination;
    this.include = new ArrayList<>(include);
    this.exclude = new ArrayList<>(exclude);
    this.commitState = CommitState.PRISTINE;
    touch();
    manager.registerTask(this);
  }

  /// Constructor for restoring an existing task from the descriptor's common fields;
  /// serves the concrete subclasses' Jackson creators. The task is registered with the
  /// manager right upon the restoration mirroring the reference implementation.
  // See the comment on the new task constructor for the deliberate this-escape rationale
  @SuppressWarnings("this-escape")
  protected Task(Manager manager, Endpoint source, Endpoint destination, String tag, OffsetDateTime modified, List<String> include, List<String> exclude) {
    if (manager == null || source == null || destination == null || tag == null || modified == null) throw new BitferryError("invalid task descriptor");
    this.manager = manager;
    this.tag = tag;
    this.source = source;
    this.destination = destination;
    this.include = include == null ? new ArrayList<>() : new ArrayList<>(include);
    this.exclude = exclude == null ? new ArrayList<>() : new ArrayList<>(exclude);
    this.mtime = modified;
    this.commitState = CommitState.LIVE;
    manager.registerTask(this);
  }

  /// Restore a task from the storage descriptor node dispatching over the
  /// 'operation' discriminator property. The manager is injected into the creator.
  public static Task restore(Manager manager, JsonNode data) {
    try {
      return Storage.mapper().readerFor(Task.class)
        .with(new InjectableValues.Std(Map.of("manager", manager)))
        .readValue(data);
    } catch (RuntimeException e) {
      throw BitferryError.resolve(e, "invalid task descriptor: " + e.getMessage());
    }
  }

  // JSON array extraction into the string list; missing nodes yield the empty list
  public static List<String> strings(JsonNode node, boolean emptyOnMissing) {
    if (node == null || node.isNull() || node.isMissingNode()) return emptyOnMissing ? new ArrayList<>() : null;
    if (!node.isArray()) throw new BitferryError("invalid task descriptor");
    List<String> xs = new ArrayList<>();
    node.forEach(x -> xs.add(x.asString()));
    return xs;
  }

  /// The task's operation used as the storage descriptor discriminator.
  public abstract String operation();

  /// The task's backend identifier (rclone, restic etc.).
  @JsonProperty("backend")
  public abstract String backend();

  /// The arrow used in the task listings between the endpoints.
  public abstract String director();

  public Manager manager() {
    return manager;
  }

  public String tag() {
    return tag;
  }

  public Endpoint source() {
    return source;
  }

  public Endpoint destination() {
    return destination;
  }

  public OffsetDateTime mtime() {
    return mtime;
  }

  public int generation() {
    return generation;
  }

  public List<String> include() {
    return java.util.Collections.unmodifiableList(include);
  }

  public List<String> exclude() {
    return java.util.Collections.unmodifiableList(exclude);
  }

  public void setInclude(Collection<String> include) {
    this.include.clear();
    if (include != null) {
      this.include.addAll(include);
    }
    touch();
  }

  public void setExclude(Collection<String> exclude) {
    this.exclude.clear();
    if (exclude != null) {
      this.exclude.addAll(exclude);
    }
    touch();
  }

  public void clearInclude() {
    this.include.clear();
    touch();
  }

  public void clearExclude() {
    this.exclude.clear();
    touch();
  }

  @JsonIgnore
  public boolean isLive() {
    return live;
  }

  /// Return true if the task is a deletion tombstone: persisted within the storages
  /// but invisible for all the operations until the garbage collection removes it.
  @JsonIgnore
  public boolean isDeleted() {
    return Boolean.TRUE.equals(deleted);
  }

  /// Return true if the task is intact, e.g. ready for processing.
  @JsonIgnore
  public boolean isIntact() {
    return source.isIntact() && destination.isIntact();
  }

  public boolean refersVolume(String vtag) {
    return source.refersVolume(vtag) || destination.refersVolume(vtag);
  }

  /// Return the volume hosting the task's password vault: the volume of the unencrypted
  /// end of the task, so the password travels along with the plaintext data.
  /// Raises [BitferryError] for the endpoints residing outside Bitferry volumes.
  public Volume vaultVolume() {
    throw new BitferryError("the directory endpoint must reside within a Bitferry volume");
  }

  /// Mark the task out-of-date: the generation is set to exceed the generations of
  /// the source/destination volumes it might refer to to flag those volumes out-of-date.
  public final void touch() {
    mtime = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    generation = Math.max(source.generation(), destination.generation()) + 1;
  }

  /// Mark the task for deletion by recording the deletion marker (the tombstone) and
  /// the DELETED transition of the commit machine: the marked task becomes invisible
  /// for all the operations immediately while the marker travels through all the
  /// referring storages, winning the conflicting restorations over the plain copies
  /// by its bumped timestamp. The physical removal is performed by the garbage
  /// collection once all the referred volumes are visible - the deferral keeps the
  /// deletion resilient against the volumes disappearing mid-flight: the stale plain
  /// copies of the vanished storages lose the conflict upon the return.
  public void delete() {
    deleted = true;
    commitState = CommitState.DELETED;
    touch();
  }

  /// Physically remove the collected (garbage) tombstone from the storages of all
  /// its referred volumes flagging them out-of-date.
  public void collect() {
    live = false;
    commitState = CommitState.DELETED;
    touch();
  }

  /// Return the tags of the volumes the task refers to through its endpoints.
  public List<String> referredVolumes() {
    Set<String> vtags = new LinkedHashSet<>();
    if (source instanceof VolumeEndpoint ve) vtags.add(ve.vtag());
    if (destination instanceof VolumeEndpoint ve) vtags.add(ve.vtag());
    return List.copyOf(vtags);
  }

  /// Serialize the task into the storage descriptor node. The empty sections
  /// are omitted mirroring the reference implementation's `filter_nones` behavior.
  public ObjectNode serialize() {
    return Storage.mapper().valueToTree(this);
  }

  // The include/exclude filters are omitted when empty
  @JsonProperty("include")
  List<String> storeInclude() {
    return include.isEmpty() ? null : include;
  }

  @JsonProperty("exclude")
  List<String> storeExclude() {
    return exclude.isEmpty() ? null : exclude;
  }

  /// Perform the one-time backend-side formatting of a newly created task
  /// (e.g. the repository initialization for the backup tasks).
  protected void format() {}

  /// Perform the encryption and backend target conformance validation against existing data.
  protected void conform() {}

  /// Commit the task performing the pending FSM transitions.
  public void commit() {
    loop:
    while (true) switch (commitState) {
      case PRISTINE -> { // Format storage etc. when a new task is created
        try {
          conform();
          format();
        } catch (BitferryError e) {
          // Abort the registration: the failed task must not persist (nor re-register
          // upon the following restorations) - the volume storage dump below reflects
          // the surviving task set
          manager.deregisterTask(this);
          throw e;
        }
        if (!manager.simulate) {
          // The format is the one-time backend-side action: the simulation runs it
          // through the simulated executors without consuming the pristine state,
          // leaving the transition pending for a following real commit
          commitState = CommitState.LIVE;
        }
      }
      case LIVE -> {
        break loop;
      }
      // The deletion is effected by the omission of the dead tasks from the future
      // storage dumps (the tasks are stored redundantly within the volume storages);
      // no descriptor cleanup is required
      // TODO perform post-mortem backend-side actions (e.g. repository cleanup)
      case DELETED -> {
        // TODO perform post-mortem actions (storage removal etc.)
        commitState = CommitState.DEAD;
      }
      case DEAD -> {
        return; // Task is RIP, do nothing
      }
    }
    LOG.log(Level.INFO, () -> "task #" + tag + " committed");
  }

  /// Process the task by delegating the backend utility execution to the manager's
  /// executor. The referred volumes are re-checked for the presence (the vanished
  /// volumes fail the processing early instead of the backend failing on the vanished
  /// paths), and the processing-in-progress guard rejects the re-entrancy (the task
  /// dispatched while its previous run is still going). Return true on success.
  public boolean process(Consumer<String> output) {
    if (processing) throw new BitferryError("task #" + tag + " is already being processed");
    processing = true;
    try {
      LOG.log(Level.INFO, () -> "processing task #" + tag);
      for (String vtag : referredVolumes()) {
        Volume v = manager.volume(vtag);
        if (v != null) v.checkPresence();
      }
      return manager.backendExecutor().run(this, output);
    } finally {
      processing = false;
    }
  }

  /// Service the task repository/endpoints by delegating to the manager's executor.
  /// The referred volumes are checked for presence, and the processing-in-progress guard
  /// rejects re-entrancy. Return true on success.
  public boolean serve(Consumer<String> output) {
    if (processing) throw new BitferryError("task #" + tag + " is already being processed");
    processing = true;
    try {
      LOG.log(Level.INFO, () -> "servicing task #" + tag);
      for (String vtag : referredVolumes()) {
        Volume v = manager.volume(vtag);
        if (v != null) v.checkPresence();
      }
      return manager.backendExecutor().serve(this, output);
    } finally {
      processing = false;
    }
  }

  /// Return the backend command lines about to execute for this task.
  public List<List<String>> commands() {
    return manager.backendExecutor().commands(this);
  }

  /// Return the backend command lines about to execute for servicing this task.
  public List<List<String>> serveCommands() {
    return manager.backendExecutor().serveCommands(this);
  }

  /// Return the human-readable operation label used for descriptions and listings.
  public String displayOperation() {
    return operation();
  }

  /// Return the task's short descriptor used in the listings.
  public String describe() {
    return displayOperation() + " " + source.describe() + " " + director() + " " + destination.describe();
  }

  @Override
  public String toString() {
    return describe();
  }

  // Resolve the specified option value against the profile route. The route maps profile
  // names (with the null key denoting the default profile) to option lists. Profile names
  // are prefixed with '@' (e.g. "@default", "@full"), '-' disables the options entirely,
  // and directly specified options are passed as comma-separated tokens.
  protected static List<String> optional(String option, Map<String, List<String>> route) {
    if (option == null) return route.get(null);
    if (option.equals("-")) return null;
    if (option.startsWith("@")) {
      String name = option.substring(1);
      List<String> profile = route.get(name);
      if (profile == null) throw new BitferryError("unknown options profile '" + option + "'");
      return profile;
    }
    return Arrays.stream(option.split(","))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .toList();
  }

  // Resolve the restored processing options list against the profile route
  protected static List<String> optional(List<String> options, Map<String, List<String>> route) {
    return options == null ? route.get(null) : options;
  }

  // The list reduced to null when empty for the section omission
  protected static List<String> orNull(List<String> xs) {
    return xs == null || xs.isEmpty() ? null : xs;
  }

  // Build the options profile route: named profiles plus the default (null-keyed) one
  protected static Map<String, List<String>> route(Map<String, List<String>> profiles, List<String> byDefault) {
    Map<String, List<String>> route = new LinkedHashMap<>(profiles);
    route.put(null, byDefault);
    return route;
  }
}
