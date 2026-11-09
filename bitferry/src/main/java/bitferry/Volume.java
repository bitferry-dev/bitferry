package bitferry;

import bitferry.endpoint.*;
import bitferry.migration.*;
import bitferry.task.*;
import com.fasterxml.jackson.annotation.*;
import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import tools.jackson.databind.*;

/// Bitferry volume: a data file (.bitferry) placed in the volume root directory which
/// denotes the root of the directory hierarchy. The volume converts the full (absolute)
/// endpoints' paths into the volume-relative ones making the data position-independent.
/// The volume state is restored from the storage file through the Jackson creator with
/// the manager and the root injected as they are not a part of the descriptor.
public final class Volume {

  // Tags used by the commit()'s FSM
  enum CommitState { PRISTINE, LIVE, DELETED, DEAD }

  @JsonProperty("bitferry")
  private final String version;

  private final Manager manager;
  private final Path root;

  @JsonProperty("volume")
  private final String tag;

  @JsonProperty("modified")
  private OffsetDateTime mtime;

  private boolean modified;
  private int generation;
  private CommitState commitState;
  private boolean live = true;
  private boolean wipe;

  // The identity (source/type pair) of the file system the root resides on, captured
  // at the discovery time and re-checked before every durable storage operation: the
  // identity change means the volume has disappeared (the mount dropped) and the
  // writes would silently materialize on the parent file system.
  private final String fileStore;

  // Password tokens of the encrypted tasks keyed by the task tags (see vault providers)
  private final Map<String, String> vault = new LinkedHashMap<>();

  private static final System.Logger LOG = System.getLogger(Volume.class.getName());

  /// Constructor for a new volume instance.
  public Volume(Manager manager, Path root) {
    this.version = Bitferry.STORAGE_VERSION;
    this.manager = manager;
    this.tag = Tags.newTag();
    this.root = Bitferry.canonicalize(root);
    this.fileStore = fileStoreId(this.root);
    this.modified = true;
    this.commitState = CommitState.PRISTINE;
    touch();
    manager.registerVolume(this);
  }

  /// Jackson creator restoring an existing volume from the storage descriptor.
  /// The tasks are restored and registered prior to the volume (as the nested
  /// values are built first) each one independently so that a failing task
  /// descriptor does not prevent the volume itself from being restored.
  /// Raises [BitferryError] for the unsupported storage versions.
  @JsonCreator
  Volume(
    @JacksonInject("manager") Manager manager,
    @JacksonInject("root") Path root,
    @JsonProperty("bitferry") String version,
    @JsonProperty("volume") String tag,
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("tasks") List<JsonNode> tasks,
    @JsonProperty("vault") Map<String, String> vault
  ) {
    if (!Bitferry.STORAGE_VERSION.equals(version)) throw new BitferryError("unsupported volume storage version " + version);
    if (tag == null) throw new IllegalArgumentException("missing volume field 'volume'");
    if (modified == null) throw new IllegalArgumentException("missing volume field 'modified'");
    this.version = version;
    this.manager = manager;
    this.tag = tag;
    this.mtime = modified;
    this.root = Bitferry.canonicalize(root);
    this.fileStore = fileStoreId(this.root);
    this.modified = false;
    this.commitState = CommitState.LIVE;
    if (vault != null) this.vault.putAll(vault);
    manager.registerVolume(this);
    if (tasks != null) {
      for (JsonNode taskData : tasks) {
        try {
          Task.restore(manager, taskData);
        } catch (BitferryError e) {
          LOG.log(Level.ERROR, () -> "failed to restore task for volume #" + tag + " from '" + root + "': " + e.getMessage());
        }
      }
    }
  }

  /// Restore a volume probing the specified root for the storage file.
  public static Volume restore(Manager manager, Path root) {
    Path storage = root.resolve(Bitferry.STORAGE);
    try {
      JsonNode node = Storage.readJson(storage);
      JsonNode migrated = bitferry.migration.Migrations.migrate(node);
      return Storage.mapper().readerFor(Volume.class)
        .with(new InjectableValues.Std(Map.of("manager", manager, "root", root)))
        .readValue(migrated);
    } catch (BitferryError e) {
      throw e;
    } catch (RuntimeException e) {
      throw BitferryError.resolve(e, "volume reading failure from file '" + storage + "'");
    }
  }

  public Manager manager() {
    return manager;
  }

  public String tag() {
    return tag;
  }

  public Path root() {
    return root;
  }

  public OffsetDateTime mtime() {
    return mtime;
  }

  @JsonIgnore
  public boolean isLive() {
    return live;
  }

  /// Return the volume-owned tasks referring the volume through either source or destination endpoints.
  public Map<String, Task> tasks() {
    return manager.tasks().entrySet().stream()
      .filter(e -> e.getValue().refersVolume(tag))
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  /// Return the volume-owned live tasks. The deletion tombstones are excluded: they are
  /// invisible for all the operations while persisting within the storages.
  public Map<String, Task> liveTasks() {
    return tasks().entrySet().stream()
      .filter(e -> e.getValue().isLive() && !e.getValue().isDeleted())
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  /// Return the volume-owned intact (ready for processing) tasks.
  public Map<String, Task> intactTasks() {
    return liveTasks().entrySet().stream()
      .filter(e -> e.getValue().isIntact())
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  /// Return the volume-owned stale (not ready for processing) tasks.
  public Map<String, Task> staleTasks() {
    return liveTasks().entrySet().stream()
      .filter(e -> !e.getValue().isIntact())
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  /// Mark the volume out-of-date rewinding the generation so that every referring task gets flagged.
  public void touch() {
    modified = true;
    mtime = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var ts = tasks();
    generation = ts.isEmpty() ? 0 : ts.values().stream().mapToInt(Task::generation).min().orElseThrow() - 1;
  }

  /// Mark the volume for deletion optionally wiping the entire volume directory.
  public void delete(boolean wipe) {
    this.wipe = wipe;
    this.commitState = CommitState.DELETED;
    touch();
  }

  /// Return true if the volume is out-of-date either by itself or by any of the referring tasks.
  @JsonIgnore
  public boolean isModified() {
    return modified || tasks().values().stream().anyMatch(t -> t.generation() > generation);
  }

  /// Return the current volume's generation used for the out-of-date flagging.
  public int generation() {
    return generation;
  }

  /// Resolve the specified root with respect to the volume's root.
  /// Raises [BitferryError] if the root is not a subpath of the volume's root.
  public Endpoint endpoint(Path root) {
    Path canonical = Bitferry.canonicalize(root);
    if (!canonical.startsWith(this.root)) throw new BitferryError("path '" + root + "' is outside the volume #" + tag + " root '" + this.root + "'");
    String relative = this.root.relativize(canonical).toString();
    return new VolumeEndpoint(manager, tag, relative.isEmpty() ? "." : relative);
  }

  /// The tasks to be stored: the live ones along with the deletion tombstones (the
  /// markers must persist until the garbage collection gathers them), omitted when
  /// empty mirroring the reference implementation's `filter_nones` behavior.
  @JsonProperty("tasks")
  List<Task> storeTasks() {
    List<Task> ts = tasks().values().stream().filter(Task::isLive).toList();
    return ts.isEmpty() ? null : ts;
  }

  /// The vault tokens to be stored: those of the live tasks only (the entries of
  /// the no longer existing (dead) tasks are purged), omitted when empty.
  @JsonProperty("vault")
  Map<String, String> storeVault() {
    Map<String, String> kept = vault.entrySet().stream()
      .filter(e -> {
        Task task = manager.tasks().get(e.getKey());
        return task != null && task.isLive();
      })
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    return kept.isEmpty() ? null : kept;
  }

  /// Commit the volume: format the storage upon creation, dump the storage contents when
  /// modified, remove the storage upon the deletion, then commit the referring tasks.
  public void commit() {
    Path storage = root.resolve(Bitferry.STORAGE);
    loop:
    while (true) switch (commitState) {
      case PRISTINE -> { // Format the volume
        if (Files.exists(storage) && !manager.force) throw new BitferryError("refuse to overwrite existing file '" + storage + "'; use --force to overcome this error");
        commitState = CommitState.LIVE;
      }
      case LIVE -> { // Dump the volume storage
        if (isModified() || manager.force) {
          checkPresence();
          try {
            // The actual storage writing time is stored, not the in-memory modification time
            mtime = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            String contents = Storage.toJson(this);
            if (manager.simulate) {
              LOG.log(Level.INFO, () -> "simulated #" + tag + " save to file '" + storage + "'");
            } else {
              Storage.writeText(storage, contents);
              LOG.log(Level.INFO, () -> "volume #" + tag + " written to file '" + storage + "'");
            }
          } catch (IOException e) {
            throw new BitferryError("volume #" + tag + " storage saving failure", e);
          }
        } else {
          LOG.log(Level.DEBUG, () -> "volume #" + tag + " is up-to-date and no forced save requested; skipped");
        }
        break loop;
      }
      case DELETED -> {
        checkPresence();
        if (manager.simulate) {
          LOG.log(Level.INFO, () -> "simulated volume #" + tag + " storage removal");
        } else if (wipe) {
          wipeDirectory();
          LOG.log(Level.INFO, () -> "wiped volume #" + tag + " directory '" + root + "'");
        } else {
          // Both the storage file and the possibly stale intermediate
          // image of an interrupted commit are removed
          try {
            Files.deleteIfExists(storage);
            Files.deleteIfExists(root.resolve(Bitferry.STORAGE_IMAGE));
          } catch (IOException e) {
            throw new BitferryError("volume #" + tag + " storage removal failure", e);
          }
          LOG.log(Level.INFO, () -> "volume #" + tag + " storage file '" + storage + "' deleted");
        }
        commitState = CommitState.DEAD;
        live = false;
      }
      case DEAD -> {
        break loop;
      }
    }
    // On successful commit advance the volume's generation so that the referring tasks won't be flagged out-of-date anymore
    var ts = tasks();
    generation = ts.isEmpty() ? 0 : ts.values().stream().mapToInt(Task::generation).max().orElseThrow() + 1;
    modified = false;
    // The volume commit itself might succeed even in spite of the task failures
    // But this case is still reported by raising the exception
    List<String> problems = new ArrayList<>();
    for (Task task : ts.values()) {
      try {
        task.commit();
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "commit failure(s) for task #" + task.tag() + ": " + e.getMessage());
        problems.add(e.getMessage());
      }
    }
    if (!problems.isEmpty()) throw new BitferryError("task commit failure(s) in volume #" + tag + ": " + String.join("; ", problems));
  }

  // Remove the volume directory contents (directories recursively, other entries unlinked)
  private void wipeDirectory() {
    try (Stream<Path> entries = Files.list(root)) {
      for (Path entry : entries.toList()) {
        if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
          deleteRecursively(entry);
        } else {
          Files.deleteIfExists(entry);
        }
      }
    } catch (IOException e) {
      throw new BitferryError("volume #" + tag + " directory wiping failure", e);
    }
  }

  /// The identity of the file system the specified directory resides on (the source
  /// and type pair), or null when it cannot be resolved (e.g. the directory has
  /// disappeared).
  private static String fileStoreId(Path directory) {
    try {
      FileStore store = Files.getFileStore(directory);
      return store.name() + "/" + store.type();
    } catch (IOException e) {
      return null;
    }
  }

  /// Detect the disappeared volume: the root resolving to a different (or no) file
  /// system than at the discovery time. The stale mount point directories left by the
  /// unclean removals would otherwise silently materialize the volume files on the
  /// parent file system.
  private void checkPresence() {
    String current = fileStoreId(root);
    if (fileStore != null && !fileStore.equals(current))
      throw new BitferryError("volume #" + tag + " root '" + root + "' has disappeared (its file system is no longer " + fileStore + ")");
  }

  private static void deleteRecursively(Path dir) {
    try (Stream<Path> entries = Files.walk(dir)) {
      for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
    } catch (IOException e) {
      throw new BitferryError("directory removal failure for '" + dir + "'", e);
    }
  }

  /// Return the obscured password token of the task stored in the volume vault
  /// (used by the vault providers).
  public String readVault(String tag) {
    return vault.get(tag);
  }

  /// Store the obscured password token of the task in the volume vault
  /// marking the volume out-of-date (used by the vault providers).
  public void writeVault(String tag, String token) {
    vault.put(tag, token);
    touch();
  }

  @Override
  public String toString() {
    return "volume#" + tag;
  }
}
