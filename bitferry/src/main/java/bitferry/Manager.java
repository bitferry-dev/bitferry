package bitferry;

import bitferry.endpoint.*;
import bitferry.task.*;
import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.util.*;
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

  /// Allow potentially dangerous operations.
  public boolean force = false;

  /// Make no on-disk changes (simulation mode).
  public boolean simulate = false;

  /// Produce the least output.
  public boolean quiet = false;

  /// Output verbosity level.
  public Verbosity verbosity = Verbosity.NORMAL;

  private Executor backendExecutor = Executor.system();

  private static final System.Logger LOG = System.getLogger(Manager.class.getName());

  /// Return the registered volumes keyed by their tags.
  public Map<String, Volume> volumes() {
    return java.util.Collections.unmodifiableMap(volumes);
  }

  /// Return the registered tasks keyed by their tags.
  public Map<String, Task> tasks() {
    return java.util.Collections.unmodifiableMap(tasks);
  }

  /// Return the volume matched by the exact tag or null.
  public Volume volume(String tag) {
    return volumes.get(tag);
  }

  /// Return the task matched by the exact tag or null.
  public Task task(String tag) {
    return tasks.get(tag);
  }

  /// The verbose flag derived from the verbosity level.
  public boolean verbose() {
    return verbosity == Verbosity.VERBOSE || verbosity == Verbosity.DEBUG;
  }

  public void verbose(boolean value) {
    verbosity = value ? Verbosity.VERBOSE : Verbosity.NORMAL;
  }

  /// Resolve the specified root with respect to the one of registered volumes' root.
  /// Raises [BitferryError] if the root is not a subpath of any volume's root.
  public Endpoint endpoint(Path root) {
    Path canonical = Bitferry.canonicalize(root);
    // Volumes are probed in the longest root first order so that the nested volumes are matched before the enclosing ones
    List<Volume> sorted = volumes.values().stream()
      .filter(Volume::isLive)
      .sorted(java.util.Comparator.comparing((Volume v) -> v.root().toString().length()).reversed())
      .toList();
    for (Volume v : sorted) {
      try {
        return v.endpoint(canonical);
      } catch (BitferryError e) {
        LOG.log(Level.DEBUG, () -> "path '" + canonical + "' can not be resolved against volume #" + v.tag());
      }
    }
    throw new BitferryError("failed to resolve path '" + canonical + "' against any registered volumes in " + this);
  }

  /// Register the volume upon its creation or restoration keeping the copy with the newest timestamp.
  public void registerVolume(Volume volume) {
    Volume existing = volumes.get(volume.tag());
    if (existing == null) {
      LOG.log(Level.INFO, () -> "volume #" + volume.tag() + " registered in " + this);
    } else if (existing.mtime().isBefore(volume.mtime())) {
      volumes.remove(volume.tag());
      LOG.log(Level.INFO, () -> "replaced already registered volume #" + volume.tag() + " with the one with newer timestamp");
    } else {
      LOG.log(Level.INFO, () -> "kept already registered volume #" + volume.tag() + " with newer timestamp");
      return;
    }
    volumes.put(volume.tag(), volume);
  }

  /// Register the task upon its creation or restoration keeping the copy with the newest timestamp.
  /// Since tasks may come from both endpoints' volumes there may be already a task restored
  /// and registered; in this case the task with the newer timestamp wins.
  public void registerTask(Task task) {
    Task existing = tasks.get(task.tag());
    if (existing == null) {
      LOG.log(Level.INFO, () -> "task #" + task.tag() + " registered in " + this);
    } else if (existing.mtime().isBefore(task.mtime())) {
      tasks.remove(task.tag());
      LOG.log(Level.INFO, () -> "replaced already registered task #" + task.tag() + " with the one with newer timestamp");
    } else {
      LOG.log(Level.INFO, () -> "kept already registered task #" + task.tag() + " with newer timestamp");
      return;
    }
    tasks.put(task.tag(), task);
  }

  /// Drop the task from the registry: the abort of a failed creation (the one-time
  /// backend initialization of a pristine task may reject the task - e.g. the
  /// repository initialization over the existing repository).
  public void deregisterTask(Task task) {
    tasks.remove(task.tag());
  }

  /// Return the registered live volumes.
  public Map<String, Volume> liveVolumes() {
    return volumes.values().stream()
      .filter(Volume::isLive)
      .collect(Collectors.toMap(Volume::tag, v -> v, (a, b) -> a, LinkedHashMap::new));
  }

  /// Return the registered live tasks. The deletion tombstones are excluded: they are
  /// invisible for all the operations until the garbage collection removes them.
  public Map<String, Task> liveTasks() {
    return tasks.values().stream()
      .filter(t -> t.isLive() && !t.isDeleted())
      .collect(Collectors.toMap(Task::tag, t -> t, (a, b) -> a, LinkedHashMap::new));
  }

  /// Return the intact tasks matched by the specified (partial) tags or all intact tasks if no tags given.
  /// Every tag part must match exactly one task.
  public Map<String, Task> selectTasks(List<String> tags) {
    Map<String, Task> intact = new LinkedHashMap<>();
    for (Volume volume : volumes.values()) intact.putAll(volume.intactTasks());
    if (tags == null || tags.isEmpty()) return intact;
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
    for (Task task : tasks.values()) {
      if (!task.isLive() || task.isDeleted()) continue;
      if (task.source().serialize().equals(sourceDescriptor) && task.destination().serialize().equals(destinationDescriptor)) return task;
    }
    return null;
  }

  /// Physically remove the collected tombstones whose referred volumes are all registered:
  /// the flagged tasks are dropped from the storages upon the following commit. The
  /// tombstones referring the unregistered volumes are retained for the conflict propagation.
  private void collectGarbage() {
    for (Task task : tasks.values()) {
      if (!task.isDeleted() || !task.isLive()) continue;
      boolean allPresent = task.referredVolumes().stream().allMatch(vtag -> volume(vtag) != null);
      if (allPresent) {
        task.collect();
        LOG.log(Level.INFO, () -> "collected the deleted task #" + task.tag());
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
  public Manager restore(List<Path> roots) {
    for (Path root : roots.stream().map(Bitferry::canonicalize).distinct().toList()) {
      try {
        // The probe no longer declares IOException (the modern JDK convenience probes
        // resolve the access errors into the false return), so the guard is the
        // unchecked SecurityException only
        if (!java.nio.file.Files.isRegularFile(root.resolve(Bitferry.STORAGE))) {
          LOG.log(Level.DEBUG, () -> "no volume storage found in '" + root + "'");
          continue;
        }
      } catch (SecurityException e) {
        LOG.log(Level.DEBUG, () -> "cannot access '" + root + "': " + e.getMessage());
        continue;
      }
      LOG.log(Level.INFO, () -> "probing '" + root + "' for volume");
      try {
        Volume.restore(this, root);
        LOG.log(Level.INFO, () -> "restored volume from '" + root + "'");
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "failed to restore volume from '" + root + "': " + e.getMessage());
      }
    }
    // The tombstones whose referred volumes have all become visible are collected:
    // their physical removal from the storages materializes upon the following commit
    collectGarbage();
    return this;
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
    int open = text.lastIndexOf(" (");
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

  /// Probe the specified roots for the volumes restoring the discovered ones.
  public Manager restore(Path... roots) {
    return restore(Arrays.asList(roots));
  }

  /// Commit all the live volumes raising [BitferryError] on a caught failure of any of
  /// them. The garbage collection is deliberately not performed here: the freshly
  /// recorded tombstones must persist within this commit (collecting them beforehand
  /// would drop the deletion markers before they ever reach the storages) - the
  /// physical tombstone removal happens at the following restore.
  public Manager commit() {
    List<String> problems = new ArrayList<>();
    for (Volume volume : volumes.values()) {
      if (!volume.isLive()) continue;
      try {
        volume.commit();
      } catch (BitferryError e) {
        LOG.log(Level.ERROR, () -> "commit failure(s) for volume #" + volume.tag() + ": " + e.getMessage());
        problems.add(e.getMessage());
      }
    }
    if (!problems.isEmpty()) throw new BitferryError(String.join("; ", problems));
    return this;
  }

  /// Process the intact tasks matched by the specified (partial) tags or all intact tasks
  /// forwarding output to the consumer (null for CLI pass-through).
  public boolean process(List<String> tags, java.util.function.Consumer<String> output) {
    boolean outcome = true;
    for (Task t : selectTasks(tags).values()) {
      outcome = t.process(output) && outcome;
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

  /// Change or update the repository password for a restic task.
  public void passwd(Restic restic, String newPassword) {
    backendExecutor().passwd(restic, newPassword);
  }

  /// Process the intact tasks matched by the specified (partial) tags or all intact tasks with CLI pass-through.
  public boolean process(String... tags) {
    return process(Arrays.asList(tags), null);
  }

  /// Service the intact tasks matched by the specified (partial) tags or all intact tasks
  /// forwarding output to the consumer (null for CLI pass-through).
  public boolean serve(List<String> tags, java.util.function.Consumer<String> output) {
    boolean outcome = true;
    for (Task t : selectTasks(tags).values()) {
      outcome = t.serve(output) && outcome;
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
