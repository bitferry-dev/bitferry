package bitferry.gui;

import bitferry.*;
import bitferry.task.Task;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.*;

/// The volumes tab controller: the management actions (create, delete, delete with
/// wipe) confirmed and dispatched through the context's operations thread mirroring
/// the CLI delete/create flows, over the volumes listing.
public final class VolumesController {

  /// The display row snapshot; rebuilt wholesale upon every model change.
  public static final class Row {
    final Volume volume;
    final StringProperty tag = new SimpleStringProperty();
    final StringProperty root = new SimpleStringProperty();
    final StringProperty state = new SimpleStringProperty();
    final StringProperty tasks = new SimpleStringProperty();
    final StringProperty modified = new SimpleStringProperty();

    Row(Volume volume) {
      this.volume = volume;
      tag.set("#" + volume.tag());
      root.set(volume.root().toString());
      state.set(volume.isLive() ? "present" : "removed");
      Map<String, Task> live = volume.liveTasks();
      long intact = live.values().stream().filter(Task::isIntact).count();
      tasks.set(intact + " intact / " + (live.size() - intact) + " stale");
      modified.set(volume.isModified() ? "out-of-date" : "up-to-date");
    }
  }

  @FXML
  private TableView<Row> table;

  @FXML
  private TableColumn<Row, String> tagCol;

  @FXML
  private TableColumn<Row, String> rootCol;

  @FXML
  private TableColumn<Row, String> stateCol;

  @FXML
  private TableColumn<Row, String> tasksCol;

  @FXML
  private TableColumn<Row, String> modifiedCol;

  @FXML
  private Button create;

  @FXML
  private Button delete;

  @FXML
  private Button deleteWipe;

  private AppContext context;
  private LogController logs;

  // The run-in-progress lock: the management actions are disabled while a run is going
  private boolean locked;

  public VolumesController() {}

  // The display wiring (no context needed yet)
  @FXML
  private void initialize() {
    tagCol.setCellValueFactory(data -> data.getValue().tag);
    rootCol.setCellValueFactory(data -> data.getValue().root);
    stateCol.setCellValueFactory(data -> data.getValue().state);
    tasksCol.setCellValueFactory(data -> data.getValue().tasks);
    modifiedCol.setCellValueFactory(data -> data.getValue().modified);
    table.getSelectionModel().selectedItemProperty().addListener((x, was, now) -> updateButtons());
    updateButtons();
  }

  /// Inject the shared context once the whole window is loaded (called by the main
  /// controller).
  public void init(AppContext context, LogController logs) {
    this.context = context;
    this.logs = logs;
  }

  /// Rebuild the rows from the manager snapshot keeping the selection by tag
  /// (called on the application thread).
  public void refresh(Manager manager) {
    Row selected = table.getSelectionModel().getSelectedItem();
    String selectedTag = selected == null ? null : selected.volume.tag();
    ObservableList<Row> rows = FXCollections.observableArrayList();
    for (Volume volume : manager.liveVolumes().values()) rows.add(new Row(volume));
    table.getItems().setAll(rows);
    if (selectedTag != null) {
      rows.stream().filter(r -> r.volume.tag().equals(selectedTag)).findFirst()
        .ifPresent(r -> table.getSelectionModel().select(r));
    }
  }

  /// Lock/unlock the management actions (a run is in progress).
  public void setActionsDisabled(boolean disabled) {
    locked = disabled;
    updateButtons();
  }

  private Row selected() {
    return table.getSelectionModel().getSelectedItem();
  }

  private void updateButtons() {
    boolean none = selected() == null;
    delete.setDisable(locked || none);
    deleteWipe.setDisable(locked || none);
  }

  @FXML
  private void onCreate() {
    Dialogs.newVolume(context, logs);
  }

  @FXML
  private void onDelete() {
    deleteVolume(false);
  }

  @FXML
  private void onDeleteWipe() {
    deleteVolume(true);
  }

  // Delete the selected volume (optionally wiping the directory contents) mirroring
  // the CLI delete volume [--wipe] flow
  private void deleteVolume(boolean wipe) {
    Row row = selected();
    if (row == null) return;
    Volume volume = row.volume;
    String title = wipe ? "Delete volume and wipe" : "Delete volume";
    String text = (wipe
      ? "Delete volume #" + volume.tag() + " at\n  " + volume.root() + "\nand wipe its directory contents permanently?"
      : "Delete volume #" + volume.tag() + " at\n  " + volume.root() + "\n(the directory contents are kept)?")
      + "\n\nThe referring task deletion tombstones propagate to the other storages.";
    Dialogs.confirmAndRun(context, title, text, () -> {
      Manager manager = context.manager();
      volume.delete(wipe);
      manager.commit();
      logs.log("volume #" + volume.tag() + " deleted" + (wipe ? " (wiped)" : ""));
      context.fireModelChanged();
    }, logs);
  }
}
