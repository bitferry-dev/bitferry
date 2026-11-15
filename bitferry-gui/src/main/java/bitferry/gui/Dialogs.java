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
    File root = chooser.showDialog(context.ownerWindow);
    if (root == null) return;
    Path path = root.toPath();
    confirmAndRun(context, "Create volume", "Create a volume with the root\n  " + path + " ?", () -> {
      Manager manager = context.manager();
      Volume volume = new Volume(manager, path);
      manager.commit();
      logs.log("volume " + volume.tag() + " created at " + volume.root());
      context.fireModelChanged();
    }, logs);
  }

  /// Show the new task dialog returning once the form is dismissed; the creation
  /// itself is asynchronous.
  static void newTask(AppContext context, LogController logs, BooleanSupplier busy) {
    if (busy.getAsBoolean()) {
      alert(context, Alert.AlertType.WARNING, "Busy", "A run is in progress — wait for it to finish.");
      return;
    }
    TaskForm form = new TaskForm(context, null);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("New task");
    dialog.setHeaderText("Create a new data processing task");
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Create");
    dialog.getDialogPane().setContent(form.pane());
    // No fixed pane width: the form sizes the scroller to its content (scaled with
    // the font size) and the dialog tracks it through the relayout's sizeToScene;
    // the dialog stays resizable for the manual widening
    dialog.setResizable(true);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    Platform.runLater(() -> {
      dialog.getDialogPane().lookupAll(".label").forEach(node -> {
        if (node instanceof Label l) {
          l.setTextFill(javafx.scene.paint.Color.web("#222222"));
          l.setStyle("-fx-text-fill: #222222;");
        }
      });
    });
    // The validation rides on the OK button's action filter: a failing form keeps
    // the dialog open with the typed input intact (the post-close validation would
    // discard everything the user typed)
    Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
    ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
      try {
        form.validate();
      } catch (BitferryError e) {
        alert(context, Alert.AlertType.ERROR, "Invalid form", e.getMessage());
        event.consume();
      }
    });
    dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(x -> createTask(context, logs, form));
  }

  /// Show the modify task dialog returning once the form is dismissed; the modification
  /// itself is executed asynchronously on the operations thread.
  static void modifyTask(AppContext context, LogController logs, Task task, BooleanSupplier busy) {
    if (busy.getAsBoolean()) {
      alert(context, Alert.AlertType.WARNING, "Busy", "A run is in progress — wait for it to finish.");
      return;
    }
    TaskForm form = new TaskForm(context, task);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Modify task " + task.tag());
    dialog.setHeaderText("Modify task " + task.tag() + " parameters");
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Save");
    dialog.getDialogPane().setContent(form.pane());
    // No fixed pane width: the form sizes the scroller to its content (scaled with
    // the font size) and the dialog tracks it through the relayout's sizeToScene;
    // the dialog stays resizable for the manual widening
    dialog.setResizable(true);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    Platform.runLater(() -> {
      dialog.getDialogPane().lookupAll(".label").forEach(node -> {
        if (node instanceof Label l) {
          l.setTextFill(javafx.scene.paint.Color.web("#222222"));
          l.setStyle("-fx-text-fill: #222222;");
        }
      });
    });
    // The validation rides on the OK button's action filter (see the creation dialog)
    Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
    ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
      try {
        form.validate();
      } catch (BitferryError e) {
        alert(context, Alert.AlertType.ERROR, "Invalid form", e.getMessage());
        event.consume();
      }
    });
    dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(x -> applyTaskModification(context, logs, task, form));
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
        if (existing != null) throw new BitferryError("task " + existing.tag() + " already covers " + existing.describe());
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
        if (form.encryption() != null && task.supportsEncryption()) {
          task.setEncryption(form.encryption(), form.password(), null, Rclone.Encryption.options(form.profile()), keyring);
        }
        task.setManual(form.manual());
        Task candidate = task;
        if (manager.volumes().values().stream().noneMatch(v -> candidate.refersVolume(v.tag()))) {
          throw new BitferryError("task does not belong to any registered volume; it cannot be persisted");
        }
        manager.commit();
        logs.log("task " + candidate.tag() + " created: " + candidate.describe());
        if (keyring && candidate.usesVault()) {
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
        Platform.runLater(() -> errorAlert(context, "Task creation failed", e.getMessage()));
      }
    });
  }

  // Apply modifications to an existing task on the operations thread
  private static void applyTaskModification(AppContext context, LogController logs, Task task, TaskForm form) {
    context.operations().execute(() -> {
      Manager manager = context.manager();
      try {
        Task current = manager.task(task.tag());
        if (current == null) throw new BitferryError("task " + task.tag() + " is no longer registered");

        // Rotate repository password if entered (restic only)
        if (form.resticMode() && !form.password().isEmpty()) {
          manager.passwd((Restic) current, form.password());
          logs.log("password updated for task " + current.tag());
        }

        // Backend execution options (the resolution rides the task's profile route)
        current.setProcessOptions(form.execute());

        // Backup retention, check, and cadence options
        current.applyPolicyOptions(form.forget(), form.check(), form.serve());

        // The manual flag (the mass-run participation): the dialog checkbox is the
        // editor (the grid checkbox mirrors it)
        current.setManual(form.manual());

        // Include filters. The backup tasks filter by exclusion only (restic backup
        // carries no include patterns): the handling is skipped and the form field is
        // disabled — the stored legacy filters, if any, are managed via the CLI
        // --clear-include
        // FIXME get rid of the selector by defining include on the restore task only
        if (current.acceptsIncludeFilters()) {
          List<String> include = patterns(form.include());
          if (include.isEmpty()) {
            current.clearInclude();
          } else {
            current.setInclude(include);
          }
        }

        // Exclude filters
        List<String> exclude = patterns(form.exclude());
        if (exclude.isEmpty()) {
          current.clearExclude();
        } else {
          current.setExclude(exclude);
        }

        manager.commit();
        logs.log("task " + current.tag() + " modified: " + current.describe());
        context.fireModelChanged();
      } catch (RuntimeException e) {
        logs.log("task modification failed: " + e.getMessage());
        Platform.runLater(() -> errorAlert(context, "Task modification failed", e.getMessage()));
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
  /// the failures into the log and the error alert. Shared by the dialogs and the
  /// per-tab management actions.
  static void confirmAndRun(AppContext context, String title, String text, Runnable action, LogController logs) {
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
    confirm.setTitle(title);
    confirm.setHeaderText(null);
    if (context.ownerWindow != null) confirm.initOwner(context.ownerWindow);
    confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(x ->
      context.operations().execute(() -> {
        try {
          action.run();
        } catch (RuntimeException e) {
          // The whole unchecked family surfaces (the BitferryError along with the
          // invalid-path/security-style failures): neither the log nor the alert
          // may be silently skipped
          logs.log(title + " failed: " + e.getMessage());
          Platform.runLater(() -> errorAlert(context, title + " failed", e.getMessage()));
        }
      }));
  }

  /// The error alert dialog (the failures also travel into the general log).
  static void errorAlert(AppContext context, String title, String text) {
    Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
    alert.setTitle(title);
    alert.setHeaderText(null);
    if (context.ownerWindow != null) alert.initOwner(context.ownerWindow);
    alert.show();
  }

  private static void alert(AppContext context, Alert.AlertType type, String title, String text) {
    Alert alert = new Alert(type, text, ButtonType.OK);
    alert.setTitle(title);
    alert.setHeaderText(null);
    if (context.ownerWindow != null) alert.initOwner(context.ownerWindow);
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
        return volume.tag() + "  —  " + volume.root();
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

    /// Build a labeled row installing the truncation self-healing: the label column
    /// pinning measures the labels in the constructor — before the scene CSS applies
    /// (the DPI-scaled font lands with the first pulse) — and an under-width column
    /// clips the right-aligned labels at their left. A truncation re-runs the relayout,
    /// re-pinning the column with the post-CSS metrics.
    private FormRow formRow(String labelText, Node control, BooleanSupplier visible) {
      Label label = createLabel(labelText);
      label.textTruncatedProperty().addListener((x, was, now) -> {
        if (now) relayout();
      });
      return new FormRow(label, control, visible);
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
    private final TextField profile = new TextField();
    private final PasswordField cryptPassword = new PasswordField();
    private final PasswordField cryptPasswordConfirm = new PasswordField();
    private final PasswordField password = new PasswordField();
    private final PasswordField passwordConfirm = new PasswordField();
    // The password storage location selector: the two exclusive destinations stated
    // explicitly — the machine-bound OS keyring vs the per-volume vault traveling with
    // the volume storage (laid out horizontally under the "Password storage:" label)
    private final RadioButton keyringChoice = new RadioButton("OS keyring — stays on this host");
    private final RadioButton vaultChoice = new RadioButton("volume vault — travels with the volume");
    private final Node keyring = new HBox(12, keyringChoice, vaultChoice);
    private final CheckBox format = new CheckBox("initialize the repository over the existing data");
    private final CheckBox manual = new CheckBox("exclude from mass runs");
    private final TextField forget = new TextField();
    private final TextField check = new TextField();
    private final TextField serve = new TextField();

    private final List<FormRow> rows = new ArrayList<>();
    private final GridPane grid = new GridPane();
    // The scroll pane wrapping the grid, created up front: the relayout sizes it to
    // the grid's natural (content-derived) width before the dialog shows
    private final ScrollPane scroller = new ScrollPane(grid);

    // The label column is pinned per relayout to the widest visible label's width:
    // the labels can never truncate (the entry fields absorb any width deficit)
    private final ColumnConstraints labelColumn = new ColumnConstraints();
    private final ColumnConstraints controlColumn = new ColumnConstraints();

    // The auto-loaded (never user-edited) pre-filled defaults keyed by the field:
    // switching the operation replaces them with the new operation's defaults while
    // the user-edited content stays intact
    private final Map<TextField, String> autoLoaded = new HashMap<>();

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
      profile.setPromptText("extra crypt options, comma-separated (optional)");
      profile.setPrefColumnCount(14);
      Tooltip.install(profile, new Tooltip("The extra crypt options passed to rclone (comma-separated); the filename encoding presets (@default, @extended) load via Edit…"));

      // The volume vault is the default destination; the keyring choice is offered (and
      // honored) only when the OS keyring is usable on this host — otherwise it is grayed
      // out and the vault stays the only selectable option
      ToggleGroup passwordStorage = new ToggleGroup();
      keyringChoice.setToggleGroup(passwordStorage);
      vaultChoice.setToggleGroup(passwordStorage);
      vaultChoice.setSelected(true);
      keyringChoice.setDisable(!KeyringVault.INSTANCE.available());
      keyringChoice.setTextFill(DARK_TEXT);
      keyringChoice.setStyle("-fx-text-fill: #222222;");
      vaultChoice.setTextFill(DARK_TEXT);
      vaultChoice.setStyle("-fx-text-fill: #222222;");
      Tooltip.install(keyringChoice, new Tooltip(KeyringVault.INSTANCE.available()
        ? "The OS keyring (Windows Credential Manager, macOS Keychain, GNOME Keyring): machine- and user-bound, recommended for the fixed volumes — the tokens do not travel with the removable media"
        : "No usable OS keyring on this host — the volume vault keeps serving"));
      Tooltip.install(vaultChoice, new Tooltip("The vault section of the storage of the volume hosting the unencrypted end: the token travels with the volume (recommended for the removable media)"));
      format.setTextFill(DARK_TEXT);
      format.setStyle("-fx-text-fill: #222222;");

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

        List<String> curOpts = existing.processOptions();
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

        manual.setSelected(existing.isManual());

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

      rows.add(formRow("Type:", type, () -> true));
      rows.add(formRow("Source:", withBrowse(source), () -> true));
      rows.add(formRow("Destination:", withBrowse(destination), () -> true));
      // The include control is disabled for the backup type: restic backup filters by
      // exclusion only, so the entered patterns would be silently ignored
      Node includeControl = withEditList(include, "Include Patterns", "Edit include patterns", () -> null);
      includeControl.disableProperty().bind(type.valueProperty().isEqualTo("backup"));
      rows.add(formRow("Include:", includeControl, () -> true));
      rows.add(formRow("Exclude:", withEditList(exclude, "Exclude Patterns", "Edit exclude patterns", () -> EXCLUDE_PROFILES), () -> true));
      rows.add(formRow("Options:", withEditList(execute, "Execution Options", "Edit backend execution options", this::executeProfiles), () -> true));
      rows.add(formRow("Encryption:", encryption, rclone));
      rows.add(formRow("Encryption options:", withEditList(profile, "Encryption Options",
        "Edit encryption options (the crypt filename encoding profiles or the raw comma-separated options)", () -> Rclone.PROFILE), encrypted));
      rows.add(formRow("Password:", cryptPassword, cryptPasswordVisible));
      rows.add(formRow("Confirm:", cryptPasswordConfirm, cryptPasswordVisible));
      rows.add(formRow("Password:", password, restic));
      rows.add(formRow("Confirm:", passwordConfirm, restic));
      rows.add(formRow("Password storage:", keyring, keyringVisible));
      rows.add(formRow("Retention:", withEditList(forget, "Retention Policy", "Edit snapshot retention policy options", () -> Backup.FORGET), backup));
      rows.add(formRow("Check:", withEditList(check, "Integrity Check", "Edit repository integrity check options", () -> Backup.CHECK), backup));
      rows.add(formRow("Format:", format, formatVisible));
      rows.add(formRow("Serve:", serve, backup));
      rows.add(formRow("Manual:", manual, () -> true));

      grid.setHgap(8);
      grid.setVgap(8);
      grid.setPadding(new Insets(10));
      labelColumn.setHalignment(HPos.RIGHT);
      controlColumn.setHgrow(Priority.ALWAYS);
      grid.getColumnConstraints().setAll(labelColumn, controlColumn);

      // The new-task option entries start from the operation's default profiles
      loadDefaultProfiles();

      relayout();

      // The operation/encryption changes refresh the empty (never user-edited) option
      // entries with the new operation's defaults and lay the rows out
      type.valueProperty().addListener((x, was, now) -> { loadDefaultProfiles(); relayout(); });
      encryption.valueProperty().addListener((x, was, now) -> { loadDefaultProfiles(); relayout(); });
    }

    private Node pane() {
      // The first relayout ran before the scene existed (the pre-CSS font measurement):
      // re-run it once the dialog shows, so the column widths land with the real metrics
      scroller.sceneProperty().addListener((x, was, now) -> {
        if (now != null) Platform.runLater(this::relayout);
      });
      scroller.setFitToWidth(true);
      // AS_NEEDED: the last-resort valve — when even the pinned minimums cannot fit
      // (the screen clamping the window growth), the user scrolls instead of the
      // content truncating
      scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
      scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
      scroller.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
      scroller.setMaxHeight(480);
      return scroller;
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
      // The label column pinned to the widest visible label (measured, so it scales
      // with the font size): when the viewport squeezes the grid (the vertical scrollbar
      // appearing, the screen clamping the window growth), the deficit is absorbed by
      // the entry fields — the labels can never truncate
      double widestLabel = 0;
      for (FormRow formRow : rows) {
        if (formRow.label().isVisible()) {
          widestLabel = Math.max(widestLabel, formRow.label().prefWidth(-1));
        }
      }
      labelColumn.setMinWidth(widestLabel);
      labelColumn.setPrefWidth(widestLabel);

      // The scroll pane carries a content-derived width so the dialog tracks its
      // contents: the width follows the widest visible row and the font size instead
      // of a fixed pixel value (a floor keeps the sparse layouts comfortable). The
      // vertical scrollbar allowance keeps the window wide enough for the scrollbar
      // not to squeeze the columns. The row toggles re-measure it, and the sizeToScene
      // below carries it to the window.
      boolean verticalScrollbar = grid.prefHeight(-1) > 460;
      scroller.setPrefWidth(Math.max(grid.prefWidth(-1) + (verticalScrollbar ? 14 : 0), 440));

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
          alert(context, Alert.AlertType.WARNING, "No volumes", "No volumes are registered — reload the manager or create one first.");
          return;
        }
        Volume volume = chooseVolume(volumes);
        if (volume == null) return;
        File start = java.nio.file.Files.isDirectory(volume.root())
          ? volume.root().toFile() : new File(System.getProperty("user.home"));
        File chosen = browseDirectory(field, start, "Select the directory within volume " + volume.tag());
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

    /// Pre-load the @default profile's concrete options into an empty (or untouched
    /// auto-loaded) entry field: the new-task entries start from the operation's
    /// defaults stated explicitly (the real entries, not the @-tags). The fields
    /// without a non-empty @default profile (the restic backup execution options,
    /// the empty check defaults, the exclude patterns) stay empty. The auto-loaded
    /// content follows the operation: switching the type replaces it with the new
    /// operation's defaults (clearing it when the new default is empty), while the
    /// user-edited content stays intact.
    private void loadDefaultProfile(TextField field, Map<String, List<String>> profiles) {
      if (existing != null) return;
      String current = field.getText().trim();
      if (!current.isBlank() && !current.equals(autoLoaded.get(field))) return;
      List<String> defaults = profiles != null ? profiles.get("default") : null;
      String text = defaults == null || defaults.isEmpty() ? "" : String.join(", ", defaults);
      if (text.equals(current)) return;
      field.setText(text);
      autoLoaded.put(field, text);
    }

    /// Refresh the profile-backed entries with the current operation's defaults
    private void loadDefaultProfiles() {
      loadDefaultProfile(execute, executeProfiles());
      loadDefaultProfile(profile, encryption() != null ? Rclone.PROFILE : null);
      loadDefaultProfile(forget, Backup.FORGET);
      loadDefaultProfile(check, Backup.CHECK);
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

    /// The mass-run participation: the checkbox inverted (checked = manual-only,
    /// excluded from the mass runs).
    boolean manual() {
      return manual.isSelected() && !manual.isDisable();
    }

    boolean keyring() {
      // The keyring destination only when its radio is selected and selectable: the
      // grayed-out choice (no usable OS keyring on the host) keeps serving the vault
      return keyringChoice.isSelected() && !keyringChoice.isDisable();
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
