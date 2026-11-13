package bitferry.gui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldListCell;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.*;

/// A reusable dialog for per-argument entry edition, reordering, and profile loading
/// across include patterns, exclude patterns, and backend execution options with
/// full inline entry edition.
final class ItemListDialog extends Dialog<List<String>> {

  private static final Color DARK_TEXT = Color.web("#222222");

  private final ObservableList<String> items;
  private final ListView<String> listView;
  private final TextField inputField;
  private final Button addBtn;
  private final Button applyBtn;
  private final Button editBtn;
  private final Button removeBtn;
  private final Button upBtn;
  private final Button downBtn;

  ItemListDialog(Window owner, String title, String header, List<String> initialItems, Map<String, List<String>> profiles) {
    if (owner != null) initOwner(owner);
    setTitle(title);
    setHeaderText(header);
    setResizable(true);

    items = FXCollections.observableArrayList(initialItems != null ? initialItems : List.of());
    listView = new ListView<>(items);
    listView.setEditable(true);
    listView.setCellFactory(TextFieldListCell.forListView());
    listView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
    listView.setPrefWidth(360);
    listView.setPrefHeight(260);

    inputField = new TextField();
    inputField.setPromptText("Enter argument or pattern (press Enter to add/apply)…");
    HBox.setHgrow(inputField, Priority.ALWAYS);

    addBtn = new Button("Add");
    addBtn.setTooltip(new Tooltip("Add the entry from the input field to the list (or add a new inline line)"));

    applyBtn = new Button("Apply");
    applyBtn.setTooltip(new Tooltip("Apply modified text to the selected entry"));
    applyBtn.setDisable(true);

    editBtn = new Button("Edit inline");
    editBtn.setMaxWidth(Double.MAX_VALUE);
    editBtn.setTooltip(new Tooltip("Edit the selected entry directly in the list (or double-click the row)"));

    removeBtn = new Button("Remove");
    removeBtn.setMaxWidth(Double.MAX_VALUE);
    removeBtn.setTooltip(new Tooltip("Remove the selected entry"));

    upBtn = new Button("▲ Move Up");
    upBtn.setMaxWidth(Double.MAX_VALUE);
    upBtn.setTooltip(new Tooltip("Move the selected entry up by one position"));

    downBtn = new Button("▼ Move Down");
    downBtn.setMaxWidth(Double.MAX_VALUE);
    downBtn.setTooltip(new Tooltip("Move the selected entry down by one position"));

    addBtn.setOnAction(e -> onAdd());
    applyBtn.setOnAction(e -> onApply());
    editBtn.setOnAction(e -> onEditInline());
    removeBtn.setOnAction(e -> onRemove());
    upBtn.setOnAction(e -> onMoveUp());
    downBtn.setOnAction(e -> onMoveDown());

    inputField.setOnAction(e -> onInputEnter());

    listView.setOnEditCommit(event -> {
      int idx = event.getIndex();
      String newVal = event.getNewValue() == null ? "" : event.getNewValue().trim();
      if (!newVal.isEmpty()) {
        items.set(idx, newVal);
        inputField.setText(newVal);
      } else {
        items.remove(idx);
        inputField.clear();
      }
      updateButtonStates();
    });

    listView.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
      int idx = newVal.intValue();
      if (idx >= 0 && idx < items.size()) {
        inputField.setText(items.get(idx));
      }
      updateButtonStates();
    });

    updateButtonStates();

    VBox buttonBox = new VBox(6, editBtn, removeBtn, new Separator(), upBtn, downBtn);
    buttonBox.setAlignment(Pos.TOP_CENTER);
    buttonBox.setPrefWidth(120);

    HBox listAndButtons = new HBox(10, listView, buttonBox);
    HBox.setHgrow(listView, Priority.ALWAYS);
    VBox.setVgrow(listAndButtons, Priority.ALWAYS);

    Label entryLabel = new Label("Entry:");
    entryLabel.setTextFill(DARK_TEXT);
    entryLabel.setStyle("-fx-text-fill: #222222;");

    HBox inlineEditBar = new HBox(8, entryLabel, inputField, addBtn, applyBtn);
    inlineEditBar.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(10);
    root.setPadding(new Insets(10));

    // Profile loader bar if profiles are supplied
    Map<String, List<String>> nonNullProfiles = filterProfiles(profiles);
    if (!nonNullProfiles.isEmpty()) {
      Label profileLabel = new Label("Profile preset:");
      profileLabel.setTextFill(DARK_TEXT);
      profileLabel.setStyle("-fx-text-fill: #222222;");

      ComboBox<String> profileCombo = new ComboBox<>();
      profileCombo.getItems().setAll(nonNullProfiles.keySet());
      profileCombo.getSelectionModel().selectFirst();
      profileCombo.setMaxWidth(Double.MAX_VALUE);

      Button loadBtn = new Button("Load");
      loadBtn.setTooltip(new Tooltip("Replace current entries with the selected profile's items"));
      loadBtn.setOnAction(e -> {
        String selected = profileCombo.getValue();
        if (selected != null && nonNullProfiles.containsKey(selected)) {
          List<String> preset = nonNullProfiles.get(selected);
          items.setAll(preset != null ? preset : List.of());
          inputField.clear();
          updateButtonStates();
        }
      });

      HBox profileBar = new HBox(8, profileLabel, profileCombo, loadBtn);
      profileBar.setAlignment(Pos.CENTER_LEFT);
      HBox.setHgrow(profileCombo, Priority.ALWAYS);
      root.getChildren().add(profileBar);
      root.getChildren().add(new Separator());
    }

    root.getChildren().addAll(listAndButtons, inlineEditBar);

    DialogPane dialogPane = getDialogPane();
    dialogPane.setContent(root);
    dialogPane.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    dialogPane.setPrefWidth(540);
    dialogPane.setPrefHeight(400);
    dialogPane.setStyle("-fx-text-fill: #222222;");

    // Ensure all static text labels within the dialog pane are crisp dark text
    Platform.runLater(() -> {
      dialogPane.lookupAll(".label").forEach(node -> {
        if (node instanceof Label l) {
          l.setTextFill(DARK_TEXT);
          l.setStyle("-fx-text-fill: #222222;");
        }
      });
    });

    setResultConverter(buttonType -> buttonType == ButtonType.OK ? cleanItems(items) : null);
  }

  private void updateButtonStates() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    boolean hasSelection = idx >= 0 && idx < items.size();
    editBtn.setDisable(!hasSelection);
    removeBtn.setDisable(!hasSelection);
    applyBtn.setDisable(!hasSelection);
    upBtn.setDisable(!hasSelection || idx == 0);
    downBtn.setDisable(!hasSelection || idx >= items.size() - 1);
  }

  private void onInputEnter() {
    String text = inputField.getText().trim();
    if (text.isEmpty()) return;
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx >= 0 && idx < items.size() && !text.equals(items.get(idx))) {
      items.set(idx, text);
    } else {
      items.add(text);
      listView.getSelectionModel().select(items.size() - 1);
      listView.scrollTo(items.size() - 1);
      inputField.clear();
    }
    updateButtonStates();
  }

  private void onAdd() {
    String text = inputField.getText().trim();
    if (!text.isEmpty()) {
      int idx = listView.getSelectionModel().getSelectedIndex();
      int insertIdx = (idx >= 0 && idx < items.size()) ? idx + 1 : items.size();
      items.add(insertIdx, text);
      listView.getSelectionModel().select(insertIdx);
      listView.scrollTo(insertIdx);
      inputField.clear();
      updateButtonStates();
    } else {
      // Create empty inline cell in the list view and initiate editing immediately
      items.add("");
      int newIdx = items.size() - 1;
      listView.getSelectionModel().select(newIdx);
      listView.scrollTo(newIdx);
      updateButtonStates();
      Platform.runLater(() -> listView.edit(newIdx));
    }
  }

  private void onApply() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx < 0 || idx >= items.size()) return;
    String text = inputField.getText().trim();
    if (!text.isEmpty()) {
      items.set(idx, text);
    } else {
      items.remove(idx);
      inputField.clear();
    }
    updateButtonStates();
  }

  private void onEditInline() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx < 0 || idx >= items.size()) return;
    listView.edit(idx);
  }

  private void onRemove() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx < 0 || idx >= items.size()) return;
    items.remove(idx);
    if (idx < items.size()) {
      listView.getSelectionModel().select(idx);
      inputField.setText(items.get(idx));
    } else if (!items.isEmpty()) {
      listView.getSelectionModel().select(items.size() - 1);
      inputField.setText(items.get(items.size() - 1));
    } else {
      inputField.clear();
    }
    updateButtonStates();
  }

  private void onMoveUp() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx <= 0) return;
    String item = items.remove(idx);
    items.add(idx - 1, item);
    listView.getSelectionModel().select(idx - 1);
    updateButtonStates();
  }

  private void onMoveDown() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx < 0 || idx >= items.size() - 1) return;
    String item = items.remove(idx);
    items.add(idx + 1, item);
    listView.getSelectionModel().select(idx + 1);
    updateButtonStates();
  }

  private static List<String> cleanItems(List<String> list) {
    List<String> result = new ArrayList<>();
    for (String item : list) {
      if (item != null) {
        String trimmed = item.trim();
        if (!trimmed.isEmpty()) result.add(trimmed);
      }
    }
    return result;
  }

  private static Map<String, List<String>> filterProfiles(Map<String, List<String>> profiles) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    if (profiles == null) return result;
    for (Map.Entry<String, List<String>> entry : profiles.entrySet()) {
      if (entry.getKey() == null) continue;
      String displayKey = entry.getKey().startsWith("@") ? entry.getKey() : "@" + entry.getKey();
      result.put(displayKey, entry.getValue());
    }
    return result;
  }

  /// Static helper to display the list editor and return the resulting items list.
  static Optional<List<String>> show(Window owner, String title, String header, List<String> initialItems, Map<String, List<String>> profiles) {
    ItemListDialog dialog = new ItemListDialog(owner, title, header, initialItems, profiles);
    return dialog.showAndWait();
  }
}
