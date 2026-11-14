package bitferry.gui;

import bitferry.*;
import bitferry.task.*;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.paint.Color;

import java.util.*;

/// The tasks tab controller: the management actions (create, execute, service,
/// delete) over the tasks listing. The execution/service requests travel through
/// the shared single-flight runner (via the run starter seam), the deletions
/// through the context's operations thread.
public final class TasksController {

  /// The display row snapshot; rebuilt wholesale upon every model change.
  public static final class Row {
    final Task task;
    final StringProperty tag = new SimpleStringProperty();
    final StringProperty operation = new SimpleStringProperty();
    final StringProperty source = new SimpleStringProperty();
    final StringProperty destination = new SimpleStringProperty();
    final StringProperty status = new SimpleStringProperty();
    final ObjectProperty<Color> statusColor = new SimpleObjectProperty<>();
    final StringProperty vault = new SimpleStringProperty();

    Row(Task task) {
      this.task = task;
      tag.set(task.tag());
      operation.set(task.displayOperation());
      source.set(task.source().describe());
      destination.set(task.destination().describe());
      boolean intact = task.isIntact();
      status.set(intact ? "✓ intact" : "✗ stale");
      statusColor.set(intact ? Color.web("#2e7d32") : Color.web("#c62828"));
      try {
        Volume volume = task.vaultVolume();
        vault.set(volume.isLive() ? volume.tag() : volume.tag() + " (missing)");
      } catch (BitferryError e) {
        vault.set("—");
      }
    }
  }

  /// The run starter seam: the controller requests a run; the main controller
  /// supplies the listener wiring (the status bar and the busy gating live there).
  interface RunStarter {
    /// Start (or request) the run over the specified tags; serve selects the
    /// servicing (integrity/retention) mode.
    void start(List<String> tags, boolean serve);
  }

  @FXML
  private TableView<Row> table;

  @FXML
  private TableColumn<Row, String> tagCol;

  @FXML
  private TableColumn<Row, String> operationCol;

  @FXML
  private TableColumn<Row, String> sourceCol;

  @FXML
  private TableColumn<Row, String> destinationCol;

  @FXML
  private TableColumn<Row, String> statusCol;

  @FXML
  private TableColumn<Row, String> vaultCol;

  @FXML
  private Button create;

  @FXML
  private Button modify;

  @FXML
  private Button execute;

  @FXML
  private Button serve;

  @FXML
  private Button delete;

  private AppContext context;
  private LogController logs;
  private RunStarter runStarter;

  // The run-in-progress lock: the management actions are disabled while a run is going
  private boolean locked;

  public TasksController() {}

  // The display wiring (no context needed yet)
  @FXML
  private void initialize() {
    tagCol.setCellValueFactory(data -> data.getValue().tag);
    operationCol.setCellValueFactory(data -> data.getValue().operation);
    sourceCol.setCellValueFactory(data -> data.getValue().source);
    destinationCol.setCellValueFactory(data -> data.getValue().destination);
    vaultCol.setCellValueFactory(data -> data.getValue().vault);
    statusCol.setCellValueFactory(data -> data.getValue().status);
    statusCol.setCellFactory(column -> new TableCell<>() {
      private final Label label = new Label();
      @Override
      protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setGraphic(null);
          return;
        }
        Row row = (Row) getTableRow().getItem();
        label.setText(item);
        label.setTextFill(row == null ? Color.BLACK : row.statusColor.get());
        setGraphic(label);
      }
    });
    table.setRowFactory(tv -> {
      TableRow<Row> row = new TableRow<>();
      row.setOnMouseClicked(event -> {
        if (event.getClickCount() == 2 && !row.isEmpty()) {
          onModify();
        }
      });
      return row;
    });
    table.getSelectionModel().selectedItemProperty().addListener((x, was, now) -> updateButtons());
    updateButtons();
  }

  /// Inject the shared context and the run starter once the whole window is loaded
  /// (called by the main controller).
  public void init(AppContext context, LogController logs, RunStarter runStarter) {
    this.context = context;
    this.logs = logs;
    this.runStarter = runStarter;
  }

  /// Rebuild the rows from the manager snapshot keeping the selection by tag
  /// (called on the application thread).
  public void refresh(Manager manager) {
    Row selected = table.getSelectionModel().getSelectedItem();
    String selectedTag = selected == null ? null : selected.task.tag();
    List<Row> rows = new ArrayList<>();
    for (Task task : manager.liveTasks().values()) rows.add(new Row(task));
    table.getItems().setAll(rows);
    if (selectedTag != null) {
      rows.stream().filter(r -> r.task.tag().equals(selectedTag)).findFirst()
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
    create.setDisable(locked);
    modify.setDisable(locked || none);
    execute.setDisable(locked || none);
    serve.setDisable(locked || none);
    delete.setDisable(locked || none);
  }

  @FXML
  private void onCreate() {
    Dialogs.newTask(context, logs, () -> locked);
  }

  @FXML
  private void onModify() {
    Row row = selected();
    if (row == null || locked) return;
    Dialogs.modifyTask(context, logs, row.task, () -> locked);
  }

  // Run or service the selected task through the shared single-flight runner
  @FXML
  private void onExecute() {
    Row row = selected();
    if (row == null || locked) return;
    runStarter.start(List.of(row.task.tag()), false);
  }

  @FXML
  private void onServe() {
    Row row = selected();
    if (row == null || locked) return;
    runStarter.start(List.of(row.task.tag()), true);
  }

  // Delete the selected task recording the deletion tombstone into the storages
  @FXML
  private void onDelete() {
    Row row = selected();
    if (row == null) return;
    Task task = row.task;
    Dialogs.confirmAndRun(context, "Delete task",
      "Delete task " + task.tag() + ":\n  " + task.describe() + "?\n\n"
        + "The deletion tombstone propagates to all the referring storages; the task repository is not removed.",
      () -> {
        // Re-resolve the task on the current manager: the captured row's task may
        // belong to a manager swapped out by a concurrent reload
        Manager manager = context.manager();
        Task current = manager.task(task.tag());
        if (current == null) throw new BitferryError("task " + task.tag() + " is no longer registered");
        current.delete();
        manager.commit();
        logs.log("task " + current.tag() + " deleted");
        context.fireModelChanged();
      }, logs);
  }
}
