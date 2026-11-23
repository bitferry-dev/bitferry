package org.bitferry;

import org.bitferry.endpoint.*;
import org.bitferry.task.*;
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
  private volatile CommitState commitState;
  private boolean live = true;

  // The identity (source/type pair) of the file system the root resides on, captured
  // at the discovery time and re-checked before every durable storage operation: the
  // identity change means the volume has disappeared (the mount dropped) and the
  // writes would silently materialize on the parent file system.
  private String fileStore;

  // The lazily computed fixed-media classification (null until probed)
  private Boolean fixed;

  /// A vault entry stored in the volume descriptor.
  public record VaultEntry(
    @JsonProperty("password") String password,
    @JsonProperty("salt") @JsonInclude(JsonInclude.Include.NON_NULL) String salt
  ) {
    public VaultEntry {
      if (password == null || password.isEmpty()) {
        throw new IllegalArgumentException("Password token must be specified");
      }
    }
  }

  // Password tokens of the encrypted tasks keyed by the task tags (see vault providers)
  private final Map<String, VaultEntry> vault = new LinkedHashMap<>();

  // The task descriptors that failed to restore preserved verbatim (the raw JSON
  // nodes): the storage dump re-emits them under their own 'preserved' section -
  // dropping them (along with their vault tokens) upon the following commit would
  // make the loss silent and unrecoverable. A later version able to parse a
  // descriptor restores it naturally, which releases the preserved copy
  private final List<JsonNode> failedTasks = new ArrayList<>();

  // The per-task restore failure messages recorded at the load time (not persisted):
  // the manager merges them into its loadErrors so both frontends surface them
  private final List<String> restoreErrors = new ArrayList<>();

  private static final System.Logger LOG = System.getLogger(Volume.class.getName());

  // The volume state lock: the mutations (the bookkeeping marks, the vault writes, the
  // commit machine) and the storage dump serialization ride it, so the concurrent GUI
  // readers (the table rebuilds snapshot the state from the FX thread) can never observe
  // a torn state, and the vault writes cannot interleave with the dump iteration. The
  // manager's registryLock stays for the registry maps only; the lock order is always
  // this lock first and the registry lock second (never the reverse).
  private final Object stateLock = new Object();

  /// Constructor for a new volume instance. The registration is deferred to
  /// [create]: it rides the successfully committed volume, not the constructor -
  /// a creation failing mid-flight (the refused overwrite, the storage writing
  /// failure) must leave nothing registered. Package-private: the creation goes
  /// through the factory.
  Volume(Manager manager, Path root) {
    this.version = Bitferry.STORAGE_VERSION;
    this.manager = manager;
    this.tag = Tags.newTag();
    this.root = Bitferry.canonicalize(root);
    this.fileStore = fileStoreId(this.root);
    this.modified = true;
    this.commitState = CommitState.PRISTINE;
    touch();
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
    @JsonProperty("preserved") List<JsonNode> preserved,
    @JsonProperty("vault") Map<String, VaultEntry> vault
  ) {
    if (version == null) throw new BitferryError("Missing volume storage version property 'bitferry'");
    if (!Bitferry.STORAGE_VERSION.equals(version)) throw new BitferryError("Unsupported volume storage version " + version);
    if (tag == null) throw new IllegalArgumentException("Missing volume field 'volume'");
    if (modified == null) throw new IllegalArgumentException("Missing volume field 'modified'");
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
    // Both sections carry the (hopefully parsable) task descriptors: the 'preserved'
    // one holds the entries that failed before (a newer version may parse them now,
    // releasing them naturally)
    restoreTasks(tasks);
    restoreTasks(preserved);
  }

  // The task descriptors restoration loop: the failures are logged (and surfaced
  // through the manager's loadErrors) while the descriptors themselves are preserved
  // verbatim - dropping them (along with their vault tokens) upon the next commit
  // would make the loss silent and unrecoverable
  private void restoreTasks(List<JsonNode> descriptors) {
    if (descriptors == null) return;
    for (JsonNode taskData : descriptors) {
      try {
        Task.restore(manager, taskData);
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "Failed to restore task for Bitferry volume " + tag + " from " + root + ": " + e.getMessage());
        String taskTag = failedTag(taskData);
        // The same tag riding both sections (the hand-merged storages): preserve once
        if (taskTag != null && failedTasks.stream().anyMatch(node -> taskTag.equals(failedTag(node)))) continue;
        failedTasks.add(taskData);
        restoreErrors.add("Failed to restore task " + (taskTag == null ? "<unknown tag>" : taskTag) + " of volume " + tag + ": " + e.getMessage());
      }
    }
  }

  /// The reason the specified root cannot host the volume storage right now, or
  /// null when it can: the absent or uncreatable root, or the file-creation denial
  /// (the locations restricting the file creation - the drive roots, the managed
  /// shares - and the read-only mounts). The creation enforces it via the probe;
  /// the frontends may consult it before the attempt to surface the informational
  /// dialogs instead of the raw failures.
  public static String storabilityProblem(Path root) {
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      return "The directory " + root + " cannot be created: " + e.getMessage();
    }
    Path probe = root.resolve(Bitferry.STORAGE_PROBE);
    try {
      Files.createFile(probe);
    } catch (AccessDeniedException e) {
      return "The directory " + root + " denies file creation for this account";
    } catch (IOException e) {
      return "The directory " + root + " is not writable: " + e.getMessage();
    } finally {
      try {
        Files.deleteIfExists(probe);
      } catch (IOException ignored) {
        // The empty probe residue is benign; its cleanup must not mask the outcome
      }
    }
    return null;
  }

  /// Create a new volume at the specified root committing its pristine state:
  /// the commit refuses to overwrite an existing foreign storage file unless the
  /// force option is enabled. The volume registers with the manager only upon the
  /// success - a failed creation leaves nothing behind (no rollback of a premature
  /// registration is needed). The simulation mode registers the volume leaving the
  /// pending write for a following real commit to materialize.
  public static Volume create(Manager manager, Path root) {
    Volume volume = new Volume(manager, root);
    // The storability enforcement: the root accepting directories while refusing
    // files (the drive roots, the managed shares, the read-only mounts) would fail
    // the storage write mid-commit with the opaque 'saving failure' - the probe
    // surfaces the restriction upfront with the actionable message; the simulation
    // skips it (nothing is written).
    if (!manager.simulate) {
      String problem = storabilityProblem(root);
      if (problem != null) {
        throw new BitferryError(problem);
      }
    }
    volume.commit();
    manager.registerVolume(volume);
    return volume;
  }

  /// Restore a volume probing the specified root for the storage file.
  public static Volume restore(Manager manager, Path root) {
    Path storage = root.resolve(Bitferry.STORAGE);
    try {
      JsonNode node = Storage.readJson(storage);
      if (node == null || !node.isObject()) {
        throw new BitferryError("Invalid volume storage descriptor: expected a JSON object");
      }
      return Storage.mapper().readerFor(Volume.class)
        .with(new InjectableValues.Std(Map.of("manager", manager, "root", root)))
        .readValue(node);
    } catch (BitferryError e) {
      throw e;
    } catch (RuntimeException e) {
      throw BitferryError.resolve(e, "Bitferry volume reading failure from " + storage);
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
    synchronized (stateLock) {
      return live;
    }
  }

  /// Whether the volume is marked for deletion (pending the DELETED -> DEAD commit
  /// transition): the deletion wins the equal-timestamp conflicts mirroring the task
  /// tombstones. Package-private: the commit state is internal bookkeeping (not a part
  /// of the storage descriptor).
  boolean pendingDeletion() {
    return commitState == CommitState.DELETED;
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
    synchronized (stateLock) {
      modified = true;
      mtime = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      var ts = tasks();
      generation = ts.isEmpty() ? 0 : ts.values().stream().mapToInt(Task::generation).min().orElseThrow() - 1;
    }
  }

  /// Mark the volume for deletion: the storage file is removed upon the commit while
  /// the volume directory contents are kept.
  public void delete() {
    synchronized (stateLock) {
      this.commitState = CommitState.DELETED;
      // The referring tasks are tombstoned with the volume: the deletion markers ride
      // the commit into the storages of the surviving volumes the tasks also refer to
      // (the tasks carried only by this volume vanish along with its storage), winning
      // the conflicting restorations over the stale plain copies - the deleted tasks
      // never resurrect upon the following restores.
      for (Task task : liveTasks().values()) task.delete();
      touch();
    }
  }

  /// Return true if the volume is out-of-date either by itself or by any of the referring tasks.
  @JsonIgnore
  public boolean isModified() {
    synchronized (stateLock) {
      return modified || tasks().values().stream().anyMatch(t -> t.generation() > generation);
    }
  }

  /// Return the current volume's generation used for the out-of-date flagging.
  public int generation() {
    synchronized (stateLock) {
      return generation;
    }
  }

  /// Resolve the specified root with respect to the volume's root.
  /// Raises [BitferryError] if the root is not a subpath of the volume's root.
  public Endpoint endpoint(Path root) {
    Path canonical = Bitferry.canonicalize(root);
    if (!canonical.startsWith(this.root)) throw new BitferryError("Path " + root + " is outside the volume " + tag + " at " + this.root);
    String relative = this.root.relativize(canonical).toString();
    return new VolumeEndpoint(manager, tag, relative.isEmpty() ? "." : relative);
  }

  // The task tag of a preserved (failed-to-restore) descriptor, or null when it
  // cannot be determined (the descriptor is too damaged)
  private static String failedTag(JsonNode node) {
    JsonNode tag = node.get("task");
    return tag != null && tag.isTextual() ? tag.asText() : null;
  }

  /// Return the per-task restore failure messages recorded at the load time (not
  /// persisted): the manager merges them into its loadErrors so both frontends
  /// surface them.
  @JsonIgnore
  public List<String> restoreErrors() {
    return List.copyOf(restoreErrors);
  }

  /// The tasks to be stored: the live ones along with the deletion tombstones (the
  /// markers must persist until the garbage collection gathers them), omitted when
  /// empty mirroring the reference implementation's `filter_nones` behavior. The
  /// declared static type is load-bearing: the polymorphic serializer writes the
  /// 'operation' discriminator only through it (serializing through the raw Object
  /// type would silently drop it, breaking the restoration).
  @JsonProperty("tasks")
  List<Task> storeTasks() {
    List<Task> ts = tasks().values().stream().filter(Task::isLive).toList();
    return ts.isEmpty() ? null : ts;
  }

  /// The task descriptors that failed to restore travel back into the storage
  /// verbatim under their own section (the raw JSON trees cannot ride the tasks
  /// section - its polymorphic discriminator is bound to the declared Task type):
  /// a later version able to parse a descriptor restores it naturally, which
  /// releases the preserved copy (as does the same tag becoming registered again,
  /// e.g. restored via another volume's copy). Omitted when empty.
  @JsonProperty("preserved")
  List<JsonNode> storePreserved() {
    List<JsonNode> kept = new ArrayList<>();
    for (JsonNode node : failedTasks) {
      String failed = failedTag(node);
      if (failed != null && manager.tasks().containsKey(failed)) continue;
      kept.add(node);
    }
    return kept.isEmpty() ? null : kept;
  }

  /// The vault tokens to be stored: those of the live tasks plus the tokens of the
  /// preserved (failed-to-restore) descriptors - the token may be the only durable
  /// copy of the password, erasing it would destroy the data it guards. The entries
  /// of the no longer existing (dead) tasks are purged, as are the entries of the
  /// descriptors whose tag cannot be determined (accepted residue). Omitted when
  /// empty.
  @JsonProperty("vault")
  Map<String, VaultEntry> storeVault() {
    Set<String> preserved = failedTasks.stream().map(Volume::failedTag).filter(Objects::nonNull).collect(Collectors.toSet());
    Map<String, VaultEntry> kept = vault.entrySet().stream()
      .filter(e -> {
        Task task = manager.tasks().get(e.getKey());
        if (task != null) return task.isLive() && !task.isDeleted();
        return preserved.contains(e.getKey());
      })
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    return kept.isEmpty() ? null : kept;
  }

  /// Commit the volume: commit the referring tasks first (a task failing its
  /// one-time initialization - the failed repository initialization - deregisters
  /// itself, and the storage dump below reflects the surviving task set), then
  /// format the storage upon creation, dump the storage contents when modified,
  /// remove the storage upon the deletion. The commit of a pristine volume that
  /// has not been registered yet (the in-flight [create] factory) raises on any
  /// failure leaving the registration to the factory's success path only.
  public void commit() {
    if (!isModified() && !manager.force && commitState == CommitState.LIVE) {
      LOG.log(Level.DEBUG, () -> "Bitferry volume " + tag + " is up-to-date and no forced save requested; skipped");
      return;
    }
    Path storage = root.resolve(Bitferry.STORAGE);
    var ts = tasks();
    List<String> problems = new ArrayList<>();
    for (Task task : ts.values()) {
      try {
        task.commit();
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "Commit failure(s) for task " + task.tag() + ": " + e.getMessage());
        problems.add(e.getMessage());
      }
    }
    // The commit machine and the storage dump serialization ride the state lock:
    // the vault writes (the operations thread) cannot interleave with the dump
    // iteration, and the concurrent GUI readers cannot observe a torn state.
    // The task commits above stay deliberately outside the lock (their backend
    // side effects must not hold the volume's readers hostage).
    synchronized (stateLock) {
      loop:
      while (true) switch (commitState) {
        case PRISTINE -> { // Format the volume
          if (Files.exists(storage) && !manager.force) {
            throw new BitferryError("Refuse to overwrite existing storage file " + storage + " without the force option enabled");
          }
          commitState = CommitState.LIVE;
        }
        case LIVE -> { // Dump the volume storage
          if (isModified() || manager.force) {
            if (fileStore == null) {
              // The identity was unresolvable at the discovery time (e.g. the mount
              // was dropped): re-resolve. An existing root whose file system stays
              // unresolvable cannot be verified - the stale mount point would
              // silently adopt the writes. The not-yet-existing root (the fresh
              // volume) has nothing to verify: the write below creates it.
              fileStore = fileStoreId(root);
              if (fileStore == null && Files.isDirectory(root)) {
                throw new BitferryError("Bitferry volume " + tag + " presence at " + root + " cannot be verified (its file system is unresolvable)");
              }
              if (fileStore != null) {
                LOG.log(Level.WARNING, () -> "Bitferry volume " + tag + " file system identity was unresolvable at the discovery time; adopted " + fileStore);
              }
            } else {
              checkPresence();
            }
            try {
              String contents = Storage.toJson(this);
              if (manager.simulate) {
                // The simulation writes nothing and consumes no durable bookkeeping:
                // the pending mtime/generation/modified state is left for a following
                // real commit to materialize
                LOG.log(Level.INFO, () -> "Simulated Bitferry volume " + tag + " save to " + storage);
              } else {
                // The actual storage writing time is stored, not the in-memory modification time
                mtime = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                Storage.writeText(storage, contents);
                LOG.log(Level.INFO, () -> "Bitferry volume " + tag + " written to " + storage);
              }
            } catch (IOException e) {
              throw new BitferryError("Bitferry volume " + tag + " storage saving failure", e);
            }
          } else {
            LOG.log(Level.DEBUG, () -> "Bitferry volume " + tag + " is up-to-date and no forced save requested; skipped");
          }
          break loop;
        }
        case DELETED -> {
          if (fileStore == null) fileStore = fileStoreId(root);
          if (fileStore == null && Files.exists(root)) {
            // The destructive removal must not proceed blind on an existing root
            // with an unresolvable file system (the stale mount directory could
            // host a re-materialized volume); the already-vanished root carries
            // no storage to protect
            throw new BitferryError("Bitferry volume " + tag + " presence at " + root + " cannot be verified (its file system is unresolvable)");
          }
          if (fileStore != null) checkPresence();
          if (manager.simulate) {
            // The simulation removes nothing and consumes no durable bookkeeping:
            // the deletion stays pending for a following real commit
            LOG.log(Level.INFO, () -> "Simulated Bitferry volume " + tag + " storage removal");
            break loop;
          } else {
            // Both the storage file and the possibly stale intermediate
            // image of an interrupted commit are removed
            try {
              Files.deleteIfExists(storage);
              Files.deleteIfExists(root.resolve(Bitferry.STORAGE_IMAGE));
            } catch (IOException e) {
              throw new BitferryError("Bitferry volume " + tag + " storage removal failure", e);
            }
            LOG.log(Level.INFO, () -> "Bitferry volume " + tag + " storage " + storage + " deleted");
          }
          commitState = CommitState.DEAD;
          live = false;
        }
        case DEAD -> {
          break loop;
        }
      }
      // On successful commit advance the volume's generation so that the referring tasks
      // won't be flagged out-of-date anymore. The simulation consumes no durable bookkeeping
      // (the pending state is left for a following real commit), and the dead volumes
      // (deleted or dead) skip the advance: their storages are gone, and no live tasks
      // refer them anymore
      if (!manager.simulate && live) {
        var surviving = tasks();
        generation = surviving.isEmpty() ? 0 : surviving.values().stream().mapToInt(Task::generation).max().orElseThrow() + 1;
        modified = false;
      }
    }
    // The storage dump is the commit boundary for the staged credentials. Failed
    // task commits have already rolled themselves back and carry no attempted write.
    for (Task task : ts.values()) task.credentialsCommitted();
    if (!problems.isEmpty()) throw new BitferryError("Task commit failure(s) in volume " + tag + ": " + String.join("; ", problems));
  }

  /// The identity of the file system the specified directory resides on (the source
  /// and type pair), or null when it cannot be resolved (e.g. the directory has
  /// disappeared).
  private static String fileStoreId(Path directory) {
    try {
      FileStore store = Files.getFileStore(directory);
      return store.toString() + "@" + store.type();
    } catch (IOException e) {
      return null;
    }
  }

  /// Whether the volume resides on a fixed (internal, non-removable) medium: a hint
  /// for the sensible default password storage location — the keyring secrets are
  /// machine-bound while the volume vault tokens travel with the storage media, so
  /// the removable volumes are better served by the per-volume vault. The explicit
  /// keyring requests are honored regardless (see [org.bitferry.vault.Vault.select]);
  /// the fixedness only steers the defaults and the advisory notes.
  ///
  /// <p>The classification is conservative: anything unresolvable (the removed
  /// media, the network or virtual file systems, the unknown devices) is reported
  /// as not fixed.
  // The machine-dependent classification is not a part of the storage descriptor
  // (the Jackson getter detection would otherwise serialize it)
  @JsonIgnore
  public boolean isFixed() {
    if (fixed == null) fixed = isFixedStore(root);
    return fixed;
  }

  // Classify the directory's hosting file system per the platform conventions
  static boolean isFixedStore(Path directory) {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    try {
      if (os.contains("windows")) return isFixedWindows(directory);
      if (os.contains("mac os") || os.contains("darwin")) return !directory.startsWith("/Volumes");
      return isFixedLinux(directory);
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.DEBUG, () -> "Storage fixedness classification failed for " + directory + ": " + e);
      return false;
    }
  }

  // Linux: a block device source is fixed when the kernel's removable flag is unset;
  // the network/virtual sources (nfs, cifs, fuse, tmpfs...) are not fixed, and the
  // sources without the resolvable block device (the plain file-backed stores) are
  // not fixed either
  private static boolean isFixedLinux(Path directory) throws IOException {
    FileStore store = Files.getFileStore(directory);
    String source = store.name();
    if (!source.startsWith("/dev/")) return false;
    try {
      // The real block device name: the device nodes are symlinks for the mapper
      // stacks (/dev/mapper/vg-root -> ../dm-0), so the name-based sysfs lookup must
      // ride the resolved node - the LUKS/LVM volumes are classified by their dm-N
      // device, not misread as removable through the missing 'mapper' path
      String device = Path.of(source).toRealPath().getFileName().toString();
      // The removable flag rides on the whole disk only: resolve the partition to its
      // holding disk exactly via the sysfs layout (the partition's real directory is
      // a child of its disk's one - nvme0n1p1 under nvme0n1, sda1 under sda), immune
      // to the kernel naming schemes (the multiplexing 'p' separator, sdp, sdaa...)
      Path sysdev = Path.of("/sys/class/block", device).toRealPath();
      if (Files.exists(sysdev.resolve("partition"))) sysdev = sysdev.getParent();
      return Files.readString(sysdev.resolve("removable")).trim().equals("0");
    } catch (IOException e) {
      LOG.log(Level.DEBUG, () -> "No sysfs removable flag for " + source);
      return false;
    }
  }

  // Windows: the fixed drive type of the root's drive letter (the removable, network,
  // optical and ram drives are not fixed)
  private static boolean isFixedWindows(Path directory) {
    String root = directory.getRoot() != null ? directory.getRoot().toString() : directory.toString();
    return com.sun.jna.platform.win32.Kernel32.INSTANCE.GetDriveType(root)
      == com.sun.jna.platform.win32.Kernel32.DRIVE_FIXED;
  }

  /// Detect the disappeared volume: the root resolving to a different (or no) file
  /// system than at the discovery time. The stale mount point directories left by the
  /// unclean removals would otherwise silently materialize the volume files on the
  /// parent file system.
  public void checkPresence() {
    synchronized (stateLock) {
      String current = fileStoreId(root);
      if (fileStore != null && !fileStore.equals(current))
        throw new BitferryError("Bitferry volume " + tag + " root " + root + " has disappeared (its file system is no longer " + fileStore + ")");
    }
  }


  /// Return the vault entry of the task stored in the volume vault
  /// (used by the vault providers).
  public VaultEntry readVault(String tag) {
    synchronized (stateLock) {
      return vault.get(tag);
    }
  }

  /// Store the vault entry of the task in the volume vault
  /// marking the volume out-of-date (used by the vault providers).
  public void writeVault(String tag, VaultEntry entry) {
    synchronized (stateLock) {
      vault.put(tag, entry);
      touch();
    }
  }

  /// Drop the vault entry of the task from the volume vault marking
  /// the volume out-of-date when something was dropped (used by the vault providers).
  public void removeVault(String tag) {
    synchronized (stateLock) {
      if (vault.remove(tag) != null) touch();
    }
  }

  @Override
  public String toString() {
    return "volume " + tag;
  }
}
