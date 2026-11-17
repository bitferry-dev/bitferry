package bitferry.gui;

import bitferry.*;
import bitferry.task.*;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
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
    final BooleanProperty runnable = new SimpleBooleanProperty();
    final StringProperty tag = new SimpleStringProperty();
    final StringProperty operation = new SimpleStringProperty();
    final StringProperty source = new SimpleStringProperty();
    final StringProperty destination = new SimpleStringProperty();
    final StringProperty status = new SimpleStringProperty();
    final ObjectProperty<Color> statusColor = new SimpleObjectProperty<>();
    final StringProperty vault = new SimpleStringProperty();

    Row(Task task) {
      this.task = task;
      runnable.set(task.isRunnable());
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
  private TableColumn<Row, Boolean> runnableColumn;

  @FXML
  private TableColumn<Row, String> tagColumn;

  @FXML
  private TableColumn<Row, String> operationColumn;

  @FXML
  private TableColumn<Row, String> sourceColumn;

  @FXML
  private TableColumn<Row, String> destinationColumn;

  @FXML
  private TableColumn<Row, String> statusColumn;

  @FXML
  private TableColumn<Row, String> vaultColumn;

  @FXML
  private Button create;

  @FXML
  private Button modify;

  @FXML
  private Button massAll;

  @FXML
  private Button massNone;

  @FXML
  private Button massInvert;

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
    table.setEditable(true);
    // The custom cell (not CheckBoxTableCell): its bidirectional binding writes the
    // STALE visual state of a reused cell into the next row's property on every
    // table rebuild — flipping the flags arbitrarily. The explicit cell sets the
    // state from the row's snapshot and persists only the user's own clicks (the
    // snapshot follows the click: the re-renders — the scrolling, the resizing —
    // must not repaint the stale pre-click state over the user's toggle).
    runnableColumn.setCellValueFactory(data -> data.getValue().runnable);
    runnableColumn.setCellFactory(column -> new TableCell<>() {
      private final CheckBox checkBox = new CheckBox();
      {
        checkBox.setOnAction(event -> {
          if (getTableRow() != null && getTableRow().getItem() instanceof Row row) {
            row.task.setRunnable(checkBox.isSelected());
            row.runnable.set(checkBox.isSelected());
          }
        });
      }

      @Override
      protected void updateItem(Boolean item, boolean empty) {
        super.updateItem(item, empty);
        Row row = getTableRow() != null && getTableRow().getItem() instanceof Row r ? r : null;
        if (empty || row == null) {
          setGraphic(null);
          return;
        }
        checkBox.setSelected(row.runnable.get());
        setGraphic(checkBox);
      }
    });
    tagColumn.setCellValueFactory(data -> data.getValue().tag);
    operationColumn.setCellValueFactory(data -> data.getValue().operation);
    sourceColumn.setCellValueFactory(data -> data.getValue().source);
    destinationColumn.setCellValueFactory(data -> data.getValue().destination);
    vaultColumn.setCellValueFactory(data -> data.getValue().vault);
    statusColumn.setCellValueFactory(data -> data.getValue().status);
    statusColumn.setCellFactory(column -> new TableCell<>() {
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
    for (Task task : manager.liveTasks().values()) {
      rows.add(new Row(task));
    }
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
    massAll.setDisable(locked);
    massNone.setDisable(locked);
    massInvert.setDisable(locked);
    modify.setDisable(locked || none);
    execute.setDisable(locked || none);
    serve.setDisable(locked || none);
    delete.setDisable(locked || none);
  }

  /// The mass checkbox controls: all on / all off / invert over every live task.
  /// Session-only: the toggles change the runtime execution status without
  /// touching the persisted descriptor. The model change notification rides the
  /// mutation: without it the row snapshots (and hence the checkboxes) keep
  /// rendering the stale states while the mass runs honor the flipped flags —
  /// "All Off" followed by Execute All runs nothing, looking like the buttons
  /// do nothing at all.
  private void setMassAll(boolean value) {
    context.operations().execute(() -> {
      Manager manager = context.manager();
      for (Task t : manager.liveTasks().values()) t.setRunnable(value);
      context.fireModelChanged();
    });
  }

  private void invertMass() {
    context.operations().execute(() -> {
      Manager manager = context.manager();
      for (Task t : manager.liveTasks().values()) t.setRunnable(!t.isRunnable());
      context.fireModelChanged();
    });
  }

  @FXML
  private void onMassAll() {
    if (locked) return;
    setMassAll(true);
  }

  @FXML
  private void onMassNone() {
    if (locked) return;
    setMassAll(false);
  }

  @FXML
  private void onMassInvert() {
    if (locked) return;
    invertMass();
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
        if (current == null) throw new BitferryError("Task " + task.tag() + " is no longer registered");
        current.delete();
        manager.commit();
        logs.log("Task " + AnsiFormat.tag(current.tag()) + " deleted");
        // Re-probe the manager mirroring the volume deletion flow: the reload's
        // garbage collection retires the tombstone right away when all the referred
        // volumes are visible (the storages are cleaned by the following commits),
        // and the wholesale swap keeps the GUI consistent with the fresh-CLI state.
        context.reload();
      }, logs);
  }
}
