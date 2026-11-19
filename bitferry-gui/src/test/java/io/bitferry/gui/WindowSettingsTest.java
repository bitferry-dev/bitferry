package io.bitferry.gui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/// The window settings geometry round trip against a real shown stage: the
/// persisted bounds must survive the apply → show path, and the live bounds
/// must survive the capture → load path (the reported Windows failure: the
/// unmaximized window does not restore its size/location after the restart).
/// The user's own persisted entries are snapshotted and restored around the probes.
class WindowSettingsTest {

  private static final CountDownLatch FX_READY = new CountDownLatch(1);
  private static final String[] KEYS = {"x", "y", "width", "height", "maximized", "divider", "verbose", "simulate"};
  private static final Preferences node = Preferences.userNodeForPackage(WindowSettings.class);
  private static Map<String, String> savedUserEntries;

  @BeforeAll
  static void startFx() throws Exception {
    try {
      Platform.startup(FX_READY::countDown);
      if (!FX_READY.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the FX toolkit did not start");
    } catch (IllegalStateException ignored) {
      // Already started by another test class in this JVM
    }
    savedUserEntries = new HashMap<>();
    for (String key : KEYS) {
      String value = node.get(key, null);
      if (value != null) savedUserEntries.put(key, value);
    }
  }

  @AfterAll
  static void restoreUserEntries() throws Exception {
    node.clear();
    for (Map.Entry<String, String> entry : savedUserEntries.entrySet()) {
      node.put(entry.getKey(), entry.getValue());
    }
    node.flush();
  }

  // Run the action on the application thread collecting the failures for the test thread
  private static void runFx(AtomicReference<Throwable> failure, CountDownLatch done, String step, Runnable action) {
    Platform.runLater(() -> {
      System.err.println("probe: entered FX for " + step);
      try {
        action.run();
        System.err.println("probe: completed FX for " + step);
      } catch (Throwable t) {
        System.err.println("probe: failed FX for " + step + ": " + t);
        failure.set(t);
        done.countDown();
      }
    });
  }

  // Two pulses: the platform geometry settles asynchronously after the show
  private static void afterSettled(Runnable action) {
    Platform.runLater(() -> Platform.runLater(action));
  }

  @Test
  void applyRestoresThePersistedGeometryOnTheShownStage() throws Exception {
    node.putDouble("x", 150);
    node.putDouble("y", 120);
    node.putDouble("width", 900);
    node.putDouble("height", 600);
    node.putBoolean("maximized", false);
    node.flush();

    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<double[]> geometry = new AtomicReference<>();
    runFx(failure, done, "apply", () -> {
      try {
        Stage stage = new Stage();
        System.err.println("probe: stage created");
        WindowSettings settings = WindowSettings.load();
        System.err.println("probe: loaded " + settings.getClass().getSimpleName());
        settings.apply(stage);
        System.err.println("probe: applied");
        stage.setScene(new Scene(new Region()));
        settings.attach(stage);
        System.err.println("probe: attached, showing");
        stage.show();
        System.err.println("probe: shown");
        afterSettled(() -> {
          System.err.println("probe: settled, reading geometry");
          try {
            geometry.set(new double[]{stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()});
            stage.close();
          } finally {
            done.countDown();
          }
        });
      } catch (Throwable t) {
        failure.set(t);
        done.countDown();
      }
    });
    assertTrue(done.await(15, TimeUnit.SECONDS), "the apply probe timed out");
    System.err.println("probe: apply latch released, failure=" + failure.get());
    if (failure.get() != null) throw new IllegalStateException("apply probe failed", failure.get());
    double[] g = geometry.get();
    assertEquals(150, g[0], 1.0, "restored x");
    assertEquals(120, g[1], 1.0, "restored y");
    assertEquals(900, g[2], 1.0, "restored width");
    assertEquals(600, g[3], 1.0, "restored height");
  }

  @Test
  void capturedGeometryRoundTripsBackThroughLoadAndApply() throws Exception {
    node.clear();
    node.flush();

    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    runFx(failure, done, "capture-round-trip", () -> {
      try {
        // The live session: the window lands wherever and is moved/resized
        Stage stage = new Stage();
        stage.setScene(new Scene(new Region()));
        stage.show();
        stage.setX(210);
        stage.setY(190);
        stage.setWidth(1010);
        stage.setHeight(710);
        afterSettled(() -> {
          try {
            WindowSettings settings = new WindowSettings();
            settings.capture(stage, 0.62, false, false);
            stage.close();

            // The next launch: the loaded state applies onto the fresh stage
            Stage restored = new Stage();
            WindowSettings reloaded = WindowSettings.load();
            reloaded.apply(restored);
            restored.setScene(new Scene(new Region()));
            reloaded.attach(restored);
            restored.show();
            afterSettled(() -> {
              try {
                assertEquals(210, restored.getX(), 1.0, "round-tripped x");
                assertEquals(190, restored.getY(), 1.0, "round-tripped y");
                assertEquals(1010, restored.getWidth(), 1.0, "round-tripped width");
                assertEquals(710, restored.getHeight(), 1.0, "round-tripped height");
                restored.close();
              } finally {
                done.countDown();
              }
            });
          } catch (Throwable t) {
            failure.set(t);
            done.countDown();
          }
        });
      } catch (Throwable t) {
        failure.set(t);
        done.countDown();
      }
    });
    assertTrue(done.await(15, TimeUnit.SECONDS), "the round-trip probe timed out");
    if (failure.get() != null) throw new IllegalStateException("round-trip probe failed", failure.get());
  }
}
