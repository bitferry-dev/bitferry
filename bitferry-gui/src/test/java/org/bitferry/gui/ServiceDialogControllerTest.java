package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.task.*;
import javafx.application.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the unified service.fxml controller: cadence thresholds and preset loading,
/// per-phase option entries (check, forget, prune) without extra per-phase toggles,
/// phase option preservation when cadence is disabled, validation guards, and
/// ServiceDialog result handling.
class ServiceDialogControllerTest {

    private static final CountDownLatch FX_READY = new CountDownLatch(1);

    @BeforeAll
    static void startFx() throws Exception {
        try {
            Platform.startup(FX_READY::countDown);
            if (!FX_READY.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the FX toolkit did not start");
        } catch (IllegalStateException ignored) {
            // Already started by another test class in this JVM
        }
    }

    @Test
    void populatesCadenceAndPhasesWithUnitDecomposition() throws Exception {
        ServiceSettings initial = new ServiceSettings(
            new Cadence(Duration.ofDays(14), 10),
            List.of("--read-data"),
            List.of("--keep-daily", "7"),
            List.of("--max-unused", "5%")
        );
        ServiceDialogController dialog = load(initial);

        assertTrue(((CheckBox) field(dialog, "enabled")).isSelected());
        assertEquals("2", ((TextField) field(dialog, "durationAmount")).getText());
        assertEquals("weeks", ((ComboBox<?>) field(dialog, "durationUnit")).getValue());
        assertEquals("10", ((TextField) field(dialog, "countAmount")).getText());

        assertEquals("--read-data", ((TextField) field(dialog, "checkFlags")).getText());
        assertEquals("--keep-daily, 7", ((TextField) field(dialog, "forgetFlags")).getText());
        assertEquals("--max-unused, 5%", ((TextField) field(dialog, "pruneFlags")).getText());

        assertEquals(initial, dialog.settings());
    }

    @Test
    void disabledCadencePreservesPhaseOptionsAndEmptyFieldsMapToNull() throws Exception {
        ServiceSettings initial = new ServiceSettings(
            null,
            null,
            List.of("--keep-daily", "7"),
            null
        );
        ServiceDialogController dialog = load(initial);

        CheckBox enabled = field(dialog, "enabled");
        TextField checkFlags = field(dialog, "checkFlags");
        Button editCheck = field(dialog, "editCheck");
        TextField forgetFlags = field(dialog, "forgetFlags");
        TextField pruneFlags = field(dialog, "pruneFlags");

        assertFalse(enabled.isSelected());
        assertEquals("", checkFlags.getText());
        assertFalse(checkFlags.isDisable());
        assertFalse(editCheck.isDisable());
        assertEquals("--keep-daily, 7", forgetFlags.getText());
        assertFalse(forgetFlags.isDisable());
        assertEquals("", pruneFlags.getText());
        assertFalse(pruneFlags.isDisable());

        assertDoesNotThrow(dialog::validate);
        ServiceSettings result = dialog.settings();
        assertNull(result.cadence());
        assertNull(result.check());
        assertEquals(List.of("--keep-daily", "7"), result.forget());
        assertNull(result.prune());
    }

    @Test
    void togglingEnabledPreservesCadenceThresholds() throws Exception {
        ServiceSettings disabledWithCustom = new ServiceSettings(
            new Cadence(false, Duration.ofDays(14), 10),
            null,
            null,
            null
        );
        ServiceDialogController dialog = load(disabledWithCustom);
        CheckBox enabled = field(dialog, "enabled");
        assertFalse(enabled.isSelected());
        assertEquals("2", ((TextField) field(dialog, "durationAmount")).getText());
        assertEquals("weeks", ((ComboBox<?>) field(dialog, "durationUnit")).getValue());
        assertEquals("10", ((TextField) field(dialog, "countAmount")).getText());
        assertEquals(new Cadence(false, Duration.ofDays(14), 10), dialog.settings().cadence());

        // Enabling restores the preserved thresholds without resetting to @default
        runFx(() -> enabled.setSelected(true));
        assertEquals(new Cadence(true, Duration.ofDays(14), 10), dialog.settings().cadence());

        // Disabling again keeps the thresholds with enabled == false
        runFx(() -> enabled.setSelected(false));
        assertEquals(new Cadence(false, Duration.ofDays(14), 10), dialog.settings().cadence());
    }

    @Test
    void validatesCadenceAndPhaseProfilesAndClearingForgetSkipsIt() throws Exception {
        ServiceDialogController dialog = load(ServiceSettings.DEFAULTS);
        VBox profileBar = field(dialog, "profileBar");
        ComboBox<String> profileCombo = field(dialog, "profileCombo");
        CheckBox enabled = field(dialog, "enabled");
        CheckBox durationEnabled = field(dialog, "durationEnabled");
        TextField durationAmount = field(dialog, "durationAmount");
        CheckBox countEnabled = field(dialog, "countEnabled");
        TextField countAmount = field(dialog, "countAmount");
        TextField forgetFlags = field(dialog, "forgetFlags");
        TextField checkFlags = field(dialog, "checkFlags");

        assertTrue(profileBar.isVisible());
        assertEquals(List.of("@default"), profileCombo.getItems());
        assertEquals("@default", profileCombo.getValue());

        // Neither cadence threshold checked while cadence enabled
        runFx(() -> {
            durationEnabled.setSelected(false);
            countEnabled.setSelected(false);
        });
        assertThrows(BitferryError.class, dialog::validate);

        // Non-positive count fails
        runFx(() -> {
            countEnabled.setSelected(true);
            countAmount.setText("0");
        });
        assertThrows(BitferryError.class, dialog::validate);

        // Profile Load restores @default thresholds
        runFx(() -> {
            try {
                var m = ServiceDialogController.class.getDeclaredMethod("onProfileLoad");
                m.setAccessible(true);
                m.invoke(dialog);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(enabled.isSelected());
        assertTrue(durationEnabled.isSelected());
        assertEquals("7", durationAmount.getText());
        assertEquals("5", countAmount.getText());
        assertDoesNotThrow(dialog::validate);

        // Clearing forgetFlags maps to null (skips forget without validation error)
        runFx(() -> forgetFlags.setText(""));
        assertDoesNotThrow(dialog::validate);
        assertNull(dialog.settings().forget());

        // @full typed in check resolves to ["--read-data"]; unknown @profile fails validation
        runFx(() -> checkFlags.setText("@full"));
        assertDoesNotThrow(dialog::validate);
        assertEquals(Backup.CHECK.get("full"), dialog.settings().check());

        runFx(() -> checkFlags.setText("@nonexistent"));
        assertThrows(BitferryError.class, dialog::validate);
    }

    @Test
    void dialogShellWrapsOkAndCancelDistinctly() throws Exception {
        ServiceDialogController okForm = load(ServiceSettings.DEFAULTS);
        ServiceDialogController cancelForm = load(ServiceSettings.DEFAULTS);
        var originalPrompter = ServiceDialog.dialogPrompter;
        try {
            ServiceDialog.dialogPrompter = d -> Optional.of(ButtonType.OK);
            Optional<ServiceSettings> okResult = runFxResult(() -> ServiceDialog.show(null, "Service", null, okForm));
            assertTrue(okResult.isPresent());
            assertEquals(ServiceSettings.DEFAULTS, okResult.get());

            ServiceDialog.dialogPrompter = d -> Optional.empty();
            Optional<ServiceSettings> cancelResult = runFxResult(() -> ServiceDialog.show(null, "Service", null, cancelForm));
            assertTrue(cancelResult.isEmpty());
        } finally {
            ServiceDialog.dialogPrompter = originalPrompter;
        }
    }

    @Test
    void checkOnlyModeHidesCadenceForgetPruneAndReturnsCheckSettings() throws Exception {
        ServiceSettings initial = new ServiceSettings(null, List.of("--read-data"), null, null);
        ServiceDialogController dialog = runFxResult(() -> ServiceDialogController.load(initial, Backup.CADENCE, true));

        VBox profileBar = field(dialog, "profileBar");
        GridPane grid = field(dialog, "grid");
        TextField checkFlags = field(dialog, "checkFlags");

        assertFalse(profileBar.isVisible());
        assertEquals(2, grid.getChildren().size(), "only Check label and Check row should inhabit the grid");
        assertEquals("--read-data", checkFlags.getText());
        assertDoesNotThrow(dialog::validate);
        assertEquals(new ServiceSettings(null, List.of("--read-data"), null, null), dialog.settings());

        runFx(() -> checkFlags.setText("--no-cache, --read-data"));
        assertDoesNotThrow(dialog::validate);
        assertEquals(List.of("--no-cache", "--read-data"), dialog.settings().check());
        assertNull(dialog.settings().cadence());
        assertNull(dialog.settings().forget());
        assertNull(dialog.settings().prune());
    }

    private static ServiceDialogController load(ServiceSettings settings) throws Exception {
        return runFxResult(() -> ServiceDialogController.load(settings, Map.of("default", new Cadence(Duration.ofDays(7), 5))));
    }

    private static void runFx(Runnable action) throws Exception {
        runFxResult(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T runFxResult(java.util.concurrent.Callable<T> action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("FX action timed out");
        if (failure.get() != null) throw new IllegalStateException("FX action failed", failure.get());
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(ServiceDialogController ctrl, String name) throws Exception {
        var f = ServiceDialogController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(ctrl);
    }
}
