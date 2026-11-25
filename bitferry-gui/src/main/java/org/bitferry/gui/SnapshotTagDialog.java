package org.bitferry.gui;

import org.bitferry.endpoint.*;
import javafx.application.*;
import javafx.scene.control.*;
import javafx.scene.paint.*;
import javafx.stage.*;

import java.util.*;

/// Modal dialog for selecting a Restic snapshot tag scoping a restore task.
/// Wraps [SnapshotTagController], which extracts tags from the repository
/// asynchronously without blocking the task form.
final class SnapshotTagDialog {

  private static final Color DARK_TEXT = Color.web("#222222");

  /// Dialog prompter seam (swappable by tests).
  static java.util.function.Function<Dialog<ButtonType>, Optional<ButtonType>> dialogPrompter = Dialog::showAndWait;

  private SnapshotTagDialog() {}

  static Optional<String> show(Window owner, AppContext context, Endpoint repository, String password, String initialTag) {
    SnapshotTagController controller = SnapshotTagController.load(context, repository, password, initialTag);
    return show(owner, controller);
  }

  static Optional<String> show(Window owner, SnapshotTagController controller) {
    Dialog<ButtonType> dialog = new Dialog<>();
    if (owner != null) dialog.initOwner(owner);
    dialog.setTitle("Snapshot Tags");
    dialog.setHeaderText("Select a snapshot tag for restore scoping");
    dialog.setResizable(true);
    dialog.getDialogPane().setContent(controller.pane());
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    Dialogs.centerOnScreen(dialog, owner);

    Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
    controller.setOnConfirm(ok::fire);

    Platform.runLater(() -> dialog.getDialogPane().lookupAll(".label").forEach(node -> {
      if (node instanceof Label l) {
        l.setTextFill(DARK_TEXT);
        l.setStyle("-fx-text-fill: #222222;");
      }
    }));

    return dialogPrompter.apply(dialog).filter(ButtonType.OK::equals).map(x -> controller.selectedTag());
  }
}
