package bitferry.gui;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import bitferry.vault.KeyringVault;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/// The creation and modification dialogs: the new volume and the unified task forms.
/// The heavy lifting (endpoint parsing, task construction, mutation, commit) travels
/// through the context's operations thread mirroring the CLI command flows; failures
/// are surfaced both in the error alerts and the general log.
final class Dialogs {

  private Dialogs() {}

  /// Hook for tests to intercept dialogs instead of blocking on user interaction.
  static java.util.function.Function<Alert, Optional<ButtonType>> dialogPrompter = Dialog::showAndWait;

  /// Center the dialog on the screen hosting the specified owner window (the primary
  /// screen when ownerless): the dialog size is only known after the showing layout
  /// pass, hence the shown-hook positioning. The owner-based screen resolution keeps
  /// the multi-monitor setups sane - the dialogs appear on the screen the application
  /// lives on rather than at the platform's default placement.
  static void centerOnScreen(Dialog<?> dialog, Window owner) {
    dialog.setOnShown(x -> {
      Screen screen = Screen.getPrimary();
      if (owner != null) {
        for (Screen candidate : Screen.getScreens()) {
          if (candidate.getVisualBounds().contains(owner.getX() + owner.getWidth() / 2, owner.getY() + owner.getHeight() / 2)) {
            screen = candidate;
            break;
          }
        }
      }
      Rectangle2D bounds = screen.getVisualBounds();
      Window window = dialog.getDialogPane().getScene().getWindow();
      window.setX(bounds.getMinX() + (bounds.getWidth() - window.getWidth()) / 2);
      window.setY(bounds.getMinY() + (bounds.getHeight() - window.getHeight()) / 2);
    });
  }

  /// Show the new volume dialog; the creation is asynchronous.
  static void newVolume(AppContext context, LogController logs) {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle("Select Bitferry volume root directory");
    File initial = new File(System.getProperty("user.home"));
    if (initial.isDirectory()) chooser.setInitialDirectory(initial);
    File root = chooser.showDialog(context.ownerWindow);
    if (root == null) return;
    createVolume(context, logs, root.toPath());
  }

  /// Create a volume at the specified path with confirmation or overwrite warning dialog.
  static void createVolume(AppContext context, LogController logs, Path path) {
    Path canonical = Bitferry.canonicalize(path);
    Path storage = canonical.resolve(Bitferry.STORAGE);
    Manager currentManager = context.manager();
    Volume occupied = currentManager.liveVolumes().values().stream()
      .filter(v -> v.root().equals(canonical))
      .findFirst().orElse(null);
    boolean exists = Files.exists(storage) || Files.exists(path.resolve(Bitferry.STORAGE)) || occupied != null;

    if (exists) {
      Alert alert = new Alert(Alert.AlertType.WARNING,
        "Overwrite existing Bitferry volume storage file at " + path + " ?",
        ButtonType.YES, ButtonType.NO);
      alert.setTitle("Overwrite Bitferry volume");
      alert.setHeaderText(null);
      if (context.ownerWindow != null) alert.initOwner(context.ownerWindow);
      centerOnScreen(alert, context.ownerWindow);
      Optional<ButtonType> result = dialogPrompter.apply(alert);
      if (result.isEmpty() || result.get() != ButtonType.YES) return;
    }

    runAsync(context, "Create Bitferry volume", () -> {
      Manager manager = context.manager();
      Volume occ = manager.liveVolumes().values().stream()
        .filter(v -> v.root().equals(canonical))
        .findFirst().orElse(null);
      if (occ != null) {
        occ.delete();
        for (Task t : occ.tasks().values()) {
          if (t.referredVolumes().stream().allMatch(vtag -> vtag.equals(occ.tag()))) {
            manager.deregisterTask(t);
          }
        }
        manager.deregisterVolume(occ);
      }
      boolean oldForce = manager.force;
      manager.force = exists;
      Volume volume = null;
      try {
        volume = new Volume(manager, path);
        manager.commit();
        logs.log("Bitferry volume " + AnsiFormat.tag(volume.tag()) + " created at " + AnsiFormat.path(volume.root()));
        context.fireModelChanged();
      } catch (RuntimeException e) {
        if (volume != null) {
          manager.deregisterVolume(volume);
        }
        context.fireModelChanged();
        throw e;
      } finally {
        manager.force = oldForce;
      }
    }, logs);
  }

  /// Show the new task dialog returning once the form is dismissed; the creation
  /// itself is asynchronous.
  static void newTask(AppContext context, LogController logs, BooleanSupplier busy) {
    if (busy.getAsBoolean()) {
      alert(context, Alert.AlertType.WARNING, "Busy", "A run is in progress — wait for it to finish.");
      return;
    }
    showTaskDialog(context, logs, "New Bitferry task", "Create a new data processing task", "Create",
      TaskFormController.load(context, null), null);
  }

  /// Show the modify task dialog returning once the form is dismissed; the modification
  /// itself is executed asynchronously on the operations thread.
  static void modifyTask(AppContext context, LogController logs, Task task, BooleanSupplier busy) {
    if (busy.getAsBoolean()) {
      alert(context, Alert.AlertType.WARNING, "Busy", "A run is in progress — wait for it to finish.");
      return;
    }
    showTaskDialog(context, logs, "Modify Bitferry task " + task.tag(), "Modify existing data procssing task " + task.tag() + " parameters", "Save",
      TaskFormController.load(context, task), task);
  }

  /// The shared FXML-backed task form dialog shell: the title, the header, the
  /// OK-Cancel buttons, the validation filter, and the dismissal action. No fixed
  /// pane width: the form sizes the scroller to its content (scaled with the font
  /// size) and the dialog tracks it through the relayout's sizeToScene; the dialog
  /// stays resizable for the manual widening.
  private static void showTaskDialog(AppContext context, LogController logs, String title, String header, String okLabel,
                                     TaskFormController form, Task task) {
    Dialog<ButtonType> dialog = new Dialog<>();
    if (context.ownerWindow != null) dialog.initOwner(context.ownerWindow);
    dialog.setTitle(title);
    dialog.setHeaderText(header);
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText(okLabel);
    dialog.getDialogPane().setContent(form.pane());
    dialog.setResizable(true);
    dialog.getDialogPane().setStyle("-fx-text-fill: #222222;");
    centerOnScreen(dialog, context.ownerWindow);
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
    dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(x -> {
      if (task == null) createTask(context, logs, form);
      else applyTaskModification(context, logs, task, form);
    });
  }

  // Run the task creation on the operations thread; the failures deregister the
  // half-constructed task (the commit aborts and deregisters on its own)
  private static void createTask(AppContext context, LogController logs, TaskFormController form) {
    context.operations().execute(() -> {
      Task task = null;
      Manager manager = context.manager();
      try {
        Endpoint source = Endpoints.parse(manager, form.source());
        Endpoint destination = Endpoints.parse(manager, form.destination());
        Task existing = manager.findTask(source, destination);
        if (existing != null) throw new BitferryError("Task " + existing.tag() + " already covers " + existing.describe());
        List<String> include = patterns(form.include());
        List<String> exclude = patterns(form.exclude());
        String execute = form.execute();
        boolean keyring = form.keyring();
        task = switch (form.type()) {
          case "copy" -> new Copy(manager, source, destination, execute, include, exclude);
          case "update" -> new Update(manager, source, destination, execute, include, exclude);
          case "replicate" -> new Replicate(manager, source, destination, execute, include, exclude);
          case "equalize" -> new Equalize(manager, source, destination, execute, include, exclude);
          // The backup carries no include filters (the capability is structurally
          // absent - restic backup filters by exclusion only)
          case "backup" -> new Backup(manager, source, destination, form.password(), form.format(),
            execute, form.forget(), form.check(), form.serve(), exclude, keyring);
          case "restore" -> new Restore(manager, source, destination, form.password(), execute, include, exclude, keyring);
          default -> throw new BitferryError("Unknown task type " + form.type());
        };
        if (form.encryption() != null && task.supportsEncryption()) {
          task.setEncryption(form.encryption(), form.password(), null, Rclone.Encryption.options(form.profile()), keyring);
        }
        task.setManual(form.manual());
        Task candidate = task;
        if (manager.volumes().values().stream().noneMatch(v -> candidate.refersVolume(v.tag()))) {
          throw new BitferryError("Task does not belong to any registered volume; it cannot be persisted");
        }
        manager.commit();
        // The simulated creation writes nothing (the storages hold no such task) and
        // skips the backend initialization (the restic repository init): say so instead
        // of claiming a plain creation - the pending initialization materializes upon
        // the first real run/commit once the simulation mode is off
        logs.log(manager.simulate
          ? "Task " + AnsiFormat.tag(candidate.tag()) + " creation simulated (nothing was written, repository not initialized; run with the simulation mode off to create the task for real): " + AnsiFormat.task(candidate)
          : "Task " + AnsiFormat.tag(candidate.tag()) + " created: " + AnsiFormat.task(candidate));
        if (keyring && candidate.usesVault()) {
          if (!KeyringVault.INSTANCE.available()) {
            logs.log("Password kept in the volume vault (no usable OS keyring on the host)");
          } else {
            try {
              if (!candidate.vaultVolume().isFixed()) {
                logs.log("Password stored in the OS keyring (the vault volume resides on removable media - the token will not travel with it)");
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
        logs.log(AnsiFormat.error("Task creation failed: " + e.getMessage()));
        Platform.runLater(() -> errorAlert(context, "Task creation failed", e.getMessage()));
      }
    });
  }

  // Apply modifications to an existing task on the operations thread
  private static void applyTaskModification(AppContext context, LogController logs, Task task, TaskFormController form) {
    context.operations().execute(() -> {
      Manager manager = context.manager();
      try {
        Task current = manager.task(task.tag());
        if (current == null) throw new BitferryError("Task " + task.tag() + " is no longer registered");

        // Rotate repository password if entered (restic only); the invisible
        // co-repository tasks (their volumes are absent) keep the stale token:
        // their runs fail the authentication until re-synced (by the auto-heal
        // upon the next run, or by re-entering the password)
        if (form.resticMode() && !form.password().isEmpty()) {
          List<String> stale = manager.passwd((Restic) current, form.password());
          logs.log("Password updated for task " + AnsiFormat.tag(current.tag()));
          if (!stale.isEmpty()) {
            logs.log(AnsiFormat.error("Warning: the co-repository task(s) " + String.join(", ", stale)
              + " were not reachable - their stored passwords were not updated (their volumes are not restored, or the tasks were deleted)."
              + " The unreachable tasks' runs will fail the authentication until the token is re-synced (by the auto-heal upon their next run, or by re-entering the password)"));
          }
        }

        // The execution options are value-checked against the effective (the post-morph)
        // profile route BEFORE the destructive morph: the morph is irreversible on disk
        // (the equalize baseline deletion), so a failing set after it would strand the
        // half-applied morph with the storage uncommitted
        if (!current.operation().equals(form.type())) {
          Task.checkProcessOptions(form.execute(), Task.processRouteFor(form.type()));
        }

        // The applicability guards and the value validation run BEFORE the morph (the
        // password rotation above included): the morph is destructive (an equalize task
        // loses its bisync baseline on disk), so a failing mutation after it would
        // leave the half-applied morph behind. The mutation calls themselves ride the
        // morphed task: the morph carries the prior state over (the process options,
        // the filters, the flags), so the resolution against the target operation's
        // profile route matches the user's selection
        if (!current.operation().equals(form.type())) {
          current = current.morph(form.type());
        }

        // Backend execution options (the resolution rides the task's profile route)
        current.setProcessOptions(form.execute());

        // Backup retention, check, and cadence options
        current.applyPolicyOptions(form.forget(), form.check(), form.serve());

        // The manual flag (the mass-run participation): the dialog checkbox is the
        // editor (the grid checkbox mirrors it)
        current.setManual(form.manual());

        // Include filters: the capability is structural ([IncludeFilters] on the rclone
        // family and the restore tasks), so the type test replaces the runtime
        // predicate — the backup tasks carry neither the state nor the accessors,
        // and the form field is disabled for them
        if (current instanceof IncludeFilters includable) {
          List<String> include = patterns(form.include());
          if (include.isEmpty()) {
            includable.clearInclude();
          } else {
            includable.setInclude(include);
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
        logs.log("Task " + AnsiFormat.tag(current.tag()) + " modified: " + AnsiFormat.task(current));
        context.fireModelChanged();
      } catch (RuntimeException e) {
        logs.log(AnsiFormat.error("Task modification failed: " + e.getMessage()));
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

  /// Run an action on the context's operations thread surfacing failures into the log and the error alert.
  static void runAsync(AppContext context, String title, Runnable action, LogController logs) {
    context.operations().execute(() -> {
      try {
        action.run();
      } catch (RuntimeException e) {
        // The whole unchecked family surfaces (the BitferryError along with the
        // invalid-path/security-style failures): neither the log nor the alert
        // may be silently skipped
        logs.log(AnsiFormat.error(title + " failed: " + e.getMessage()));
        Platform.runLater(() -> errorAlert(context, title + " failed", e.getMessage()));
      }
    });
  }

  /// Confirm the action and run it on the context's operations thread surfacing
  /// the failures into the log and the error alert. Shared by the dialogs and the
  /// per-tab management actions.
  static void confirmAndRun(AppContext context, String title, String text, Runnable action, LogController logs) {
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
    confirm.setTitle(title);
    confirm.setHeaderText(null);
    if (context.ownerWindow != null) confirm.initOwner(context.ownerWindow);
    centerOnScreen(confirm, context.ownerWindow);
    dialogPrompter.apply(confirm).filter(ButtonType.OK::equals).ifPresent(x ->
      runAsync(context, title, action, logs));
  }

  /// The error alert dialog (the failures also travel into the general log).
  static void errorAlert(AppContext context, String title, String text) {
    Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
    alert.setTitle(title);
    alert.setHeaderText(null);
    if (context.ownerWindow != null) alert.initOwner(context.ownerWindow);
    centerOnScreen(alert, context.ownerWindow);
    alert.show();
  }

  static void alert(AppContext context, Alert.AlertType type, String title, String text) {
    Alert alert = new Alert(type, text, ButtonType.OK);
    alert.setTitle(title);
    alert.setHeaderText(null);
    if (context.ownerWindow != null) alert.initOwner(context.ownerWindow);
    centerOnScreen(alert, context.ownerWindow);
    alert.show();
  }
}
