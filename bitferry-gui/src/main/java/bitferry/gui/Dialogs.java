package bitferry.gui;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import bitferry.vault.KeyringVault;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// The creation and modification dialogs: the new volume and the unified task forms.
/// The heavy lifting (endpoint parsing, task construction, mutation, commit) travels
/// through the context's operations thread mirroring the CLI command flows; failures
/// are surfaced both in the error alerts and the general log.
final class Dialogs {

  private Dialogs() {}

  /// The endpoint specifier hint shared by the task dialog fields.
  private static final String ENDPOINT_HINT = """
    Endpoint specifier:
      directory        bare path, attached to the encompassing volume
      local:directory  explicit local path (outside the volumes)
      ::directory      resolved against the intact volume
      :tag:directory   relative to the volume matched by (partial) tag""";

  /// Presets for exclude pattern lists.
  private static final Map<String, List<String>> EXCLUDE_PROFILES = new LinkedHashMap<>();
  static {
    EXCLUDE_PROFILES.put("@common-temp", List.of("*.tmp", "*~", ".cache/**", "Thumbs.db", ".DS_Store"));
    EXCLUDE_PROFILES.put("@vcs", List.of(".git/**", ".svn/**", ".hg/**"));
    EXCLUDE_PROFILES.put("@build", List.of("build/**", "target/**", "node_modules/**", "*.class", "*.o"));
  }

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
    TaskForm form = new TaskForm(context, null);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("New task");
    dialog.setHeaderText("Create a new data processing task");
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Create");
    dialog.getDialogPane().setContent(form.pane());
    dialog.setResizable(true);
    dialog.getDialogPane().setPrefWidth(620);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    Platform.runLater(() -> {
      dialog.getDialogPane().lookupAll(".label").forEach(node -> {
        if (node instanceof Label l) {
          l.setTextFill(javafx.scene.paint.Color.web("#222222"));
          l.setStyle("-fx-text-fill: #222222;");
        }
      });
    });
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

  /// Show the modify task dialog returning once the form is dismissed; the modification
  /// itself is executed asynchronously on the operations thread.
  static void modifyTask(AppContext context, LogController logs, Task task, BooleanSupplier busy) {
    if (busy.getAsBoolean()) {
      alert(Alert.AlertType.WARNING, "Busy", "A run is in progress — wait for it to finish.");
      return;
    }
    TaskForm form = new TaskForm(context, task);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Modify task #" + task.tag());
    dialog.setHeaderText("Modify task #" + task.tag() + " parameters");
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Save");
    dialog.getDialogPane().setContent(form.pane());
    dialog.setResizable(true);
    dialog.getDialogPane().setPrefWidth(620);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    Platform.runLater(() -> {
      dialog.getDialogPane().lookupAll(".label").forEach(node -> {
        if (node instanceof Label l) {
          l.setTextFill(javafx.scene.paint.Color.web("#222222"));
          l.setStyle("-fx-text-fill: #222222;");
        }
      });
    });
    dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(x -> {
      try {
        form.validate();
      } catch (BitferryError e) {
        alert(Alert.AlertType.ERROR, "Invalid form", e.getMessage());
        return;
      }
      applyTaskModification(context, logs, task, form);
    });
  }

  // Run the task creation on the operations thread; the failures deregister the
  // half-constructed task (the commit aborts and deregisters on its own)
  private static void createTask(AppContext context, LogController logs, TaskForm form) {
    context.operations().execute(() -> {
      Task task = null;
      Manager manager = context.manager();
      try {
        Endpoint source = Endpoints.parse(manager, form.source());
        Endpoint destination = Endpoints.parse(manager, form.destination());
        Task existing = manager.findTask(source, destination);
        if (existing != null) throw new BitferryError("task #" + existing.tag() + " already covers " + existing.describe());
        List<String> include = patterns(form.include());
        List<String> exclude = patterns(form.exclude());
        String execute = form.execute();
        boolean keyring = form.keyring();
        task = switch (form.type()) {
          case "copy" -> new Copy(manager, source, destination, execute, include, exclude);
          case "update" -> new Update(manager, source, destination, execute, include, exclude);
          case "synchronize" -> new Synchronize(manager, source, destination, execute, include, exclude);
          case "equalize" -> new Equalize(manager, source, destination, execute, include, exclude);
          case "backup" -> new Backup(manager, source, destination, form.password(), form.format(),
            execute, form.forget(), form.check(), form.serve(), include, exclude, keyring);
          case "restore" -> new Restore(manager, source, destination, form.password(), execute, include, exclude, keyring);
          default -> throw new BitferryError("unknown task type '" + form.type() + "'");
        };
        if (form.encryption() != null && task instanceof Rclone rclone) {
          if (form.encryption().equals("encrypt")) {
            rclone.encrypt(form.password(), null, Rclone.Encryption.options(form.profile()), keyring);
          } else {
            rclone.decrypt(form.password(), null, Rclone.Encryption.options(form.profile()), keyring);
          }
        }
        Task candidate = task;
        if (manager.volumes().values().stream().noneMatch(v -> candidate.refersVolume(v.tag()))) {
          throw new BitferryError("task does not belong to any registered volume; it cannot be persisted");
        }
        manager.commit();
        logs.log("task #" + candidate.tag() + " created: " + candidate.describe());
        if (keyring && (candidate instanceof Restic || candidate instanceof Rclone rclone && rclone.encryption() != null && rclone.encryption().operation() != null)) {
          if (!KeyringVault.INSTANCE.available()) {
            logs.log("password kept in the volume vault (no usable OS keyring on the host)");
          } else {
            try {
              if (!candidate.vaultVolume().isFixed()) {
                logs.log("password stored in the OS keyring (the vault volume resides on removable media - the token will not travel with it)");
              }
            } catch (BitferryError ignored) {
              // No volume-resident end: the persistence check reports it separately
            }
          }
        }
        context.fireModelChanged();
      } catch (RuntimeException e) {
        // Deregister on the same manager instance the task was built on: the context's
        // manager may have been swapped by a concurrent reload meanwhile
        if (task != null) manager.deregisterTask(task);
        logs.log("task creation failed: " + e.getMessage());
        Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Task creation failed", e.getMessage()));
      }
    });
  }

  // Apply modifications to an existing task on the operations thread
  private static void applyTaskModification(AppContext context, LogController logs, Task task, TaskForm form) {
    context.operations().execute(() -> {
      Manager manager = context.manager();
      try {
        Task current = manager.task(task.tag());
        if (current == null) throw new BitferryError("task #" + task.tag() + " is no longer registered");

        // Rotate repository password if entered (restic only)
        if (form.resticMode() && !form.password().isEmpty()) {
          manager.passwd((Restic) current, form.password());
          logs.log("password updated for task #" + current.tag());
        }

        // Backend execution options
        String execute = form.execute();
        if (current instanceof Rclone rclone) {
          rclone.setProcessOptions(execute);
        } else if (current instanceof Restic restic) {
          restic.setProcessOptions(execute);
        }

        // Backup retention, check, and cadence options
        if (current instanceof Backup backup) {
          backup.setForget(form.forget());
          backup.setCheck(form.check());
          backup.setCadence(form.serve());
        }

        // Include filters
        List<String> include = patterns(form.include());
        if (include.isEmpty()) {
          current.clearInclude();
        } else {
          current.setInclude(include);
        }

        // Exclude filters
        List<String> exclude = patterns(form.exclude());
        if (exclude.isEmpty()) {
          current.clearExclude();
        } else {
          current.setExclude(exclude);
        }

        manager.commit();
        logs.log("task #" + current.tag() + " modified: " + current.describe());
        context.fireModelChanged();
      } catch (RuntimeException e) {
        logs.log("task modification failed: " + e.getMessage());
        Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Task modification failed", e.getMessage()));
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

  /// The unified task form for both creation and modification: type-dependent rows
  /// are dynamically shown/hidden and validated, and immutable fields are disabled
  /// when modifying an existing task.
  private static final class TaskForm {

    /// The volume selector entry carrying the display form.
    private record VolumeChoice(Volume volume) {
      @Override
      public String toString() {
        return "#" + volume.tag() + "  —  " + volume.root();
      }
    }

    private static final javafx.scene.paint.Color DARK_TEXT = javafx.scene.paint.Color.web("#222222");

    private static Label createLabel(String text) {
      Label label = new Label(text);
      label.setTextFill(DARK_TEXT);
      label.setStyle("-fx-text-fill: #222222;");
      return label;
    }

    // One labeled grid row; the visibility is toggled per row as a whole
    private record FormRow(Label label, Node control, BooleanSupplier visible) {
      FormRow(String labelText, Node control, BooleanSupplier visible) {
        this(createLabel(labelText), control, visible);
      }
    }

    private final AppContext context;
    private final Task existing;

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
    private final CheckBox keyring = new CheckBox("store the password in the system keyring");
    private final CheckBox format = new CheckBox("initialize the repository over the existing data");
    private final TextField forget = new TextField();
    private final TextField check = new TextField();
    private final TextField serve = new TextField();

    private final List<FormRow> rows = new ArrayList<>();
    private final GridPane grid = new GridPane();

    TaskForm(AppContext context, Task existing) {
      this.context = context;
      this.existing = existing;

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
      forget.setPromptText("retention policy (e.g. @default) (optional)");
      check.setPromptText("integrity check options (e.g. @default, @full) (optional)");
      serve.setPromptText("e.g. @default or '<duration>,<count>' (optional)");
      profile.setPrefColumnCount(14);

      keyring.setDisable(!KeyringVault.INSTANCE.available());
      keyring.setTextFill(DARK_TEXT);
      keyring.setStyle("-fx-text-fill: #222222;");
      format.setTextFill(DARK_TEXT);
      format.setStyle("-fx-text-fill: #222222;");
      Tooltip.install(keyring, new Tooltip(KeyringVault.INSTANCE.available()
        ? "Store the password token in the OS keyring instead of the volume storage (recommended for the fixed volumes; the keyring-stored tokens do not travel with removable media)"
        : "No usable OS keyring on this host — the volume storage keeps serving"));

      // Populate existing task values and disable immutable fields
      if (existing != null) {
        String existingType = switch (existing) {
          case Copy ignored -> "copy";
          case Update ignored -> "update";
          case Synchronize ignored -> "synchronize";
          case Equalize ignored -> "equalize";
          case Backup ignored -> "backup";
          case Restore ignored -> "restore";
          default -> "";
        };
        type.setValue(existingType);
        type.setDisable(true);

        source.setText(existing.source().describe());
        source.setDisable(true);
        destination.setText(existing.destination().describe());
        destination.setDisable(true);

        if (!existing.include().isEmpty()) {
          include.setText(String.join(", ", existing.include()));
        }
        if (!existing.exclude().isEmpty()) {
          exclude.setText(String.join(", ", existing.exclude()));
        }

        List<String> curOpts = existing instanceof Rclone rclone ? rclone.processOptions()
          : existing instanceof Restic restic ? restic.processOptions() : List.of();
        if (!curOpts.isEmpty()) {
          execute.setText(String.join(", ", curOpts));
        }

        if (existing instanceof Rclone rclone) {
          Rclone.Encryption enc = rclone.encryption();
          if (enc != null && enc.operation() != null) {
            encryption.setValue(enc.operation());
            if (enc.options() != null && !enc.options().isEmpty()) {
              profile.setText(String.join(", ", enc.options()));
            }
          } else {
            encryption.setValue("(none)");
          }
          encryption.setDisable(true);
          profile.setDisable(true);
          cryptPassword.setDisable(true);
          cryptPasswordConfirm.setDisable(true);
        }

        if (existing instanceof Backup backup) {
          if (backup.forget() != null && !backup.forget().isEmpty()) {
            forget.setText(String.join(", ", backup.forget()));
          }
          if (backup.check() != null && !backup.check().isEmpty()) {
            check.setText(String.join(", ", backup.check()));
          }
          if (backup.cadence() != null) {
            serve.setText(backup.cadence().format());
          }
          format.setDisable(true);
          keyring.setDisable(true);
        }

        if (existing instanceof Restore) {
          keyring.setDisable(true);
        }

        if (existing instanceof Restic) {
          password.setPromptText("leave empty to keep current password");
          passwordConfirm.setPromptText("confirm new password");
        }
      }

      // Visibility predicates re-evaluated on type/encryption changes
      BooleanSupplier restic = () -> "backup".equals(type()) || "restore".equals(type());
      BooleanSupplier backup = () -> "backup".equals(type());
      BooleanSupplier rclone = () -> !restic.getAsBoolean();
      BooleanSupplier encrypted = () -> rclone.getAsBoolean() && encryption() != null;
      BooleanSupplier cryptPasswordVisible = () -> existing == null && encrypted.getAsBoolean();
      BooleanSupplier formatVisible = () -> existing == null && backup.getAsBoolean();
      BooleanSupplier keyringVisible = () -> existing == null && (restic.getAsBoolean() || encrypted.getAsBoolean());

      rows.add(new FormRow("Type:", type, () -> true));
      rows.add(new FormRow("Source:", withBrowse(source), () -> true));
      rows.add(new FormRow("Destination:", withBrowse(destination), () -> true));
      rows.add(new FormRow("Include:", withEditList(include, "Include Patterns", "Edit include patterns", () -> null), () -> true));
      rows.add(new FormRow("Exclude:", withEditList(exclude, "Exclude Patterns", "Edit exclude patterns", () -> EXCLUDE_PROFILES), () -> true));
      rows.add(new FormRow("Options:", withEditList(execute, "Execution Options", "Edit backend execution options", this::executeProfiles), () -> true));
      rows.add(new FormRow("Encryption:", encryption, rclone));
      rows.add(new FormRow("Profile:", profile, encrypted));
      rows.add(new FormRow("Password:", cryptPassword, cryptPasswordVisible));
      rows.add(new FormRow("Confirm:", cryptPasswordConfirm, cryptPasswordVisible));
      rows.add(new FormRow("Password:", password, restic));
      rows.add(new FormRow("Confirm:", passwordConfirm, restic));
      rows.add(new FormRow("Keyring:", keyring, keyringVisible));
      rows.add(new FormRow("Retention:", withEditList(forget, "Retention Policy", "Edit snapshot retention policy options", () -> Backup.FORGET), backup));
      rows.add(new FormRow("Check:", withEditList(check, "Integrity Check", "Edit repository integrity check options", () -> Backup.CHECK), backup));
      rows.add(new FormRow("Format:", format, formatVisible));
      rows.add(new FormRow("Serve:", serve, backup));

      grid.setHgap(8);
      grid.setVgap(8);
      grid.setPadding(new Insets(10));
      ColumnConstraints col0 = new ColumnConstraints();
      col0.setMinWidth(95);
      col0.setPrefWidth(95);
      col0.setHalignment(HPos.RIGHT);
      ColumnConstraints col1 = new ColumnConstraints();
      col1.setHgrow(Priority.ALWAYS);
      grid.getColumnConstraints().setAll(col0, col1);

      relayout();

      type.valueProperty().addListener((x, was, now) -> relayout());
      encryption.valueProperty().addListener((x, was, now) -> relayout());
    }

    private Node pane() {
      ScrollPane scroll = new ScrollPane(grid);
      scroll.setFitToWidth(true);
      scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
      scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
      scroll.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
      scroll.setMaxHeight(480);
      return scroll;
    }

    // Lay visible rows out consecutively and request scene sizing
    private void relayout() {
      grid.getChildren().clear();
      int row = 0;
      for (FormRow formRow : rows) {
        boolean visible = formRow.visible().getAsBoolean();
        formRow.label().setVisible(visible);
        formRow.label().setManaged(visible);
        formRow.control().setVisible(visible);
        formRow.control().setManaged(visible);
        if (visible) {
          grid.add(formRow.label(), 0, row);
          grid.add(formRow.control(), 1, row);
          GridPane.setHgrow(formRow.control(), Priority.ALWAYS);
          row++;
        }
      }
      Platform.runLater(() -> {
        if (grid.getScene() != null && grid.getScene().getWindow() != null) {
          grid.getScene().getWindow().sizeToScene();
        }
      });
    }

    private Node withBrowse(TextField field) {
      Button browse = new Button("…");
      Tooltip.install(browse, new Tooltip("Browse the local filesystem — the bare path attaches to the encompassing volume"));
      browse.setOnAction(x -> {
        File chosen = browseDirectory(field, new File(System.getProperty("user.home")), "Select the local directory");
        if (chosen != null) field.setText(chosen.getAbsolutePath());
      });

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
        field.setText(path.startsWith(root)
          ? ":" + volume.tag() + ":" + root.relativize(path).toString().replace('\\', '/')
          : path.toString());
      });

      if (existing != null) {
        browse.setDisable(true);
        browseVolume.setDisable(true);
      }

      HBox box = new HBox(6, field, browse, browseVolume);
      HBox.setHgrow(field, Priority.ALWAYS);
      return box;
    }

    private Node withEditList(TextField field, String title, String header, Supplier<Map<String, List<String>>> profilesSupplier) {
      Button edit = new Button("Edit…");
      Tooltip.install(edit, new Tooltip("Open list editor for item-by-item editing, reordering, and profile presets"));
      edit.disableProperty().bind(field.disableProperty());
      edit.setOnAction(e -> {
        List<String> current = patterns(field.getText());
        Map<String, List<String>> profiles = profilesSupplier != null ? profilesSupplier.get() : null;
        if (current.size() == 1 && current.get(0).startsWith("@") && profiles != null) {
          String rawKey = current.get(0);
          String unAt = rawKey.substring(1);
          if (profiles.containsKey(unAt)) {
            current = profiles.get(unAt);
          } else if (profiles.containsKey(rawKey)) {
            current = profiles.get(rawKey);
          }
        }
        Window window = field.getScene() != null ? field.getScene().getWindow() : null;
        ItemListDialog.show(window, title, header, current, profiles)
          .ifPresent(list -> field.setText(String.join(", ", list)));
      });
      HBox box = new HBox(6, field, edit);
      HBox.setHgrow(field, Priority.ALWAYS);
      return box;
    }

    private Map<String, List<String>> executeProfiles() {
      String t = type();
      if ("backup".equals(t)) {
        return Restic.PROCESS;
      } else if ("restore".equals(t)) {
        return Restore.PROCESS;
      } else {
        return Rclone.PROCESS;
      }
    }

    private File browseDirectory(TextField field, File start, String title) {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle(title);
      if (start.isDirectory()) chooser.setInitialDirectory(start);
      return chooser.showDialog(field.getScene() == null ? null : field.getScene().getWindow());
    }

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
      if (value.isEmpty()) return existing == null ? null : "-";
      return value;
    }

    String encryption() {
      String value = encryption.getValue();
      return "(none)".equals(value) ? null : value;
    }

    String profile() {
      return profile.getText().trim();
    }

    String password() {
      return resticMode() ? password.getText() : cryptPassword.getText();
    }

    private boolean resticMode() {
      return "backup".equals(type()) || "restore".equals(type());
    }

    Boolean format() {
      return "backup".equals(type()) && format.isSelected() ? Boolean.TRUE : null;
    }

    String forget() {
      String value = forget.getText().trim();
      if (value.isEmpty()) return existing == null ? null : "-";
      return value;
    }

    String check() {
      String value = check.getText().trim();
      if (value.isEmpty()) return existing == null ? null : "-";
      return value;
    }

    String serve() {
      String value = serve.getText().trim();
      if (value.isEmpty()) return existing == null ? null : "-";
      return value;
    }

    boolean keyring() {
      return keyring.isSelected() && !keyring.isDisable();
    }

    void validate() {
      if (source().isEmpty()) throw new BitferryError("source endpoint must be specified");
      if (destination().isEmpty()) throw new BitferryError("destination endpoint must be specified");
      if (existing == null) {
        if (resticMode()) {
          if (password.getText().isEmpty()) throw new BitferryError("password must be specified");
          if (!password.getText().equals(passwordConfirm.getText())) throw new BitferryError("passwords do not match");
        }
        if (encryption() != null) {
          if (cryptPassword.getText().isEmpty()) throw new BitferryError("encryption password must be specified");
          if (!cryptPassword.getText().equals(cryptPasswordConfirm.getText())) throw new BitferryError("encryption passwords do not match");
        }
      } else {
        if (resticMode()) {
          if (!password.getText().isEmpty() || !passwordConfirm.getText().isEmpty()) {
            if (password.getText().isEmpty()) throw new BitferryError("password must be specified");
            if (!password.getText().equals(passwordConfirm.getText())) throw new BitferryError("passwords do not match");
          }
        }
      }
    }
  }
}
