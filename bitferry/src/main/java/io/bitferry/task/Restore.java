package io.bitferry.task;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.function.Consumer;
import java.util.*;

/// Latest snapshot restore (Restic). The restore filters by inclusion and exclusion
/// both (the [IncludeFilters] capability carrying class): restic restore narrows the
/// snapshot content with the include patterns before applying the exclusions.
public class Restore extends Restic implements IncludeFilters {

  public static final String OPERATION = "restore";

  /// Processing options profiles; the null key denotes the default profile.
  public static final Map<String, List<String>> PROCESS =
    route(Map.of("default", List.of("--sparse")), List.of("--sparse"));

  // The [IncludeFilters] backing state: the restic restore filters by inclusion and
  // exclusion both (unlike the backup, which filters by exclusion only) - the behavior
  // rides the interface defaults over [includeState]
  protected final List<String> include = new ArrayList<>();

  // The snapshot scope: the tag of the backup task whose snapshots the restore selects
  // from (the newest of that task's snapshots). Null falls back to the repo-wide newest
  // snapshot carrying the marker tag (shared repositories would otherwise restore the
  // newest snapshot across all the co-repository tasks - possibly a sibling task's data)
  private String snapshotTag;

  /// Constructor for a new task instance.
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, Collection<String> include, Collection<String> exclude) {
    this(manager, source, destination, password, process, include, exclude, false);
  }

  /// Constructor for a new task instance with the explicit keyring storage request:
  /// the repository password travels to the OS keyring when the keyring is usable.
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, Collection<String> include, Collection<String> exclude, boolean keyring) {
    this(manager, source, destination, password, process, include, exclude, keyring, null);
  }

  /// Constructor for a new task instance with the explicit keyring storage request
  /// and the snapshot scope: the repository password travels to the OS keyring when
  /// the keyring is usable; the scope names the backup task whose snapshots the
  /// restore selects from (null falling back to the repo-wide newest marker-tagged
  /// snapshot).
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, Collection<String> include, Collection<String> exclude, boolean keyring, String snapshotTag) {
    super(manager, source, destination, password, process, PROCESS, exclude, keyring);
    this.include.addAll(include);
    this.snapshotTag = snapshotTag;
  }

  /// Jackson creator restoring an existing task from the storage descriptor;
  /// the manager is injected as it is not a part of the descriptor.
  @JsonCreator
  Restore(
    @JacksonInject("manager") Manager manager,
    @JsonProperty("source") Endpoint source,
    @JsonProperty("destination") Endpoint destination,
    @JsonProperty("task") String tag,
    @JsonProperty("backend") String backend,
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("include") List<String> include,
    @JsonProperty("exclude") List<String> exclude,
    @JsonProperty("snapshotTag") String snapshotTag,
    @JsonProperty("restic") Backend backendOpts
  ) {
    super(manager, source, destination, tag, modified, exclude, backendOpts, PROCESS);
    this.include.addAll(include == null ? List.of() : include);
    this.snapshotTag = snapshotTag;
    if (backend != null && !BACKEND.equals(backend)) {
      throw new BitferryError("Invalid task backend: expected " + BACKEND + " but got " + backend);
    }
  }

  /// The snapshot scope: the source backup task tag the restore selects the newest
  /// snapshot of (null selecting the repo-wide newest marker-tagged snapshot).
  @JsonProperty("snapshotTag")
  public String snapshotTag() {
    return snapshotTag;
  }

  /// Re-scope the snapshot selection: the backup task whose snapshots the restore
  /// selects from (null clearing the explicit scope, the default derived selection
  /// applying at the run time).
  public void setSnapshotTag(String snapshotTag) {
    this.snapshotTag = snapshotTag;
    touch();
  }

  /// The live backup tasks serving the specified repository endpoint: the snapshot
  /// scope candidates of a restore task targeting it (matching by the processed
  /// endpoint form, mirroring the repository the backups write into).
  public static List<String> scopeCandidates(Manager manager, Endpoint repository) {
    return manager.tasks().values().stream()
      .filter(t -> t instanceof Backup backup && t.isLive() && !t.isDeleted())
      .map(t -> (Backup) t)
      .filter(backup -> Restic.sameRepository(backup.destination().processOpts(), repository.processOpts()))
      .map(Task::tag)
      .distinct()
      .toList();
  }

  /// Resolve the snapshot scope of a new restore task: the explicit request must name
  /// a live backup task; the blank request auto-derives the backup task bound to the
  /// specified repository. Returns null when nothing scopes the restore (the repo-wide
  /// newest marker-tagged snapshot is selected at the run time). Raises [BitferryError]
  /// when the requested tag does not name a live backup task or the derivation is
  /// ambiguous (multiple co-repository backup tasks).
  public static String resolveSnapshotTag(Manager manager, Endpoint repository, String requested) {
    if (requested != null && !requested.isBlank()) {
      String tag = requested.trim();
      if (!(manager.tasks().get(tag) instanceof Backup backup) || backup.isDeleted()) {
        throw new BitferryError("Snapshot scope " + tag + " does not name a live backup task");
      }
      if (!Restic.sameRepository(backup.destination().processOpts(), repository.processOpts())) {
        throw new BitferryError("Backup task " + tag + " targets repository " + backup.destination().describe()
          + " and does not match restore repository " + repository.describe());
      }
      return tag;
    }
    List<String> candidates = scopeCandidates(manager, repository);
    if (candidates.size() > 1) {
      throw new BitferryError("Repository " + repository.processOpts() + " is served by multiple backup tasks (" + String.join(", ", candidates)
        + "); specify the snapshot scope explicitly (--backup-task in the CLI, the snapshot scope field in the GUI)");
    }
    return candidates.isEmpty() ? null : candidates.getFirst();
  }

  /// The [IncludeFilters] backing state (the behavior rides the interface defaults).
  @Override
  public List<String> includeState() {
    return include;
  }

  @Override
  public String operation() {
    return OPERATION;
  }

  /// The repository travels with the source endpoint.
  @Override
  public Endpoint repoEndpoint() {
    return source;
  }

  /// The restore direction: the source is the repository, the destination is the
  /// target directory.
  @Override
  public List<EndpointEntry> endpointEntries() {
    return List.of(new EndpointEntry("Repository:", source), new EndpointEntry("Destination:", destination));
  }

  /// The conformance dispatch: the override makes the call land on the executor's
  /// restore slot (the static type of `this` decides the overload).
  @Override
  protected void conform() {
    manager.backendExecutor().conform(this);
  }

  /// The restore tasks share the repository without owning the key.
  @Override
  public boolean mayRotateRepositoryKey() {
    return false;
  }

  /// Return the volume hosting the task's password vault: the volume of the destination
  /// directory (the unencrypted end of the restore task).
  /// Raises [BitferryError] if the destination endpoint resides outside Bitferry volumes.
  @Override
  public Volume vaultVolume() {
    return destination.requireVolume(manager, "restore destination endpoint must reside within a Bitferry volume");
  }

  @Override
  public Outcome runWith(Executor executor, Consumer<String> output) {
    return executor.run(this, output);
  }

  @Override
  public Outcome serveWith(Executor executor, Consumer<String> output) {
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
}
