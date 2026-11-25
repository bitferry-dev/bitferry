package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.Backup;
import org.bitferry.task.Copy;
import org.bitferry.task.Equalize;
import org.bitferry.task.IncludeFilters;
import org.bitferry.task.Replicate;
import org.bitferry.task.Rclone;
import org.bitferry.task.Restore;
import org.bitferry.task.Restic;
import org.bitferry.task.Snapshot;
import org.bitferry.task.Task;
import org.bitferry.task.Update;
import org.bitferry.vault.Vault;
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
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /// Single-line descriptions displayed next to the operation type selector.
  static final Map<String, String> OPERATION_DESCRIPTIONS = Map.of(
    Copy.OPERATION, "unconditional one-way source → destination copy (changed or missing files, no destination deletions)",
    Update.OPERATION, "one-way source → destination update (newer or missing files, no destination deletions)",
    Replicate.OPERATION, "one-way source → destination replication (newer or missing files, destination deletions)",
    Equalize.OPERATION, "two-way source ↔ destination synchronization",
    Backup.OPERATION, "incremental source → repository backup",
    Restore.OPERATION, "latest snapshot repository → destination restore"
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
  private HBox typeControl;

  @FXML
  private ComboBox<String> type;

  @FXML
  private Label typeDescription;

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
  private ComboBox<String> snapshotScope;
  @FXML
  private TextField include;

  @FXML
  private HBox includeControl;

  @FXML
  private TextField exclude;

  @FXML
  private TextField execute;

  @FXML
  private HBox encryptionControl;

  @FXML
  private RadioButton cryptNoneChoice;

  @FXML
  private RadioButton cryptEncryptChoice;

  @FXML
  private RadioButton cryptDecryptChoice;

  @FXML
  private ToggleGroup encryptionMode;

  @FXML
  private Button editSecurity;

  @FXML
  private HBox securityControl;

  @FXML
  private Button editSecurityRestic;

  @FXML
  private Button editInclude;

  @FXML
  private Button editExclude;

  @FXML
  private Button editExecute;

  @FXML
  private HBox serveControl;

  @FXML
  private Button editServe;

  @FXML
  private CheckBox format;

  @FXML
  private CheckBox manual;

  // The row labels (the visibility toggles and the width pinning need the pairs)
  @FXML
  private Label typeLabel;
  @FXML
  private Label sourceLabel;
  @FXML
  private Label destinationLabel;
  @FXML
  private Label snapshotScopeLabel;
  @FXML
  private Label includeLabel;
  @FXML
  private Label excludeLabel;
  @FXML
  private Label executeLabel;
  @FXML
  private Label encryptionLabel;
  @FXML
  private Label securityLabel;
  @FXML
  private Label formatLabel;
  @FXML
  private Label serveLabel;
  @FXML
  private Label manualLabel;

  private final List<Row> rows = new ArrayList<>();

  private AppContext context;
  private Task existing;

  // The encryption and vault state edited via the security dialog: the crypt mode
  // and options (the rclone family), the family-scoped password pair, and the
  // password-storage request. The accessors below (and thereby the dialog apply
  // flows in [Dialogs]) read it.
  private EncryptionSettings settings = EncryptionSettings.NONE;

  // The backup service configuration edited via the Service dialog (defaults to
  // @default for new backups; cadence and phase lists remain independent).
  private ServiceSettings serviceSettings = ServiceSettings.DEFAULTS;

  // The auto-loaded (never user-edited) pre-filled defaults keyed by the field:
  // switching the operation replaces them with the new operation's defaults while
  // the user-edited content stays intact
  private final Map<TextField, String> autoLoaded = new HashMap<>();

  public TaskFormController() {}

  // The static display wiring (the context and the existing task ride init())
  @FXML
  private void initialize() {
    // The default operation preselect (the predicates below read it)
    type.getSelectionModel().selectFirst();
    updateTypeDescription();
    updateEndpointLabels();

    // Visibility predicates re-evaluated on type changes
    BooleanSupplier restic = () -> "backup".equals(type()) || "restore".equals(type());
    BooleanSupplier backup = () -> "backup".equals(type());
    // The snapshot scope rides the restore tasks (both creation and modification)
    BooleanSupplier restoreScope = () -> "restore".equals(type());
    BooleanSupplier formatVisible = () -> existing == null && backup.getAsBoolean();

    rows.add(new Row(typeLabel, typeControl, () -> true));
    rows.add(new Row(sourceLabel, source.getParent(), () -> true));
    rows.add(new Row(destinationLabel, destination.getParent(), () -> true));
    // The restore snapshot scope: the source backup task whose snapshots the restore
    // selects from
    rows.add(new Row(snapshotScopeLabel, snapshotScope, restoreScope));
    // The include control is disabled for the backup type: restic backup filters by
    // exclusion only, so the entered patterns would be silently ignored
    rows.add(new Row(includeLabel, includeControl, () -> true));
    rows.add(new Row(excludeLabel, exclude.getParent(), () -> true));
    rows.add(new Row(executeLabel, execute.getParent(), () -> true));
    // The encryption mode radios with the inline security editor button (the rclone
    // family only): the triple selection rides the task form, the button opens the
    // security dialog and sleeps while (none) is chosen (nothing to configure)
    rows.add(new Row(encryptionLabel, encryptionControl, () -> !restic.getAsBoolean()));
    // The security row (the restic family): the repository password and the storage
    // live in the security dialog (the crypt rows stay hidden there)
    rows.add(new Row(securityLabel, securityControl, restic));
    rows.add(new Row(formatLabel, format, formatVisible));
    rows.add(new Row(serveLabel, serveControl, backup));
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

    Icons.equip(browseSourceSmart, Icons.Symbol.BROWSE);
    Icons.equip(browseSourceLocal, Icons.Symbol.BROWSE);
    Icons.equip(browseSourceVolume, Icons.Symbol.DRIVE);
    Icons.equip(browseDestinationSmart, Icons.Symbol.BROWSE);
    Icons.equip(browseDestinationLocal, Icons.Symbol.BROWSE);
    Icons.equip(browseDestinationVolume, Icons.Symbol.DRIVE);

    Icons.equip(editInclude, Icons.Symbol.EDIT);
    Icons.equip(editExclude, Icons.Symbol.EDIT);
    Icons.equip(editExecute, Icons.Symbol.EDIT);
    Icons.equip(editSecurity, Icons.Symbol.EDIT);
    Icons.equip(editSecurityRestic, Icons.Symbol.EDIT);
    Icons.equip(editServe, Icons.Symbol.EDIT);

    // The security editor on the radio line sleeps while (none) is chosen: with no
    // crypt mode there is nothing to configure (the restic family uses its own row)
    editSecurity.disableProperty().bind(cryptNoneChoice.selectedProperty());

    Tooltip.install(source, new Tooltip(ENDPOINT_HINT));
    Tooltip.install(destination, new Tooltip(ENDPOINT_HINT));
    Tooltip.install(encryptionControl, new Tooltip(
      "The rclone crypt mode: encrypt the destination, decrypt the source, or none. "
        + "The restic tasks keep no crypt mode (the repository password rides the security dialog)"));

    // The encryption mode radios carry into the settings: the dialog opens against
    // the selection (the crypt rows and the secret visibility follow it)
    encryptionMode.selectedToggleProperty().addListener((x, was, now) -> {
      settings = new EncryptionSettings(radioMode(), settings.profileText(), settings.password(),
        settings.passwordConfirm(), settings.salt(), settings.saltConfirm(), settings.keyring());
    });

    // The operation changes refresh the empty (never user-edited) option entries
    // with the new operation's defaults and lay the rows out; crossing the
    // restic/rclone family boundary drops the entered secrets (the crypt password
    // and the salt must not ride into the repository or back)
    type.valueProperty().addListener((x, was, now) -> {
      if (isRestic(was) != isRestic(now)) {
        settings = new EncryptionSettings(settings.mode(), settings.profileText(), "", "", "", "", settings.keyring());
      }
      if (existing == null && !"backup".equals(was) && "backup".equals(now)) {
        serviceSettings = ServiceSettings.DEFAULTS;
      }
      loadDefaultProfiles();
      updateTypeDescription();
      updateEndpointLabels();
      if ("restore".equals(now)) updateRestoreCandidates();
      relayout();
    });

    source.textProperty().addListener((obs, was, now) -> updateRestoreCandidates());

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

      // The password-storage mirror: the settings carry the task's actual provider
      // (switching it in the security dialog migrates the token upon the apply)
      boolean keyring = existing.usesVault() && existing.vaultProvider().orElse(Vault.volume()) == Vault.keyring();

      if (existing instanceof Rclone rclone) {
        // The stored encryption state mirrors into the settings: the crypt mode and
        // the resolved options (the modification keeps them immutable via the
        // security dialog); the stored passwords never echo back
        Rclone.Encryption enc = rclone.encryption();
        String mode = enc != null && enc.operation() != null ? enc.operation() : null;
        String profileText = enc != null && enc.options() != null && !enc.options().isEmpty()
          ? String.join(", ", enc.options()) : "";
        settings = new EncryptionSettings(mode, profileText, "", "", "", "", keyring);
      } else {
        settings = new EncryptionSettings(null, "", "", "", "", "", keyring);
      }

      // The stored crypt mode mirrors into the radios; the mode is the creation-time
      // choice — the modification keeps it immutable
      selectMode(settings.mode());
      encryptionControl.setDisable(true);

      manual.setSelected(existing.isManual());

      if (existing instanceof Backup backup) {
        serviceSettings = new ServiceSettings(backup.cadence(), backup.check(), backup.forget(), backup.prune());
        format.setDisable(true);
      }

      if (existing instanceof Restore restore) {
        updateRestoreCandidates();
        String curTag = restore.snapshotTag();
        if (curTag != null) {
          String match = snapshotScope.getItems().stream()
            .filter(item -> item.startsWith(curTag + " ") || item.equals(curTag))
            .findFirst()
            .orElse(curTag);
          snapshotScope.setValue(match);
        } else {
          snapshotScope.setValue("");
        }
      }
    }

    if (existing == null) {
      updateRestoreCandidates();
    }

    // The new-task option entries start from the operation's default profiles
    loadDefaultProfiles();
    updateTypeDescription();
    updateEndpointLabels();

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
    String specifier = volumeSpecifier(context.manager(), volume, chosen.toPath());
    if (specifier != null) {
      field.setText(specifier);
    } else {
      Dialogs.alert(context, javafx.scene.control.Alert.AlertType.WARNING, "Outside volumes",
        "The selected directory is outside any registered Bitferry volume. Please select a directory inside a volume, or use the 'Local' button to select an unmanaged directory.");
    }
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

  /// The volume endpoint specifier for the browsed directory: the volume-relative
  /// ':tag:path' specifier when the directory is encompassed by the selected volume,
  /// or by any other registered volume when navigated outside it. Returns null when
  /// the directory resides outside all registered volumes.
  static String volumeSpecifier(Manager manager, Volume volume, Path chosen) {
    Path canonical = Bitferry.canonicalize(chosen);
    Path root = Bitferry.canonicalize(volume.root());
    if (canonical.startsWith(root)) {
      Path rel = root.relativize(canonical);
      String relStr = rel.toString().replace('\\', '/');
      return ":" + volume.tag() + ":" + (".".equals(relStr) ? "" : relStr);
    }
    try {
      Endpoint ep = manager.endpoint(canonical);
      if (ep instanceof VolumeEndpoint ve) {
        String p = ve.path();
        return ":" + ve.vtag() + ":" + (".".equals(p) ? "" : p);
      }
    } catch (BitferryError ignored) {
      // Outside any registered volume
    }
    return null;
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
  private void onEditSecurity() {
    EncryptionDialogController dialog = EncryptionDialogController.load(resticMode(), existing == null, settings);
    EncryptionDialog.show(context, "Encryption",
      existing == null
        ? "Configure the encryption and the password storage of the new task"
        : "Manage the encryption and the password storage of task " + existing.tag(),
      dialog).ifPresent(s -> {
        settings = s;
        if ("restore".equals(type())) {
          updateRestoreCandidates();
        }
      });
  }

  @FXML
  private void onEditServe() {
    ServiceDialogController dialog = ServiceDialogController.load(serviceSettings, Backup.CADENCE);
    ServiceDialog.show(context, "Repository Service",
      existing == null
        ? "Configure repository service cadence and maintenance phases"
        : "Manage repository service cadence and maintenance phases for task " + existing.tag(),
      dialog).ifPresent(s -> serviceSettings = s);
  }

  String type() {
    return type.getValue();
  }

  private void updateTypeDescription() {
    if (typeDescription != null) {
      typeDescription.setText(OPERATION_DESCRIPTIONS.getOrDefault(type(), ""));
    }
  }

  String typeDescription() {
    return typeDescription != null ? typeDescription.getText() : "";
  }

  private void updateEndpointLabels() {
    if (sourceLabel == null || destinationLabel == null) {
      return;
    }
    if (Backup.OPERATION.equals(type())) {
      sourceLabel.setText("Source:");
      destinationLabel.setText("Repository:");
    } else if (Restore.OPERATION.equals(type())) {
      sourceLabel.setText("Repository:");
      destinationLabel.setText("Destination:");
    } else {
      sourceLabel.setText("Source:");
      destinationLabel.setText("Destination:");
    }
  }

  String sourceLabel() {
    return sourceLabel != null ? sourceLabel.getText() : "";
  }

  String destinationLabel() {
    return destinationLabel != null ? destinationLabel.getText() : "";
  }

  String source() {
    return source.getText().trim();
  }

  String destination() {
    return destination.getText().trim();
  }

  /// The restore snapshot scope: the source backup task tag (blank = auto-derive, '-' = clear/repo-wide).
  String snapshotScope() {
    String value = snapshotScope.getEditor().getText();
    if (value == null || value.isBlank()) {
      value = snapshotScope.getValue();
    }
    if (value == null || value.isBlank()) return "";
    value = value.trim();
    if (value.startsWith("(") || "-".equals(value)) {
      return "-";
    }
    return value.split("\\s+")[0];
  }

  private static String formatSnapshotTime(String iso) {
    if (iso == null || iso.isEmpty()) return "";
    try {
      var odt = java.time.OffsetDateTime.parse(iso);
      return odt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    } catch (Exception e) {
      return iso.length() >= 16 ? iso.substring(0, 16).replace('T', ' ') : iso;
    }
  }

  /// Refresh the candidate backup tasks serving the current repository endpoint.
  private void updateRestoreCandidates() {
    if (context == null || !"restore".equals(type())) return;
    Endpoint repo = null;
    if (existing instanceof Restore restore) {
      repo = restore.source();
    } else {
      String srcText = source.getText().trim();
      if (srcText.isEmpty()) {
        snapshotScope.getItems().clear();
        return;
      }
      try {
        repo = Endpoints.parse(context.manager(), srcText);
      } catch (Exception e) {
        if (srcText.startsWith("/") || srcText.matches("^[a-zA-Z]:[/\\\\].*")) {
          try {
            repo = new LocalEndpoint(java.nio.file.Path.of(srcText));
          } catch (Exception ignored) {
            return;
          }
        } else {
          return;
        }
      }
    }
    List<String> candidates = Restore.scopeCandidates(context.manager(), repo);
    List<String> items = new ArrayList<>();
    Set<String> seenTags = new HashSet<>(candidates);
    for (String cTag : candidates) {
      Task t = context.manager().tasks().get(cTag);
      String label = (t instanceof Backup b)
        ? cTag + " (backup " + b.source().describe() + " → " + b.destination().describe() + ")"
        : cTag;
      items.add(label);
    }

    String pwd = password();
    if ((pwd == null || pwd.isEmpty()) && existing instanceof Restore restore) {
      try {
        pwd = restore.password();
      } catch (Exception ignored) {}
    }
    if (pwd != null && !pwd.isEmpty() && repo != null) {
      try {
        List<Snapshot> snapshots = Restore.repositorySnapshots(context.manager(), repo, pwd);
        Map<String, Snapshot> tagToLatestSnapshot = new LinkedHashMap<>();
        List<Snapshot> untagged = new ArrayList<>();
        for (Snapshot s : snapshots) {
          List<String> nonMarkerTags = s.tags().stream()
            .filter(tag -> !Tags.MARKER.equals(tag))
            .toList();
          if (nonMarkerTags.isEmpty()) {
            untagged.add(s);
          } else {
            for (String t : nonMarkerTags) {
              tagToLatestSnapshot.putIfAbsent(t, s);
            }
          }
        }
        for (var entry : tagToLatestSnapshot.entrySet()) {
          String tag = entry.getKey();
          if (seenTags.add(tag)) {
            Snapshot s = entry.getValue();
            String pathDesc = s.paths().isEmpty() ? "" : String.join(", ", s.paths());
            String hostDesc = s.hostname() != null && !s.hostname().isEmpty() ? " on " + s.hostname() : "";
            String timeDesc = formatSnapshotTime(s.time());
            String label = tag + " (backup " + pathDesc + hostDesc + (timeDesc.isEmpty() ? "" : ", latest: " + timeDesc) + ")";
            items.add(label);
          }
        }
        for (Snapshot s : untagged) {
          String sId = s.shortId() != null && !s.shortId().isEmpty() ? s.shortId() : s.id();
          if (sId != null && !sId.isEmpty() && seenTags.add(sId)) {
            String pathDesc = s.paths().isEmpty() ? "" : String.join(", ", s.paths());
            String hostDesc = s.hostname() != null && !s.hostname().isEmpty() ? " on " + s.hostname() : "";
            String timeDesc = formatSnapshotTime(s.time());
            String label = sId + " (snapshot " + pathDesc + hostDesc + (timeDesc.isEmpty() ? "" : ", " + timeDesc) + ")";
            items.add(label);
          }
        }
      } catch (Exception ignored) {
        // Failed probe or incorrect password silently skips repository snapshot discovery
      }
    }

    String current = snapshotScope();
    snapshotScope.getItems().setAll(items);
    if (existing == null) {
      if (items.size() == 1 && (current.isEmpty() || !seenTags.contains(current))) {
        snapshotScope.getSelectionModel().select(0);
      }
    }
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

  /// The crypt mode of the carried settings or null for none.
  String encryption() {
    return settings.mode();
  }

  /// The selected radio's crypt mode or null for (none).
  private String radioMode() {
    if (cryptEncryptChoice.isSelected()) return "encrypt";
    if (cryptDecryptChoice.isSelected()) return "decrypt";
    return null;
  }

  /// Mirror the crypt mode into the radios.
  private void selectMode(String mode) {
    if ("encrypt".equals(mode)) cryptEncryptChoice.setSelected(true);
    else if ("decrypt".equals(mode)) cryptDecryptChoice.setSelected(true);
    else cryptNoneChoice.setSelected(true);
  }

  /// The raw encryption options specifier carried by the settings.
  String profile() {
    return settings.profileText();
  }

  /// The family-scoped password (the crypt password for the rclone tasks, the
  /// repository password for the restic ones).
  String password() {
    return settings.password();
  }

  /// The optional crypt salt (the filename encryption secondary password); empty
  /// for the restic family.
  String salt() {
    return settings.salt();
  }

  boolean resticMode() {
    return isRestic(type());
  }

  private static boolean isRestic(String type) {
    return "backup".equals(type) || "restore".equals(type);
  }

  Boolean format() {
    return "backup".equals(type()) && format.isSelected() ? Boolean.TRUE : null;
  }

  ServiceSettings serviceSettings() {
    return serviceSettings;
  }

  /// The mass-run participation: the checkbox inverted (checked = manual-only,
  /// excluded from the mass runs).
  boolean manual() {
    return manual.isSelected() && !manual.isDisable();
  }

  boolean keyring() {
    // The settings carry the storage request; the grayed-out choice (no usable OS
    // keyring on the host) cannot come back from the security dialog's radio, and
    // the apply-side artifact guard keeps the doomed migrations out
    return settings.keyring();
  }

  void validate() {
    if (source().isEmpty()) throw new BitferryError("Source endpoint must be specified");
    if (destination().isEmpty()) throw new BitferryError("Destination endpoint must be specified");
    // The snapshot scope must name a live backup task or discovered repository snapshot
    if (resticMode() && "restore".equals(type()) && !snapshotScope().isEmpty() && !"-".equals(snapshotScope())) {
      Endpoint repo = null;
      try {
        repo = Endpoints.parse(context.manager(), source());
      } catch (Exception ignored) {}
      String pwd = password();
      if ((pwd == null || pwd.isEmpty()) && existing instanceof Restore restore) {
        try {
          pwd = restore.password();
        } catch (Exception ignored) {}
      }
      Restore.resolveSnapshotTag(context.manager(), repo, snapshotScope(), pwd);
    }
    if (existing == null) {
      // The repository password rides the security dialog: the form only guards the
      // skipped-dialog path (the dialog itself enforces the pair and the match)
      if (resticMode() && settings.password().isEmpty()) {
        throw new BitferryError("Repository password must be specified — set it via the security dialog");
      }
      // The rclone encryption pair is validated inside the security dialog: the
      // returned settings carry a matching non-empty pair whenever the mode is set
    }
  }

  /// Load the FXML-backed form injecting the context and the existing task (null
  /// for the creation mode; called by the facade before the dialog shows).
  static TaskFormController load(AppContext context, Task existing) {
    try {
      FXMLLoader loader = new FXMLLoader(TaskFormController.class.getResource("/org/bitferry/gui/task.fxml"));
      loader.load();
      TaskFormController controller = loader.getController();
      controller.init(context, existing);
      return controller;
    } catch (IOException e) {
      throw new BitferryError("Task form loading failure", e);
    }
  }
}
