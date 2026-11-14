package bitferry.gui;

import bitferry.*;
import bitferry.task.Task;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.*;

/// The volumes tab controller: the management actions (create, delete) confirmed
/// and dispatched through the context's operations thread mirroring the CLI
/// delete/create flows, over the volumes listing.
public final class VolumesController {

  /// The display row snapshot; rebuilt wholesale upon every model change.
  public static final class Row {
    final Volume volume;
    final StringProperty tag = new SimpleStringProperty();
    final StringProperty root = new SimpleStringProperty();
    final StringProperty tasks = new SimpleStringProperty();
    final StringProperty modified = new SimpleStringProperty();

    Row(Volume volume) {
      this.volume = volume;
      tag.set(volume.tag());
      root.set(volume.root().toString());
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
  private TableColumn<Row, String> tasksCol;

  @FXML
  private TableColumn<Row, String> modifiedCol;

  @FXML
  private Button create;

  @FXML
  private Button delete;


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
    // The creation gates on the run lock too: the queued runnable would silently
    // apply after the run ends with no feedback (mirroring the tasks pane)
    create.setDisable(locked);
    delete.setDisable(locked || none);
  }

  @FXML
  private void onCreate() {
    Dialogs.newVolume(context, logs);
  }

  @FXML
  private void onDelete() {
    deleteVolume();
  }

  // Delete the selected volume mirroring the CLI delete volume flow: the storage file
  // is removed while the directory contents are kept
  private void deleteVolume() {
    Row row = selected();
    if (row == null) return;
    Volume volume = row.volume;
    String title = "Delete volume";
    String text = "Delete volume " + volume.tag() + " at\n  " + volume.root()
      + "\n(the directory contents are kept)?"
      + "\n\nThe referring task deletion tombstones propagate to the other storages.";
    Dialogs.confirmAndRun(context, title, text, () -> {
      // Re-resolve the volume on the current manager: the captured row's volume may
      // belong to a manager swapped out by a concurrent reload
      Manager manager = context.manager();
      Volume current = manager.volume(volume.tag());
      if (current == null) throw new BitferryError("volume " + volume.tag() + " is no longer registered");
      current.delete();
      manager.commit();
      logs.log("volume " + current.tag() + " deleted");
      context.fireModelChanged();
    }, logs);
  }
}
