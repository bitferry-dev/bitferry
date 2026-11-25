package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
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
                volume = Volume.create(manager, Files.createTempDirectory("bitferry-form-probe").resolve("volume"));
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
        assertEquals(TaskFormController.OPERATION_DESCRIPTIONS.get("copy"), form.typeDescription());
        assertEquals("Source:", form.sourceLabel());
        assertEquals("Destination:", form.destinationLabel());
        // The rclone @default profile's concrete options pre-fill the execution entry
        assertEquals("--metadata", execute(form).getText());
        // The mass-run participation defaults to on, the vault keeps the password,
        // and the security state starts unset (no encryption)
        assertFalse(form.manual());
        assertFalse(form.keyring());
        assertNull(form.encryption());
        assertEquals("", form.password());
        assertEquals("", form.salt());
        // The encryption mode radios: (none) preselected, and the inline security
        // editor sleeps until a crypt mode is chosen
        RadioButton noneChoice = field(form, "cryptNoneChoice");
        assertTrue(noneChoice.isSelected());
        RadioButton encryptChoice = field(form, "cryptEncryptChoice");
        assertFalse(encryptChoice.isSelected());
        Button editSecurity = field(form, "editSecurity");
        assertTrue(editSecurity.isDisable());
    }

    @Test
    void rowsFollowTheOperationType() throws Exception {
        TaskFormController form = load(null);

        // The rclone (copy) layout: the encryption mode radio row (with the inline
        // security editor) visible, the backup rows and the restic security row
        // hidden; the secret details live in the security dialog, not the form
        List<String> present = rowLabels(form);
        assertTrue(present.contains("Encryption:"), present.toString());
        assertFalse(present.contains("Security:"), present.toString());
        assertFalse(present.contains("Encryption options:"), present.toString());
        assertFalse(present.contains("Password storage:"), present.toString());
        assertFalse(present.contains("Retention:"), present.toString());
        assertFalse(present.contains("Serve:"), present.toString());
        assertFalse(present.contains("Snapshot scope:"), present.toString());

        // The backup layout: the retention/check/serve rows swap in for the rclone
        // operation rows (the encryption mode radios hide — restic keeps no crypt
        // mode), the restic security row replaces them, and the auto-loaded
        // execution entry clears (the restic default is empty)
        ComboBox<String> typeField = field(form, "type");
        runFx(() -> typeField.getSelectionModel().select("backup"));
        assertEquals("", execute(form).getText());
        assertEquals(TaskFormController.OPERATION_DESCRIPTIONS.get("backup"), form.typeDescription());
        assertEquals("Source:", form.sourceLabel());
        assertEquals("Repository:", form.destinationLabel());
        present = rowLabels(form);
        assertFalse(present.contains("Encryption:"), present.toString());
        assertTrue(present.contains("Security:"), present.toString());
        assertFalse(present.contains("Retention:"), present.toString());
        assertFalse(present.contains("Check:"), present.toString());
        assertTrue(present.contains("Serve:"), present.toString());
        assertFalse(present.contains("Snapshot scope:"), present.toString());

        // The restore layout: snapshot scope swaps in, serve hides
        runFx(() -> typeField.getSelectionModel().select("restore"));
        present = rowLabels(form);
        assertTrue(present.contains("Snapshot scope:"), present.toString());
        assertFalse(present.contains("Serve:"), present.toString());
    }

    @Test
    void typeDescriptionUpdatesWithSelectedOperation() throws Exception {
        TaskFormController form = load(null);
        ComboBox<String> typeField = field(form, "type");

        for (String op : typeField.getItems()) {
            runFx(() -> typeField.setValue(op));
            assertEquals(TaskFormController.OPERATION_DESCRIPTIONS.get(op), form.typeDescription());
            assertFalse(form.typeDescription().isBlank(), "description should not be blank for " + op);
        }
    }

    @Test
    void endpointLabelsUpdateWithSelectedOperation() throws Exception {
        TaskFormController form = load(null);
        ComboBox<String> typeField = field(form, "type");

        // default copy
        assertEquals("Source:", form.sourceLabel());
        assertEquals("Destination:", form.destinationLabel());

        // backup: Source -> Repository
        runFx(() -> typeField.setValue("backup"));
        assertEquals("Source:", form.sourceLabel());
        assertEquals("Repository:", form.destinationLabel());

        // restore: Repository -> Destination
        runFx(() -> typeField.setValue("restore"));
        assertEquals("Repository:", form.sourceLabel());
        assertEquals("Destination:", form.destinationLabel());

        // switch back to rclone operations
        for (String op : List.of("copy", "update", "replicate", "equalize")) {
            runFx(() -> typeField.setValue(op));
            assertEquals("Source:", form.sourceLabel());
            assertEquals("Destination:", form.destinationLabel());
        }
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
        // The crypt mode is the creation-time choice: the radio row rides disabled
        // (the stored mode of this plain task is (none), so the inline security
        // editor sleeps; the restic row's editor stays for its own family)
        HBox encryptionControl = field(form, "encryptionControl");
        assertTrue(encryptionControl.isDisable());
        Button editSecurity = field(form, "editSecurity");
        assertTrue(editSecurity.isDisable());
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
        // The inline security editor follows the crypt mode: asleep at (none),
        // awake once encrypt is chosen, asleep again on the way back
        Button editSecurity = field(form, "editSecurity");
        assertTrue(editSecurity.isDisable());
        RadioButton encryptChoice = field(form, "cryptEncryptChoice");
        runFx(() -> encryptChoice.setSelected(true));
        assertFalse(editSecurity.isDisable());
        RadioButton noneChoice = field(form, "cryptNoneChoice");
        runFx(() -> noneChoice.setSelected(true));
        assertTrue(editSecurity.isDisable());
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
        manager.commit(copy);

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
        manager.commit(task);

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
        TaskFormController form = load(task);
        assertEquals("backup", form.type());
        ComboBox<String> type = field(form, "type");
        assertTrue(type.isDisable());
        assertEquals("Source:", form.sourceLabel());
        assertEquals("Repository:", form.destinationLabel());

        Restore restore = new Restore(manager,
            Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-restore-repo")),
            Endpoints.parse(manager, ":" + volume.tag() + ":restore-dst"),
            "secret", null, List.of(), List.of(), false, null);
        TaskFormController restoreForm = load(restore);
        assertEquals("restore", restoreForm.type());
        ComboBox<String> restoreType = field(restoreForm, "type");
        assertTrue(restoreType.isDisable());
        assertEquals("Repository:", restoreForm.sourceLabel());
        assertEquals("Destination:", restoreForm.destinationLabel());
        ComboBox<String> scope = field(restoreForm, "snapshotScope");
        assertFalse(scope.isDisable(), "snapshotScope should remain editable");
        assertTrue(rowLabels(restoreForm).contains("Snapshot scope:"));
    }

  @Test
  void editTypeSwitchSwapsTheDefaultExecutionOptionsAndKeepsTheCustomized() throws Exception {
    // 1. The default-profile options follow the type switch: the bisync-only flags
    // must not ride a morphed sync task
    Equalize defaulted = new Equalize(manager,
        Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-eq-form-src")),
        Endpoints.parse(manager, ":" + volume.tag() + ":eq-form-dst"),
        null, List.of(), List.of());
    TaskFormController form = load(defaulted);
    try {
      ComboBox<String> type = field(form, "type");
      assertEquals("equalize", form.type());
      assertTrue(execute(form).getText().contains("--recover"), execute(form).getText());
      runFx(() -> type.setValue("replicate"));
      assertEquals("--metadata", execute(form).getText(), "the bisync-only defaults must be replaced by the target operation's default");
    } finally {
      defaulted.delete();
    }

    // 2. The customized options are user data: the switch leaves them alone
    Equalize customized = new Equalize(manager,
        Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-eq-form-src2")),
        Endpoints.parse(manager, ":" + volume.tag() + ":eq-form-dst2"),
        null, List.of(), List.of());
    customized.setProcessOptions("--spare, --options");
    TaskFormController customForm = load(customized);
    try {
      ComboBox<String> type = field(customForm, "type");
      assertTrue(execute(customForm).getText().contains("--spare"), execute(customForm).getText());
      runFx(() -> type.setValue("replicate"));
      assertTrue(execute(customForm).getText().contains("--spare"), "the customized options must survive the type switch: " + execute(customForm).getText());
    } finally {
      customized.delete();
    }
  }

  @Test
  void alteringOperationTypeForRcloneTasksMorphsAndPersists() throws Exception {
        Task task = new Copy(manager,
            Endpoints.parse(manager, "local:" + Files.createTempDirectory("bitferry-morph-src")),
            Endpoints.parse(manager, ":" + volume.tag() + ":morph-dst"),
            null, List.of("data/**"), List.of("*.tmp"));
        manager.commit(task);

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

    @Test
    void includeAndExcludePresetsContainOnlyEmptyDefaultPreset() {
        assertEquals(Map.of("@default", List.of()), TaskFormController.INCLUDE_PROFILES);
        assertEquals(Map.of("@default", List.of()), TaskFormController.EXCLUDE_PROFILES);
        assertFalse(TaskFormController.EXCLUDE_PROFILES.containsKey("@common-temp"));
        assertFalse(TaskFormController.EXCLUDE_PROFILES.containsKey("@vcs"));
        assertFalse(TaskFormController.EXCLUDE_PROFILES.containsKey("@build"));
    }

    @Test
    void restoreTaskFormCandidateDerivationAndSelection() throws Exception {
        java.nio.file.Path repoDir = Files.createTempDirectory("bitferry-form-restore-repo");
        java.nio.file.Path dstDir = Files.createTempDirectory("bitferry-form-restore-dst");
        Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
        Backup backup1 = new Backup(manager,
            Endpoints.parse(manager, ":" + volume.tag() + ":data1"),
            repo, "secret", false, null, null, null, null, List.of(), false);
        manager.commit(backup1);

        try {
            // 1. New task form: when type is restore and repository matches, single candidate is auto-selected
            TaskFormController form = load(null);
            ComboBox<String> type = field(form, "type");
            TextField source = field(form, "source");
            TextField destination = field(form, "destination");
            ComboBox<String> scope = field(form, "snapshotScope");

            runFx(() -> {
                type.getSelectionModel().select("restore");
                source.setText("local:" + repoDir);
                destination.setText("local:" + dstDir);
            });

            assertEquals(1, scope.getItems().size());
            assertTrue(scope.getItems().get(0).startsWith(backup1.tag()));
            assertEquals(scope.getItems().get(0), scope.getSelectionModel().getSelectedItem());
            assertEquals(backup1.tag(), form.snapshotScope());

            // 2. Clear explicit scope with "-"
            runFx(() -> scope.getEditor().setText("-"));
            assertEquals("-", form.snapshotScope());

            // 3. Invalid scope fails validation
            runFx(() -> scope.getEditor().setText("00000000"));
            assertEquals("00000000", form.snapshotScope());
            BitferryError err = assertThrows(BitferryError.class, form::validate);
            assertTrue(err.getMessage().contains("does not name a live backup task"), err.getMessage());
        } finally {
            backup1.delete();
            manager.commit();
        }
    }

    @Test
    void modifyRestoreTaskFormReflectsAndUpdatesSnapshotScope() throws Exception {
        java.nio.file.Path repoDir = Files.createTempDirectory("bitferry-form-restore-modify-repo");
        Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
        Backup backup1 = new Backup(manager,
            Endpoints.parse(manager, ":" + volume.tag() + ":mod-data1"),
            repo, "secret", false, null, null, null, null, List.of(), false);
        Backup backup2 = new Backup(manager,
            Endpoints.parse(manager, ":" + volume.tag() + ":mod-data2"),
            repo, "secret", false, null, null, null, null, List.of(), false);
        manager.commit(backup1);
        manager.commit(backup2);

        Restore restore = new Restore(manager, repo,
            Endpoints.parse(manager, ":" + volume.tag() + ":mod-restored"),
            "secret", null, List.of(), List.of(), false, backup1.tag());
        manager.commit(restore);

        try {
            TaskFormController form = load(restore);
            ComboBox<String> scope = field(form, "snapshotScope");
            assertFalse(scope.isDisable(), "snapshotScope should be editable in modify mode");
            assertNotNull(scope.getValue());
            assertTrue(scope.getValue().startsWith(backup1.tag()), "should reflect initial backup1 tag");
            assertEquals(2, scope.getItems().size());

            // Switch to backup2
            runFx(() -> scope.setValue(backup2.tag()));
            assertEquals(backup2.tag(), form.snapshotScope());
            Dialogs.applyTaskModification(context, new LogController(), restore, form);
            context.operations().submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(backup2.tag(), restore.snapshotTag());

            // Clear scope with "-"
            runFx(() -> scope.setValue("-"));
            assertEquals("-", form.snapshotScope());
            Dialogs.applyTaskModification(context, new LogController(), restore, form);
            context.operations().submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertNull(restore.snapshotTag());
        } finally {
            restore.delete();
            backup1.delete();
            backup2.delete();
            manager.commit();
        }
    }

    @Test
    void candidateTaskCreationValidatesPasswordBeforeFinalizing() throws Exception {
        java.nio.file.Path repoDir = Files.createTempDirectory("bitferry-pw-check-repo");
        Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
        Backup backup = new Backup(manager,
            Endpoints.parse(manager, ":" + volume.tag() + ":pw-src"),
            repo, "correct-secret", false, null, null, null, null, List.of(), false);
        manager.commit(backup);

        try {
            TaskFormController form = load(null);
            ComboBox<String> type = field(form, "type");
            TextField source = field(form, "source");
            TextField destination = field(form, "destination");

            runFx(() -> {
                type.getSelectionModel().select("restore");
                source.setText("local:" + repoDir);
                destination.setText(":" + volume.tag() + ":pw-dst");
            });

            var settingsField = TaskFormController.class.getDeclaredField("settings");
            settingsField.setAccessible(true);

            // 1. Wrong password fails conformance probe
            settingsField.set(form, new EncryptionSettings(null, "", "wrong-secret", "wrong-secret", "", "", false));
            Task candidate = Dialogs.buildCandidateTask(context, form, null);
            BitferryError err = assertThrows(BitferryError.class, candidate::conform);
            assertTrue(err.getMessage().contains("does not accept the provided password"), err.getMessage());

            // 2. Correct password passes conformance probe without dismissing or resetting the form
            settingsField.set(form, new EncryptionSettings(null, "", "correct-secret", "correct-secret", "", "", false));
            Task validCandidate = Dialogs.buildCandidateTask(context, form, null);
            assertDoesNotThrow(validCandidate::conform);
        } finally {
            backup.delete();
            manager.commit();
        }
    }

    private static boolean available(String executable) {
        String env = System.getenv(executable.toUpperCase());
        String command = env != null && !env.isBlank() ? env : executable;
        try {
            Process process = new ProcessBuilder(command, "version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void restoreDiscoversForeignRepositorySnapshotsWhenCorrectPasswordProvided() throws Exception {
        Assumptions.assumeTrue(available("restic"), "restic not available");

        Path foreignRepoDir = Files.createTempDirectory("bitferry-foreign-repo");
        Path foreignSrcDir = Files.createTempDirectory("bitferry-foreign-src");
        Files.writeString(foreignSrcDir.resolve("file.txt"), "data");

        ProcessBuilder initPb = new ProcessBuilder("restic", "init", "-r", foreignRepoDir.toString());
        initPb.environment().put("RESTIC_PASSWORD", "foreign-secret");
        assertEquals(0, initPb.start().waitFor());

        ProcessBuilder backupPb = new ProcessBuilder("restic", "-r", foreignRepoDir.toString(), "backup",
            "--tag", "bitferry,foreign-task-42", foreignSrcDir.toString());
        backupPb.environment().put("RESTIC_PASSWORD", "foreign-secret");
        assertEquals(0, backupPb.start().waitFor());

        TaskFormController form = load(null);
        ComboBox<String> type = field(form, "type");
        TextField source = field(form, "source");
        TextField destination = field(form, "destination");
        ComboBox<String> scope = field(form, "snapshotScope");

        runFx(() -> {
            type.getSelectionModel().select("restore");
            source.setText("local:" + foreignRepoDir);
            destination.setText(":" + volume.tag() + ":foreign-dst");
        });

        // 1. Without password, scope candidate list is empty
        runFx(() -> {
            try {
                var m = TaskFormController.class.getDeclaredMethod("updateRestoreCandidates");
                m.setAccessible(true);
                m.invoke(form);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(scope.getItems().isEmpty(), "No local candidates exist and no password provided yet");

        // 2. Once password is provided in settings (simulating Security dialog exit), candidates are discovered
        var settingsField = TaskFormController.class.getDeclaredField("settings");
        settingsField.setAccessible(true);
        settingsField.set(form, new EncryptionSettings(null, "", "foreign-secret", "foreign-secret", "", "", false));

        runFx(() -> {
            try {
                var m = TaskFormController.class.getDeclaredMethod("updateRestoreCandidates");
                m.setAccessible(true);
                m.invoke(form);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertFalse(scope.getItems().isEmpty(), "Foreign repository snapshots should be discovered");
        assertTrue(scope.getItems().getFirst().startsWith("foreign-task-42"), scope.getItems().getFirst());
        assertEquals("foreign-task-42", form.snapshotScope());

        // 3. Candidate builds and conforms cleanly
        Task candidate = Dialogs.buildCandidateTask(context, form, null);
        assertDoesNotThrow(candidate::conform);
        assertDoesNotThrow(form::validate);
    }

    @Test
    void backupServeDefaultsAndServiceDialogEdit() throws Exception {
        java.nio.file.Path repoDir = Files.createTempDirectory("bitferry-form-service-repo");
        TaskFormController form = load(null);
        ComboBox<String> type = field(form, "type");
        TextField source = field(form, "source");
        TextField destination = field(form, "destination");
        HBox serveControl = field(form, "serveControl");
        Button editServe = field(form, "editServe");

        runFx(() -> {
            type.getSelectionModel().select("backup");
            source.setText(":" + volume.tag() + ":svc-src");
            destination.setText("local:" + repoDir);
        });

        var settingsField = TaskFormController.class.getDeclaredField("settings");
        settingsField.setAccessible(true);
        settingsField.set(form, new EncryptionSettings(null, "", "secret", "secret", "", "", false));

        assertTrue(rowLabels(form).contains("Serve:"));
        assertFalse(editServe.isDisable());
        assertEquals(List.of(editServe), serveControl.getChildren());
        assertEquals(ServiceSettings.DEFAULTS, form.serviceSettings());
        assertDoesNotThrow(form::validate);
        Backup defaultCandidate = (Backup) Dialogs.buildCandidateTask(context, form, null);
        assertEquals(Backup.CADENCE.get("default"), defaultCandidate.cadence());
        assertEquals(Backup.FORGET.get("default"), defaultCandidate.forget());
        assertNull(defaultCandidate.check());
        assertNull(defaultCandidate.prune());

        // Edit button launches ServiceDialog pre-seeded with cadence + phase lists and stores result on OK
        var originalPrompter = ServiceDialog.dialogPrompter;
        try {
            ServiceDialog.dialogPrompter = dialog -> {
                var root = dialog.getDialogPane().getContent();
                var durField = (TextField) root.lookup("#durationAmount");
                var cntField = (TextField) root.lookup("#countAmount");
                var checkField = (TextField) root.lookup("#checkFlags");
                var pruneField = (TextField) root.lookup("#pruneFlags");
                assertEquals("7", durField.getText());
                assertEquals("5", cntField.getText());
                assertEquals("", checkField.getText());
                assertEquals("", pruneField.getText());
                durField.setText("14");
                cntField.setText("10");
                checkField.setText("--read-data");
                pruneField.setText("--max-unused, 5%");
                return java.util.Optional.of(javafx.scene.control.ButtonType.OK);
            };
            runFx(() -> {
                try {
                    var m = TaskFormController.class.getDeclaredMethod("onEditServe");
                    m.setAccessible(true);
                    m.invoke(form);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals("14d,10", form.serviceSettings().cadence().format());
            assertEquals(List.of("--read-data"), form.serviceSettings().check());
            assertEquals(List.of("--max-unused", "5%"), form.serviceSettings().prune());

            // Unchecking Service cadence preserves check, forget, and prune settings
            ServiceDialog.dialogPrompter = dialog -> {
                var root = dialog.getDialogPane().getContent();
                var enabledBox = (javafx.scene.control.CheckBox) root.lookup("#enabled");
                var pruneField = (TextField) root.lookup("#pruneFlags");
                assertFalse(pruneField.isDisable(), "phase fields stay editable when cadence is disabled");
                enabledBox.setSelected(false);
                return java.util.Optional.of(javafx.scene.control.ButtonType.OK);
            };
            runFx(() -> {
                try {
                    var m = TaskFormController.class.getDeclaredMethod("onEditServe");
                    m.setAccessible(true);
                    m.invoke(form);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertNotNull(form.serviceSettings().cadence());
            assertFalse(form.serviceSettings().cadence().enabled());
            assertEquals("14d,10", form.serviceSettings().cadence().format());
            assertEquals(List.of("--read-data"), form.serviceSettings().check());
            assertEquals(Backup.FORGET.get("default"), form.serviceSettings().forget());
            assertEquals(List.of("--max-unused", "5%"), form.serviceSettings().prune());
        } finally {
            ServiceDialog.dialogPrompter = originalPrompter;
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
