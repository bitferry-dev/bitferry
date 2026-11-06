package bitferry.gui;

import bitferry.Cargo;
import bitferry.Ferry;
import bitferry.Manifest;
import bitferry.Route;
import bitferry.Version;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX frontend for the bitferry ferry service.
 *
 * <p>{@code --train} starts the UI, dispatches a representative fleet and exits shortly after
 * the first frame is shown — the mode used by the build to record the AOT cache.
 */
public final class BitferryGui extends Application {
    private static final Logger log = LoggerFactory.getLogger(BitferryGui.class);

    private final Ferry ferry = new Ferry();
    private TextArea logArea;
    private TextField cargoField;

    @Override
    public void start(Stage stage) {
        boolean train = getParameters().getRaw().contains("--train");
        log.info("Starting bitferry-gui {} (train mode: {})", Version.get(), train);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setFont(Font.font("Monospaced", 12));

        cargoField = new TextField("amphorae:250:olive oil");
        cargoField.setPrefWidth(220);
        Button dispatch = new Button("Dispatch");
        dispatch.setOnAction(e -> dispatchOne());
        Button fleet = new Button("Dispatch fleet");
        fleet.setOnAction(e -> dispatchFleet());

        ToolBar bar = new ToolBar(new Label("Cargo:"), cargoField, dispatch, fleet);
        BorderPane root = new BorderPane(logArea);
        root.setTop(bar);
        root.setBottom(new Label("bitferry " + Version.get()));

        stage.setTitle("Bitferry");
        stage.setScene(new Scene(root, 680, 440));
        stage.setMinWidth(480);
        stage.setMinHeight(320);

        if (train) {
            // Exercise the UI and the library once the first frame is rendered, then exit.
            stage.setOnShown(e -> {
                dispatchFleet();
                exitLater(Duration.millis(400));
            });
            exitLater(Duration.seconds(5)); // safety net if the shown event never fires (headless)
        }
        stage.show();
        log.info("UI ready");
    }

    private void dispatchOne() {
        try {
            Cargo cargo = parseCargo(cargoField.getText());
            Manifest manifest = ferry.dispatch(cargo, currentRoute());
            append(manifest);
        } catch (RuntimeException e) {
            log.warn("Could not dispatch cargo: {}", e.getMessage());
            append("error: " + e.getMessage());
        }
    }

    private void dispatchFleet() {
        List<Cargo> fleet = List.of(
                new Cargo("amphorae", "olive oil", 250),
                new Cargo("ingots", "bronze", 700),
                new Cargo("silk", "", 60),
                new Cargo("grain", "", 1200));
        ferry.dispatchAll(fleet, currentRoute()).forEach(this::append);
    }

    private Route currentRoute() {
        return new Route("alpha", "omega");
    }

    private void append(Manifest manifest) {
        append(manifest.summary());
    }

    private void append(String line) {
        logArea.appendText(line + System.lineSeparator());
        logArea.setScrollTop(Double.MAX_VALUE);
    }

    private static Cargo parseCargo(String spec) {
        List<String> parts = new ArrayList<>(List.of(spec.split(":", 3)));
        while (parts.size() < 3) {
            parts.add("");
        }
        int weight = parts.get(1).isBlank() ? 100 : Integer.parseInt(parts.get(1));
        return new Cargo(parts.get(0).trim(), parts.get(2), weight);
    }

    private static void exitLater(Duration delay) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(e -> Platform.exit());
        pause.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
