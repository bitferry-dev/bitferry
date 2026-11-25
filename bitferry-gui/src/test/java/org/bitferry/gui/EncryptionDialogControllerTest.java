package org.bitferry.gui;

import org.bitferry.*;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the real encryption.fxml controller: the family-, mode-, and
/// creation-dependent row visibility (the crypt mode rides the task form's radio
/// row and is carried in read-only), the auto-loaded default crypt options, the
/// password validation, the salt entries, the storage radio, and the settings
/// capture.
class EncryptionDialogControllerTest {

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
    void unencryptedRcloneCreationShowsOnlyTheHint() throws Exception {
        EncryptionDialogController dialog = load(false, true, EncryptionSettings.NONE);
        assertFalse(dialog.keyring());
        // No encryption selected: the hint row explains where the mode lives, every
        // secret row stays hidden
        assertTrue(visible(dialog, "noCryptHint"));
        assertFalse(visible(dialog, "profile"));
        assertFalse(visible(dialog, "password"));
        assertFalse(visible(dialog, "salt"));
        assertFalse(visible(dialog, "keyring"));
        // The mode rides through unchanged (the task form's radios own it)
        assertNull(dialog.settings().mode());
    }

    @Test
    void carriedCryptModeRevealsTheRowsAndSeedsTheDefaults() throws Exception {
        EncryptionDialogController dialog = load(false, true,
            new EncryptionSettings("encrypt", "", "", "", "", "", false));
        // The @default crypt profile's concrete options pre-fill the options entry
        TextField profile = field(dialog, "profile");
        assertEquals("--crypt-filename-encoding, base32, --crypt-filename-encryption, standard", profile.getText());
        assertTrue(visible(dialog, "password"));
        assertTrue(visible(dialog, "passwordConfirm"));
        assertTrue(visible(dialog, "keyring"));
        assertFalse(visible(dialog, "noCryptHint"));
        assertEquals("encrypt", dialog.settings().mode());
        // The salt entries serve the rclone family: present and enabled
        assertTrue(visible(dialog, "salt"));
        PasswordField enabledSalt = field(dialog, "salt");
        assertFalse(enabledSalt.isDisable());
        assertTrue(visible(dialog, "saltConfirm"));
        PasswordField enabledSaltConfirm = field(dialog, "saltConfirm");
        assertFalse(enabledSaltConfirm.isDisable());
    }

    @Test
    void theSaltEntryCarriesIntoTheSettings() throws Exception {
        EncryptionDialogController dialog = load(false, true,
            new EncryptionSettings("encrypt", "", "", "", "", "", false));
        PasswordField password = field(dialog, "password");
        PasswordField salt = field(dialog, "salt");
        runFx(() -> password.setText("secret"));
        runFx(() -> salt.setText("pepper"));
        EncryptionSettings settings = dialog.settings();
        assertEquals("secret", settings.password());
        assertEquals("pepper", settings.salt());
        // The salt stays optional: the empty pair captures as empty
        runFx(() -> salt.setText(""));
        assertEquals("", dialog.settings().salt());
    }

    @Test
    void creationValidatesTheCryptPairAndTheSaltPair() throws Exception {
        EncryptionDialogController dialog = load(false, true,
            new EncryptionSettings("encrypt", "", "", "", "", "", false));
        // The password is required and must match its confirmation
        assertEquals("Encryption password must be specified",
            assertThrows(BitferryError.class, dialog::validate).getMessage());
        PasswordField password = field(dialog, "password");
        PasswordField passwordConfirm = field(dialog, "passwordConfirm");
        runFx(() -> password.setText("secret"));
        assertEquals("Encryption passwords do not match",
            assertThrows(BitferryError.class, dialog::validate).getMessage());
        runFx(() -> passwordConfirm.setText("secret"));
        // The salt stays optional: the empty pair passes
        assertDoesNotThrow(dialog::validate);
        // An entered salt must match its confirmation (both directions)
        PasswordField salt = field(dialog, "salt");
        PasswordField saltConfirm = field(dialog, "saltConfirm");
        runFx(() -> salt.setText("pepper"));
        assertEquals("Salts do not match", assertThrows(BitferryError.class, dialog::validate).getMessage());
        runFx(() -> saltConfirm.setText("pepper"));
        assertDoesNotThrow(dialog::validate);
        runFx(() -> salt.setText(""));
        assertEquals("Salts do not match", assertThrows(BitferryError.class, dialog::validate).getMessage());
        runFx(() -> saltConfirm.setText(""));
        assertDoesNotThrow(dialog::validate);
        assertEquals("secret", dialog.settings().password());
        assertEquals("", dialog.settings().salt());
    }

    @Test
    void resticCreationRequiresTheRepositoryPasswordAndKeepsNoSalt() throws Exception {
        EncryptionDialogController dialog = load(true, true, EncryptionSettings.NONE);
        assertEquals("Password must be specified",
            assertThrows(BitferryError.class, dialog::validate).getMessage());
        // The salt entries ride the restic backend disabled
        assertTrue(visible(dialog, "salt"));
        PasswordField disabledSalt = field(dialog, "salt");
        assertTrue(disabledSalt.isDisable());
        PasswordField disabledSaltConfirm = field(dialog, "saltConfirm");
        assertTrue(disabledSaltConfirm.isDisable());
        PasswordField password = field(dialog, "password");
        PasswordField passwordConfirm = field(dialog, "passwordConfirm");
        runFx(() -> password.setText("repo-secret"));
        assertEquals("Passwords do not match", assertThrows(BitferryError.class, dialog::validate).getMessage());
        runFx(() -> passwordConfirm.setText("repo-secret"));
        assertDoesNotThrow(dialog::validate);
        // The restic dialog never carries a crypt mode or a salt out
        assertNull(dialog.settings().mode());
        assertEquals("repo-secret", dialog.settings().password());
        assertEquals("", dialog.settings().salt());
    }

    @Test
    void resticModificationTreatsTheEmptyPairAsKeep() throws Exception {
        EncryptionDialogController dialog = load(true, false, EncryptionSettings.NONE);
        PasswordField password = field(dialog, "password");
        assertEquals("leave empty to keep current password", password.getPromptText());
        PasswordField disabledSalt = field(dialog, "salt");
        assertTrue(disabledSalt.isDisable());
        PasswordField disabledSaltConfirm = field(dialog, "saltConfirm");
        assertTrue(disabledSaltConfirm.isDisable());
        assertDoesNotThrow(dialog::validate);
        assertEquals("", dialog.settings().password());
        // The optional rotation validates the entered pair
        runFx(() -> password.setText("rotated"));
        assertEquals("Passwords do not match", assertThrows(BitferryError.class, dialog::validate).getMessage());
        PasswordField passwordConfirm = field(dialog, "passwordConfirm");
        runFx(() -> passwordConfirm.setText("rotated"));
        assertDoesNotThrow(dialog::validate);
        assertEquals("rotated", dialog.settings().password());
    }

    @Test
    void rcloneModificationKeepsTheStoredStateImmutable() throws Exception {
        EncryptionSettings carried = new EncryptionSettings("encrypt",
            "--crypt-filename-encoding, base32, --crypt-filename-encryption, standard", "", "", "", "", false);
        EncryptionDialogController dialog = load(false, false, carried);
        TextField profile = field(dialog, "profile");
        assertTrue(profile.isDisable());
        // The stored crypt token never echoes and the secrets ride the creation only
        assertFalse(visible(dialog, "password"));
        assertFalse(visible(dialog, "passwordConfirm"));
        assertFalse(visible(dialog, "salt"));
        assertTrue(visible(dialog, "keyring"));
        assertEquals("encrypt", dialog.settings().mode());
        assertEquals(carried.profileText(), dialog.settings().profileText());
        assertFalse(dialog.settings().keyring());
    }

    @Test
    void theStorageRadioDrivesTheRequest() throws Exception {
        EncryptionDialogController dialog = load(true, true, EncryptionSettings.NONE);
        RadioButton keyringChoice = field(dialog, "keyringChoice");
        RadioButton vaultChoice = field(dialog, "vaultChoice");
        if (keyringChoice.isDisable()) {
            // No usable OS keyring on this host: the volume vault stays the only option
            assertFalse(dialog.keyring());
        } else {
            runFx(() -> keyringChoice.setSelected(true));
            assertTrue(dialog.keyring());
            assertTrue(dialog.settings().keyring());
            runFx(() -> vaultChoice.setSelected(true));
        }
        assertFalse(dialog.keyring());
        assertFalse(dialog.settings().keyring());
    }

    @Test
    void showDialogReturnsSettingsOnOkAndEmptyOnCancel() throws Exception {
        AppContext context = new AppContext();
        EncryptionDialogController form = load(false, true,
            new EncryptionSettings("encrypt", "", "pass1", "pass1", "", "", false));

        var originalPrompter = EncryptionDialog.dialogPrompter;
        try {
            AtomicReference<Optional<EncryptionSettings>> okResult = new AtomicReference<>();
            EncryptionDialog.dialogPrompter = d -> Optional.of(ButtonType.OK);
            runFx(() -> okResult.set(EncryptionDialog.show(context, "Encryption", "Header", form)));
            assertTrue(okResult.get().isPresent());
            assertEquals("encrypt", okResult.get().get().mode());
            assertEquals("pass1", okResult.get().get().password());

            AtomicReference<Optional<EncryptionSettings>> cancelResult = new AtomicReference<>();
            EncryptionDialog.dialogPrompter = d -> Optional.of(ButtonType.CANCEL);
            runFx(() -> cancelResult.set(EncryptionDialog.show(context, "Encryption", "Header", form)));
            assertTrue(cancelResult.get().isEmpty());
        } finally {
            EncryptionDialog.dialogPrompter = originalPrompter;
        }
    }

    // The helpers: the FXML controller is loaded through its factory, the private
    // fields are reached reflectively, and the row visibility reads the relayout
    // (the invisible rows are detached from the grid)

    private static EncryptionDialogController load(boolean resticMode, boolean creation,
                                                   EncryptionSettings settings) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<EncryptionDialogController> reference = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                reference.set(EncryptionDialogController.load(resticMode, creation, settings));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                loaded.countDown();
            }
        });
        if (!loaded.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the dialog load timed out");
        if (failure.get() != null) throw new IllegalStateException("the dialog load failed", failure.get());
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

    private static boolean visible(EncryptionDialogController dialog, String name) throws Exception {
        GridPane grid = field(dialog, "grid");
        Node control = field(dialog, name);
        return grid.getChildren().contains(control);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(EncryptionDialogController dialog, String name) throws Exception {
        var field = EncryptionDialogController.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(dialog);
    }
}
