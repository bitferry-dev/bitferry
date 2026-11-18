package bitferry.gui;

import bitferry.Manager;
import bitferry.Volume;
import bitferry.endpoint.Endpoints;
import bitferry.task.Backup;
import bitferry.task.Copy;
import bitferry.task.IncludeFilters;
import bitferry.task.Replicate;
import bitferry.task.Task;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the real task.fxml controller: the row visibility per the operation
/// type, the auto-loaded default profiles and their swap on the type change, and the
/// edit-mode population with the immutable fields disabled.
class TaskFormControllerTest {

    private static final CountDownLatch FX_READY = new CountDownLatch(1);
    private static AppContext context;
    private static Manager manager;
    private static Volume volume;
    private static volatile String phase = "startup";

    @BeforeAll
    static void startFx() throws Exception {
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
                volume = new Volume(manager, Files.createTempDirectory("bitferry-form-probe").resolve("volume"));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                built.countDown();
            }
        });
        if (!built.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the fixture build timed out at " + phase);
        if (failure.get() != null) throw new IllegalStateException("the fixture build failed", failure.get());
    }

    @AfterAll
    static void stopFx() {
    }

    @Test
    void newTaskFormStartsFromTheRcloneDefaults() throws Exception {
        TaskFormController form = load(null);
        assertEquals("copy", form.type());
        // The rclone @default profile's concrete options pre-fill the execution entry
        assertEquals("--metadata", execute(form).getText());
        // The mass-run participation defaults to on, the vault keeps the password
        assertFalse(form.manual());
        assertFalse(form.keyring());
    }

    @Test
    void rowsFollowTheOperationType() throws Exception {
        TaskFormController form = load(null);

        // The rclone (copy) layout: the encryption row visible, the backup rows hidden
        List<String> present = rowLabels(form);
        assertTrue(present.contains("Encryption:"), present.toString());
        assertFalse(present.contains("Retention:"), present.toString());
        assertFalse(present.contains("Serve:"), present.toString());

        // The backup layout: the retention/check/serve rows swap in for the encryption
        // ones, and the auto-loaded execution entry clears (the restic default is empty)
        ComboBox<String> typeField = field(form, "type");
        runFx(() -> typeField.getSelectionModel().select("backup"));
        assertEquals("", execute(form).getText());
        present = rowLabels(form);
        assertFalse(present.contains("Encryption options:"), present.toString());
        assertTrue(present.contains("Retention:"), present.toString());
        assertTrue(present.contains("Check:"), present.toString());
        assertTrue(present.contains("Serve:"), present.toString());
    }

    @Test
    void modifyFormPopulatesAndDisablesTheImmutableFields() throws Exception {
        Task task = new Replicate(manager,
            Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-form-data")),
            Endpoints.parse(manager, ":" + volume.tag() + ":backup"),
            null, List.of("keep/**"), List.of("skip/**"));
        TaskFormController form = load(task);

        assertEquals("replicate", form.type());
        ComboBox<String> type = field(form, "type");
        TextField source = field(form, "source");
        TextField destination = field(form, "destination");
        TextField include = field(form, "include");
        TextField exclude = field(form, "exclude");
        assertFalse(type.isDisable());
        assertEquals(List.of("copy", "update", "replicate", "equalize"), type.getItems());
        assertTrue(source.isDisable());
        assertTrue(destination.isDisable());
        Button browseSourceSmart = field(form, "browseSourceSmart");
        Button browseSourceLocal = field(form, "browseSourceLocal");
        Button browseSourceVolume = field(form, "browseSourceVolume");
        Button browseDestinationSmart = field(form, "browseDestinationSmart");
        Button browseDestinationLocal = field(form, "browseDestinationLocal");
        Button browseDestinationVolume = field(form, "browseDestinationVolume");
        assertTrue(browseSourceSmart.isDisable());
        assertTrue(browseSourceLocal.isDisable());
        assertTrue(browseSourceVolume.isDisable());
        assertTrue(browseDestinationSmart.isDisable());
        assertTrue(browseDestinationLocal.isDisable());
        assertTrue(browseDestinationVolume.isDisable());
        TextField profile = field(form, "profile");
        Button editProfile = field(form, "editProfile");
        assertTrue(profile.isDisable());
        assertTrue(editProfile.isDisable());
        assertEquals(task.source().describe(), source.getText());
        assertEquals("keep/**", include.getText());
        assertEquals("skip/**", exclude.getText());
        // The task's resolved execution options populate the entry
        assertEquals("--metadata", execute(form).getText());
        // The stored manual flag mirrors into the checkbox
        assertFalse(form.manual());
    }

    @Test
    void clearingExecutionOptionsInNewTaskFormPersists() throws Exception {
        TaskFormController form = load(null);
        Button browseSourceSmart = field(form, "browseSourceSmart");
        assertFalse(browseSourceSmart.isDisable());
        Button editProfile = field(form, "editProfile");
        assertFalse(editProfile.isDisable());
        TextField exec = execute(form);
        assertEquals("--metadata", exec.getText());

        // Clear execution options in new task dialog
        runFx(() -> exec.setText(""));
        assertEquals("-", form.execute());

        // Task created with form.execute() has empty options
        Copy copy = new Copy(manager,
            Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-new-copy-src")),
            Endpoints.parse(manager, ":" + volume.tag() + ":new-copy-dst"),
            form.execute(), List.of(), List.of());
        assertEquals(List.of(), copy.processOptions());
        manager.commit();

        // Round trip through volume restore keeps empty options
        Manager reloaded = new Manager().restore(volume.root());
        Task restored = reloaded.tasks().get(copy.tag());
        assertNotNull(restored);
        assertEquals(List.of(), restored.processOptions());

        // Editing the restored task reflects the empty options
        TaskFormController editForm = load(restored);
        assertEquals("", execute(editForm).getText());
    }

    @Test
    void modifyingExecutionOptionsInEditTaskFormPersists() throws Exception {
        Task task = new Copy(manager,
            Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-edit-copy-src")),
            Endpoints.parse(manager, ":" + volume.tag() + ":edit-copy-dst"),
            null, List.of(), List.of());
        assertEquals(List.of("--metadata"), task.processOptions());
        manager.commit();

        // 1. Change options to custom list
        TaskFormController form1 = load(task);
        TextField exec1 = execute(form1);
        assertEquals("--metadata", exec1.getText());
        runFx(() -> exec1.setText("--checkers, 8, --fast-list"));
        task.setProcessOptions(form1.execute());
        assertEquals(List.of("--checkers", "8", "--fast-list"), task.processOptions());
        manager.commit();

        // Verify across reload
        Manager reload1 = new Manager().restore(volume.root());
        Task restored1 = reload1.tasks().get(task.tag());
        assertEquals(List.of("--checkers", "8", "--fast-list"), restored1.processOptions());
        TaskFormController editForm1 = load(restored1);
        assertEquals("--checkers, 8, --fast-list", execute(editForm1).getText());

        // 2. Change options to empty
        TextField exec2 = execute(editForm1);
        runFx(() -> exec2.setText(""));
        restored1.setProcessOptions(editForm1.execute());
        assertEquals(List.of(), restored1.processOptions());
        reload1.commit();

        // Verify empty options persist across reload
        Manager reload2 = new Manager().restore(volume.root());
        Task restored2 = reload2.tasks().get(task.tag());
        assertEquals(List.of(), restored2.processOptions());
        TaskFormController editForm2 = load(restored2);
        assertEquals("", execute(editForm2).getText());
    }

    @Test
    void modifyFormDisablesTypeForNonRcloneTasks() throws Exception {
        Task task = new Backup(manager,
            Endpoints.parse(manager, ":" + volume.tag() + ":backup-src"),
            Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-backup-repo")),
            "secret", false, null, null, null, null, List.of(), false);
        try {
            TaskFormController form = load(task);
            assertEquals("backup", form.type());
            ComboBox<String> type = field(form, "type");
            assertTrue(type.isDisable());
        } finally {
            manager.deregisterTask(task);
        }
    }

    @Test
    void alteringOperationTypeForRcloneTasksMorphsAndPersists() throws Exception {
        Task task = new Copy(manager,
            Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-morph-src")),
            Endpoints.parse(manager, ":" + volume.tag() + ":morph-dst"),
            null, List.of("data/**"), List.of("*.tmp"));
        manager.commit();

        Task morphed = null;
        try {
            TaskFormController form = load(task);
            ComboBox<String> type = field(form, "type");
            assertFalse(type.isDisable());
            assertEquals("copy", form.type());

            // Alter operation type to replicate
            runFx(() -> type.setValue("replicate"));
            assertEquals("replicate", form.type());

            // Morph the task
            morphed = task.morph(form.type());
            assertInstanceOf(Replicate.class, morphed);
            assertEquals(task.tag(), morphed.tag());
            assertEquals(task.source().describe(), morphed.source().describe());
            assertEquals(task.destination().describe(), morphed.destination().describe());
            // The filters ride the [IncludeFilters] capability (the static type of the
            // morph result is the base Task)
            assertTrue(morphed instanceof IncludeFilters);
            assertEquals(List.of("data/**"), ((IncludeFilters) morphed).include());
            assertEquals(List.of("*.tmp"), morphed.exclude());
            manager.commit();

            // Round trip through volume restore keeps morphed type
            Manager reloaded = new Manager().restore(volume.root());
            Task restored = reloaded.tasks().get(task.tag());
            assertNotNull(restored);
            assertInstanceOf(Replicate.class, restored);
            assertEquals("replicate", restored.operation());
            assertEquals(List.of("data/**"), ((IncludeFilters) restored).include());
            assertEquals(List.of("*.tmp"), restored.exclude());
        } finally {
            task.delete();
            if (morphed != null) morphed.delete();
            manager.commit();
        }
    }

    // The helpers: the FXML controller is loaded through its factory, the private
    // fields are reached reflectively (mirroring the MassButtonsTest access pattern)

    private static TaskFormController load(Task existing) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<TaskFormController> reference = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                reference.set(TaskFormController.load(context, existing));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                loaded.countDown();
            }
        });
        if (!loaded.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the form load timed out at " + phase);
        if (failure.get() != null) throw new IllegalStateException("the form load failed", failure.get());
        return reference.get();
    }

    private static void runFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the fx action timed out");
        if (failure.get() != null) throw new IllegalStateException("the fx action failed", failure.get());
    }

    private static TextField execute(TaskFormController form) throws Exception {
        return field(form, "execute");
    }

    private static List<String> rowLabels(TaskFormController form) throws Exception {
        GridPane grid = field(form, "grid");
        List<String> labels = new java.util.ArrayList<>();
        for (Node node : grid.getChildren()) {
            if (node instanceof javafx.scene.control.Label label) labels.add(label.getText());
        }
        return labels;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(TaskFormController form, String name) throws Exception {
        var field = TaskFormController.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(form);
    }
}
