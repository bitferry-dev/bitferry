package io.bitferry.gui;

import io.bitferry.*;
import io.bitferry.task.Backup;
import io.bitferry.task.Copy;
import io.bitferry.task.Equalize;
import io.bitferry.task.IncludeFilters;
import io.bitferry.task.Replicate;
import io.bitferry.task.Rclone;
import io.bitferry.task.Restore;
import io.bitferry.task.Restic;
import io.bitferry.task.Task;
import io.bitferry.task.Update;
import io.bitferry.vault.KeyringVault;
import io.bitferry.vault.Vault;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// The unified task form controller (task.fxml) for both creation and
/// modification: the type-dependent rows are shown/hidden and validated, and the
/// immutable fields are disabled when modifying an existing task. The dialog shell
/// (the title, the buttons, the validation filter) lives in [Dialogs].
public final class TaskFormController {

  /// The endpoint specifier hint shared by the endpoint entry fields.
  private static final String ENDPOINT_HINT = """
    Endpoint specifier:
      directory        bare path, attached to the encompassing volume
      local:directory  explicit local path (outside the volumes)
      ::directory      resolved against the intact volume
      :tag:directory   relative to the volume matched by (partial) tag""";

  /// Presets for include pattern lists.
  static final Map<String, List<String>> INCLUDE_PROFILES = Map.of("@default", List.of());

  /// Presets for exclude pattern lists.
  static final Map<String, List<String>> EXCLUDE_PROFILES = Map.of("@default", List.of());

  /// Execution options profiles per task operation type.
  private static final Map<String, Map<String, List<String>>> EXECUTE_PROFILES = Map.of(
    Backup.OPERATION, Restic.PROCESS,
    Restore.OPERATION, Restore.PROCESS,
    Equalize.OPERATION, Equalize.PROCESS,
    Copy.OPERATION, Rclone.PROCESS,
    Update.OPERATION, Rclone.PROCESS,
    Replicate.OPERATION, Rclone.PROCESS
  );

  /// The volume selector entry carrying the display form.
  private record VolumeChoice(Volume volume) {
    @Override
    public String toString() {
      return volume.tag() + "  —  " + volume.root();
    }
  }

  // One labeled grid row; the visibility is toggled per row as a whole
  private record Row(Label label, Node control, BooleanSupplier visible) {}

  @FXML
  private ScrollPane scroller;

  @FXML
  private GridPane grid;

  @FXML
  private ColumnConstraints labelColumn;

  @FXML
  private ComboBox<String> type;

  @FXML
  private TextField source;

  @FXML
  private Button browseSourceSmart;

  @FXML
  private Button browseSourceLocal;

  @FXML
  private Button browseSourceVolume;

  @FXML
  private TextField destination;

  @FXML
  private Button browseDestinationSmart;

  @FXML
  private Button browseDestinationLocal;

  @FXML
  private Button browseDestinationVolume;

  @FXML
  private TextField include;

  @FXML
  private HBox includeControl;

  @FXML
  private TextField exclude;

  @FXML
  private TextField execute;

  @FXML
  private ComboBox<String> encryption;

  @FXML
  private TextField profile;

  @FXML
  private Button editProfile;

  @FXML
  private PasswordField cryptPassword;

  @FXML
  private PasswordField cryptPasswordConfirm;

  @FXML
  private PasswordField password;

  @FXML
  private PasswordField passwordConfirm;

  @FXML
  private HBox keyring;

  @FXML
  private RadioButton keyringChoice;

  @FXML
  private RadioButton vaultChoice;

  @FXML
  private CheckBox format;

  @FXML
  private CheckBox manual;

  @FXML
  private TextField forget;

  @FXML
  private TextField check;

  @FXML
  private TextField serve;

  // The row labels (the visibility toggles and the width pinning need the pairs)
  @FXML
  private Label typeLabel;
  @FXML
  private Label sourceLabel;
  @FXML
  private Label destinationLabel;
  @FXML
  private Label includeLabel;
  @FXML
  private Label excludeLabel;
  @FXML
  private Label executeLabel;
  @FXML
  private Label encryptionLabel;
  @FXML
  private Label profileLabel;
  @FXML
  private Label cryptPasswordLabel;
  @FXML
  private Label cryptPasswordConfirmLabel;
  @FXML
  private Label passwordLabel;
  @FXML
  private Label passwordConfirmLabel;
  @FXML
  private Label keyringLabel;
  @FXML
  private Label forgetLabel;
  @FXML
  private Label checkLabel;
  @FXML
  private Label formatLabel;
  @FXML
  private Label serveLabel;
  @FXML
  private Label manualLabel;

  private final List<Row> rows = new ArrayList<>();

  private AppContext context;
  private Task existing;

  // The auto-loaded (never user-edited) pre-filled defaults keyed by the field:
  // switching the operation replaces them with the new operation's defaults while
  // the user-edited content stays intact
  private final Map<TextField, String> autoLoaded = new HashMap<>();

  public TaskFormController() {}

  // The static display wiring (the context and the existing task ride init())
  @FXML
  private void initialize() {
    // The default operation and the no-encryption mode preselect (the predicates
    // below read them)
    type.getSelectionModel().selectFirst();
    encryption.getSelectionModel().selectFirst();

    // Visibility predicates re-evaluated on type/encryption changes
    BooleanSupplier restic = () -> "backup".equals(type()) || "restore".equals(type());
    BooleanSupplier backup = () -> "backup".equals(type());
    BooleanSupplier rclone = () -> !restic.getAsBoolean();
    BooleanSupplier encrypted = () -> rclone.getAsBoolean() && encryption() != null;
    BooleanSupplier cryptPasswordVisible = () -> existing == null && encrypted.getAsBoolean();
    BooleanSupplier formatVisible = () -> existing == null && backup.getAsBoolean();
    // The password-storage row rides the modify mode too: the radio selection mirrors
    // the task's current provider, and switching it migrates the token on apply
    BooleanSupplier keyringVisible = () -> restic.getAsBoolean() || encrypted.getAsBoolean();

    rows.add(new Row(typeLabel, type, () -> true));
    rows.add(new Row(sourceLabel, source.getParent(), () -> true));
    rows.add(new Row(destinationLabel, destination.getParent(), () -> true));
    // The include control is disabled for the backup type: restic backup filters by
    // exclusion only, so the entered patterns would be silently ignored
    rows.add(new Row(includeLabel, includeControl, () -> true));
    rows.add(new Row(excludeLabel, exclude.getParent(), () -> true));
    rows.add(new Row(executeLabel, execute.getParent(), () -> true));
    rows.add(new Row(encryptionLabel, encryption, rclone));
    rows.add(new Row(profileLabel, profile.getParent(), encrypted));
    rows.add(new Row(cryptPasswordLabel, cryptPassword, cryptPasswordVisible));
    rows.add(new Row(cryptPasswordConfirmLabel, cryptPasswordConfirm, cryptPasswordVisible));
    rows.add(new Row(passwordLabel, password, restic));
    rows.add(new Row(passwordConfirmLabel, passwordConfirm, restic));
    rows.add(new Row(keyringLabel, keyring, keyringVisible));
    rows.add(new Row(forgetLabel, forget.getParent(), backup));
    rows.add(new Row(checkLabel, check.getParent(), backup));
    rows.add(new Row(formatLabel, format, formatVisible));
    rows.add(new Row(serveLabel, serve, backup));
    rows.add(new Row(manualLabel, manual, () -> true));

    // The row buttons are pinned to their pref width: under the viewport squeeze
    // (the window resize, the screen clamping, the vertical scrollbar allowance)
    // they would shrink below the label width truncating it - the deficit is
    // absorbed by the entry fields instead (mirroring the label column pinning)
    for (Row row : rows) {
      if (row.control() instanceof HBox box) {
        for (Node child : box.getChildren()) {
          if (child instanceof Button button) button.setMinWidth(Region.USE_PREF_SIZE);
        }
      }
    }

    includeControl.disableProperty().bind(type.valueProperty().isEqualTo("backup"));

    // The endpoint browsing buttons mirror the disabled state of the corresponding entries
    browseSourceSmart.disableProperty().bind(source.disableProperty());
    browseSourceLocal.disableProperty().bind(source.disableProperty());
    browseSourceVolume.disableProperty().bind(source.disableProperty());
    browseDestinationSmart.disableProperty().bind(destination.disableProperty());
    browseDestinationLocal.disableProperty().bind(destination.disableProperty());
    browseDestinationVolume.disableProperty().bind(destination.disableProperty());

    // The encryption options edit button mirrors the disabled state of the profile entry
    editProfile.disableProperty().bind(profile.disableProperty());

    // The volume vault is the default destination (selected in the FXML); the keyring
    // choice is offered (and honored) only when the OS keyring is usable on this host
    // — otherwise it is grayed out and the vault stays the only selectable option
    keyringChoice.setDisable(!KeyringVault.INSTANCE.available());
    Tooltip.install(keyringChoice, new Tooltip(KeyringVault.INSTANCE.available()
      ? "The OS keyring (Windows Credential Manager, macOS Keychain, GNOME Keyring): machine- and user-bound, recommended for the fixed volumes — the tokens do not travel with the removable media"
      : "No usable OS keyring on this host — the volume vault keeps serving"));
    Tooltip.install(vaultChoice, new Tooltip("The vault section of the storage of the volume hosting the unencrypted end: the token travels with the volume (recommended for the removable media)"));

    Tooltip.install(source, new Tooltip(ENDPOINT_HINT));
    Tooltip.install(destination, new Tooltip(ENDPOINT_HINT));

    // The operation/encryption changes refresh the empty (never user-edited) option
    // entries with the new operation's defaults and lay the rows out
    type.valueProperty().addListener((x, was, now) -> { loadDefaultProfiles(); relayout(); });
    encryption.valueProperty().addListener((x, was, now) -> { loadDefaultProfiles(); relayout(); });

    // The first relayout ran before the scene existed (the pre-CSS font measurement):
    // re-run it once the dialog shows, so the column widths land with the real metrics
    scroller.sceneProperty().addListener((x, was, now) -> {
      if (now != null) Platform.runLater(this::relayout);
    });

    relayout();
  }

  /// Inject the shared context and the existing task (null for the creation mode):
  /// the population of the existing task's values, the immutable fields disabling,
  /// and the default profiles loading (called by the facade before the dialog shows).
  public void init(AppContext context, Task existing) {
    this.context = context;
    this.existing = existing;

    // Populate existing task values and disable immutable fields
    if (existing != null) {
      String existingType = switch (existing) {
        case Copy ignored -> "copy";
        case Update ignored -> "update";
        case Replicate ignored -> "replicate";
        case Equalize ignored -> "equalize";
        case Backup ignored -> "backup";
        case Restore ignored -> "restore";
        default -> "";
      };
      if (existing.supportsOperationChange()) {
        type.getItems().setAll("copy", "update", "replicate", "equalize");
        type.setValue(existingType);
        type.setDisable(false);
      } else {
        type.setValue(existingType);
        type.setDisable(true);
      }

      source.setText(existing.source().describe());
      source.setDisable(true);
      destination.setText(existing.destination().describe());
      destination.setDisable(true);

      // The include field exists on the capable tasks only (the capability is
      // structural): the form mirrors the task's actual state
      if (existing instanceof IncludeFilters includable && !includable.include().isEmpty()) {
        include.setText(String.join(", ", includable.include()));
      }
      if (!existing.exclude().isEmpty()) {
        exclude.setText(String.join(", ", existing.exclude()));
      }

      List<String> curOpts = existing.processOptions();
      execute.setText(String.join(", ", curOpts));
      // The options equal to the operation's default profile are treated as the
      // auto-loaded content: the type switch replaces them with the new operation's
      // default (the bisync-only flags must not ride a morphed sync task), while a
      // customized set stays with the task
      if (curOpts.equals(executeProfiles().get("default"))) {
        autoLoaded.put(execute, String.join(", ", curOpts));
      }

      if (existing instanceof Rclone rclone) {
        Rclone.Encryption enc = rclone.encryption();
        if (enc != null && enc.operation() != null) {
          encryption.setValue(enc.operation());
          if (enc.options() != null && !enc.options().isEmpty()) {
            profile.setText(String.join(", ", enc.options()));
          }
        } else {
          encryption.setValue("(none)");
        }
        encryption.setDisable(true);
        profile.setDisable(true);
        cryptPassword.setDisable(true);
        cryptPasswordConfirm.setDisable(true);
      }

      manual.setSelected(existing.isManual());

      // The password-storage radios mirror the task's actual provider: switching the
      // selection migrates the token upon the apply (the radio gray-out from the
      // availability probe still applies)
      if (existing.usesVault() && existing.vaultProvider().orElse(Vault.volume()) == Vault.keyring()) {
        keyringChoice.setSelected(true);
      }

      if (existing instanceof Backup backup) {
        if (backup.forget() != null && !backup.forget().isEmpty()) {
          forget.setText(String.join(", ", backup.forget()));
        }
        if (backup.check() != null && !backup.check().isEmpty()) {
          check.setText(String.join(", ", backup.check()));
        }
        if (backup.cadence() != null) {
          serve.setText(backup.cadence().format());
        }
        format.setDisable(true);
      }

      if (existing instanceof Restic) {
        password.setPromptText("leave empty to keep current password");
        passwordConfirm.setPromptText("confirm new password");
      }
    }

    // The new-task option entries start from the operation's default profiles
    loadDefaultProfiles();

    relayout();
  }

  /// The form root for the dialog shell content.
  public ScrollPane pane() {
    return scroller;
  }

  // Lay visible rows out consecutively and request scene sizing. The rows travel
  // through the grid dynamically: the invisible ones are detached (the FXML-declared
  // children form the pool), the visible ones re-added consecutively keeping the
  // vertical gaps uniform.
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
    // The label column pinned to the widest visible label (measured, so it scales
    // with the font size): when the viewport squeezes the grid (the vertical scrollbar
    // appearing, the screen clamping the window growth), the deficit is absorbed by
    // the entry fields — the labels can never truncate
    double widestLabel = 0;
    for (Row formRow : rows) {
      if (formRow.label().isVisible()) {
        widestLabel = Math.max(widestLabel, formRow.label().prefWidth(-1));
      }
    }
    labelColumn.setMinWidth(widestLabel);
    labelColumn.setPrefWidth(widestLabel);

    // The scroll pane carries a content-derived width so the dialog tracks its
    // contents: the width follows the widest visible row and the font size instead
    // of a fixed pixel value (a floor keeps the sparse layouts comfortable). The
    // vertical scrollbar allowance keeps the window wide enough for the scrollbar
    // not to squeeze the columns. The row toggles re-measure it, and the sizeToScene
    // below carries it to the window.
    boolean verticalScrollbar = grid.prefHeight(-1) > 460;
    scroller.setPrefWidth(Math.max(grid.prefWidth(-1) + (verticalScrollbar ? 14 : 0), 440));

    Platform.runLater(() -> {
      if (grid.getScene() != null && grid.getScene().getWindow() != null) {
        grid.getScene().getWindow().sizeToScene();
      }
    });
  }

  // The smart filesystem browse: the engine picks the specifier form — the bare
  // path when the picked directory is encompassed by a registered volume (it
  // parses back into the volume endpoint), the explicit local: form outside the
  // volumes (the bare form would be rejected by the parser)
  private void browseSmart(TextField field) {
    if (field.isDisable()) return;
    File chosen = browseDirectory(field, new File(System.getProperty("user.home")), "Select the local directory");
    if (chosen != null) field.setText(endpointSpecifier(context.manager(), chosen.toPath()));
  }

  // The explicit local form browse: the only way to reference the paths outside
  // the volumes (and to force the local form for a path inside one)
  private void browseLocal(TextField field) {
    if (field.isDisable()) return;
    File chosen = browseDirectory(field, new File(System.getProperty("user.home")), "Select the local directory");
    if (chosen != null) field.setText("local:" + Bitferry.canonicalize(chosen.toPath()));
  }

  private void browseVolume(TextField field) {
    if (field.isDisable()) return;
    List<Volume> volumes = new ArrayList<>(context.manager().liveVolumes().values());
    if (volumes.isEmpty()) {
      Dialogs.alert(context, javafx.scene.control.Alert.AlertType.WARNING, "No volumes", "No volumes are registered — reload the manager or create one first.");
      return;
    }
    Volume volume = chooseVolume(volumes);
    if (volume == null) return;
    File start = Files.isDirectory(volume.root())
      ? volume.root().toFile() : new File(System.getProperty("user.home"));
    File chosen = browseDirectory(field, start, "Select the directory within volume " + volume.tag());
    if (chosen == null) return;
    Path root = volume.root();
    Path path = chosen.toPath();
    field.setText(path.startsWith(root)
      ? ":" + volume.tag() + ":" + root.relativize(path).toString().replace('\\', '/')
      : path.toString());
  }

  private void editList(TextField field, String title, String header, Supplier<Map<String, List<String>>> profilesSupplier) {
    if (field.isDisable()) return;
    List<String> current = patterns(field.getText());
    Map<String, List<String>> profiles = profilesSupplier != null ? profilesSupplier.get() : null;
    if (current.size() == 1 && current.get(0).startsWith("@") && profiles != null) {
      String rawKey = current.get(0);
      String unAt = rawKey.substring(1);
      if (profiles.containsKey(unAt)) {
        current = profiles.get(unAt);
      } else if (profiles.containsKey(rawKey)) {
        current = profiles.get(rawKey);
      }
    }
    Window window = field.getScene() != null ? field.getScene().getWindow() : null;
    ItemListDialog.show(window, title, header, current, profiles)
      .ifPresent(list -> field.setText(String.join(", ", list)));
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

  /// The endpoint specifier for the browsed directory: the bare path when the directory
  /// is encompassed by a registered volume (it parses back into the same volume endpoint),
  /// the explicit local: form outside the volumes (the bare form would be rejected by the
  /// parser - the local: is the only way to reference the paths outside them).
  static String endpointSpecifier(Manager manager, Path chosen) {
    Path canonical = Bitferry.canonicalize(chosen);
    try {
      manager.endpoint(canonical);
      return canonical.toString();
    } catch (BitferryError e) {
      return "local:" + canonical;
    }
  }

  private Map<String, List<String>> executeProfiles() {
    return EXECUTE_PROFILES.getOrDefault(type(), Rclone.PROCESS);
  }

  /// Pre-load the @default profile's concrete options into an empty (or untouched
  /// auto-loaded) entry field: the new-task entries start from the operation's
  /// defaults stated explicitly (the real entries, not the @-tags), and the edit-mode
  /// entries whose stored options match their operation's default profile follow the
  /// type switches the same way. The fields without a non-empty @default profile (the
  /// restic backup execution options, the empty check defaults, the exclude patterns)
  /// stay empty. The auto-loaded content follows the operation: switching the type
  /// replaces it with the new operation's defaults (clearing it when the new default
  /// is empty), while the user-edited content stays intact.
  private void loadDefaultProfile(TextField field, Map<String, List<String>> profiles) {
    // The edit-mode fields carry user data unless seeded as the auto-loaded default
    // (the explicit '-' cleared options stay untouched by the type switches)
    if (existing != null && !autoLoaded.containsKey(field)) return;
    String current = field.getText().trim();
    if (!current.isBlank() && !current.equals(autoLoaded.get(field))) return;
    List<String> defaults = profiles != null
      ? (profiles.containsKey("@default") ? profiles.get("@default") : profiles.get("default"))
      : null;
    String text = defaults == null || defaults.isEmpty() ? "" : String.join(", ", defaults);
    if (text.equals(current)) return;
    field.setText(text);
    autoLoaded.put(field, text);
  }

  /// Refresh the profile-backed entries with the current operation's defaults
  private void loadDefaultProfiles() {
    loadDefaultProfile(execute, executeProfiles());
    loadDefaultProfile(profile, encryption() != null ? Rclone.PROFILE : null);
    loadDefaultProfile(forget, Backup.FORGET);
    loadDefaultProfile(check, Backup.CHECK);
  }

  private File browseDirectory(TextField field, File start, String title) {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle(title);
    if (start.isDirectory()) chooser.setInitialDirectory(start);
    return chooser.showDialog(field.getScene() == null ? null : field.getScene().getWindow());
  }

  private Volume chooseVolume(List<Volume> volumes) {
    List<VolumeChoice> choices = volumes.stream().map(VolumeChoice::new).toList();
    ChoiceDialog<VolumeChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
    if (context != null && context.ownerWindow != null) dialog.initOwner(context.ownerWindow);
    dialog.setTitle("Select the volume");
    dialog.setHeaderText("Browse within a registered volume");
    dialog.setContentText("Volume:");
    Dialogs.centerOnScreen(dialog, context.ownerWindow);
    return dialog.showAndWait().map(VolumeChoice::volume).orElse(null);
  }

  @FXML
  private void onBrowseSourceSmart() {
    browseSmart(source);
  }

  @FXML
  private void onBrowseSourceLocal() {
    browseLocal(source);
  }

  @FXML
  private void onBrowseSourceVolume() {
    browseVolume(source);
  }

  @FXML
  private void onBrowseDestinationSmart() {
    browseSmart(destination);
  }

  @FXML
  private void onBrowseDestinationLocal() {
    browseLocal(destination);
  }

  @FXML
  private void onBrowseDestinationVolume() {
    browseVolume(destination);
  }

  @FXML
  private void onEditInclude() {
    editList(include, "Include Patterns", "Edit include patterns", () -> INCLUDE_PROFILES);
  }

  @FXML
  private void onEditExclude() {
    editList(exclude, "Exclude Patterns", "Edit exclude patterns", () -> EXCLUDE_PROFILES);
  }

  @FXML
  private void onEditExecute() {
    editList(execute, "Execution Options", "Edit backend execution options", this::executeProfiles);
  }

  @FXML
  private void onEditProfile() {
    editList(profile, "Encryption Options",
      "Edit encryption options (the crypt filename encoding profiles or the raw comma-separated options)", () -> Rclone.PROFILE);
  }

  @FXML
  private void onEditForget() {
    editList(forget, "Retention Policy", "Edit snapshot retention policy options", () -> Backup.FORGET);
  }

  @FXML
  private void onEditCheck() {
    editList(check, "Integrity Check", "Edit repository integrity check options", () -> Backup.CHECK);
  }

  String type() {
    return type.getValue();
  }

  String source() {
    return source.getText().trim();
  }

  String destination() {
    return destination.getText().trim();
  }

  String include() {
    return include.getText();
  }

  String exclude() {
    return exclude.getText();
  }

  String execute() {
    String value = execute.getText().trim();
    if (value.isEmpty()) return "-";
    return value;
  }

  String encryption() {
    String value = encryption.getValue();
    return "(none)".equals(value) ? null : value;
  }

  String profile() {
    return profile.getText().trim();
  }

  String password() {
    return resticMode() ? password.getText() : cryptPassword.getText();
  }

  boolean resticMode() {
    return "backup".equals(type()) || "restore".equals(type());
  }

  Boolean format() {
    return "backup".equals(type()) && format.isSelected() ? Boolean.TRUE : null;
  }

  String forget() {
    String value = forget.getText().trim();
    if (value.isEmpty()) return existing == null ? null : "-";
    return value;
  }

  String check() {
    String value = check.getText().trim();
    if (value.isEmpty()) return existing == null ? null : "-";
    return value;
  }

  String serve() {
    String value = serve.getText().trim();
    if (value.isEmpty()) return existing == null ? null : "-";
    return value;
  }

  /// The mass-run participation: the checkbox inverted (checked = manual-only,
  /// excluded from the mass runs).
  boolean manual() {
    return manual.isSelected() && !manual.isDisable();
  }

  boolean keyring() {
    // The keyring destination only when its radio is selected and selectable: the
    // grayed-out choice (no usable OS keyring on the host) keeps serving the vault
    return keyringChoice.isSelected() && !keyringChoice.isDisable();
  }

  void validate() {
    if (source().isEmpty()) throw new BitferryError("Source endpoint must be specified");
    if (destination().isEmpty()) throw new BitferryError("Destination endpoint must be specified");
    if (existing == null) {
      if (resticMode()) {
        if (password.getText().isEmpty()) throw new BitferryError("Password must be specified");
        if (!password.getText().equals(passwordConfirm.getText())) throw new BitferryError("Passwords do not match");
      }
      if (encryption() != null) {
        if (cryptPassword.getText().isEmpty()) throw new BitferryError("Encryption password must be specified");
        if (!cryptPassword.getText().equals(cryptPasswordConfirm.getText())) throw new BitferryError("Encryption passwords do not match");
      }
    } else {
      if (resticMode()) {
        if (!password.getText().isEmpty() || !passwordConfirm.getText().isEmpty()) {
          if (password.getText().isEmpty()) throw new BitferryError("Password must be specified");
          if (!password.getText().equals(passwordConfirm.getText())) throw new BitferryError("Passwords do not match");
        }
      }
    }
  }

  /// Load the FXML-backed form injecting the context and the existing task (null
  /// for the creation mode; called by the facade before the dialog shows).
  static TaskFormController load(AppContext context, Task existing) {
    try {
      FXMLLoader loader = new FXMLLoader(TaskFormController.class.getResource("/io/bitferry/gui/task.fxml"));
      loader.load();
      TaskFormController controller = loader.getController();
      controller.init(context, existing);
      return controller;
    } catch (IOException e) {
      throw new BitferryError("Task form loading failure", e);
    }
  }
}
