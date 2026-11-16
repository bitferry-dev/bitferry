package bitferry.gui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LogControllerScrollTest {

  private static final CountDownLatch FX_READY = new CountDownLatch(1);

  @BeforeAll
  static void startFx() throws Exception {
    try {
      Platform.startup(FX_READY::countDown);
      if (!FX_READY.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the FX toolkit did not start");
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  private static boolean getFollow(Object stream) throws Exception {
    Field followField = stream.getClass().getDeclaredField("follow");
    followField.setAccessible(true);
    return (boolean) followField.get(stream);
  }

  private static Object getGeneralStream(LogController controller) throws Exception {
    Field generalField = LogController.class.getDeclaredField("general");
    generalField.setAccessible(true);
    return generalField.get(controller);
  }

  @Test
  void testLogAutoFollowIncremental() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    AtomicReference<Double> vvalueVal = new AtomicReference<>();

    Platform.runLater(() -> {
      try {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/bitferry/gui/log.fxml"));
        TabPane root = loader.load();
        LogController controller = loader.getController();

        Stage stage = new Stage();
        stage.setScene(new Scene(root, 400, 200));
        stage.show();

        var sink = controller.openRun("Test Run");

        new Thread(() -> {
          try {
            for (int batch = 0; batch < 5; batch++) {
              for (int i = 0; i < 15; i++) {
                sink.accept("Batch " + batch + " Line " + i + ": some output text from rclone/restic");
              }
              Thread.sleep(80);
            }
            Thread.sleep(200);

            Platform.runLater(() -> {
              try {
                Tab runTab = root.getTabs().get(root.getTabs().size() - 1);
                ScrollPane scroll = (ScrollPane) runTab.getContent();
                vvalueVal.set(scroll.getVvalue());
                stage.close();
              } catch (Exception e) {
                err.set(e);
              } finally {
                latch.countDown();
              }
            });
          } catch (Exception e) {
            err.set(e);
            latch.countDown();
          }
        }).start();

      } catch (Exception e) {
        err.set(e);
        latch.countDown();
      }
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    if (err.get() != null) throw new RuntimeException(err.get());

    assertEquals(1.0, vvalueVal.get(), 0.05, "vvalue should be near 1.0 (at the tail)");
  }

  @Test
  void testUserScrollDisarmsAndRearms() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();

    Platform.runLater(() -> {
      try {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/bitferry/gui/log.fxml"));
        TabPane root = loader.load();
        LogController controller = loader.getController();

        Stage stage = new Stage();
        stage.setScene(new Scene(root, 400, 200));
        stage.show();

        var sink = controller.openRun("Scroll Test Run");

        new Thread(() -> {
          try {
            // 1. Initial output to create scrollable content
            for (int i = 0; i < 50; i++) {
              sink.accept("Initial line " + i);
            }
            Thread.sleep(200);

            CountDownLatch step1 = new CountDownLatch(1);
            Platform.runLater(() -> {
              try {
                Tab runTab = root.getTabs().get(root.getTabs().size() - 1);
                ScrollPane scroll = (ScrollPane) runTab.getContent();
                assertEquals(1.0, scroll.getVvalue(), 0.05);

                // 2. User scrolls to top
                scroll.setVvalue(0.0);
              } catch (Throwable t) {
                err.set(t);
              } finally {
                step1.countDown();
              }
            });
            assertTrue(step1.await(5, TimeUnit.SECONDS));
            if (err.get() != null) return;

            // 3. Emit more lines while scrolled to top
            for (int i = 0; i < 20; i++) {
              sink.accept("Scrolled away line " + i);
            }
            Thread.sleep(200);

            CountDownLatch step2 = new CountDownLatch(1);
            Platform.runLater(() -> {
              try {
                Tab runTab = root.getTabs().get(root.getTabs().size() - 1);
                ScrollPane scroll = (ScrollPane) runTab.getContent();
                // Should stay scrolled near top, not jumped to tail (1.0)
                assertTrue(scroll.getVvalue() < 0.5, "vvalue should not jump to bottom while user is reading history: " + scroll.getVvalue());

                // 4. User scrolls back to bottom (re-arm)
                scroll.setVvalue(1.0);
              } catch (Throwable t) {
                err.set(t);
              } finally {
                step2.countDown();
              }
            });
            assertTrue(step2.await(5, TimeUnit.SECONDS));
            if (err.get() != null) return;

            // 5. Emit more lines while re-armed at tail
            for (int i = 0; i < 20; i++) {
              sink.accept("Resumed line " + i);
            }
            Thread.sleep(200);

            Platform.runLater(() -> {
              try {
                Tab runTab = root.getTabs().get(root.getTabs().size() - 1);
                ScrollPane scroll = (ScrollPane) runTab.getContent();
                assertEquals(1.0, scroll.getVvalue(), 0.05, "vvalue should follow to the tail once re-armed");
                stage.close();
              } catch (Throwable t) {
                err.set(t);
              } finally {
                latch.countDown();
              }
            });
          } catch (Exception e) {
            err.set(e);
            latch.countDown();
          }
        }).start();

      } catch (Exception e) {
        err.set(e);
        latch.countDown();
      }
    });

    assertTrue(latch.await(15, TimeUnit.SECONDS));
    if (err.get() != null) throw new RuntimeException(err.get());
  }

  @Test
  void testGeneralLogAutoFollow() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> err = new AtomicReference<>();
    AtomicReference<Double> vvalueVal = new AtomicReference<>();

    Platform.runLater(() -> {
      try {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/bitferry/gui/log.fxml"));
        TabPane root = loader.load();
        LogController controller = loader.getController();

        Stage stage = new Stage();
        stage.setScene(new Scene(root, 400, 200));
        stage.show();

        new Thread(() -> {
          try {
            for (int batch = 0; batch < 5; batch++) {
              for (int i = 0; i < 15; i++) {
                controller.log("General log message batch " + batch + " line " + i);
              }
              Thread.sleep(80);
            }
            Thread.sleep(200);

            Platform.runLater(() -> {
              try {
                Tab generalTab = root.getTabs().get(0);
                ScrollPane scroll = (ScrollPane) generalTab.getContent();
                vvalueVal.set(scroll.getVvalue());
                stage.close();
              } catch (Exception e) {
                err.set(e);
              } finally {
                latch.countDown();
              }
            });
          } catch (Exception e) {
            err.set(e);
            latch.countDown();
          }
        }).start();

      } catch (Exception e) {
        err.set(e);
        latch.countDown();
      }
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    if (err.get() != null) throw new RuntimeException(err.get());

    assertEquals(1.0, vvalueVal.get(), 0.05, "general tab vvalue should be near 1.0 (at the tail)");
  }
}
