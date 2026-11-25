package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.task.*;
import javafx.application.*;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the real tasks.fxml controller: the mass buttons must flip the tasks'
/// mass-run participation, and the Execute column must render the checkboxes
/// (the regression guards for the "the buttons do nothing" / "no execute checkboxes
/// appear" reports).
class MassButtonsTest {

    private static final CountDownLatch FX_READY = new CountDownLatch(1);
    private static AppContext context;
    private static TasksController controller;
    private static Manager manager;
    private static final List<Task> tasks = new java.util.ArrayList<>();
    private static volatile String phase = "startup";

    @BeforeAll
    static void startFxAndBuildThePane() throws Exception {
        try {
            Platform.startup(FX_READY::countDown);
            if (!FX_READY.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the FX toolkit did not start");
        } catch (IllegalStateException ignored) {
            // Already started by another test class in this JVM
        }

        CountDownLatch built = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                context = new AppContext();
                manager = context.manager();
                Volume volume = Volume.create(manager, Files.createTempDirectory("bitferry-mass-probe").resolve("volume"));
                for (int i = 0; i < 2; i++) {
                    Replicate task = new Replicate(manager,
                        org.bitferry.endpoint.Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-mass-data-" + i)),
                        org.bitferry.endpoint.Endpoints.parse(manager, ":" + volume.tag() + ":t" + i),
                        null, List.of(), List.of());
                    task.setRunnable(false);
                    manager.commit(task);
                    tasks.add(task);
                }
                manager.commit();

                FXMLLoader loader = new FXMLLoader(MassButtonsTest.class.getResource("/org/bitferry/gui/tasks.fxml"));
                loader.load();
                controller = loader.getController();
                controller.init(context, new LogController(), (tags, serve) -> {});
                controller.refresh(manager);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                built.countDown();
            }
        });
        if (!built.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the pane build timed out at " + phase);
        if (failure.get() != null) throw new IllegalStateException("the pane build failed", failure.get());
    }

    @AfterAll
    static void stopFx() {
    }

    @Test
    void massButtonsFlipTheParticipation() throws Exception {
        // All Off from the initial excluded state: the state stays excluded
        fireAndWait("massNone", false);
        // All On: both tasks join the mass run
        fireAndWait("massAll", true);
        // Invert from participating: both drop out again
        fireAndWait("massInvert", false);
        // All On once more: the steady state
        fireAndWait("massAll", true);
    }

    @Test
    void massButtonsNotifyTheModelChange() throws Exception {
        // The toggles ride the model change notification: without it the rows (and
        // the checkboxes) keep rendering the stale states — the "the buttons do
        // nothing" report. The listener fan-out runs on the operations thread; the
        // listener stays registered for the remaining tests (a counted-down latch
        // makes it a no-op).
        CountDownLatch notified = new CountDownLatch(1);
        context.onModelChanged(notified::countDown);
        fireAndWait("massAll", true);
        assertTrue(notified.await(5, TimeUnit.SECONDS),
            "the mass buttons must fire the model change notification for the panes to refresh");
    }

    @Test
    void theExecuteColumnRendersTheCheckboxes() throws Exception {
        // The stage hosts the table so the skin creates and lays out the real cells
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        CountDownLatch shown = new CountDownLatch(1);
        AtomicReference<Throwable> showFailure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(new StackPane(table()), 800, 600));
                stage.show();
            } catch (Throwable t) {
                showFailure.set(t);
            } finally {
                shown.countDown();
            }
        });
        assertTrue(shown.await(5, TimeUnit.SECONDS), "the probe stage failed to show: " + showFailure.get());
        try {
            // Two pulses: the CSS + the skin layout create and populate the cells
            CountDownLatch pulsed = new CountDownLatch(1);
            Platform.runLater(() -> Platform.runLater(pulsed::countDown));
            assertTrue(pulsed.await(5, TimeUnit.SECONDS));

            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    var cells = table().lookupAll(".table-cell");
                    var sb = new StringBuilder();
                    int count = 0;
                    for (var node : cells) {
                        if (node instanceof TableCell<?, ?> cell) {
                            count++;
                            var col = cell.getTableColumn();
                            var graphic = cell.getGraphic();
                            sb.append("  col=").append(col == null ? "null" : col.getText())
                              .append(" graphic=").append(graphic == null ? "null" : graphic.getClass().getSimpleName())
                              .append(" item=").append(cell.getItem())
                              .append(" empty=").append(cell.isEmpty());
                            if (count <= 8) sb.append(" [").append(count).append("]");
                        }
                    }
                    result.set("totalCells=" + cells.size() + sb);
                } catch (Throwable t) {
                    result.set("probe failure: " + t);
                } finally {
                    checked.countDown();
                }
            });
            assertTrue(checked.await(5, TimeUnit.SECONDS), "the render probe timed out");
            // The Execute column rendered with the checkbox graphics (at least one per task)
            long checkboxCount = result.get().split("graphic=CheckBox", -1).length - 1;
            assertTrue(checkboxCount >= 2, "the Execute checkboxes: " + result.get());
        } finally {
            CountDownLatch closed = new CountDownLatch(1);
            Platform.runLater(() -> {
                Stage stage = stageRef.get();
                if (stage != null) stage.close();
                closed.countDown();
            });
            assertTrue(closed.await(5, TimeUnit.SECONDS), "the probe stage failed to close");
        }
    }

    private static void fireAndWait(String buttonName, boolean expected) throws Exception {
        phase = "fire " + buttonName;
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Button button = button(buttonName);
                assertNotNull(button, "no " + buttonName + " button wired in the controller");
                button.fire();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                fired.countDown();
            }
        });
        if (!fired.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the button fire timed out at " + phase);
        if (failure.get() != null) throw new IllegalStateException("the button fire failed at " + phase, failure.get());

        // The handler runs on the operations thread: poll for the expected state
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (allTasksInState(expected)) return;
            Thread.sleep(50);
        }
        fail("the " + buttonName + " button did not flip the tasks' mass-run participation; phase=" + phase
            + " states=" + states());
    }

    private static boolean allTasksInState(boolean expected) {
        for (Task t : tasks) {
            if (t.isRunnable() != expected) return false;
        }
        return true;
    }

    private static String states() {
        var sb = new StringBuilder();
        for (Task t : tasks) sb.append(t.tag()).append("=").append(t.isRunnable()).append(" ");
        return sb.toString();
    }

    private static Button button(String name) throws Exception {
        return field(name);
    }

    private static javafx.scene.control.TableView<?> table() throws Exception {
        return field("table");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(String name) throws Exception {
        var field = TasksController.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(controller);
    }
}
