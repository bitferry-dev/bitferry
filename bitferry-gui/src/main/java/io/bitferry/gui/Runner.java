package io.bitferry.gui;

import io.bitferry.*;
import io.bitferry.task.*;
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
    default void taskFinished(int index, int total, int ok, int partial, int failed) {
      taskFinished(index, total, ok, failed);
    }

    default void taskFinished(int index, int total, int ok, int failed) {}

    /// The whole run finished with the outcome summary.
    default void runFinished(boolean outcome, int ok, int partial, int failed) {
      runFinished(outcome, ok, failed);
    }

    default void runFinished(boolean outcome, int ok, int failed) {}

    /// The run was aborted by the user: the running backend has been terminated and
    /// the remaining tasks skipped.
    default void runAborted(int ok, int partial, int failed) {
      runAborted(ok, failed);
    }

    default void runAborted(int ok, int failed) {}
  }

  private final AppContext context;
  private final LogController logs;

  private volatile boolean busy;

  // The abort request flag: set on the application thread (or anywhere), polled by
  // the run loop before every task; the running backend process is torn down eagerly
  private volatile boolean aborted;

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

  /// Abort the run in progress: terminate the currently executing backend process
  /// and stop before the next task. No-op when no run is in progress. Safe to call
  /// from any thread.
  public void abort() {
    if (!busy) return;
    aborted = true;
    int terminated = BackendProcesses.abort();
    logs.log(terminated > 0
      ? "Abort requested: terminating the backend process(es)"
      : "Abort requested: stopping before the next task");
    if (terminated > 0) {
      // WINDOWS: the terminated backend cannot clean its repository lock up
      // (TerminateProcess is immediate - no signal, no graceful exit): point at
      // the repair action so the following 'already locked' failure is not a mystery
      logs.log("\u001B[33mThe terminated backend may leave a stale repository lock - "
        + "use 'Repair task…' if a subsequent run reports the repository locked\u001B[0m");
    }
  }

  // The shared sequential run loop over the selected tasks (execute or serve)
  private boolean dispatch(List<String> tags, Listener listener, boolean serve) {
    if (busy) return false;
    busy = true;
    aborted = false;
    context.operations().execute(() -> {
      int ok = 0;
      int partial = 0;
      int failed = 0;
      try {
        Manager manager = context.manager();
        List<Task> tasks = new ArrayList<>(manager.selectTasks(tags).values());
        if (tasks.isEmpty()) {
          logs.log("\u001B[33mNo intact tasks to " + (serve ? "service" : "execute") + "\u001B[0m");
          done(listener, true, 0, 0, 0);
          return;
        }
        int total = tasks.size();
        Platform.runLater(() -> listener.runStarted(total));
        String runTitle = (manager.simulate ? "simulated " : "") + (serve ? "service" : "run") + ": " + tasks.size() + " task(s)";
        Consumer<String> output = logs.openRun(runTitle);
        if (manager.simulate) {
          output.accept("\u001B[33mSimulation mode (dry run) — no on-disk changes will be made\u001B[0m");
        }
        int index = 0;
        for (Task task : tasks) {
          // The abort request skips the remaining tasks (the running backend has
          // already been terminated eagerly by the abort())
          if (aborted) break;
          index++;
          int at = index;
          Platform.runLater(() -> listener.taskStarted(at, total, task.tag()));
          output.accept(String.format("\u001B[1m[%d/%d]\u001B[0m %s  %s", at, total, AnsiFormat.tag(task.tag()), AnsiFormat.task(task)));
          Task.Outcome outcomeTask;
          try {
            outcomeTask = serve ? task.serve(output) : task.process(output);
          } catch (BitferryError e) {
            output.accept("\u001B[31mError: " + e.getMessage() + "\u001B[0m");
            outcomeTask = Task.Outcome.FAILURE;
          } catch (RuntimeException e) {
            output.accept("\u001B[31mUnexpected failure: " + e + "\u001B[0m");
            outcomeTask = Task.Outcome.FAILURE;
          }
          switch (outcomeTask) {
            case SUCCESS -> {
              output.accept("\u001B[32m✓ success\u001B[0m");
              ok++;
            }
            case PARTIAL -> {
              output.accept("\u001B[33m⚠ partial\u001B[0m");
              partial++;
            }
            case FAILURE -> {
              output.accept("\u001B[31m✗ failure\u001B[0m");
              failed++;
            }
          }
          int okCount = ok;
          int partialCount = partial;
          int failedCount = failed;
          Platform.runLater(() -> listener.taskFinished(at, total, okCount, partialCount, failedCount));
        }
        // Persist the run bookkeeping (e.g. the equalize bisync baseline marker): only
        // the volumes whose state actually changed are rewritten. A failing commit is
        // reported without failing the run: the unmaterialized bookkeeping flips
        // re-materialize upon the following commits.
        if (manager.isModified()) {
          try {
            manager.commit();
          } catch (RuntimeException e) {
            output.accept("\u001B[31mError: post-run commit failed: " + e.getMessage() + "\u001B[0m");
          }
        }
        if (aborted) {
          output.accept("\u001B[31m✗ run aborted\u001B[0m");
          doneAborted(listener, ok, partial, failed);
        } else {
          output.accept("");
          String summary = failed > 0
            ? String.format("\u001B[31mSome of the %d task(s) failed to %s.\u001B[0m", total, serve ? "service" : "execute")
            : partial > 0
              ? String.format("\u001B[33mSome of the %d task(s) %s with warnings (partial success).\u001B[0m", total, serve ? "serviced" : "executed")
              : String.format("\u001B[32mAll %d task(s) %s successfully.\u001B[0m", total, serve ? "serviced" : "executed");
          // The simulation reminder rides the summary: a dry run's "executed
          // successfully" reads exactly like a real one, and the durable bookkeeping
          // (the repository state, the bisync baselines) recorded nothing
          if (manager.simulate) {
            summary = summary + " \u001B[33m(simulated)\u001B[0m";
          }
          output.accept(summary);
          done(listener, failed == 0 && partial == 0, ok, partial, failed);
        }
      } catch (Throwable t) {
        // The runner must never wedge the toolbar: report and finish the run. The
        // Throwable (not the RuntimeException) - an Error escaping here would leave
        // the busy flag set forever (the whole GUI gated on it until the restart)
        logs.log("Run failed: " + t);
        done(listener, false, ok, partial, failed);
      }
    });
    return true;
  }

  private void done(Listener listener, boolean outcome, int ok, int partial, int failed) {
    busy = false;
    aborted = false;
    Platform.runLater(() -> listener.runFinished(outcome, ok, partial, failed));
  }

  private void doneAborted(Listener listener, int ok, int partial, int failed) {
    busy = false;
    aborted = false;
    Platform.runLater(() -> listener.runAborted(ok, partial, failed));
  }
}
