package org.bitferry.gui;

import org.bitferry.*;
import javafx.application.*;
import javafx.event.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.paint.*;
import javafx.stage.*;

import java.util.Optional;

/// Modal dialog shell for editing backup repository service configuration ([ServiceSettings]):
/// wraps [ServiceDialogController] with OK/Cancel buttons and pre-close validation.
final class ServiceDialog {

  private static final Color DARK_TEXT = Color.web("#222222");

  /// Dialog prompter seam (swappable by tests).
  static java.util.function.Function<Dialog<ButtonType>, Optional<ButtonType>> dialogPrompter = Dialog::showAndWait;

  private ServiceDialog() {}

  static Optional<ServiceSettings> show(AppContext context, String title, String header,
                                        ServiceDialogController form) {
    Dialog<ButtonType> dialog = new Dialog<>();
    if (context != null && context.ownerWindow != null) dialog.initOwner(context.ownerWindow);
    dialog.setTitle(title);
    dialog.setHeaderText(header);
    dialog.setResizable(true);
    dialog.getDialogPane().setContent(form.pane());
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");

    dialog.setOnShown(event -> {
      Window window = dialog.getDialogPane().getScene().getWindow();
      window.sizeToScene();
      Platform.runLater(window::sizeToScene);
      Screen screen = Screen.getPrimary();
      if (context != null && context.ownerWindow != null) {
        for (Screen candidate : Screen.getScreens()) {
          if (candidate.getVisualBounds().contains(
            context.ownerWindow.getX() + context.ownerWindow.getWidth() / 2,
            context.ownerWindow.getY() + context.ownerWindow.getHeight() / 2)) {
            screen = candidate;
            break;
          }
        }
      }
      Rectangle2D bounds = screen.getVisualBounds();
      window.setX(bounds.getMinX() + (bounds.getWidth() - window.getWidth()) / 2);
      window.setY(bounds.getMinY() + (bounds.getHeight() - window.getHeight()) / 2);
    });

    Platform.runLater(() -> dialog.getDialogPane().lookupAll(".label").forEach(node -> {
      if (node instanceof Label l) {
        l.setTextFill(DARK_TEXT);
        l.setStyle("-fx-text-fill: #222222;");
      }
    }));

    Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
    ok.addEventFilter(ActionEvent.ACTION, event -> {
      try {
        form.validate();
      } catch (BitferryError e) {
        Dialogs.alert(context, javafx.scene.control.Alert.AlertType.ERROR, "Invalid service settings", e.getMessage());
        event.consume();
      }
    });

    return dialogPrompter.apply(dialog).filter(ButtonType.OK::equals).map(x -> form.settings());
  }
}
