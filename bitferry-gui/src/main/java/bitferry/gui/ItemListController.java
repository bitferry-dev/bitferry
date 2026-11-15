package bitferry.gui;

import bitferry.BitferryError;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldListCell;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// The item list editor controller (items.fxml): per-argument entry edition,
/// reordering, and profile loading shared by the include patterns, the exclude
/// patterns, and the backend execution options entries. The dialog shell (the
/// title, the buttons, the result conversion) lives in [ItemListDialog].
public final class ItemListController {

  @FXML
  private VBox root;

  // The profile preset bar (the bar and its separator hide when no profiles supplied)
  @FXML
  private VBox profileBar;

  @FXML
  private ComboBox<String> profileCombo;

  @FXML
  private ListView<String> listView;

  @FXML
  private TextField inputField;

  @FXML
  private Button applyBtn;

  @FXML
  private Button editBtn;

  @FXML
  private Button removeBtn;

  @FXML
  private Button upBtn;

  @FXML
  private Button downBtn;

  private final ObservableList<String> items = FXCollections.observableArrayList();

  private Map<String, List<String>> profiles;

  public ItemListController() {}

  // The display wiring (no data yet)
  @FXML
  private void initialize() {
    listView.setItems(items);
    listView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
    listView.setCellFactory(TextFieldListCell.forListView());
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
  }

  /// Inject the initial entries and the profile presets (called by the facade before
  /// the dialog shows)
  public void init(List<String> initialItems, Map<String, List<String>> profiles) {
    this.profiles = profiles;
    items.setAll(initialItems != null ? initialItems : List.of());
    Map<String, List<String>> nonNull = filterProfiles(profiles);
    if (!nonNull.isEmpty()) {
      profileCombo.getItems().setAll(nonNull.keySet());
      profileCombo.getSelectionModel().selectFirst();
    } else {
      profileBar.setVisible(false);
      profileBar.setManaged(false);
    }
    updateButtonStates();
  }

  /// The form root for the dialog shell content.
  public VBox pane() {
    return root;
  }

  /// The trimmed non-empty entries for the dialog result.
  List<String> cleanedItems() {
    return cleanItems(items);
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

  @FXML
  private void onProfileLoad() {
    String selected = profileCombo.getValue();
    Map<String, List<String>> nonNull = filterProfiles(profiles);
    if (selected != null && nonNull.containsKey(selected)) {
      List<String> preset = nonNull.get(selected);
      items.setAll(preset != null ? preset : List.of());
      inputField.clear();
      updateButtonStates();
    }
  }

  @FXML
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

  @FXML
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
      javafx.application.Platform.runLater(() -> listView.edit(newIdx));
    }
  }

  @FXML
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

  @FXML
  private void onEditInline() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx < 0 || idx >= items.size()) return;
    listView.edit(idx);
  }

  @FXML
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

  @FXML
  private void onMoveUp() {
    int idx = listView.getSelectionModel().getSelectedIndex();
    if (idx <= 0) return;
    String item = items.remove(idx);
    items.add(idx - 1, item);
    listView.getSelectionModel().select(idx - 1);
    updateButtonStates();
  }

  @FXML
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
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    if (profiles == null) return result;
    for (Map.Entry<String, List<String>> entry : profiles.entrySet()) {
      if (entry.getKey() == null) continue;
      String displayKey = entry.getKey().startsWith("@") ? entry.getKey() : "@" + entry.getKey();
      result.put(displayKey, entry.getValue());
    }
    return result;
  }

  /// Load the FXML-backed form injecting the initial entries (called by the facade).
  static ItemListController load(List<String> initialItems, Map<String, List<String>> profiles) {
    try {
      FXMLLoader loader = new FXMLLoader(ItemListController.class.getResource("/bitferry/gui/items.fxml"));
      loader.load();
      ItemListController controller = loader.getController();
      controller.init(initialItems, profiles);
      return controller;
    } catch (IOException e) {
      throw new BitferryError("the item list editor form loading failure", e);
    }
  }
}
