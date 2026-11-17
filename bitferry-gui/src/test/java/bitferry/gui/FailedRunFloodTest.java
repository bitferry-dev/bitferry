package bitferry.gui;

import bitferry.Manager;
import bitferry.Volume;
import bitferry.endpoint.Endpoints;
import bitferry.task.Copy;
import bitferry.task.Executor;
import bitferry.task.SystemCommandExecutor;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

  @Test
  void failedRunKeepsFxResponsive() throws Exception {
    CountDownLatch sceneReady = new CountDownLatch(1);
    AtomicReference<LogController> logsRef = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/bitferry/gui/log.fxml"));
        TabPane root = loader.load();
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 600, 300));
        stage.show();
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
      SystemCommandExecutor.INSTANCE.execute(
        List.of("sh", "-c", "seq 1 200000 | sed 's/^/Failed to copy: retries exhausted: /'; exit 6"),
        null, null, output)));
    manager.simulate(false);

    // The mass-run selection aggregates the volume-owned tasks only, so the task
    // endpoints must reference a registered volume (the flood seam substitutes the
    // rclone execution; the endpoints are never touched by the backend)
    Volume vol = new Volume(manager, Path.of(System.getProperty("java.io.tmpdir"), "floodtest-vol"));
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
    }
  }
}
