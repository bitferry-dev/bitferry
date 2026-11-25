package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.task.*;
import javafx.application.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.io.*;
import java.time.*;
import java.util.*;

/// Controller for the unified backup service configuration dialog (`service.fxml`):
/// manages service scheduling cadence (`cadence`) alongside the per-phase option
/// lists (`check`, `forget`, `prune`). Phase option lists remain editable and
/// preserved regardless of whether automatic cadence is enabled.
public final class ServiceDialogController {

  private static final String UNIT_SECONDS = "seconds";
  private static final String UNIT_MINUTES = "minutes";
  private static final String UNIT_HOURS = "hours";
  private static final String UNIT_DAYS = "days";
  private static final String UNIT_WEEKS = "weeks";

  @FXML
  private VBox root;

  @FXML
  private VBox profileBar;

  @FXML
  private ComboBox<String> profileCombo;

  @FXML
  private Button loadBtn;

  @FXML
  private GridPane grid;

  @FXML
  private ColumnConstraints labelColumn;

  @FXML
  private Label cadenceLabel;

  @FXML
  private CheckBox enabled;

  @FXML
  private Label durationLabel;

  @FXML
  private HBox durationRow;

  @FXML
  private CheckBox durationEnabled;

  @FXML
  private TextField durationAmount;

  @FXML
  private ComboBox<String> durationUnit;

  @FXML
  private Label countLabel;

  @FXML
  private HBox countRow;

  @FXML
  private CheckBox countEnabled;

  @FXML
  private TextField countAmount;

  @FXML
  private Separator cadenceSeparator;

  @FXML
  private Label checkLabel;

  @FXML
  private HBox checkRow;

  @FXML
  private TextField checkFlags;

  @FXML
  private Button editCheck;

  @FXML
  private Label forgetLabel;

  @FXML
  private HBox forgetRow;

  @FXML
  private TextField forgetFlags;

  @FXML
  private Button editForget;

  @FXML
  private Label pruneLabel;

  @FXML
  private HBox pruneRow;

  @FXML
  private TextField pruneFlags;

  @FXML
  private Button editPrune;

  private Map<String, Cadence> cadenceProfiles;
  private boolean hadCadence;
  private boolean checkOnly;

  public ServiceDialogController() {}

  @FXML
  private void initialize() {
    durationUnit.getItems().setAll(UNIT_SECONDS, UNIT_MINUTES, UNIT_HOURS, UNIT_DAYS, UNIT_WEEKS);
    durationUnit.getSelectionModel().select(UNIT_DAYS);

    // Disabling automatic cadence suspends only the cadence thresholds; maintenance
    // phases remain independently configurable and preserved in storage.
    durationRow.disableProperty().bind(enabled.selectedProperty().not());
    countRow.disableProperty().bind(enabled.selectedProperty().not());
    durationAmount.disableProperty().bind(durationEnabled.selectedProperty().not());
    durationUnit.disableProperty().bind(durationEnabled.selectedProperty().not());
    countAmount.disableProperty().bind(countEnabled.selectedProperty().not());

    loadBtn.setMinWidth(Region.USE_PREF_SIZE);
    editCheck.setMinWidth(Region.USE_PREF_SIZE);
    editForget.setMinWidth(Region.USE_PREF_SIZE);
    editPrune.setMinWidth(Region.USE_PREF_SIZE);

    Icons.equip(loadBtn, Icons.Symbol.LOAD);
    Icons.equip(editCheck, Icons.Symbol.EDIT);
    Icons.equip(editForget, Icons.Symbol.EDIT);
    Icons.equip(editPrune, Icons.Symbol.EDIT);

    enabled.selectedProperty().addListener((obs, was, now) -> {
      if (Boolean.TRUE.equals(now)) hadCadence = true;
    });

    root.sceneProperty().addListener((x, was, now) -> {
      if (now != null) Platform.runLater(this::sizeWindow);
    });
  }

  /// Populate controls from existing [ServiceSettings] and cadence profile presets.
  public void init(ServiceSettings current, Map<String, Cadence> cadenceProfiles) {
    init(current, cadenceProfiles, false);
  }

  /// Populate controls from existing [ServiceSettings], optionally hiding backup-only
  /// cadence, retention, and prune rows (`checkOnly == true` for restore tasks).
  public void init(ServiceSettings current, Map<String, Cadence> cadenceProfiles, boolean checkOnly) {
    this.cadenceProfiles = cadenceProfiles;
    this.checkOnly = checkOnly;

    Map<String, Cadence> nonNull = filterProfiles(cadenceProfiles);
    if (!checkOnly && !nonNull.isEmpty()) {
      List<String> keys = new ArrayList<>(nonNull.keySet());
      if (keys.contains("@default")) {
        keys.remove("@default");
        keys.add(0, "@default");
        profileCombo.getItems().setAll(keys);
        profileCombo.getSelectionModel().select("@default");
      } else {
        profileCombo.getItems().setAll(keys);
        profileCombo.getSelectionModel().selectFirst();
      }
      profileBar.setVisible(true);
      profileBar.setManaged(true);
    } else {
      profileBar.setVisible(false);
      profileBar.setManaged(false);
    }

    relayoutGrid(checkOnly);

    ServiceSettings effective = current == null ? ServiceSettings.DEFAULTS : current;
    if (!checkOnly) {
      if (effective.cadence() == null) {
        hadCadence = false;
        enabled.setSelected(false);
        applyThresholds(Cadence.PROFILES.get("default"));
      } else {
        hadCadence = true;
        enabled.setSelected(effective.cadence().enabled());
        applyThresholds(effective.cadence());
      }
      initPhase(forgetFlags, effective.forget());
      initPhase(pruneFlags, effective.prune());
    }

    initPhase(checkFlags, effective.check());
    sizeWindow();
  }

  private void relayoutGrid(boolean checkOnly) {
    grid.getChildren().clear();
    if (checkOnly) {
      GridPane.setRowIndex(checkLabel, 0);
      GridPane.setColumnIndex(checkLabel, 0);
      GridPane.setRowIndex(checkRow, 0);
      GridPane.setColumnIndex(checkRow, 1);
      grid.getChildren().addAll(checkLabel, checkRow);
    } else {
      grid.getChildren().addAll(
        cadenceLabel, enabled,
        durationLabel, durationRow,
        countLabel, countRow,
        cadenceSeparator,
        checkLabel, checkRow,
        forgetLabel, forgetRow,
        pruneLabel, pruneRow
      );
    }
  }

  public void init(ServiceSettings current) {
    init(current, Backup.CADENCE);
  }

  private static void initPhase(TextField field, List<String> flags) {
    field.setText(flags == null || flags.isEmpty() ? "" : String.join(", ", flags));
  }

  private void applyThresholds(Cadence cadence) {
    Cadence defaults = Cadence.PROFILES.get("default");
    if (cadence.duration() != null) {
      durationEnabled.setSelected(true);
      setDurationFields(cadence.duration());
    } else {
      durationEnabled.setSelected(false);
      setDurationFields(defaults.duration());
    }
    if (cadence.backups() != null) {
      countEnabled.setSelected(true);
      countAmount.setText(String.valueOf(cadence.backups()));
    } else {
      countEnabled.setSelected(false);
      countAmount.setText(String.valueOf(defaults.backups()));
    }
  }

  private void setDurationFields(Duration d) {
    long days = d.toDays();
    if (days > 0 && d.toHoursPart() == 0 && d.toMinutesPart() == 0 && d.toSecondsPart() == 0) {
      if (days >= 14 && days % 7 == 0) {
        durationAmount.setText(String.valueOf(days / 7));
        durationUnit.getSelectionModel().select(UNIT_WEEKS);
      } else {
        durationAmount.setText(String.valueOf(days));
        durationUnit.getSelectionModel().select(UNIT_DAYS);
      }
      return;
    }
    long hours = d.toHours();
    if (hours > 0 && d.toMinutesPart() == 0 && d.toSecondsPart() == 0) {
      durationAmount.setText(String.valueOf(hours));
      durationUnit.getSelectionModel().select(UNIT_HOURS);
      return;
    }
    long minutes = d.toMinutes();
    if (minutes > 0 && d.toSecondsPart() == 0) {
      durationAmount.setText(String.valueOf(minutes));
      durationUnit.getSelectionModel().select(UNIT_MINUTES);
      return;
    }
    durationAmount.setText(String.valueOf(d.toSeconds()));
    durationUnit.getSelectionModel().select(UNIT_SECONDS);
  }

  private void sizeWindow() {
    if (root.getScene() != null) root.applyCss();
    double widestLabel = 0;
    for (javafx.scene.Node node : grid.getChildren()) {
      if (node instanceof javafx.scene.control.Label l
        && Integer.valueOf(0).equals(GridPane.getColumnIndex(l))) {
        widestLabel = Math.max(widestLabel, l.prefWidth(-1));
      }
    }
    if (widestLabel > 0) {
      labelColumn.setMinWidth(widestLabel);
      labelColumn.setPrefWidth(widestLabel);
    }
    Platform.runLater(() -> {
      if (root.getScene() != null && root.getScene().getWindow() != null) {
        root.getScene().getWindow().sizeToScene();
      }
    });
  }

  public VBox pane() {
    return root;
  }

  @FXML
  private void onProfileLoad() {
    String selected = profileCombo.getValue();
    Map<String, Cadence> nonNull = filterProfiles(cadenceProfiles);
    if (selected != null && nonNull.containsKey(selected)) {
      Cadence preset = nonNull.get(selected);
      if (preset == null) {
        enabled.setSelected(false);
      } else {
        enabled.setSelected(true);
        applyThresholds(preset);
      }
    }
  }

  @FXML
  private void onEditCheck() {
    editPhaseList(checkFlags, "Integrity Check", "Edit repository integrity check options", Backup.CHECK);
  }

  @FXML
  private void onEditForget() {
    editPhaseList(forgetFlags, "Retention Policy", "Edit snapshot retention policy options", Backup.FORGET);
  }

  @FXML
  private void onEditPrune() {
    editPhaseList(pruneFlags, "Repository Prune", "Edit repository prune options", Backup.PRUNE);
  }

  private void editPhaseList(TextField field, String title, String header, Map<String, List<String>> profiles) {
    List<String> current;
    try {
      current = resolvePhaseTokens(field.getText(), profiles);
    } catch (BitferryError e) {
      Window window = field.getScene() != null ? field.getScene().getWindow() : null;
      javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, e.getMessage());
      if (window != null) alert.initOwner(window);
      alert.setTitle("Invalid options");
      alert.setHeaderText(null);
      alert.showAndWait();
      return;
    }
    Window window = field.getScene() != null ? field.getScene().getWindow() : null;
    ItemListDialog.show(window, title, header, current, profiles)
      .ifPresent(list -> field.setText(String.join(", ", list)));
  }

  private static List<String> resolvePhaseTokens(String raw, Map<String, List<String>> profiles) {
    if (raw == null || raw.isBlank() || "-".equals(raw.trim())) return List.of();
    List<String> tokens = Arrays.stream(raw.split(","))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .toList();
    if (tokens.size() == 1 && tokens.get(0).startsWith("@") && profiles != null) {
      String key = tokens.get(0).substring(1);
      List<String> preset = profiles.containsKey(key) ? profiles.get(key) : profiles.get(tokens.get(0));
      if (preset == null) {
        throw new BitferryError("Unknown options profile " + tokens.get(0));
      }
      return preset;
    }
    return tokens;
  }

  private static Map<String, Cadence> filterProfiles(Map<String, Cadence> profiles) {
    Map<String, Cadence> result = new LinkedHashMap<>();
    if (profiles == null) return result;
    for (Map.Entry<String, Cadence> entry : profiles.entrySet()) {
      if (entry.getKey() == null) continue;
      String displayKey = entry.getKey().startsWith("@") ? entry.getKey() : "@" + entry.getKey();
      result.put(displayKey, entry.getValue());
    }
    return result;
  }

  /// Validate cadence thresholds and phase option profiles; throws [BitferryError] on invalid values.
  void validate() {
    resolvePhaseTokens(checkFlags.getText(), Restic.CHECK);
    if (checkOnly) return;
    if (enabled.isSelected()) {
      if (!durationEnabled.isSelected() && !countEnabled.isSelected()) {
        throw new BitferryError("Enable at least an elapsed time or a backup count threshold, or disable automatic service");
      }
      if (durationEnabled.isSelected()) {
        parseDuration();
      }
      if (countEnabled.isSelected()) {
        parseCount();
      }
    }
    resolvePhaseTokens(forgetFlags.getText(), Backup.FORGET);
    resolvePhaseTokens(pruneFlags.getText(), Backup.PRUNE);
  }

  /// Build the validated [ServiceSettings] from the form controls.
  ServiceSettings settings() {
    List<String> check = resolvePhaseTokens(checkFlags.getText(), Restic.CHECK);
    if (checkOnly) {
      return new ServiceSettings(null, check, null, null);
    }
    Cadence cadence = cadence();
    List<String> forget = resolvePhaseTokens(forgetFlags.getText(), Backup.FORGET);
    List<String> prune = resolvePhaseTokens(pruneFlags.getText(), Backup.PRUNE);
    return new ServiceSettings(cadence, check, forget, prune);
  }

  private Cadence cadence() {
    boolean on = enabled.isSelected();
    if (!on && !hadCadence) return null;
    try {
      Duration dur = durationEnabled.isSelected() ? parseDuration() : null;
      Integer cnt = countEnabled.isSelected() ? parseCount() : null;
      if (dur == null && cnt == null) return null;
      return new Cadence(on, dur, cnt);
    } catch (BitferryError e) {
      if (!on) return null;
      throw e;
    }
  }

  private Duration parseDuration() {
    String raw = durationAmount.getText().trim();
    long amount;
    try {
      amount = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new BitferryError("Elapsed time must be a positive integer", e);
    }
    if (amount <= 0) {
      throw new BitferryError("Elapsed time must be positive");
    }
    String unit = durationUnit.getValue();
    return switch (unit == null ? UNIT_DAYS : unit) {
      case UNIT_SECONDS -> Duration.ofSeconds(amount);
      case UNIT_MINUTES -> Duration.ofMinutes(amount);
      case UNIT_HOURS -> Duration.ofHours(amount);
      case UNIT_DAYS -> Duration.ofDays(amount);
      case UNIT_WEEKS -> {
        try {
          yield Duration.ofDays(Math.multiplyExact(amount, 7L));
        } catch (ArithmeticException e) {
          throw new BitferryError("Elapsed time is too large", e);
        }
      }
      default -> throw new BitferryError("Unknown duration unit: " + unit);
    };
  }

  private int parseCount() {
    String raw = countAmount.getText().trim();
    int count;
    try {
      count = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new BitferryError("Backup count must be a positive integer", e);
    }
    if (count <= 0) {
      throw new BitferryError("Backup count must be positive");
    }
    return count;
  }

  static ServiceDialogController load(ServiceSettings current, Map<String, Cadence> cadenceProfiles, boolean checkOnly) {
    try {
      FXMLLoader loader = new FXMLLoader(ServiceDialogController.class.getResource("/org/bitferry/gui/service.fxml"));
      loader.load();
      ServiceDialogController controller = loader.getController();
      controller.init(current, cadenceProfiles, checkOnly);
      return controller;
    } catch (IOException e) {
      throw new BitferryError("Service dialog form loading failure", e);
    }
  }

  static ServiceDialogController load(ServiceSettings current, Map<String, Cadence> cadenceProfiles) {
    return load(current, cadenceProfiles, false);
  }

  static ServiceDialogController load(ServiceSettings current) {
    return load(current, Backup.CADENCE);
  }
}
