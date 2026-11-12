package bitferry.gui;

import bitferry.*;
import bitferry.task.*;
import javafx.application.Platform;

import java.util.*;
import java.util.function.*;

/// The background task processing orchestrator: runs the intact tasks sequentially
/// on the context's operations thread, streams the backend output into a dedicated
/// run log tab and drives the progress bar.
///
/// <p>The runner is single-flight: the submit while a run is in progress is rejected
/// (the core enforces the same re-entrancy guard per task). The busy flag is mirrored
/// on the application thread so the toolbar disables the mutating actions meanwhile.
public final class Runner {

  /// The run progress listener; every method is invoked on the application thread.
  public interface Listener {
    /// The run started: the total task count is known.
    void runStarted(int total);

    /// The next task is about to run (1-based index).
    void taskStarted(int index, int total, String tag);

    /// One task has finished with the running totals.
    void taskFinished(int index, int total, int ok, int failed);

    /// The whole run finished with the outcome summary.
    void runFinished(boolean outcome, int ok, int failed);
  }

  private final AppContext context;
  private final LogController logs;

  private volatile boolean busy;

  public Runner(AppContext context, LogController logs) {
    this.context = context;
    this.logs = logs;
  }

  public boolean isBusy() {
    return busy;
  }

  /// Run the intact tasks matched by the specified (partial) tags (or all of them)
  /// reporting into the specified listener. Returns false when rejected (busy).
  public boolean submit(List<String> tags, Listener listener) {
    return dispatch(tags, listener, false);
  }

  /// Service the intact tasks matched by the specified (partial) tags (or all of
  /// them) — the integrity verification, retention and garbage collection pass.
  /// Returns false when rejected (busy).
  public boolean submitServe(List<String> tags, Listener listener) {
    return dispatch(tags, listener, true);
  }

  // The shared sequential run loop over the selected tasks (execute or serve)
  private boolean dispatch(List<String> tags, Listener listener, boolean serve) {
    if (busy) return false;
    busy = true;
    context.operations().execute(() -> {
      int ok = 0;
      int failed = 0;
      try {
        Manager manager = context.manager();
        List<Task> tasks = new ArrayList<>(manager.selectTasks(tags).values());
        if (tasks.isEmpty()) {
          logs.log("no intact tasks to " + (serve ? "service" : "execute"));
          done(listener, true, 0, 0);
          return;
        }
        int total = tasks.size();
        Platform.runLater(() -> listener.runStarted(total));
        Consumer<String> output = logs.openRun((serve ? "service" : "run") + ": " + tasks.size() + " task(s)");
        int index = 0;
        for (Task task : tasks) {
          index++;
          int at = index;
          Platform.runLater(() -> listener.taskStarted(at, total, task.tag()));
          output.accept(String.format("[%d/%d] #%s  %s", at, total, task.tag(), task.describe()));
          boolean okTask;
          try {
            okTask = serve ? task.serve(output) : task.process(output);
          } catch (BitferryError e) {
            output.accept("error: " + e.getMessage());
            okTask = false;
          } catch (RuntimeException e) {
            output.accept("unexpected failure: " + e);
            okTask = false;
          }
          output.accept(okTask ? "    ✓ success" : "    ✗ failure");
          if (okTask) ok++; else failed++;
          int okCount = ok;
          int failedCount = failed;
          Platform.runLater(() -> listener.taskFinished(at, total, okCount, failedCount));
        }
        done(listener, failed == 0, ok, failed);
      } catch (RuntimeException e) {
        // The runner must never wedge the toolbar: report and finish the run
        logs.log("run aborted: " + e);
        done(listener, false, ok, failed);
      }
    });
    return true;
  }

  private void done(Listener listener, boolean outcome, int ok, int failed) {
    busy = false;
    Platform.runLater(() -> listener.runFinished(outcome, ok, failed));
  }
}
