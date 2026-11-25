package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.task.*;
import org.bitferry.vault.*;
import javafx.application.*;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/// The security dialog form controller (encryption.fxml): the encryption mode and
/// options (the rclone family), the password entry (the crypt password for the
/// rclone tasks, the repository password for the restic ones) with the salt entry
/// (the rclone family only, disabled for the restic backend), and the password
/// storage selection. The family-dependent rows are shown/hidden and validated; the
/// dialog shell (the title, the buttons, the validation filter) lives in
/// [EncryptionDialog]. The task family direction and the creation/modification mode
/// are fixed by the calling task form.
public final class EncryptionDialogController {

  // One labeled grid row; the visibility is toggled per row as a whole
  private record Row(Label label, Node control, BooleanSupplier visible) {}

  /// The label column width floor covering the widest label ("Encryption options:")
  /// for the first frame, before the CSS-passed label measurements land (the labels
  /// read 0 pre-CSS; the post-show relayout refines the exact width).
  private static final double LABEL_COLUMN_FLOOR = 130;

  @FXML
  private GridPane grid;

  @FXML
  private ColumnConstraints labelColumn;

  @FXML
  private HBox profileControl;

  @FXML
  private TextField profile;

  @FXML
  private Button editProfile;

  @FXML
  private PasswordField password;

  @FXML
  private PasswordField passwordConfirm;

  @FXML
  private PasswordField salt;

  @FXML
  private PasswordField saltConfirm;

  @FXML
  private HBox keyring;

  @FXML
  private RadioButton keyringChoice;

  @FXML
  private RadioButton vaultChoice;

  // The row labels (the visibility toggles and the width pinning need the pairs)
  @FXML
  private Label hintLabel;
  @FXML
  private Label noCryptHint;
  @FXML
  private Label profileLabel;
  @FXML
  private Label passwordLabel;
  @FXML
  private Label passwordConfirmLabel;
  @FXML
  private Label saltLabel;
  @FXML
  private Label saltConfirmLabel;
  @FXML
  private Label keyringLabel;

  private final List<Row> rows = new ArrayList<>();

  // The auto-loaded (never user-edited) pre-filled defaults keyed by the field
  private final Map<TextField, String> autoLoaded = new HashMap<>();

  // The calling task form's context: the family, the mode, and the carried crypt
  // mode (the task form's radio row owns the mode — the dialog mirrors it read-only)
  private boolean resticMode;
  private boolean creation;
  private String mode;

  public EncryptionDialogController() {}

  // The static display wiring (the family and the mode ride init())
  @FXML
  private void initialize() {
    BooleanSupplier encrypted = this::encrypted;
    // The password rides the creation mode only (the modification keeps the stored
    // token) except the restic repository password, which stays visible in the
    // modification as the optional rotation entry
    BooleanSupplier passwordVisible = () -> resticMode || (creation && encrypted.getAsBoolean());

    rows.add(new Row(hintLabel, noCryptHint, () -> !resticMode && !encrypted.getAsBoolean()));
    rows.add(new Row(profileLabel, profileControl, () -> !resticMode && encrypted.getAsBoolean()));
    rows.add(new Row(passwordLabel, password, passwordVisible));
    rows.add(new Row(passwordConfirmLabel, passwordConfirm, passwordVisible));
    rows.add(new Row(saltLabel, salt, passwordVisible));
    rows.add(new Row(saltConfirmLabel, saltConfirm, passwordVisible));
    rows.add(new Row(keyringLabel, keyring, () -> resticMode || encrypted.getAsBoolean()));

    // The row buttons are pinned to their pref width (mirroring the task form)
    for (Row row : rows) {
      if (row.control() instanceof HBox box) {
        for (Node child : box.getChildren()) {
          if (child instanceof Button button) button.setMinWidth(Region.USE_PREF_SIZE);
        }
      }
    }

    Icons.equip(editProfile, Icons.Symbol.EDIT);

    // The volume vault is the default destination (selected in the FXML); the keyring
    // choice is offered (and honored) only when the OS keyring is usable on this host
    // — otherwise it is grayed out and the vault stays the only selectable option
    keyringChoice.setDisable(!KeyringVault.INSTANCE.available());
    Tooltip.install(keyringChoice, new Tooltip(KeyringVault.INSTANCE.available()
      ? "The OS keyring (Windows Credential Manager, macOS Keychain, GNOME Keyring): machine- and user-bound, recommended for the fixed volumes — the tokens do not travel with the removable media"
      : "No usable OS keyring on this host — the volume vault keeps serving"));
    Tooltip.install(vaultChoice, new Tooltip("The vault section of the storage of the volume hosting the unencrypted end: the token travels with the volume (recommended for the removable media)"));

    // The first relayout ran before the scene existed (the pre-CSS font measurement):
    // re-run it once the dialog shows, so the column widths land with the real metrics
    grid.sceneProperty().addListener((x, was, now) -> {
      if (now != null) Platform.runLater(this::relayout);
    });

    relayout();
  }

  /// Inject the calling context (called by the facade before the dialog shows):
  /// @param resticMode the restic family (the repository password; the salt entry
  ///                   disabled; no crypt rows)
  /// @param creation   the creation mode (the password required; the crypt pair
  ///                   visible) as opposed to the modification (the optional rotation)
  /// @param current    the settings carried from the task form (the reopen round-trip)
  public void init(boolean resticMode, boolean creation, EncryptionSettings current) {
    this.resticMode = resticMode;
    this.creation = creation;

    // The crypt mode rides the task form's radios: the dialog mirrors it read-only
    // (the crypt rows and the secret visibility follow it)
    this.mode = resticMode ? null : current.mode();

    // Populate the carried settings (the in-memory secret round-trip included: the
    // same exposure as the task form's password fields while the dialog is open)
    if (current.profileText() != null && !current.profileText().isBlank()) {
      profile.setText(current.profileText());
    }
    password.setText(current.password());
    passwordConfirm.setText(current.passwordConfirm());
    salt.setText(current.salt());
    saltConfirm.setText(current.saltConfirm());

    if (!creation) {
      if (resticMode) {
        password.setPromptText("leave empty to keep current password");
      } else {
        // The crypt options are the creation-time choices: the modification keeps
        // the stored ones immutable (mirroring the task form)
        profile.setDisable(true);
        editProfile.setDisable(true);
      }
    }

    // The salt entries serve the rclone family only: the restic backend keeps no salt
    salt.setDisable(resticMode);
    saltConfirm.setDisable(resticMode);

    // The password-storage radios mirror the carried selection: switching migrates
    // the token upon the apply (the radio gray-out from the availability probe
    // still applies)
    if (current.keyring()) {
      keyringChoice.setSelected(true);
    }

    seedProfileDefaults();
    relayout();
  }

  /// The form root for the dialog shell content.
  public GridPane pane() {
    return grid;
  }

  // Lay visible rows out consecutively (mirroring the task form's dynamic grid: the
  // invisible ones detached, the visible ones re-added consecutively), then measure.
  // The freshly revealed rows read 0 for their preferred sizes until their CSS lands
  // (deferred runLater passes may still precede that pulse), so the CSS is applied
  // to the whole grid subtree explicitly: once the grid rides a scene the sizes are
  // real immediately (pre-show the floor stands in for the label column).
  private void relayout() {
    grid.getChildren().clear();
    int row = 0;
    for (Row formRow : rows) {
      boolean visible = formRow.visible().getAsBoolean();
      formRow.label().setVisible(visible);
      formRow.label().setManaged(visible);
      formRow.control().setVisible(visible);
      formRow.control().setManaged(visible);
      if (visible) {
        grid.add(formRow.label(), 0, row);
        grid.add(formRow.control(), 1, row);
        GridPane.setHgrow(formRow.control(), Priority.ALWAYS);
        row++;
      }
    }
    if (grid.getScene() != null) grid.applyCss();

    // The label column pinned to the widest visible label (measured, so it scales
    // with the font size), and the grid demanded at its full content width: the
    // dialog window sizes from it, both on the first frame and on the row toggles.
    // Recomputed fresh each pass — reading back the earlier set would go stale. The
    // manual widening still absorbs into the hgrow control column.
    double widestLabel = 0;
    for (Row formRow : rows) {
      if (formRow.label().isVisible()) {
        widestLabel = Math.max(widestLabel, formRow.label().prefWidth(-1));
      }
    }
    labelColumn.setMinWidth(widestLabel);
    labelColumn.setPrefWidth(widestLabel);

    double widestControl = 0;
    for (Row formRow : rows) {
      if (formRow.control().isVisible()) {
        widestControl = Math.max(widestControl, formRow.control().prefWidth(-1));
      }
    }

    double insets = grid.snappedLeftInset() + grid.snappedRightInset();
    grid.setPrefWidth(Math.max(widestLabel, LABEL_COLUMN_FLOOR) + grid.getHgap() + widestControl + insets);

    Platform.runLater(() -> {
      if (grid.getScene() != null && grid.getScene().getWindow() != null) {
        grid.getScene().getWindow().sizeToScene();
      }
    });
  }

  /// Whether the carried crypt mode is set (the rclone family with a selected mode).
  private boolean encrypted() {
    return mode != null;
  }

  /// Pre-load the @default profile's concrete options into an empty (or untouched
  /// auto-loaded) options entry when a crypt mode is selected (mirroring the task
  /// form's loadDefaultProfile); the user-edited text stays intact.
  private void seedProfileDefaults() {
    // The edit-mode fields carry user data unless seeded as the auto-loaded default
    if (!creation && !autoLoaded.containsKey(profile)) return;
    String current = profile.getText().trim();
    if (!current.isBlank() && !current.equals(autoLoaded.get(profile))) return;
    List<String> defaults = encrypted() ? Rclone.PROFILE.get("default") : null;
    String text = defaults == null || defaults.isEmpty() ? "" : String.join(", ", defaults);
    if (text.equals(current)) return;
    profile.setText(text);
    autoLoaded.put(profile, text);
  }

  @FXML
  private void onEditProfile() {
    if (profile.isDisable()) return;
    List<String> current = patterns(profile.getText());
    // The single @-specifier entry resolves into the profile's concrete options
    // before the editing (mirroring the task form's editList)
    if (current.size() == 1 && current.get(0).startsWith("@")) {
      String rawKey = current.get(0);
      String unAt = rawKey.substring(1);
      if (Rclone.PROFILE.containsKey(unAt)) {
        current = Rclone.PROFILE.get(unAt);
      } else if (Rclone.PROFILE.containsKey(rawKey)) {
        current = Rclone.PROFILE.get(rawKey);
      }
    }
    javafx.stage.Window window = profile.getScene() != null ? profile.getScene().getWindow() : null;
    ItemListDialog.show(window, "Encryption Options",
      "Edit encryption options (the crypt filename encoding profiles or the raw comma-separated options)", current, Rclone.PROFILE)
      .ifPresent(list -> profile.setText(String.join(", ", list)));
  }

  // The comma-separated specifier list into the pattern list
  private static List<String> patterns(String spec) {
    List<String> patterns = new ArrayList<>();
    if (spec != null && !spec.isBlank()) {
      for (String part : spec.split(",")) {
        part = part.trim();
        if (!part.isEmpty()) patterns.add(part);
      }
    }
    return patterns;
  }

  String profile() {
    return profile.getText().trim();
  }

  boolean keyring() {
    // The keyring destination only when its radio is selected and selectable: the
    // grayed-out choice (no usable OS keyring on the host) keeps serving the vault
    return keyringChoice.isSelected() && !keyringChoice.isDisable();
  }

  /// Validate the entries on the dialog's OK (the shell routes the failure into the
  /// error alert keeping the dialog open): the creation requires the password and
  /// the matching pair; the restic modification treats the empty pair as keep; the
  /// salt stays optional (mirroring the CLI's --salt) but an entered salt must match
  /// its confirmation.
  void validate() {
    boolean passwordVisible = resticMode || (creation && encrypted());
    if (!passwordVisible) return;
    if (creation) {
      if (password.getText().isEmpty()) {
        throw new BitferryError(resticMode ? "Password must be specified" : "Encryption password must be specified");
      }
      if (!password.getText().equals(passwordConfirm.getText())) {
        throw new BitferryError(resticMode ? "Passwords do not match" : "Encryption passwords do not match");
      }
    } else if (resticMode) {
      // The optional rotation: the empty pair keeps the current password
      if (!password.getText().isEmpty() || !passwordConfirm.getText().isEmpty()) {
        if (password.getText().isEmpty()) throw new BitferryError("Password must be specified");
        if (!password.getText().equals(passwordConfirm.getText())) throw new BitferryError("Passwords do not match");
      }
    }
    if (!resticMode && !salt.getText().equals(saltConfirm.getText())) {
      throw new BitferryError("Salts do not match");
    }
  }

  /// The settings captured from the entries (the shell converts the OK result). The
  /// crypt mode rides through unchanged — the task form's radios own it.
  EncryptionSettings settings() {
    return new EncryptionSettings(mode, profile(), password.getText(),
      passwordConfirm.getText(), salt.getText(), saltConfirm.getText(), keyring());
  }

  /// Load the FXML-backed form injecting the calling context (called by the facade
  /// before the dialog shows).
  static EncryptionDialogController load(boolean resticMode, boolean creation, EncryptionSettings current) {
    try {
      FXMLLoader loader = new FXMLLoader(EncryptionDialogController.class.getResource("/org/bitferry/gui/encryption.fxml"));
      loader.load();
      EncryptionDialogController controller = loader.getController();
      controller.init(resticMode, creation, current);
      return controller;
    } catch (IOException e) {
      throw new BitferryError("Encryption dialog form loading failure", e);
    }
  }
}
