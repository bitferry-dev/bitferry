package io.bitferry.gui;

import io.bitferry.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;

/// The main window controller: the global toolbar actions, the status bar with the
/// run progress and the coordination of the embedded volumes/tasks/log controllers.
public final class MainController {

  @FXML
  private Button executeAll;

  @FXML
  private Button abort;

  @FXML
  private Button reload;

  @FXML
  private MenuButton moreMenu;

  @FXML
  private MenuItem searchRootsItem;

  @FXML
  private CheckBox verbose;

  @FXML
  private CheckBox simulate;

  @FXML
  private Button exit;

  @FXML
  private SplitPane split;

  @FXML
  private TabPane contentTabs;

  @FXML
  private ProgressBar progress;

  @FXML
  private Label status;

  // The included pane controllers (injected by the fx:include fx:id naming convention)
  @FXML
  private VolumesController volumesPaneController;

  @FXML
  private TasksController tasksPaneController;

  @FXML
  private LogController logPaneController;

  private AppContext context;
  private Runner runner;

  public MainController() {}

  /// Inject the shared context once the whole window is loaded (called by the
  /// Application before the window shows). The persisted window status applies
  /// its share (the log split position and the verbosity switch).
  public void init(AppContext context, WindowSettings settings) {
    this.context = context;
    this.runner = new Runner(context, logPaneController);
    context.logger = logPaneController::log;
    // The media watcher defers the re-probe while the runner carries the run
    context.busy = runner::isBusy;
    volumesPaneController.init(context, logPaneController);
    tasksPaneController.init(context, logPaneController, this::startRun);
    context.onModelChanged(() -> Platform.runLater(this::refreshPanes));
    split.setDividerPositions(settings.divider());
    verbose.setSelected(settings.verbose());
    context.verbose(settings.verbose());
    simulate.setSelected(settings.simulate());
    context.simulate(settings.simulate());
    // The persisted simulation mode applies to the whole session: surface it right
    // upon the launch - the silently restored dry-run would mask the task creations
    // (nothing written, no backend initialization) as the plain ones
    if (settings.simulate()) {
      logPaneController.log("Simulation mode: enabled (dry run) - restored from the previous session");
    }
    // The tasks pane is the working surface: open on it rather than the volumes tab
    contentTabs.getTabs().stream()
      .filter(tab -> "Tasks".equals(tab.getText()))
      .findFirst()
      .ifPresent(tab -> contentTabs.getSelectionModel().select(tab));
    // The Execute All carries the default button role: park the focus on it so the
    // immediate execution goes through the Enter/Space keystroke right upon the launch
    Platform.runLater(() -> executeAll.requestFocus());
  }

  /// The log pane share of the content split (for the status capture upon hiding).
  public double dividerPosition() {
    double[] positions = split.getDividerPositions();
    return positions.length > 0 ? positions[0] : 0.62;
  }

  /// The verbosity switch state (for the status capture upon hiding).
  public boolean verboseSelected() {
    return verbose.isSelected();
  }

  /// The simulation mode switch state (for the status capture upon hiding).
  public boolean simulateSelected() {
    return simulate.isSelected();
  }

  @FXML
  private void onExecuteAll() {
    startRun(List.of(), false);
  }

  @FXML
  private void onAbort() {
    runner.abort();
  }

  @FXML
  private void onVerbose() {
    context.verbose(verbose.isSelected());
    logPaneController.log("Backend verbosity: " + (verbose.isSelected() ? "verbose" : "normal"));
  }

  @FXML
  private void onSimulate() {
    context.simulate(simulate.isSelected());
    logPaneController.log("Simulation mode: " + (simulate.isSelected() ? "enabled (dry run)" : "disabled"));
  }

  @FXML
  private void onExit() {
    Platform.exit();
  }

  @FXML
  private void onReload() {
    if (runner.isBusy()) {
      logPaneController.log("Reload rejected: a run is in progress");
      return;
    }
    logPaneController.log("Reloading the manager");
    context.reload();
  }

  @FXML
  private void onSearchRoots() {
    if (runner.isBusy()) {
      logPaneController.log("Search paths management rejected: a run is in progress");
      return;
    }
    Dialogs.manageSearchPaths(context, logPaneController);
  }

  /// The run starter seam for the tasks panel: route into the runner choosing the
  /// execute or the service mode (the runner rejects while busy).
  private void startRun(List<String> tags, boolean serve) {
    if (runner.isBusy()) return;
    String verb = serve ? "service" : "run";
    if (serve) runner.submitServe(tags, buildRunListener(verb));
    else runner.submit(tags, buildRunListener(verb));
  }

  /// Rebuild both panes from the current manager snapshot (application thread).
  private void refreshPanes() {
    Manager manager = context.manager();
    volumesPaneController.refresh(manager);
    tasksPaneController.refresh(manager);
  }

  /// The run listener driving the status bar, the busy gating and the post-run refresh.
  private Runner.Listener buildRunListener(String verb) {
    boolean sim = context.simulate();
    String action = verb.equals("service") ? "service" : "execute";
    String baseDoing = (sim ? "simulating " : "") + (verb.equals("service") ? "servicing" : "executing");
    String doing = Character.toUpperCase(baseDoing.charAt(0)) + baseDoing.substring(1);
    return new Runner.Listener() {
      @Override
      public void runStarted(int total) {
        setActionsDisabled(true);
        status.setText(doing + " 0/" + total + "...");
        progress.setProgress(-1); // indeterminate until the first task completes
        progress.setVisible(true);
      }

      @Override
      public void taskStarted(int index, int total, String tag) {
        status.setText(doing + " task " + index + "/" + total + " (" + tag + ")...");
        progress.setProgress(-1);
      }

      @Override
      public void taskFinished(int index, int total, int ok, int failed) {
        progress.setProgress((double) index / total);
        status.setText(doing + " " + index + "/" + total + " — " + ok + " ok, " + failed + " failed");
        refreshPanes();
      }

      @Override
      public void runFinished(boolean outcome, int ok, int failed) {
        setActionsDisabled(false);
        progress.setProgress(ok + failed == 0 ? 0 : (double) ok / (ok + failed));
        String what = (sim ? "Simulated " : "") + (verb.equals("service") ? "service pass" : "run");
        status.setText(outcome && ok == 0
          ? "No intact tasks to " + action
          : outcome
            ? "All " + ok + " task(s) " + (verb.equals("service") ? "serviced" : "executed") + (sim ? " (simulated)" : "") + " successfully"
            : what + " finished: " + ok + " ok, " + failed + " failed");
        refreshPanes();
      }

      @Override
      public void runAborted(int ok, int failed) {
        setActionsDisabled(false);
        progress.setProgress(ok + failed == 0 ? 0 : (double) ok / (ok + failed));
        status.setText((sim ? "Simulated run" : "Run") + " aborted — " + ok + " ok, " + failed + " failed, the rest skipped");
        refreshPanes();
      }
    };
  }

  private void setActionsDisabled(boolean disabled) {
    executeAll.setDisable(disabled);
    reload.setDisable(disabled);
    if (moreMenu != null) moreMenu.setDisable(disabled);
    simulate.setDisable(disabled);
    // The abort is only meaningful while a run is in progress: the inverse gating
    abort.setDisable(!disabled);
    volumesPaneController.setActionsDisabled(disabled);
    tasksPaneController.setActionsDisabled(disabled);
  }
}
