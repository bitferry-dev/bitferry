package io.bitferry.task;

import io.bitferry.Storage;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/// Runtime service state of a repository tracked in `<repo>/.bitferry.json`: the
/// per-task maintenance entries keyed by the task tag. The repository may host
/// several backup tasks (the later ones auto-attach to the existing repository):
/// each task alters its own entry only (its backup counter and timestamps), while
/// the cadence check inspects all the co-repository entries - the repository-wide
/// service applies every sharing task's retention policy and resets every sharing
/// task's entry (the per-repo maintenance would otherwise starve the retention
/// policies of the tasks that did not happen to trigger the service). The entries
/// of the tags unknown to the current manager session are preserved untouched:
/// another host or session may own them.
///
/// The legacy shapes are read and migrated: the pre-envelope single-entry file
/// (and the pre-rename plain `bitferry.json` name) is adopted upon loading, and
/// [ResticRepositoryState#normalize] seeds the per-task entries from it upon the
/// first executor touch.
public final class ResticRepositoryState {

  public static final String FILENAME = ".bitferry.json";
  public static final String LEGACY_FILENAME = "bitferry.json";
  private static final System.Logger LOG = System.getLogger(ResticRepositoryState.class.getName());

  /// The per-task maintenance entry: the last backup/service instants and the
  /// backups-since-service counter of one backup task.
  public static final class Entry {

    @JsonProperty("backup")
    private Instant backup;

    @JsonProperty("maintenance")
    private Instant maintenance;

    @JsonProperty("backups_since_maintenance")
    @JsonAlias("backups")
    private int backups;

    public Entry() {}

    @JsonCreator
    public Entry(
      @JsonProperty("backup") Instant backup,
      @JsonProperty("maintenance") Instant maintenance,
      @JsonProperty("backups_since_maintenance") @JsonAlias("backups") int backups
    ) {
      this.backup = backup;
      this.maintenance = maintenance;
      this.backups = backups;
    }

    public Instant backup() {
      return backup;
    }

    public Instant maintenance() {
      return maintenance;
    }

    public int backups() {
      return backups;
    }

    Entry copy() {
      return new Entry(backup, maintenance, backups);
    }
  }

  // The per-task entries keyed by the task tag. The legacy single entry travels
  // separately (never serialized) until the normalization seeds it into the tasks.
  @JsonProperty("tasks")
  private Map<String, Entry> tasks = new LinkedHashMap<>();

  @JsonIgnore
  private boolean corruptOrMissing;

  @JsonIgnore
  private Entry legacy;

  public ResticRepositoryState() {}

  public ResticRepositoryState(Map<String, Entry> tasks, boolean corruptOrMissing) {
    this.tasks = tasks == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tasks);
    this.corruptOrMissing = corruptOrMissing;
  }

  /// The repository-level trigger: the missing or corrupt state file asks for the
  /// service of every task (the per-task entries cannot be trusted).
  public boolean isCorruptOrMissing() {
    return corruptOrMissing;
  }

  /// The task's entry for the read-only consumers: the own entry, falling back to
  /// the legacy single entry (the pre-envelope baseline shared by every task), else
  /// a fresh one.
  public Entry entry(String tag) {
    Entry own = tasks.get(tag);
    if (own != null) return own;
    return legacy != null ? legacy : new Entry();
  }

  /// Whether service is due for the given task per its entry and cadence. The
  /// corrupt or missing state is due for every task; the disabled cadence is
  /// never due.
  public boolean isDue(String tag, Cadence cadence) {
    if (corruptOrMissing) return true;
    if (cadence == null) return false;
    Entry entry = entry(tag);
    return cadence.isDue(entry.maintenance, entry.backups);
  }

  /// Record a completed backup run of the given task in its own entry (seeded from
  /// the legacy single entry when the task has none yet).
  public void recordBackup(String tag) {
    Entry entry = tasks.get(tag);
    if (entry == null) {
      entry = legacy != null ? legacy.copy() : new Entry();
      tasks.put(tag, entry);
    }
    entry.backup = Instant.now();
    entry.backups++;
  }

  /// Record a completed service run resetting the entries of the given tasks (the
  /// service covers the whole repository: every co-repository task's retention
  /// policy has been applied).
  public void recordServe(Collection<String> tags) {
    for (String tag : tags) {
      Entry entry = tasks.get(tag);
      if (entry == null) {
        entry = legacy != null ? legacy.copy() : new Entry();
        tasks.put(tag, entry);
      }
      entry.maintenance = Instant.now();
      entry.backups = 0;
    }
  }

  /// Migrate the legacy single-entry state into the per-task entries: every listed
  /// task gets its own entry seeded from the legacy baseline, and the legacy entry
  /// is dropped. Return true when the state changed (the caller persists it).
  public boolean normalize(Collection<String> tags) {
    boolean changed = false;
    for (String tag : tags) {
      if (!tasks.containsKey(tag)) {
        tasks.put(tag, legacy != null ? legacy.copy() : new Entry());
        changed = true;
      }
    }
    if (legacy != null) {
      legacy = null;
      changed = true;
    }
    return changed;
  }

  /// Remove the entries of the specified tags. Called for the tasks deleted in the
  /// current session: their tags are never reused, so the entries would otherwise
  /// linger in the file forever, being reported as the stale co-repository tasks
  /// by every following password change (a deleted task holds no token anymore).
  /// The tags unknown to the file are ignored. Return true when anything was removed.
  public boolean drop(Collection<String> tags) {
    boolean changed = false;
    for (String tag : tags) {
      if (tasks.remove(tag) != null) changed = true;
    }
    return changed;
  }

  /// The entry tags currently tracked (the unknown-tag entries of the other
  /// sessions are preserved within the file but not listed until seen here).
  public Collection<String> tags() {
    return new ArrayList<>(tasks.keySet());
  }

  /// Load repository state from `<repoDir>/.bitferry.json` (or the legacy plain
  /// `bitferry.json`). If missing or corrupt, logs a warning and returns an
  /// uninitialized state that triggers service.
  public static ResticRepositoryState load(Path repoDir) {
    if (repoDir == null) {
      return new ResticRepositoryState(null, true);
    }
    Path file = repoDir.resolve(FILENAME);
    if (!Files.isRegularFile(file)) {
      file = repoDir.resolve(LEGACY_FILENAME); // The pre-rename plain name
    }
    if (!Files.isRegularFile(file)) {
      return new ResticRepositoryState(null, true);
    }
    try {
      final Path path = file;
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        LOG.log(Level.WARNING, () -> "Empty repository state file in " + path + ", will trigger service");
        return new ResticRepositoryState(null, true);
      }
      var node = Storage.mapper().readTree(bytes);
      ResticRepositoryState state;
      if (node.has("tasks")) { // The per-task envelope shape
        state = Storage.mapper().readerFor(ResticRepositoryState.class).readValue(node);
      } else if (node.has("backup") || node.has("maintenance") || node.has("backups_since_maintenance") || node.has("backups")) {
        // The legacy single-entry shape: the shared baseline of every task until
        // the normalization splits it
        state = new ResticRepositoryState(null, false);
        state.legacy = Storage.mapper().readerFor(Entry.class).readValue(node);
      } else {
        LOG.log(Level.WARNING, () -> "Unrecognized repository state file in " + path + ", will trigger service");
        return new ResticRepositoryState(null, true);
      }
      state.corruptOrMissing = false;
      return state;
    } catch (Exception e) {
      final Path path = file;
      LOG.log(Level.WARNING, () -> "Corrupted repository state file in " + path + " (" + e.getMessage() + "), will trigger service");
      return new ResticRepositoryState(null, true);
    }
  }

  /// Persist the repository state to `<repoDir>/.bitferry.json`, replacing the
  /// legacy plain-named file when present (the envelope format supersedes it).
  public void save(Path repoDir) {
    if (repoDir == null) return;
    Path file = repoDir.resolve(FILENAME);
    try {
      String json = Storage.toJson(this);
      Storage.writeText(file, json);
      this.corruptOrMissing = false;
      Path legacyFile = repoDir.resolve(LEGACY_FILENAME);
      Files.deleteIfExists(legacyFile);
    } catch (IOException e) {
      LOG.log(Level.WARNING, () -> "Failed to save repository state file " + file + ": " + e.getMessage());
    }
  }
}
