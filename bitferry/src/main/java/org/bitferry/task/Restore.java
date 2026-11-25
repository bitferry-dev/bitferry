package org.bitferry.task;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.function.Consumer;
import java.util.*;

/// Latest snapshot restore (Restic). The restore filters by inclusion and exclusion
/// both (the [IncludeFilters] capability carrying class): restic restore narrows the
/// snapshot content with the include patterns before applying the exclusions.
public class Restore extends Restic implements IncludeFilters {

  public static final String OPERATION = "restore";

  /// Default positional snapshot identifier passed to restic restore.
  public static final String DEFAULT_SNAPSHOT = "latest";

  /// Repository checking profiles; the null key denotes the default (no extra flags) profile.
  public static final Map<String, List<String>> CHECK = Restic.CHECK;

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

  // Specific snapshot ID (or snapshotID:subfolder) to restore; null represents DEFAULT_SNAPSHOT ("latest")
  @JsonProperty("snapshotId")
  private String snapshotId;

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
    this(manager, source, destination, password, process, include, exclude, keyring, snapshotTag, null);
  }

  /// Constructor for a new task instance with the explicit keyring storage request,
  /// snapshot scope, and target snapshot ID (null/blank/"latest" defaults to "latest").
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, Collection<String> include, Collection<String> exclude, boolean keyring, String snapshotTag, String snapshotId) {
    this(manager, source, destination, password, process, null, include, exclude, keyring, snapshotTag, snapshotId);
  }

  /// Constructor for a new task instance with explicit repository checking options,
  /// keyring storage request, snapshot scope, and target snapshot ID.
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, String check, Collection<String> include, Collection<String> exclude, boolean keyring, String snapshotTag, String snapshotId) {
    super(manager, source, destination, password, process, PROCESS, exclude, keyring);
    this.check = optional(check, CHECK);
    this.include.addAll(include);
    this.snapshotTag = snapshotTag;
    this.snapshotId = normalizeSnapshotId(snapshotId);
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
    @JsonProperty("snapshotId") String snapshotId,
    @JsonProperty("restic") Backend backendOpts
  ) {
    super(manager, source, destination, tag, modified, exclude, backendOpts, PROCESS);
    this.include.addAll(include == null ? List.of() : include);
    this.snapshotTag = snapshotTag;
    this.snapshotId = normalizeSnapshotId(snapshotId);
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

  /// Normalize snapshot ID input: null, blank, "-", or "latest" normalize to null
  /// (meaning [DEFAULT_SNAPSHOT]); any other trimmed identifier (including "latest:subpath")
  /// is preserved.
  public static String normalizeSnapshotId(String snapshotId) {
    if (snapshotId == null) return null;
    String trimmed = snapshotId.trim();
    if (trimmed.isEmpty() || "-".equals(trimmed) || DEFAULT_SNAPSHOT.equals(trimmed)) {
      return null;
    }
    return trimmed;
  }

  /// Whether the given snapshot ID selects the rolling "latest" snapshot (either "latest"
  /// itself or "latest:subpath"), where `--tag` filtering applies.
  public static boolean isLatestSnapshot(String snapshotId) {
    String normalized = normalizeSnapshotId(snapshotId);
    return normalized == null || normalized.startsWith(DEFAULT_SNAPSHOT + ":");
  }

  /// Target snapshot ID for restic restore (defaults to [DEFAULT_SNAPSHOT]).
  @JsonIgnore
  public String snapshotId() {
    return snapshotId != null ? snapshotId : DEFAULT_SNAPSHOT;
  }

  /// Set the target snapshot ID (null, blank, "-", or "latest" resets to default [DEFAULT_SNAPSHOT]).
  public void setSnapshotId(String snapshotId) {
    this.snapshotId = normalizeSnapshotId(snapshotId);
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

  /// Query the repository directly using the provided password to discover snapshot scopes
  /// (tags and untagged snapshot IDs present in the repository).
  public static List<Snapshot> repositorySnapshots(Manager manager, Endpoint repository, String password) {
    if (manager == null || repository == null || password == null || password.isEmpty()) {
      return List.of();
    }
    return manager.backendExecutor().snapshots(manager, repository, password);
  }

  /// Resolve the snapshot scope of a new restore task: the explicit request must name
  /// a live backup task or a snapshot present in the repository; the blank request auto-derives
  /// the backup task bound to the specified repository. Returns null when nothing scopes the restore
  /// (the repo-wide newest marker-tagged snapshot is selected at the run time). Raises [BitferryError]
  /// when the requested tag names an invalid local task or is missing from both local tasks and the repository,
  /// or when derivation is ambiguous.
  public static String resolveSnapshotTag(Manager manager, Endpoint repository, String requested) {
    return resolveSnapshotTag(manager, repository, requested, null);
  }

  /// Resolve the snapshot scope of a new restore task, validating against both local tasks
  /// and the repository snapshots using the provided repository password.
  public static String resolveSnapshotTag(Manager manager, Endpoint repository, String requested, String password) {
    if (requested != null && !requested.isBlank()) {
      String tag = requested.trim();
      Task t = manager.tasks().get(tag);
      if (t != null) {
        if (!(t instanceof Backup backup) || backup.isDeleted()) {
          throw new BitferryError("Snapshot scope " + tag + " does not name a live backup task");
        }
        if (repository != null && !Restic.sameRepository(backup.destination().processOpts(), repository.processOpts())) {
          throw new BitferryError("Backup task " + tag + " targets repository " + backup.destination().describe()
            + " and does not match restore repository " + repository.describe());
        }
        return tag;
      }
      boolean inRepo = false;
      try {
        inRepo = repositorySnapshots(manager, repository, password).stream()
          .anyMatch(s -> s.tags().contains(tag) || tag.equals(s.shortId()) || tag.equals(s.id()));
      } catch (Exception ignored) {}
      if (!inRepo) {
        throw new BitferryError("Snapshot scope " + tag + " does not name a live backup task");
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
  public void conform() {
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
