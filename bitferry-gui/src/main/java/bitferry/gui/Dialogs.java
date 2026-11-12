package bitferry.gui;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/// The creation dialogs: the new volume and the new task forms. The heavy lifting
/// (endpoint parsing, task construction, commit) travels through the context's
/// operations thread mirroring the CLI command flows; the failures are surfaced
/// both in the error alerts and the general log.
final class Dialogs {

  private Dialogs() {}

  /// The endpoint specifier hint shared by the task dialog fields.
  private static final String ENDPOINT_HINT = """
    Endpoint specifier:
      directory        bare path, attached to the encompassing volume
      local:directory  explicit local path (outside the volumes)
      ::directory      resolved against the intact volume
      :tag:directory   relative to the volume matched by (partial) tag""";

  /// Show the new volume dialog; the creation is asynchronous.
  static void newVolume(AppContext context, LogController logs) {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle("Select the volume root directory");
    File initial = new File(System.getProperty("user.home"));
    if (initial.isDirectory()) chooser.setInitialDirectory(initial);
    File root = chooser.showDialog(null);
    if (root == null) return;
    Path path = root.toPath();
    confirmAndRun(context, "Create volume", "Create a volume with the root\n  " + path + " ?", () -> {
      Manager manager = context.manager();
      Volume volume = new Volume(manager, path);
      manager.commit();
      logs.log("volume #" + volume.tag() + " created at " + volume.root());
      context.fireModelChanged();
    }, logs);
  }

  /// Show the new task dialog returning once the form is dismissed; the creation
  /// itself is asynchronous.
  static void newTask(AppContext context, LogController logs, BooleanSupplier busy) {
    if (busy.getAsBoolean()) {
      alert(Alert.AlertType.WARNING, "Busy", "A run is in progress — wait for it to finish.");
      return;
    }
    TaskForm form = new TaskForm(context);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("New task");
    dialog.setHeaderText("Create a new data processing task");
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Create");
    dialog.getDialogPane().setContent(form.pane());
    dialog.getDialogPane().setPrefWidth(560);
    dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(x -> {
      try {
        form.validate();
      } catch (BitferryError e) {
        alert(Alert.AlertType.ERROR, "Invalid form", e.getMessage());
        return;
      }
      createTask(context, logs, form);
    });
  }

  // Run the task creation on the operations thread; the failures deregister the
  // half-constructed task (the commit aborts and deregisters on its own)
  private static void createTask(AppContext context, LogController logs, TaskForm form) {
    context.operations().execute(() -> {
      Task task = null;
      try {
        Manager manager = context.manager();
        Endpoint source = Endpoints.parse(manager, form.source());
        Endpoint destination = Endpoints.parse(manager, form.destination());
        Task existing = manager.findTask(source, destination);
        if (existing != null) throw new BitferryError("task #" + existing.tag() + " already covers " + existing.describe());
        List<String> include = patterns(form.include());
        List<String> exclude = patterns(form.exclude());
        String execute = form.execute();
        task = switch (form.type()) {
          case "copy" -> new Copy(manager, source, destination, execute, include, exclude);
          case "update" -> new Update(manager, source, destination, execute, include, exclude);
          case "synchronize" -> new Synchronize(manager, source, destination, execute, include, exclude);
          case "equalize" -> new Equalize(manager, source, destination, execute, include, exclude);
          case "backup" -> new Backup(manager, source, destination, form.password(), form.format(),
            execute, null, null, form.serve(), include, exclude);
          case "restore" -> new Restore(manager, source, destination, form.password(), execute, include, exclude);
          default -> throw new BitferryError("unknown task type '" + form.type() + "'");
        };
        if (form.encryption() != null && task instanceof Rclone rclone) {
          if (form.encryption().equals("encrypt")) {
            rclone.encrypt(form.password(), null, Rclone.Encryption.options(form.profile()));
          } else {
            rclone.decrypt(form.password(), null, Rclone.Encryption.options(form.profile()));
          }
        }
        Task candidate = task;
        if (manager.volumes().values().stream().noneMatch(v -> candidate.refersVolume(v.tag()))) {
          throw new BitferryError("task does not belong to any registered volume; it cannot be persisted");
        }
        manager.commit();
        logs.log("task #" + candidate.tag() + " created: " + candidate.describe());
        context.fireModelChanged();
      } catch (BitferryError e) {
        if (task != null) context.manager().deregisterTask(task);
        logs.log("task creation failed: " + e.getMessage());
        Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Task creation failed", e.getMessage()));
      }
    });
  }

  // The comma-separated specifier list into the pattern list
  private static List<String> patterns(String spec) {
    List<String> patterns = new ArrayList<>();
    if (spec != null && !spec.isBlank()) {
      for (String part : spec.split(",")) {
        part = part.trim();
        if (!part.isEmpty()) patterns.add(part);
      }
    }
    return patterns;
  }

  /// Confirm the action and run it on the context's operations thread surfacing
  /// the [BitferryError] failures into the log and the error alert. Shared by the
  /// dialogs and the per-tab management actions.
  static void confirmAndRun(AppContext context, String title, String text, Runnable action, LogController logs) {
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
    confirm.setTitle(title);
    confirm.setHeaderText(null);
    confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(x ->
      context.operations().execute(() -> {
        try {
          action.run();
        } catch (BitferryError e) {
          logs.log(title + " failed: " + e.getMessage());
          Platform.runLater(() -> errorAlert(title + " failed", e.getMessage()));
        }
      }));
  }

  /// The error alert dialog (the failures also travel into the general log).
  static void errorAlert(String title, String text) {
    Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.show();
  }

  private static void alert(Alert.AlertType type, String title, String text) {
    Alert alert = new Alert(type, text, ButtonType.OK);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.show();
  }

  /// The new task form: the type-dependent rows are shown/hidden and validated
  /// according to the selected task type.
  private static final class TaskForm {

    /// The volume selector entry carrying the display form.
    private record VolumeChoice(Volume volume) {
      @Override
      public String toString() {
        return "#" + volume.tag() + "  —  " + volume.root();
      }
    }

    // One labeled grid row; the visibility is toggled per row as a whole
    private record FormRow(String label, Node control, BooleanSupplier visible) {}

    private final AppContext context;
    private final ComboBox<String> type = new ComboBox<>();
    private final TextField source = new TextField();
    private final TextField destination = new TextField();
    private final TextField include = new TextField();
    private final TextField exclude = new TextField();
    private final TextField execute = new TextField();
    private final ComboBox<String> encryption = new ComboBox<>();
    private final TextField profile = new TextField("@default");
    private final PasswordField cryptPassword = new PasswordField();
    private final PasswordField cryptPasswordConfirm = new PasswordField();
    private final PasswordField password = new PasswordField();
    private final PasswordField passwordConfirm = new PasswordField();
    private final CheckBox format = new CheckBox("initialize the repository over the existing data");
    private final TextField serve = new TextField();

    private final List<FormRow> rows = new ArrayList<>();
    private final GridPane grid = new GridPane();

    TaskForm(AppContext context) {
      this.context = context;
      type.getItems().setAll("copy", "update", "synchronize", "equalize", "backup", "restore");
      type.getSelectionModel().selectFirst();
      encryption.getItems().setAll("(none)", "encrypt", "decrypt");
      encryption.getSelectionModel().selectFirst();

      source.setPromptText("directory | local:directory | ::directory | :tag:directory");
      destination.setPromptText("directory | local:directory | ::directory | :tag:directory");
      Tooltip.install(source, new Tooltip(ENDPOINT_HINT));
      Tooltip.install(destination, new Tooltip(ENDPOINT_HINT));
      include.setPromptText("comma-separated patterns (optional)");
      exclude.setPromptText("comma-separated patterns or extensions (optional)");
      execute.setPromptText("extra backend options, comma-separated (optional)");
      serve.setPromptText("'<duration>,<count>' (optional)");
      profile.setPrefColumnCount(14);

      // The row visibility predicates re-evaluated upon the type/encryption changes;
      // the encryption applies to the rclone-backed tasks only
      BooleanSupplier restic = () -> "backup".equals(type()) || "restore".equals(type());
      BooleanSupplier backup = () -> "backup".equals(type());
      BooleanSupplier rclone = () -> !restic.getAsBoolean();
      BooleanSupplier encrypted = () -> rclone.getAsBoolean() && encryption() != null;

      rows.add(new FormRow("Type:", type, () -> true));
      rows.add(new FormRow("Source:", withBrowse(source), () -> true));
      rows.add(new FormRow("Destination:", withBrowse(destination), () -> true));
      rows.add(new FormRow("Include:", include, () -> true));
      rows.add(new FormRow("Exclude:", exclude, () -> true));
      rows.add(new FormRow("Options:", execute, () -> true));
      rows.add(new FormRow("Encryption:", encryption, rclone));
      rows.add(new FormRow("Profile:", profile, encrypted));
      rows.add(new FormRow("Password:", cryptPassword, encrypted));
      rows.add(new FormRow("Confirm:", cryptPasswordConfirm, encrypted));
      rows.add(new FormRow("Password:", password, restic));
      rows.add(new FormRow("Confirm:", passwordConfirm, restic));
      rows.add(new FormRow("Format:", format, backup));
      rows.add(new FormRow("Serve:", serve, backup));

      grid.setHgap(8);
      grid.setVgap(8);
      grid.setPadding(new Insets(8));
      relayout();

      type.valueProperty().addListener((x, was, now) -> relayout());
      encryption.valueProperty().addListener((x, was, now) -> relayout());
    }

    private Pane pane() {
      return grid;
    }

    // Lay the visible rows out consecutively; the hidden rows collapse entirely
    private void relayout() {
      grid.getChildren().clear();
      int row = 0;
      for (FormRow formRow : rows) {
        boolean visible = formRow.visible().getAsBoolean();
        Label label = new Label(formRow.label());
        label.setVisible(visible);
        label.setManaged(visible);
        formRow.control().setVisible(visible);
        formRow.control().setManaged(visible);
        if (visible) {
          grid.add(label, 0, row);
          grid.add(formRow.control(), 1, row);
          GridPane.setHgrow(formRow.control(), Priority.ALWAYS);
          row++;
        }
      }
    }

    private Node withBrowse(TextField field) {
      // The plain directory selector: the bare path attaches to the encompassing volume
      Button browse = new Button("…");
      Tooltip.install(browse, new Tooltip("Browse the local filesystem — the bare path attaches to the encompassing volume"));
      browse.setOnAction(x -> {
        File chosen = browseDirectory(field, new File(System.getProperty("user.home")), "Select the local directory");
        if (chosen != null) field.setText(chosen.getAbsolutePath());
      });
      // The volume-rooted selector: starts at the respective volume root filling the
      // volume-relative specifier
      Button browseVolume = new Button("Vol…");
      Tooltip.install(browseVolume, new Tooltip("Browse within a registered volume — starts at the volume root, fills the volume-relative ':tag:path' specifier"));
      browseVolume.setOnAction(x -> {
        List<Volume> volumes = new ArrayList<>(context.manager().liveVolumes().values());
        if (volumes.isEmpty()) {
          alert(Alert.AlertType.WARNING, "No volumes", "No volumes are registered — reload the manager or create one first.");
          return;
        }
        Volume volume = chooseVolume(volumes);
        if (volume == null) return;
        File start = java.nio.file.Files.isDirectory(volume.root())
          ? volume.root().toFile() : new File(System.getProperty("user.home"));
        File chosen = browseDirectory(field, start, "Select the directory within volume #" + volume.tag());
        if (chosen == null) return;
        Path root = volume.root();
        Path path = chosen.toPath();
        // The browsed path outside the volume root falls back to the bare form
        field.setText(path.startsWith(root)
          ? ":" + volume.tag() + ":" + root.relativize(path).toString().replace('\\', '/')
          : path.toString());
      });
      HBox box = new HBox(6, field, browse, browseVolume);
      HBox.setHgrow(field, Priority.ALWAYS);
      return box;
    }

    // Show the directory chooser starting at the specified directory (null on dismissal)
    private File browseDirectory(TextField field, File start, String title) {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle(title);
      if (start.isDirectory()) chooser.setInitialDirectory(start);
      return chooser.showDialog(field.getScene() == null ? null : field.getScene().getWindow());
    }

    // The volume choice dialog among the registered volumes (null on the dismissal)
    private Volume chooseVolume(List<Volume> volumes) {
      List<VolumeChoice> choices = volumes.stream().map(VolumeChoice::new).toList();
      ChoiceDialog<VolumeChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
      dialog.setTitle("Select the volume");
      dialog.setHeaderText("Browse within a registered volume");
      dialog.setContentText("Volume:");
      return dialog.showAndWait().map(VolumeChoice::volume).orElse(null);
    }

    String type() {
      return type.getValue();
    }

    String source() {
      return source.getText().trim();
    }

    String destination() {
      return destination.getText().trim();
    }

    String include() {
      return include.getText();
    }

    String exclude() {
      return exclude.getText();
    }

    String execute() {
      String value = execute.getText().trim();
      return value.isEmpty() ? null : value;
    }

    /// The configured encryption mode or null.
    String encryption() {
      String value = encryption.getValue();
      return "(none)".equals(value) ? null : value;
    }

    String profile() {
      return profile.getText().trim();
    }

    /// The password of the active mode (the restic repository or the crypt one).
    String password() {
      return resticMode() ? password.getText() : cryptPassword.getText();
    }

    private boolean resticMode() {
      return "backup".equals(type()) || "restore".equals(type());
    }

    Boolean format() {
      return "backup".equals(type()) && format.isSelected() ? Boolean.TRUE : null;
    }

    String serve() {
      String value = serve.getText().trim();
      return value.isEmpty() ? null : value;
    }

    // Validate the form raising [BitferryError] on the missing required fields
    void validate() {
      if (source().isEmpty()) throw new BitferryError("source endpoint must be specified");
      if (destination().isEmpty()) throw new BitferryError("destination endpoint must be specified");
      if (resticMode()) {
        if (password.getText().isEmpty()) throw new BitferryError("password must be specified");
        if (!password.getText().equals(passwordConfirm.getText())) throw new BitferryError("passwords do not match");
      }
      if (encryption() != null) {
        if (cryptPassword.getText().isEmpty()) throw new BitferryError("encryption password must be specified");
        if (!cryptPassword.getText().equals(cryptPasswordConfirm.getText())) throw new BitferryError("encryption passwords do not match");
      }
    }
  }
}
