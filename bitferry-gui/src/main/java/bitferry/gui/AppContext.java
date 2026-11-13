package bitferry.gui;

import bitferry.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.function.Consumer;

/// Shared GUI state: the manager instance with its probing policy, the single-threaded
/// executor serializing all the core (blocking) operations off the JavaFX application
/// thread, and the change notification fan-out for the model-driven UI parts.
///
/// <p>The manager reference is swapped wholesale upon the reload (the volumes appear and
/// disappear along with the mounted file systems), so the readers must hold it in a local
/// variable for the multi-call sequences; the field itself is volatile. All the manager
/// mutations travel through the single operations thread: the reads (the table rebuilds)
/// snapshot the unmodifiable live maps on the application thread.
public final class AppContext {

  /// The serialized core operations executor; daemon threads so the GUI exits promptly.
  private final ExecutorService operations = Executors.newSingleThreadExecutor(x -> {
    Thread thread = new Thread(x, "bitferry-operations");
    thread.setDaemon(true);
    return thread;
  });

  private volatile Manager manager;

  // The model change listeners invoked on the operations thread upon the manager swap;
  // the UI parts re-dispatch to the application thread on their own. Copy-on-write:
  // the registrations (the application thread) must not race the fan-out iterations
  // (the operations thread).
  private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

  /// The owner window the dialogs and alerts parent to (set by the main window upon
  /// its assembly); null until then - the ownerless dialogs remain the fallback.
  public volatile javafx.stage.Window ownerWindow;

  /// The log sink receiving the GUI-level messages (the task output travels through
  /// the processing runner instead). Configured by the main window upon its assembly.
  public volatile Consumer<String> logger = message -> {};

  /// The backend verbosity switch (the toolbar toggle); re-applied to every manager
  /// instance since the manager swap upon the reload resets it to the default.
  private volatile boolean verbose;

  /// The run-in-progress gate consulted by the media watcher (wired by the main
  /// window once the runner exists; the default keeps the watcher ungated).
  public volatile java.util.function.BooleanSupplier busy = () -> false;

  /// The media change watcher handle (null until [startMediaWatcher]).
  private MediaWatch mediaWatch;

  public AppContext() {
    manager = new Manager();
    manager.verbose(verbose);
  }

  public Manager manager() {
    return manager;
  }

  /// The current backend verbosity flag.
  public boolean verbose() {
    return verbose;
  }

  /// Set the backend verbosity applying it to the live manager immediately (the
  /// application thread; the flag is also carried over the manager swaps).
  public void verbose(boolean value) {
    verbose = value;
    manager.verbose(value);
  }

  /// Return the operations executor the core operations must be submitted to.
  public ExecutorService operations() {
    return operations;
  }

  /// Register the model change listener (invoked after every manager swap).
  public void onModelChanged(Runnable listener) {
    listeners.add(listener);
  }

  /// Restore the manager probing the policy roots: the BITFERRY_PATH directories,
  /// the user's home directory and all the mounted file systems (mirroring the CLI
  /// probing policy). Runs on the operations thread (or directly before the UI starts).
  public void reload() {
    operations.execute(() -> {
      Manager restored = restoreNow();
      manager = restored;
      logger.accept("manager restored: " + restored.liveVolumes().size() + " volume(s), "
        + restored.liveTasks().size() + " task(s)");
      fireModelChanged();
    });
  }

  /// Start the media change watcher: the mount-set changes (the media arrival and
  /// removal) automatically re-probe the manager without the manual Reload. The
  /// re-probe is deferred while a run is in progress (the [busy] gate) and skipped
  /// entirely when the change does not touch any bitferry volume or task (the
  /// unrelated mounts must not spam the log nor flicker the tables).
  public void startMediaWatcher() {
    mediaWatch = MediaWatch.start(this::reloadOnMediaChange, () -> !busy.getAsBoolean(), Manager::mountSetSignature);
  }

  /// The media watcher consumer (the watcher thread): route the re-probe through
  /// the operations executor. The changes observed while the gate is closed are
  /// kept outstanding by the watcher and re-fired later.
  private void reloadOnMediaChange() {
    operations.execute(() -> {
      Manager current = manager;
      Manager restored = restoreNow();
      manager = restored;
      // The no-op suppression: the unrelated mount churn produces the identical
      // live model - swap silently (the fresh file store identities still matter
      // for the presence checks) without the log noise nor the table flicker
      Map<String, Path> currentRoots = new LinkedHashMap<>();
      if (current != null) {
        for (Volume v : current.liveVolumes().values()) currentRoots.put(v.tag(), v.root());
      }
      Map<String, Path> restoredRoots = new LinkedHashMap<>();
      for (Volume v : restored.liveVolumes().values()) restoredRoots.put(v.tag(), v.root());
      if (current != null
        && currentRoots.equals(restoredRoots)
        && current.liveTasks().keySet().equals(restored.liveTasks().keySet())
        && current.selectTasks().keySet().equals(restored.selectTasks().keySet())) {
        return;
      }
      logger.accept("media change detected - manager restored: " + restored.liveVolumes().size()
        + " volume(s), " + restored.liveTasks().size() + " task(s)");
      fireModelChanged();
    });
  }

  // The shared restore: the probing policy roots + the verbosity carry-over.
  private Manager restoreNow() {
    List<Path> roots = new ArrayList<>();
    String env = System.getenv("BITFERRY_PATH");
    if (env != null && !env.isBlank()) {
      for (String entry : env.split(Pattern.quote(File.pathSeparator))) roots.add(Path.of(entry));
    }
    roots.add(Path.of(System.getProperty("user.home")));
    roots.addAll(Manager.mountPoints());
    Manager restored = new Manager().restore(roots);
    restored.verbose(verbose);
    return restored;
  }

  /// Notify the UI parts about a model mutation (a committed creation or deletion)
  /// without the manager swap. The callers run on the operations thread; the
  /// listeners re-dispatch to the application thread on their own.
  public void fireModelChanged() {
    for (Runnable listener : listeners) listener.run();
  }

  @Override
  public String toString() {
    return "gui@" + Integer.toHexString(hashCode());
  }
}
