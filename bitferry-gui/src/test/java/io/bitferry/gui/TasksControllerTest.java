package io.bitferry.gui;

import io.bitferry.Manager;
import io.bitferry.Volume;
import io.bitferry.endpoint.Endpoints;
import io.bitferry.endpoint.LocalEndpoint;
import io.bitferry.endpoint.VolumeEndpoint;
import io.bitferry.task.Copy;
import io.bitferry.task.Replicate;
import io.bitferry.task.Task;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for TasksController and its EndpointTableCell rendering in the global tasks list:
/// full paths for local and intact volume endpoints, smaller colored volume tags in front
/// of intact volume-relative full paths, and the usual :tag:path with colored tags for stale endpoints.
class TasksControllerTest {

    private static final CountDownLatch FX_READY = new CountDownLatch(1);
    private static AppContext context;
    private static Manager manager;
    private static Volume volume;
    private static Path localDir;

    @BeforeAll
    static void initFx() throws Exception {
        try {
            Platform.startup(FX_READY::countDown);
            if (!FX_READY.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX failed to start");
            }
        } catch (IllegalStateException ignored) {
            // Already started by another test
        }

        CountDownLatch init = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                context = new AppContext();
                manager = context.manager();
                Path volRoot = Files.createTempDirectory("bitferry-tasks-vol");
                volume = Volume.create(manager, volRoot);
                localDir = Files.createTempDirectory("bitferry-tasks-local");
            } catch (Throwable t) {
                error.set(t);
            } finally {
                init.countDown();
            }
        });
        if (!init.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Init timed out");
        if (error.get() != null) throw new IllegalStateException("Init failed", error.get());
    }

    @AfterAll
    static void stopFx() {
    }

    @Test
    void rowFormatsEndpointsWithFullPathsForIntactAndUsualForStale() throws Exception {
        // 1. Intact task: local source -> intact volume destination
        Task intactTask = new Copy(manager,
            Endpoints.parse(manager, "local:" + localDir),
            Endpoints.parse(manager, ":" + volume.tag() + ":sub/dir"),
            null, List.of(), List.of());
        manager.commit(intactTask);

        TasksController.Row intactRow = new TasksController.Row(intactTask);
        assertEquals(localDir.toRealPath().toString(), intactRow.source.get(),
            "local endpoint should display full canonical path");
        String expectedDst = ":" + volume.tag() + ": " + volume.root().resolve("sub/dir").normalize();
        assertEquals(expectedDst, intactRow.destination.get(),
            "intact volume endpoint should show volume tag in front of the full resolved path");

        // 2. Stale task with unregistered volume
        Task staleTask = new Replicate(manager,
            new VolumeEndpoint(manager, "unregistered_vol", "stale/path"),
            Endpoints.parse(manager, "local:" + localDir),
            null, List.of(), List.of());

        TasksController.Row staleRow = new TasksController.Row(staleTask);
        assertEquals(":unregistered_vol:stale/path", staleRow.source.get(),
            "stale volume endpoint should display usual :tag:path");
        assertEquals(localDir.toRealPath().toString(), staleRow.destination.get());
    }

    @Test
    void endpointTableCellRendersIntactVolumeEndpointWithSmallerColoredTagAndFullPath() throws Exception {
        Task intactTask = new Copy(manager,
            Endpoints.parse(manager, "local:" + localDir),
            Endpoints.parse(manager, ":" + volume.tag() + ":data"),
            null, List.of(), List.of());
        TasksController.Row row = new TasksController.Row(intactTask);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                // Test destination cell (intact volume endpoint)
                TasksController.EndpointTableCell destCell =
                    new TasksController.EndpointTableCell(TasksController.Row::destinationEndpoint);
                destCell.update(row, row.destination.get());

                assertTrue(destCell.tagLabel().isVisible());
                assertEquals(":" + volume.tag() + ": ", destCell.tagLabel().getText());
                assertEquals(Color.web("#00838f"), destCell.tagLabel().getTextFill(),
                    "intact volume tag should be colored cyan");
                assertTrue(destCell.tagLabel().getStyle().contains("-fx-font-size: 0.85em;"),
                    "intact volume tag should have smaller font");
                assertEquals(volume.root().resolve("data").normalize().toString(), destCell.pathLabel().getText(),
                    "path label should show the full resolved path");
                assertFalse(destCell.isStale());

                // Test source cell (local endpoint)
                TasksController.EndpointTableCell srcCell =
                    new TasksController.EndpointTableCell(TasksController.Row::sourceEndpoint);
                srcCell.update(row, row.source.get());

                assertFalse(srcCell.tagLabel().isVisible(), "local endpoint should not show a volume tag");
                assertEquals(localDir.toRealPath().toString(), srcCell.pathLabel().getText(),
                    "local endpoint should show full path");
            } catch (Exception e) {
                fail(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void endpointTableCellRendersStaleVolumeEndpointWithColoredTagAndUsualSubpath() throws Exception {
        Task staleTask = new Replicate(manager,
            new VolumeEndpoint(manager, "missingvol", "backup"),
            Endpoints.parse(manager, "local:" + localDir),
            null, List.of(), List.of());
        TasksController.Row row = new TasksController.Row(staleTask);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                TasksController.EndpointTableCell cell =
                    new TasksController.EndpointTableCell(TasksController.Row::sourceEndpoint);
                cell.update(row, row.source.get());

                assertTrue(cell.tagLabel().isVisible());
                assertEquals(":missingvol:", cell.tagLabel().getText(),
                    "stale endpoint should have usual :tag: prefix");
                assertEquals(Color.web("#c62828"), cell.tagLabel().getTextFill(),
                    "stale volume tag should be colored red");
                assertEquals("backup", cell.pathLabel().getText(),
                    "path label should show the usual subpath for stale volume");
                assertTrue(cell.isStale());
            } catch (Exception e) {
                fail(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void endpointTableCellSelectionColors() throws Exception {
        Task intactTask = new Copy(manager,
            Endpoints.parse(manager, "local:" + localDir),
            Endpoints.parse(manager, ":" + volume.tag() + ":data"),
            null, List.of(), List.of());
        TasksController.Row row = new TasksController.Row(intactTask);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                TasksController.EndpointTableCell cell =
                    new TasksController.EndpointTableCell(TasksController.Row::destinationEndpoint);
                cell.update(row, row.destination.get());

                // Unselected
                cell.updateColors(false);
                assertEquals(Color.web("#00838f"), cell.tagLabel().getTextFill());
                assertEquals(Color.web("#222222"), cell.pathLabel().getTextFill());

                // Selected
                cell.updateColors(true);
                assertEquals(Color.web("#80deea"), cell.tagLabel().getTextFill());
                assertEquals(Color.WHITE, cell.pathLabel().getTextFill());
            } catch (Exception e) {
                fail(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void tasksControllerLoadsAndRefreshesWithTableCells() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/io/bitferry/gui/tasks.fxml"));
                loader.load();
                TasksController controller = loader.getController();
                controller.init(context, new LogController(), (tags, serve) -> {});
                controller.refresh(manager);
                assertNotNull(controller);
            } catch (Exception e) {
                fail(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
