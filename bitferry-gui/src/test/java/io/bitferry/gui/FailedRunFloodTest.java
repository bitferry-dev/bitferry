package io.bitferry.gui;

import io.bitferry.Manager;
import io.bitferry.Volume;
import io.bitferry.endpoint.Endpoints;
import io.bitferry.task.Copy;
import io.bitferry.task.Executor;
import io.bitferry.task.SystemCommandExecutor;
import io.bitferry.task.Task;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// The failed-run UI responsiveness probe: a backend flooding the captured output
/// (the retry-storm shape of the rclone failures, exit code 6 among them) followed
/// by the process death must leave the FX application thread responsive: the run
/// finishes, the completion callbacks fire, and the heartbeat gaps stay bounded.
class FailedRunFloodTest {

  private static final CountDownLatch FX_READY = new CountDownLatch(1);

  @BeforeAll
  static void startFx() throws Exception {
    try {
      Platform.startup(FX_READY::countDown);
      if (!FX_READY.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the FX toolkit did not start");
    } catch (IllegalStateException ignored) {
      // Already started by another test class in this JVM
    }
    Platform.setImplicitExit(false);
  }

  /// The flood child agent: 200000 failure lines into the captured stdout followed
  /// by the exit code 6 (the retry-storm shape). Doubles as the JVM-spawned process
  /// so the test stays portable across the platforms (no shell pipeline dependency).
  public static void main(String[] args) throws IOException {
    if (args.length > 0 && "flood".equals(args[0])) {
      StringBuilder flood = new StringBuilder();
      for (int i = 1; i <= 200000; i++) {
        flood.append("Failed to copy: retries exhausted: ").append(i).append('\n');
      }
      try (BufferedWriter out = new BufferedWriter(new java.io.OutputStreamWriter(System.out))) {
        out.write(flood.toString());
        out.flush();
      }
      System.exit(6);
    }
  }

  /// The OS-aware JVM spawn command carrying the current classpath (mirroring the
  /// SystemCommandExecutorPassThroughTest pattern).
  private static List<String> floodCommand() {
    String java = Path.of(System.getProperty("java.home"), "bin",
      System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    List<String> cmd = new ArrayList<>();
    cmd.add(java);
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(FailedRunFloodTest.class.getName());
    cmd.add("flood");
    return cmd;
  }

  @Test
  void failedRunKeepsFxResponsive() throws Exception {
    CountDownLatch sceneReady = new CountDownLatch(1);
    AtomicReference<LogController> logsRef = new AtomicReference<>();
    AtomicReference<Stage> stageRef = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/io/bitferry/gui/log.fxml"));
        TabPane root = loader.load();
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 600, 300));
        stage.show();
        stageRef.set(stage);
        logsRef.set(loader.getController());
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        sceneReady.countDown();
      }
    });
    assertTrue(sceneReady.await(10, TimeUnit.SECONDS), "scene setup timed out");
    LogController logs = logsRef.get();

    AppContext context = new AppContext();
    Manager manager = context.manager();
    // The real captured-mode process mechanics; the fake seam substitutes the flood
    // for whatever argv the task builds (the actual rclone binary is not needed)
    manager.backendExecutor(Executor.system((command, dir, env, output) ->
      SystemCommandExecutor.INSTANCE.execute(floodCommand(), null, null, output)));
    manager.simulate(false);

    // The mass-run selection aggregates the volume-owned tasks only, so the task
    // endpoints must reference a registered volume (the flood seam substitutes the
    // rclone execution; the endpoints are never touched by the backend)
    Volume vol = Volume.create(manager, Files.createTempDirectory("floodtest-vol-"));
    Copy task = new Copy(manager, Endpoints.parse(manager, ":" + vol.tag() + ":src"), Endpoints.parse(manager, ":" + vol.tag() + ":dst"),
      null, List.of(), List.of());
    manager.registerTask(task);

    Runner runner = new Runner(context, logs);

    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<long[]> totals = new AtomicReference<>(new long[]{-1, -1});
    Runner.Listener listener = new Runner.Listener() {
      @Override public void runStarted(int total) {}
      @Override public void taskStarted(int index, int total, String tag) {}
      @Override public void taskFinished(int index, int total, int ok, int failed) {
        totals.set(new long[]{ok, failed});
      }
      @Override public void runFinished(boolean outcome, int ok, int failed) { finished.countDown(); }
      @Override public void runAborted(int ok, int failed) { finished.countDown(); }
    };

    // The FX-thread heartbeat: a runLater ping every 50 ms; the gaps between the
    // successive pings quantify the application thread's responsiveness
    AtomicLong lastPing = new AtomicLong(System.nanoTime());
    AtomicLong maxGap = new AtomicLong(0);
    ScheduledExecutorService heart = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "fx-heartbeat");
      t.setDaemon(true);
      return t;
    });
    heart.scheduleAtFixedRate(() -> Platform.runLater(() -> {
      long now = System.nanoTime();
      long gap = TimeUnit.NANOSECONDS.toMillis(now - lastPing.getAndSet(now));
      maxGap.accumulateAndGet(gap, Math::max);
    }), 50, 50, TimeUnit.MILLISECONDS);

    try {
      assertTrue(runner.submit(List.of(), listener), "runner rejected the submit");

      // The run must complete: the flood plus the exit code 6 must not wedge the
      // pipeline (the completion callback is a runLater queued behind the flushes)
      assertTrue(finished.await(120, TimeUnit.SECONDS),
        () -> "run did not finish within 120 s; max FX heartbeat gap "
          + TimeUnit.MILLISECONDS.toSeconds(maxGap.get()) + " s");
      assertArrayEquals(new long[]{0, 1}, totals.get(), "the failed task must report 0 ok, 1 failed");

      // The window must stay interactive throughout: the heartbeat rides the same
      // application thread as the input handling; a stalled thread is a locked UI
      long gapSeconds = TimeUnit.MILLISECONDS.toSeconds(maxGap.get());
      assertTrue(gapSeconds < 10, () -> "FX thread stalled for " + gapSeconds + " s during the failed run");
      System.out.println("FailedRunFloodTest: max FX heartbeat gap " + maxGap.get() + " ms");
    } finally {
      heart.shutdownNow();
      CountDownLatch closed = new CountDownLatch(1);
      Platform.runLater(() -> {
        Stage stage = stageRef.get();
        if (stage != null) stage.close();
        closed.countDown();
      });
      closed.await(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void partialRunReportsWarningAndTracksPartialCount() throws Exception {
    CountDownLatch sceneReady = new CountDownLatch(1);
    AtomicReference<LogController> logsRef = new AtomicReference<>();
    AtomicReference<Stage> stageRef = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/io/bitferry/gui/log.fxml"));
        TabPane root = loader.load();
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 600, 300));
        stage.show();
        stageRef.set(stage);
        logsRef.set(loader.getController());
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        sceneReady.countDown();
      }
    });
    assertTrue(sceneReady.await(10, TimeUnit.SECONDS), "scene setup timed out");
    LogController logs = logsRef.get();

    AppContext context = new AppContext();
    Manager manager = context.manager();
    manager.backendExecutor(new Executor() {
      public Task.Outcome run(Task task, Consumer<String> output) {
        if (output != null) output.accept("Warning: some files unreadable");
        return Task.Outcome.PARTIAL;
      }
    });
    manager.simulate(false);

    Volume vol = Volume.create(manager, Files.createTempDirectory("partial-vol-"));
    Copy task = new Copy(manager, Endpoints.parse(manager, ":" + vol.tag() + ":src"), Endpoints.parse(manager, ":" + vol.tag() + ":dst"),
      null, List.of(), List.of());
    manager.registerTask(task);

    Runner runner = new Runner(context, logs);

    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<long[]> finishedTotals = new AtomicReference<>();
    AtomicReference<Boolean> finalOutcome = new AtomicReference<>();
    Runner.Listener listener = new Runner.Listener() {
      @Override public void runStarted(int total) {}
      @Override public void taskStarted(int index, int total, String tag) {}
      @Override public void taskFinished(int index, int total, int ok, int partial, int failed) {
        finishedTotals.set(new long[]{ok, partial, failed});
      }
      @Override public void runFinished(boolean outcome, int ok, int partial, int failed) {
        finalOutcome.set(outcome);
        finished.countDown();
      }
    };

    try {
      assertTrue(runner.submit(List.of(), listener), "runner rejected the submit");
      assertTrue(finished.await(10, TimeUnit.SECONDS), "run did not finish in time");
      assertArrayEquals(new long[]{0, 1, 0}, finishedTotals.get(), "the partial task must report 0 ok, 1 partial, 0 failed");
      assertFalse(finalOutcome.get(), "partial run outcome must be false (not complete success)");
    } finally {
      CountDownLatch closed = new CountDownLatch(1);
      Platform.runLater(() -> {
        Stage stage = stageRef.get();
        if (stage != null) stage.close();
        closed.countDown();
      });
      closed.await(5, TimeUnit.SECONDS);
    }
  }
}
