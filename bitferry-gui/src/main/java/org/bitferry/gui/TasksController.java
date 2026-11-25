package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import javafx.beans.property.*;
import javafx.fxml.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;

import java.util.*;
import java.util.function.*;

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
      source.set(formatEndpoint(task.source()));
      destination.set(formatEndpoint(task.destination()));
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

    public Endpoint sourceEndpoint() {
      return task.source();
    }

    public Endpoint destinationEndpoint() {
      return task.destination();
    }

    public String sourceText() {
      return source.get();
    }

    public String destinationText() {
      return destination.get();
    }

    static String formatEndpoint(Endpoint endpoint) {
      if (endpoint instanceof LocalEndpoint l) {
        return l.root().toString();
      } else if (endpoint instanceof VolumeEndpoint ve) {
        if (ve.isIntact()) {
          try {
            return ":" + ve.vtag() + ": " + ve.processOpts();
          } catch (Exception ignored) {
            return ve.describe();
          }
        } else {
          return ve.describe();
        }
      }
      return endpoint != null ? endpoint.describe() : "";
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
  private Button repair;

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
    Icons.equip(create, Icons.Symbol.PLUS);
    Icons.equip(modify, Icons.Symbol.EDIT);
    Icons.equip(massAll, Icons.Symbol.ALL_ON);
    Icons.equip(massNone, Icons.Symbol.ALL_OFF);
    Icons.equip(massInvert, Icons.Symbol.INVERT);
    Icons.equip(execute, Icons.Symbol.PLAY);
    Icons.equip(serve, Icons.Symbol.SERVE);
    Icons.equip(repair, Icons.Symbol.WRENCH);
    Icons.equip(delete, Icons.Symbol.DELETE);

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
            // The model mutation marshals onto the operations executor (the mass runs
            // read the flag there): the direct FX-thread write would race the dispatch.
            // The row's snapshot property still follows the click immediately (the
            // re-renders must not repaint the stale pre-click state over the toggle)
            boolean selected = checkBox.isSelected();
            context.operations().execute(() -> {
              Task current = context.manager().task(row.task.tag());
              if (current != null) current.setRunnable(selected);
            });
            row.runnable.set(selected);
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
    sourceColumn.setCellFactory(column -> new EndpointTableCell(Row::sourceEndpoint));
    destinationColumn.setCellValueFactory(data -> data.getValue().destination);
    destinationColumn.setCellFactory(column -> new EndpointTableCell(Row::destinationEndpoint));
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
    updateButtons();
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
    Row row = selected();
    boolean none = row == null;
    boolean stale = none || !row.task.isIntact();
    create.setDisable(locked);
    massAll.setDisable(locked);
    massNone.setDisable(locked);
    massInvert.setDisable(locked);
    modify.setDisable(locked || none);
    execute.setDisable(locked || stale);
    serve.setDisable(locked || stale);
    repair.setDisable(locked || stale);
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
    if (row == null || locked || !row.task.isIntact()) return;
    runStarter.start(List.of(row.task.tag()), false);
  }

  @FXML
  private void onServe() {
    Row row = selected();
    if (row == null || locked || !row.task.isIntact()) return;
    runStarter.start(List.of(row.task.tag()), true);
  }

  // Repair the selected task's backend via the repair operations registry (e.g.
  // removing the stale repository locks): the dialog previews the operation's
  // dry-run report before the explicit confirmation
  @FXML
  private void onRepair() {
    Row row = selected();
    if (row == null || locked || !row.task.isIntact()) return;
    Dialogs.repairTask(context, logs, row.task);
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

  /// The custom table cell for source and destination endpoints rendering full paths
  /// with volume tags colored (cyan for intact, red for stale) and intact volume tags
  /// displayed with a smaller font.
  static final class EndpointTableCell extends TableCell<Row, String> {
    private final Function<Row, Endpoint> endpointExtractor;
    private final HBox box = new HBox();
    private final Label tagLabel = new Label();
    private final Label pathLabel = new Label();
    private boolean isStale;

    EndpointTableCell(Function<Row, Endpoint> endpointExtractor) {
      this.endpointExtractor = endpointExtractor;
      tagLabel.setStyle("-fx-font-size: 0.85em;");
      tagLabel.setMinWidth(Region.USE_PREF_SIZE);
      pathLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
      HBox.setHgrow(pathLabel, Priority.ALWAYS);
      box.setAlignment(Pos.BASELINE_LEFT);
      box.getChildren().addAll(tagLabel, pathLabel);

      selectedProperty().addListener((obs, wasSelected, isSelected) -> updateColors(isSelected));
    }

    @Override
    protected void updateItem(String item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setText(null);
        setGraphic(null);
        setTooltip(null);
        return;
      }
      Row row = getTableRow() != null && getTableRow().getItem() instanceof Row r ? r : null;
      if (row == null && getTableView() != null && getIndex() >= 0 && getIndex() < getTableView().getItems().size()) {
        row = getTableView().getItems().get(getIndex());
      }
      update(row, item);
      setText(null);
      setGraphic(box);
    }

    void update(Row row, String fallback) {
      Endpoint endpoint = row != null ? endpointExtractor.apply(row) : null;
      render(endpoint, fallback);
      updateColors(isSelected());
    }

    private void render(Endpoint endpoint, String fallback) {
      if (endpoint instanceof LocalEndpoint l) {
        isStale = false;
        tagLabel.setText("");
        tagLabel.setVisible(false);
        tagLabel.setManaged(false);
        String path = l.root().toString();
        pathLabel.setText(path);
        setTooltip(new Tooltip(path));
      } else if (endpoint instanceof VolumeEndpoint ve) {
        if (ve.isIntact()) {
          isStale = false;
          tagLabel.setText(":" + ve.vtag() + ": ");
          tagLabel.setVisible(true);
          tagLabel.setManaged(true);
          String fullPath;
          try {
            fullPath = ve.processOpts();
          } catch (Exception e) {
            fullPath = ve.describe();
          }
          pathLabel.setText(fullPath);
          setTooltip(new Tooltip(":" + ve.vtag() + ": " + fullPath));
        } else {
          isStale = true;
          tagLabel.setText(":" + ve.vtag() + ":");
          tagLabel.setVisible(true);
          tagLabel.setManaged(true);
          String sub = ve.path().equals(".") ? "" : ve.path();
          pathLabel.setText(sub);
          setTooltip(new Tooltip(ve.describe() + " (volume missing)"));
        }
      } else {
        isStale = false;
        tagLabel.setText("");
        tagLabel.setVisible(false);
        tagLabel.setManaged(false);
        pathLabel.setText(fallback != null ? fallback : "");
        setTooltip(fallback != null && !fallback.isEmpty() ? new Tooltip(fallback) : null);
      }
    }

    void updateColors(boolean selected) {
      if (selected) {
        pathLabel.setTextFill(Color.WHITE);
        tagLabel.setTextFill(isStale ? Color.web("#ffcdd2") : Color.web("#80deea"));
      } else {
        pathLabel.setTextFill(Color.web("#222222"));
        tagLabel.setTextFill(isStale ? Color.web("#c62828") : Color.web("#00838f"));
      }
    }

    Label tagLabel() {
      return tagLabel;
    }

    Label pathLabel() {
      return pathLabel;
    }

    boolean isStale() {
      return isStale;
    }
  }
}
