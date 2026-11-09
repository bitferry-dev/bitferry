package bitferry.gui;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.logging.Logger;

/// JavaFX frontend entry point.
///
/// <p>Placeholder pending the real GUI replant: shows a window carrying the library
/// version. {@code --train} shows the window and exits on the first frame pulse — the
/// mode the build uses to record the AOT cache.
public final class Main extends Application {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    @Override
    public void start(Stage stage) {
        boolean train = getParameters().getRaw().contains("--train");
        log.info(() -> "Starting bitferry-gui " + bitferry.Bitferry.VERSION + " (train mode: " + train + ")");

        Label version = new Label("bitferry " + bitferry.Bitferry.VERSION);
        version.setFont(Font.font("Monospaced", 16));
        stage.setScene(new Scene(new BorderPane(version), 400, 200));
        stage.setTitle("Bitferry");
        stage.show();
        log.info("UI ready");

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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
