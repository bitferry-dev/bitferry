package io.bitferry.gui;

import io.bitferry.*;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.logging.Level;
import java.util.logging.Logger;

/// JavaFX frontend entry point.
///
/// <p>The main window layout travels through the FXML resources; the controllers
/// carry the behavior. {@code --train} shows the window and exits on the first
/// frame pulse — the mode the build uses to record the AOT cache.
public final class Main extends Application {

  private static final Logger log = Logger.getLogger(Main.class.getName());

  @Override
  public void start(Stage stage) throws Exception {
    boolean train = getParameters().getRaw().contains("--train");
    log.info(() -> "Starting bitferry-gui " + Bitferry.VERSION + " (train mode: " + train + ")");

    AppContext context = new AppContext();
    WindowSettings settings = WindowSettings.load();
    FXMLLoader loader = new FXMLLoader(Main.class.getResource("main.fxml"));
    Parent root = loader.load();
    MainController controller = loader.getController();
    // The host services drive the info line's home-site link (the system browser)
    controller.init(context, settings, getHostServices());

    Scene scene = new Scene(root);
    stage.setScene(scene);
    stage.setTitle("Bitferry " + Bitferry.VERSION);
    settings.apply(stage);
    // The normal bounds tracking seeds from the applied size and keeps the
    // pre-maximize geometry current for the capture upon hiding
    settings.attach(stage);
    // The dialogs and alerts parent to the main window (set before the first use)
    context.ownerWindow = stage;
    stage.show();

    // The window status (the placement, the size, the maximized flag, the log split
    // position, the verbosity, the simulation mode) travels back into the preferences store upon hiding
    // (both the Exit action and the window manager close go through the same hook)
    stage.setOnHiding(event ->
      settings.capture(stage, controller.dividerPosition(), controller.verboseSelected(), controller.simulateSelected()));

    // The window manager close shares the Exit action's busy guard: while a run is
    // in progress the close request is consumed (the exit is confirmed there and
    // performed once the run winds down - the unguarded exit would orphan the
    // running backend child)
    stage.setOnCloseRequest(controller::handleCloseRequest);

    // The initial manager restoration travels through the operations thread;
    // the panes fill in upon the model change notification
    context.reload();
    if (!train) context.startMediaWatcher();

    if (train) {
      // Warm up the dialog forms so the AOT cache covers the dialog controllers and FXML resources
      try {
        TaskFormController.load(context, null);
        ItemListController.load(java.util.List.of(), java.util.Map.of());
      } catch (Throwable t) {
        log.log(Level.WARNING, "Dialog forms training warmup failure", t);
      }
      // Exit after the first frame pulse: enough to exercise the toolkit startup
      // the AOT cache covers.
      PauseTransition pulse = new PauseTransition(Duration.millis(200));
      pulse.setOnFinished(event -> {
        log.info("Train frame shown, exiting");
        Platform.exit();
      });
      pulse.play();
    }
    log.info("UI ready");
  }

  public static void main(String[] args) {
    // The compact single-line console logging in place of the verbose JUL blocks
    ConsoleLog.compact();
    // The GUI must not die on an uncaught background exception: surface it in the log
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
      log.log(Level.SEVERE, "uncaught exception on " + thread.getName(), throwable));
    launch(args);
  }
}
