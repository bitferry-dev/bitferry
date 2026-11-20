package io.bitferry.gui;

import io.bitferry.BitferryError;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.util.Optional;

/// The encryption and vault dialog shell: the FXML-backed form (encryption.fxml +
/// EncryptionDialogController) wrapped in a plain dialog shell carrying the title,
/// the buttons, and the validation filter (a failing form keeps the dialog open with
/// the typed input intact). Returns the captured settings on OK, empty on cancel.
final class EncryptionDialog {

  private static final Color DARK_TEXT = Color.web("#222222");

  private EncryptionDialog() {}

  /// Static helper to display the security dialog and return the resulting settings.
  static Optional<EncryptionSettings> show(AppContext context, String title, String header,
                                           EncryptionDialogController form) {
    Dialog<EncryptionSettings> dialog = new Dialog<>();
    if (context.ownerWindow != null) dialog.initOwner(context.ownerWindow);
    dialog.setTitle(title);
    dialog.setHeaderText(header);
    dialog.setResizable(true);
    dialog.getDialogPane().setContent(form.pane());
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    // The dialog stage snaps its size from the first (pre-CSS) layout pass and does
    // not re-track the pane afterwards — the pane's own pref is exact (the form
    // demands its content width), so the window is re-sized to it as the dialog
    // shows, then once more after the CSS settles, and centered on the screen
    // hosting the owner window (mirroring Dialogs.centerOnScreen — the shown-hook
    // positioning keeps the multi-monitor setups sane the same way).
    dialog.setOnShown(event -> {
      Window window = dialog.getDialogPane().getScene().getWindow();
      window.sizeToScene();
      Platform.runLater(window::sizeToScene);
      Screen screen = Screen.getPrimary();
      if (context.ownerWindow != null) {
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
    // Ensure all static text labels within the dialog pane are crisp dark text
    // (the FXML styles the form's own labels; this covers the shell's header)
    Platform.runLater(() -> {
      dialog.getDialogPane().lookupAll(".label").forEach(node -> {
        if (node instanceof Label l) {
          l.setTextFill(DARK_TEXT);
          l.setStyle("-fx-text-fill: #222222;");
        }
      });
    });
    // The validation rides on the OK button's action filter: a failing form keeps
    // the dialog open with the typed input intact (mirroring the task form dialog)
    Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
    ok.addEventFilter(ActionEvent.ACTION, event -> {
      try {
        form.validate();
      } catch (BitferryError e) {
        Dialogs.alert(context, javafx.scene.control.Alert.AlertType.ERROR, "Invalid form", e.getMessage());
        event.consume();
      }
    });
    return dialog.showAndWait().filter(ButtonType.OK::equals).map(x -> form.settings());
  }
}
