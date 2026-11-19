package io.bitferry;

import io.bitferry.endpoint.*;
import io.bitferry.task.*;
import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.*;
import tools.jackson.databind.JsonNode;

/// Central entity keeping the registered volumes and tasks and orchestrating
/// their restoration, selection, processing and committing.
public class Manager {

  /// Explicit default constructor: the class is a part of the exported
  /// module API.
  public Manager() {}

  private final Map<String, Volume> volumes = new LinkedHashMap<>();
  private final Map<String, Task> tasks = new LinkedHashMap<>();
  private final List<String> loadErrors = new ArrayList<>();

  /// The registry lock: the mutations and the snapshot iterations are serialized so
  /// that a concurrent reader (a GUI table rebuild on the FX thread) never observes a
  /// structurally mutating map (ConcurrentModificationException, hung iteration).
  private final Object registryLock = new Object();

  /// Allow potentially dangerous operations.
  public volatile boolean force = false;

  /// Make no on-disk changes (simulation mode).
  public volatile boolean simulate = false;

  /// Produce the least output.
  public volatile boolean quiet = false;

  /// Output verbosity level.
  public volatile Verbosity verbosity = Verbosity.NORMAL;

  private volatile Executor backendExecutor = Executor.system();

  private static final System.Logger LOG = System.getLogger(Manager.class.getName());

  /// Return the registered volumes keyed by their tags. The returned map is an
  /// immutable snapshot: a subsequent registry mutation does not change it.
  public Map<String, Volume> volumes() {
    synchronized (registryLock) {
      return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(volumes));
    }
  }

  /// Return the registered tasks keyed by their tags. The returned map is an
  /// immutable snapshot: a subsequent registry mutation does not change it.
  public Map<String, Task> tasks() {
    synchronized (registryLock) {
      return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
    }
  }

  /// Return the volume matched by the exact tag or null.
  public Volume volume(String tag) {
    synchronized (registryLock) {
      return volumes.get(tag);
    }
  }

  /// Return any error messages recorded during volume loading / restoration.
  public List<String> loadErrors() {
    synchronized (registryLock) {
      return List.copyOf(loadErrors);
    }
  }

  /// Alias for [loadErrors] matching the restoration terminology.
  public List<String> restoreErrors() {
    return loadErrors();
  }

  /// The comparable snapshot of the current mount set (the sorted mount-point
  /// strings): the media change watcher diff is the equality of two consecutive
  /// snapshots. Every call re-reads the live OS mount table ([mountPoints]).
  public static java.util.SortedSet<String> mountSetSignature() {
    return new java.util.TreeSet<>(mountPoints().stream().map(java.lang.String::valueOf).toList());
  }

  /// Return the task matched by the exact tag or null.
  public Task task(String tag) {
    synchronized (registryLock) {
      return tasks.get(tag);
    }
  }

  /// The verbose flag derived from the verbosity level.
  public boolean verbose() {
    return verbosity == Verbosity.VERBOSE || verbosity == Verbosity.DEBUG;
  }

  public void verbose(boolean value) {
    verbosity = value ? Verbosity.VERBOSE : Verbosity.NORMAL;
  }

  /// The simulation mode flag.
  public boolean simulate() {
    return simulate;
  }

  public void simulate(boolean value) {
    this.simulate = value;
  }

  /// Resolve the specified root with respect to the one of registered volumes' root.
  /// Raises [BitferryError] if the root is not a subpath of any volume's root.
  public Endpoint endpoint(Path root) {
    Path canonical = Bitferry.canonicalize(root);
    // Volumes are probed in the longest root first order so that the nested volumes are matched before the enclosing ones
    List<Volume> sorted = volumes().values().stream()
      .filter(Volume::isLive)
        .sorted(java.util.Comparator.comparing((Volume v) -> v.root().getNameCount()).reversed())
      .toList();
    for (Volume v : sorted) {
      try {
        return v.endpoint(canonical);
      } catch (BitferryError e) {
        LOG.log(Level.DEBUG, () -> "Path " + canonical + " can not be resolved against volume " + v.tag());
      }
    }
    throw new BitferryError("Failed to resolve path " + canonical + " against any registered Bitferry volumes in " + this);
  }

  /// Register the volume upon its creation or restoration keeping the copy with the newest
  /// timestamp. The equal-timestamp ties prefer the copy marked for deletion mirroring
  /// the task tombstones: the deletions must never lose a conflict.
  public void registerVolume(Volume volume) {
    synchronized (registryLock) {
      Volume existing = volumes.get(volume.tag());
      if (existing == null) {
        LOG.log(Level.INFO, () -> "Bitferry volume " + volume.tag() + " registered in " + this);
      } else if (existing.mtime().isBefore(volume.mtime())
        || (existing.mtime().isEqual(volume.mtime()) && volume.pendingDeletion() && !existing.pendingDeletion())) {
        volumes.remove(volume.tag());
        LOG.log(Level.INFO, () -> "Replaced already registered Bitferry volume " + volume.tag() + " with the one with newer timestamp");
      } else {
        LOG.log(Level.INFO, () -> "Kept already registered Bitferry volume " + volume.tag() + " with newer timestamp");
        return;
      }
      volumes.put(volume.tag(), volume);
    }
  }

  /// Register the task upon its creation or restoration keeping the copy with the newest timestamp.
  /// Since tasks may come from both endpoints' volumes there may be already a task restored
  /// and registered; in this case the task with the newer timestamp wins. The equal-timestamp
  /// ties prefer the deletion tombstone: the deletions must never lose a conflict (a
  /// resurrected task would restore the deleted data).
  public void registerTask(Task task) {
    synchronized (registryLock) {
      Task existing = tasks.get(task.tag());
      if (existing == null) {
        LOG.log(Level.INFO, () -> "Task " + task.tag() + " registered in " + this);
      } else if (existing.mtime().isBefore(task.mtime())
        || (existing.mtime().isEqual(task.mtime()) && task.isDeleted() && !existing.isDeleted())) {
        tasks.remove(task.tag());
        LOG.log(Level.INFO, () -> "Replaced already registered task " + task.tag() + " with the one with newer timestamp");
      } else {
        LOG.log(Level.INFO, () -> "Kept already registered task " + task.tag() + " with newer timestamp");
        return;
      }
      tasks.put(task.tag(), task);
    }
  }

  /// Commit the given newly created task performing its one-time backend
  /// initialization (the task registers itself upon the success only - see
  /// [Task#commit]), then commit the live volumes persisting the result.
  public Manager commit(Task task) {
    task.commit();
    return commit();
  }

  /// Drop the task from the registry: the abort of a failed materialization of a
  /// simulated creation (the real one-time backend initialization rejecting the
  /// task the simulated one accepted), or a frontend's cleanup of an entity it is
  /// about to replace.
  public void deregisterTask(Task task) {
    synchronized (registryLock) {
      tasks.remove(task.tag());
    }
  }

  /// Replace an existing task in the registry (e.g. upon an operation type change).
  public void replaceTask(Task task) {
    synchronized (registryLock) {
      tasks.put(task.tag(), task);
      LOG.log(Level.INFO, () -> "Task " + task.tag() + " replaced in " + this);
    }
  }

  /// Drop the volume from the registry: a frontend's cleanup of an entity it is
  /// about to replace (a volume re-created over the occupied root). The creation
  /// itself needs no rollback anymore: the registration rides the successful
  /// pristine commit only (see [Volume#create]).
  public void deregisterVolume(Volume volume) {
    synchronized (registryLock) {
      volumes.remove(volume.tag());
    }
  }

  /// Return the registered live volumes.
  public Map<String, Volume> liveVolumes() {
    synchronized (registryLock) {
      return volumes.values().stream()
        .filter(Volume::isLive)
        .collect(Collectors.toMap(Volume::tag, v -> v, (a, b) -> a, LinkedHashMap::new));
    }
  }

  /// Return the registered live tasks. The deletion tombstones are excluded: they are
  /// invisible for all the operations until the garbage collection removes them.
  public Map<String, Task> liveTasks() {
    synchronized (registryLock) {
      return tasks.values().stream()
        .filter(t -> t.isLive() && !t.isDeleted())
        .collect(Collectors.toMap(Task::tag, t -> t, (a, b) -> a, LinkedHashMap::new));
    }
  }

  /// Return the intact tasks matched by the specified (partial) tags or all intact tasks if no tags given.
  /// Every tag part must match exactly one task.
  public Map<String, Task> selectTasks(List<String> tags) {
    Map<String, Task> intact = new LinkedHashMap<>();
    for (Volume volume : volumes().values()) intact.putAll(volume.intactTasks());
    if (tags == null || tags.isEmpty()) {
      // The mass run: the tasks whose runtime execution status excludes them are
      // skipped (they run when requested specifically by their tags)
      intact.values().removeIf(t -> !t.isRunnable());
      return intact;
    }
    Map<String, Task> selected = new LinkedHashMap<>();
    for (Task t : pick(intact, tags, 1)) selected.put(t.tag(), t);
    return selected;
  }

  /// Return the intact tasks matched by the specified (partial) tags or all intact tasks if no tags given.
  public Map<String, Task> selectTasks(String... tags) {
    return selectTasks(Arrays.asList(tags));
  }

  /// Return the live volumes matched by the specified (partial) tags. Every tag part must match exactly one volume.
  public List<Volume> selectVolumes(List<String> tags) {
    return pick(liveVolumes(), tags, 1);
  }

  /// Return the live tasks matched by the specified (partial) tags. Every tag part must match exactly one task.
  public List<Task> selectLiveTasks(List<String> tags) {
    return pick(liveTasks(), tags, 1);
  }

  /// Return the live task covering the specified source-destination endpoint pair or null.
  /// The endpoints are compared by their storage descriptors, the direction matters.
  /// The deletion tombstones are invisible and never match.
  public Task findTask(Endpoint source, Endpoint destination) {
    JsonNode sourceDescriptor = source.serialize();
    JsonNode destinationDescriptor = destination.serialize();
    for (Task task : tasks().values()) {
      if (!task.isLive() || task.isDeleted()) continue;
      if (task.source().serialize().equals(sourceDescriptor) && task.destination().serialize().equals(destinationDescriptor)) return task;
    }
    return null;
  }

  /// Physically remove the collected tombstones whose referred volumes are all registered:
  /// the flagged tasks are dropped from the storages upon the following commit. The
  /// tombstones referring the unregistered volumes are retained for the conflict propagation.
  private void collectGarbage() {
    for (Task task : tasks().values()) {
      if (!task.isDeleted() || !task.isLive()) continue;
      boolean allPresent = task.referredVolumes().stream().allMatch(vtag -> volume(vtag) != null);
      if (allPresent) {
        task.collect();
        LOG.log(Level.INFO, () -> "Collected the deleted task " + task.tag());
      }
    }
  }

  /// Select the entities matched by the specified tag parts via the substring rules.
  /// When per-tag is not null every tag part must match exactly that many entities.
  static <T> List<T> pick(Map<String, T> entities, List<String> tags, Integer perTag) {
    List<T> xs = new ArrayList<>();
    for (String t : tags) {
      List<T> es = entities.entrySet().stream()
        .filter(e -> Tags.matches(t, e.getKey()))
        .map(Map.Entry::getValue)
        .toList();
      if (perTag != null) {
        if (es.isEmpty()) throw new BitferryError("no entries matching specified tag part <" + t + ">");
        if (es.size() > perTag) throw new BitferryError("too many entries matching specified tag part <" + t + ">");
      }
      xs.addAll(es);
    }
    return xs;
  }

  /// Probe the specified roots for the volumes restoring the discovered ones. Only the
  /// specified roots are probed: the global probing policy (the user's home directory,
  /// the BITFERRY_PATH directories, the mounted file systems) is a caller's concern -
  /// the CLI composes it in CliUtils.restoreManager keeping the direct Manager use
  /// (the tests, the embedders) isolated from the host environment.
  public Manager restore(List<Path> roots, Consumer<String> errorConsumer) {
    for (Path root : roots.stream().map(Bitferry::canonicalize).distinct().toList()) {
      try {
        // The probe no longer declares IOException (the modern JDK convenience probes
        // resolve the access errors into the false return), so the guard is the
        // unchecked SecurityException only
        if (!java.nio.file.Files.isRegularFile(root.resolve(Bitferry.STORAGE))) {
          LOG.log(Level.DEBUG, () -> "No Bitferry volume storage found in " + root);
          continue;
        }
      } catch (SecurityException e) {
        LOG.log(Level.DEBUG, () -> "Cannot access " + root + ": " + e.getMessage());
        continue;
      }
      LOG.log(Level.INFO, () -> "Probing " + root + " for Bitferry volume");
      try {
        Volume volume = Volume.restore(this, root);
        LOG.log(Level.INFO, () -> "Restored Bitferry volume from " + root);
        // The per-task restore failures (the preserved unparsable descriptors) travel
        // into the same loadErrors record as the whole-volume ones: the frontends
        // surface both uniformly
        List<String> errors = volume.restoreErrors();
        if (!errors.isEmpty()) {
          synchronized (registryLock) {
            loadErrors.addAll(errors);
          }
        }
      } catch (Exception e) {
        // The message stays plain: it travels into the loadErrors record (the API
        // data) and the JUL console handler verbatim; the colorization is the
        // frontends' concern
        String message = "Failed to restore Bitferry volume from " + root + ": " + e.getMessage();
        LOG.log(Level.ERROR, () -> message);
        synchronized (registryLock) {
          loadErrors.add(message);
        }
        if (errorConsumer != null) errorConsumer.accept(message);
      }
    }
    // The tombstones whose referred volumes have all become visible are collected:
    // their physical removal from the storages materializes upon the following commit
    collectGarbage();
    return this;
  }

  public Manager restore(List<Path> roots) {
    return restore(roots, null);
  }

  /// The mount points of the presently mounted file systems — the
  /// psutil.disk_partitions() equivalent built upon the module file stores. The
  /// file store's textual form carries the mount point as {@code mount (source)}.
  /// No filtering is applied: the skip lists are operating system specific, while
  /// the probe itself is a single stat per mount - the false positives are next
  /// to free and the false negatives lose the volumes.
  public static List<Path> mountPoints() {
    List<Path> mounts = new ArrayList<>();
    for (Path root : FileSystems.getDefault().getRootDirectories()) {
      mounts.add(root);
    }
    for (FileStore store : FileSystems.getDefault().getFileStores()) {
      Path mount = mountPoint(store);
      if (mount != null && !mounts.contains(mount)) mounts.add(mount);
    }
    return mounts;
  }

  /// Extract the mount point out of the file store's textual {@code mount (source)} or
  /// Windows {@code [label ](drive:)} form.
  static Path mountPoint(FileStore store) {
    String text = store.toString();
    if (!text.endsWith(")")) return null;
    // The mount is the prefix before the final parenthesized group (the file store
    // name); the name may itself carry parentheses (a bind-mount or a network source
    // with a paren in the path), so the opening paren is located by matching the
    // bracket depth backward from the end - a plain lastIndexOf(" (") would split
    // inside the name and truncate the mount ("​/mnt/data (/mnt/other (x))" used to
    // yield "/mnt/data (/mnt/other")
    int open = trailingGroupOpenParen(text);
    if (open > 0) {
      String inside = text.substring(open + 2, text.length() - 1);
      if (inside.matches("^[A-Za-z]:.*")) {
        return Path.of(inside.endsWith("\\") ? inside : inside + "\\");
      }
      return Path.of(text.substring(0, open));
    }
    if (text.startsWith("(") && text.endsWith(")")) {
      String inside = text.substring(1, text.length() - 1);
      if (inside.matches("^[A-Za-z]:.*")) {
        return Path.of(inside.endsWith("\\") ? inside : inside + "\\");
      }
    }
    return null;
  }

  /// The index of the " (" separator opening the final balanced group (the one
  /// closed by the trailing ')'), or -1 when the text carries no such group in the
  /// expected form. The zero index is reported for the unlabeled "(drive:)" form.
  private static int trailingGroupOpenParen(String text) {
    int depth = 1; // Scanning starts inside the group closed by the trailing ')'
    for (int i = text.length() - 2; i >= 0; i--) {
      char c = text.charAt(i);
      if (c == ')') depth++;
      else if (c == '(') {
        depth--;
        if (depth == 0) {
          // The separator is the " (" form (the caller skips both characters);
          // the leading paren of the unlabeled Windows form reports zero
          if (i > 0 && text.charAt(i - 1) == ' ') return i - 1;
          return i == 0 ? 0 : -1;
        }
      }
    }
    return -1;
  }

  /// Probe the specified roots for the volumes restoring the discovered ones.
  public Manager restore(Path... roots) {
    return restore(Arrays.asList(roots));
  }

  /// Return true if any registered live volume is modified.
  public boolean isModified() {
    return volumes().values().stream().anyMatch(v -> v.isLive() && v.isModified());
  }

  /// Commit all the live volumes raising [BitferryError] on a caught failure of any of
  /// them. The garbage collection is deliberately not performed here: the freshly
  /// recorded tombstones must persist within this commit (collecting them beforehand
  /// would drop the deletion markers before they ever reach the storages) - the
  /// physical tombstone removal happens at the following restore.
  public Manager commit() {
    List<String> problems = new ArrayList<>();
    for (Volume volume : volumes().values()) {
      if (!volume.isLive()) continue;
      try {
        volume.commit();
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "Commit failure(s) for Bitferry volume " + volume.tag() + ": " + e.getMessage());
        problems.add(e.getMessage());
      }
    }
    if (!problems.isEmpty()) throw new BitferryError(String.join("; ", problems));
    return this;
  }

  /// Process the intact tasks matched by the specified (partial) tags or all intact tasks
  /// forwarding output to the consumer (null for CLI pass-through). The task-level
  /// failures (BitferryError - e.g. a vanished volume, a dropped vault token) isolate to
  /// the failing task and fail the outcome: the remaining tasks still run.
  public boolean process(List<String> tags, java.util.function.Consumer<String> output) {
    boolean outcome = true;
    for (Task t : selectTasks(tags).values()) {
      try {
        outcome = t.process(output) && outcome;
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "Task " + t.tag() + " processing failure: " + e.getMessage());
        outcome = false;
      }
    }
    return outcome;
  }

  /// Process the intact tasks matched by the specified (partial) tags or all intact tasks with CLI pass-through.
  public boolean process(List<String> tags) {
    return process(tags, null);
  }

  /// Process the intact tasks with captured output.
  public boolean process(java.util.function.Consumer<String> output, String... tags) {
    return process(Arrays.asList(tags), output);
  }

  /// Change or update the repository password for a restic task, propagating the new
  /// token to the registered co-repository tasks. Return the tags of the invisible
  /// co-repository tasks whose stored tokens could not be updated (their volumes are
  /// absent) - those tasks hold the stale token until it is re-synced (by the
  /// auto-heal upon their next run, or by re-entering the password).
  public List<String> passwd(Restic restic, String newPassword) {
    return backendExecutor().passwd(restic, newPassword);
  }

  /// Process the intact tasks matched by the specified (partial) tags or all intact tasks with CLI pass-through.
  public boolean process(String... tags) {
    return process(Arrays.asList(tags), null);
  }

  /// Service the intact tasks matched by the specified (partial) tags or all intact tasks
  /// forwarding output to the consumer (null for CLI pass-through). The task-level
  /// failures (BitferryError) isolate to the failing task and fail the outcome: the
  /// remaining tasks still run.
  public boolean serve(List<String> tags, java.util.function.Consumer<String> output) {
    boolean outcome = true;
    for (Task t : selectTasks(tags).values()) {
      try {
        outcome = t.serve(output) && outcome;
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "Task " + t.tag() + " servicing failure: " + e.getMessage());
        outcome = false;
      }
    }
    return outcome;
  }

  /// Service the intact tasks matched by the specified (partial) tags or all intact tasks with CLI pass-through.
  public boolean serve(List<String> tags) {
    return serve(tags, null);
  }

  /// Service the intact tasks with captured output.
  public boolean serve(java.util.function.Consumer<String> output, String... tags) {
    return serve(Arrays.asList(tags), output);
  }

  /// Service the intact tasks matched by the specified (partial) tags or all intact tasks with CLI pass-through.
  public boolean serve(String... tags) {
    return serve(Arrays.asList(tags), null);
  }

  /// Return the backend executor used for the task processing.
  public Executor backendExecutor() {
    return backendExecutor;
  }

  /// Set the backend executor used for the task processing.
  public Manager backendExecutor(Executor executor) {
    this.backendExecutor = executor;
    return this;
  }

  @Override
  public String toString() {
    return "manager@" + Integer.toHexString(hashCode());
  }
}
