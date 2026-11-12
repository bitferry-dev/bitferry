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
  // the UI parts re-dispatch to the application thread on their own
  private final List<Runnable> listeners = new ArrayList<>();

  /// The log sink receiving the GUI-level messages (the task output travels through
  /// the processing runner instead). Configured by the main window upon its assembly.
  public volatile Consumer<String> logger = message -> {};

  public AppContext() {
    manager = new Manager();
  }

  public Manager manager() {
    return manager;
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
      List<Path> roots = new ArrayList<>();
      String env = System.getenv("BITFERRY_PATH");
      if (env != null && !env.isBlank()) {
        for (String entry : env.split(Pattern.quote(File.pathSeparator))) roots.add(Path.of(entry));
      }
      roots.add(Path.of(System.getProperty("user.home")));
      roots.addAll(Manager.mountPoints());
      Manager restored = new Manager().restore(roots);
      manager = restored;
      logger.accept("manager restored: " + restored.liveVolumes().size() + " volume(s), "
        + restored.liveTasks().size() + " task(s)");
      fireModelChanged();
    });
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
