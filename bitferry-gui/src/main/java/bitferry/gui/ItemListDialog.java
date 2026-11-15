package bitferry.gui;

import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A reusable dialog for per-argument entry edition, reordering, and profile loading
/// across include patterns, exclude patterns, and backend execution options. The
/// FXML-backed form (items.fxml + ItemListController) wrapped in a plain dialog
/// shell carrying the title, the buttons, and the result conversion.
final class ItemListDialog {

  private static final Color DARK_TEXT = Color.web("#222222");

  private ItemListDialog() {}

  /// Static helper to display the list editor and return the resulting items list.
  static Optional<List<String>> show(Window owner, String title, String header, List<String> initialItems, Map<String, List<String>> profiles) {
    ItemListController controller = ItemListController.load(initialItems, profiles);
    Dialog<List<String>> dialog = new Dialog<>();
    if (owner != null) dialog.initOwner(owner);
    dialog.setTitle(title);
    dialog.setHeaderText(header);
    dialog.setResizable(true);
    dialog.getDialogPane().setContent(controller.pane());
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    dialog.getDialogPane().setPrefWidth(540);
    dialog.getDialogPane().setPrefHeight(400);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    Dialogs.centerOnScreen(dialog, owner);
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
    dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? controller.cleanedItems() : null);
    return dialog.showAndWait();
  }
}
