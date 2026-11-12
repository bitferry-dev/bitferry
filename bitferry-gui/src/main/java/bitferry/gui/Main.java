package bitferry.gui;

import bitferry.*;
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
    FXMLLoader loader = new FXMLLoader(Main.class.getResource("main.fxml"));
    Parent root = loader.load();
    MainController controller = loader.getController();
    controller.init(context);

    Scene scene = new Scene(root, 1080, 720);
    stage.setScene(scene);
    stage.setTitle("Bitferry " + Bitferry.VERSION);
    stage.show();

    // The initial manager restoration travels through the operations thread;
    // the panes fill in upon the model change notification
    context.reload();

    if (train) {
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
    // The GUI must not die on an uncaught background exception: surface it in the log
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
      log.log(Level.SEVERE, "uncaught exception on " + thread.getName(), throwable));
    launch(args);
  }
}
