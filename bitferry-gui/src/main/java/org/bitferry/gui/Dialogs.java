package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import org.bitferry.vault.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/// The creation and modification dialogs: the new volume and the unified task forms.
/// The heavy lifting (endpoint parsing, task construction, mutation, commit) travels
/// through the context's operations thread mirroring the CLI command flows; failures
/// are surfaced both in the error alerts and the general log.
final class Dialogs {

  private Dialogs() {}

  /// Hook for tests to intercept dialogs instead of blocking on user interaction.
  static java.util.function.Function<Alert, Optional<ButtonType>> dialogPrompter = Dialog::showAndWait;

  /// Hook for tests to intercept the volume discovery tri-choice (the location
  /// outside the scanning policy) instead of blocking on user interaction.
  static java.util.function.Function<Alert, Optional<ButtonType>> discoveryPrompter = Dialog::showAndWait;

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

    // The discovery tri-choice: the location outside the volume scanning policy
    // (the per-site scanned locations, the BITFERRY_PATH entries, the home
    // directory, the mount roots - the probing is exact-root, no recursion) hosts
    // the volume the next start will not discover - offer adding it to the
    // per-site scanned locations list
    boolean addToSearchRoots = false;
    if (!org.bitferry.Paths.covers(canonical)) {
      ButtonType createAndAdd = new ButtonType("Create and add", ButtonBar.ButtonData.OK_DONE);
      ButtonType createWithout = new ButtonType("Create without adding", ButtonBar.ButtonData.NO);
      Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
        "The location " + path + " is not scanned for volumes on the start - the volume will not be discovered automatically.\n\n"
          + "Add the location to the scanned locations?",
        createAndAdd, createWithout, ButtonType.CANCEL);
      alert.setTitle("Volume discovery");
      alert.setHeaderText(null);
      if (context.ownerWindow != null) alert.initOwner(context.ownerWindow);
      centerOnScreen(alert, context.ownerWindow);
      Optional<ButtonType> choice = discoveryPrompter.apply(alert);
      if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) return;
      addToSearchRoots = createAndAdd.equals(choice.get());
    }
    final boolean addOnCreate = addToSearchRoots;

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
      try {
        // The creation registers the volume only upon its successful pristine
        // commit (a failed creation leaves nothing behind); the follow-up commit
        // persists the rest of the volume set as before
        Volume volume = Volume.create(manager, path);
        manager.commit();
        logs.log("Bitferry volume " + AnsiFormat.tag(volume.tag()) + " created at " + AnsiFormat.path(volume.root()));
        // The discovery tri-choice follow-up: the created-and-added location rides
        // the per-site scanned locations list (the next start discovers the volume)
        if (addOnCreate) {
          List<String> roots = new ArrayList<>(org.bitferry.Paths.load());
          if (!roots.contains(canonical.toString())) {
            roots.add(canonical.toString());
            org.bitferry.Paths.save(roots);
            logs.log("The location " + AnsiFormat.path(canonical) + " added to the scanned locations");
          }
        }
        context.fireModelChanged();
      } catch (RuntimeException e) {
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
    showTaskDialog(context, logs, "Modify Bitferry task " + task.tag(), "Modify existing data processing task " + task.tag() + " parameters", "Save",
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
    // discard everything the user typed). The pre-flight check validates the candidate
    // task and runs backend conformance (probes password / salt against existing repositories
    // or encrypted remotes) before the dialog can dismiss.
    Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
    ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
      Scene scene = dialog.getDialogPane().getScene();
      Cursor prevCursor = scene != null ? scene.getCursor() : null;
      if (scene != null) scene.setCursor(Cursor.WAIT);
      try {
        form.validate();
        if (task == null) {
          Task candidate = buildCandidateTask(context, form, null);
          candidate.conform();
        }
      } catch (BitferryError e) {
        alert(context, Alert.AlertType.ERROR, "Validation failed", e.getMessage());
        event.consume();
      } finally {
        if (scene != null) scene.setCursor(prevCursor);
      }
    });
    dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(x -> {
      if (task == null) createTask(context, logs, form);
      else applyTaskModification(context, logs, task, form);
    });
  }

  // Build a candidate task instance from the form entries (pre-commit validation and creation)
  static Task buildCandidateTask(AppContext context, TaskFormController form, LogController logs) {
    Manager manager = context.manager();
    Endpoint source = Endpoints.parse(manager, form.source());
    Endpoint destination = Endpoints.parse(manager, form.destination());
    Task existing = manager.findTask(source, destination);
    if (existing != null) throw new BitferryError("Task " + existing.tag() + " already covers " + existing.describe());
    List<String> include = patterns(form.include());
    List<String> exclude = patterns(form.exclude());
    String execute = form.execute();
    boolean keyring = form.keyring();
    Task task = switch (form.type()) {
      case "copy" -> new Copy(manager, source, destination, execute, include, exclude);
      case "update" -> new Update(manager, source, destination, execute, include, exclude);
      case "replicate" -> new Replicate(manager, source, destination, execute, include, exclude);
      case "equalize" -> new Equalize(manager, source, destination, execute, include, exclude);
      // The backup carries no include filters (the capability is structurally
      // absent - restic backup filters by exclusion only)
      case "backup" -> {
        ServiceSettings svc = form.serviceSettings();
        Backup b = new Backup(manager, source, destination, form.password(), form.format(),
          execute, null, null, null, null, exclude, keyring);
        b.setCadence(svc.cadence());
        b.setCheck(svc.check());
        b.setForget(svc.forget());
        b.setPrune(svc.prune());
        yield b;
      }
      // The restore resolves its snapshot scope when targeting 'latest': the explicit
      // (or uniquely derived) source backup task whose snapshots it selects from - on a
      // shared (co-repository) repository the repo-wide fallback would restore whichever
      // sibling task's data is the newest
      case "restore" -> {
        String snapId = form.snapshotId();
        boolean isLatest = Restore.isLatestSnapshot(snapId);
        String requested = form.snapshotScope();
        String scope = (isLatest || (!requested.isEmpty() && !"-".equals(requested)))
          ? Restore.resolveSnapshotTag(manager, source, "-".equals(requested) ? null : requested, form.password())
          : null;
        if (logs != null) {
          if (!isLatest) {
            logs.log("Restore snapshot ID: " + snapId);
          } else {
            logs.log(scope == null
              ? "No backup task bound to the repository found; the restore will select the newest bitferry-tagged snapshot repo-wide"
              : "Restore snapshot scope: backup task " + scope);
          }
        }
        Restore r = new Restore(manager, source, destination, form.password(), execute, include, exclude, keyring, scope, snapId);
        r.setCheck(form.serviceSettings().check());
        yield r;
      }
      default -> throw new BitferryError("Unknown task type " + form.type());
    };
    if (form.encryption() != null && task.supportsEncryption()) {
      task.setEncryption(form.encryption(), form.password(), form.salt(), Rclone.Encryption.options(form.profile()), keyring);
    }
    task.setManual(form.manual());
    if (manager.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("Task does not belong to any registered volume; it cannot be persisted");
    }
    return task;
  }

  // Run the task creation on the operations thread; the task registers itself
  // upon its successful one-time initialization only (a failed creation leaves
  // nothing behind - no frontend-side rollback is needed)
  private static void createTask(AppContext context, LogController logs, TaskFormController form) {
    context.operations().execute(() -> {
      Task task = null;
      Manager manager = context.manager();
      try {
        task = buildCandidateTask(context, form, logs);
        Task candidate = task;
        manager.commit(task);
        // The simulated creation writes nothing (the storages hold no such task) and
        // skips the backend initialization (the restic repository init): say so instead
        // of claiming a plain creation - the pending initialization materializes upon
        // the first real run/commit once the simulation mode is off
        logs.log(manager.simulate
          ? "Task " + AnsiFormat.tag(candidate.tag()) + " creation simulated (nothing was written, repository not initialized; run with the simulation mode off to create the task for real): " + AnsiFormat.task(candidate)
          : "Task " + AnsiFormat.tag(candidate.tag()) + " created: " + AnsiFormat.task(candidate));
        if (form.keyring() && candidate.usesVault()) {
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
        logs.log(AnsiFormat.error("Task creation failed: " + e.getMessage()));
        Platform.runLater(() -> errorAlert(context, "Task creation failed", e.getMessage()));
      }
    });
  }

  // Apply modifications to an existing task on the operations thread
  static void applyTaskModification(AppContext context, LogController logs, Task task, TaskFormController form) {
    context.operations().execute(() -> {
      Manager manager = context.manager();
      try {
        Task current = manager.task(task.tag());
        if (current == null) throw new BitferryError("Task " + task.tag() + " is no longer registered");

        Endpoint nextSource = form.isSourceEditable() ? Endpoints.parse(manager, form.source()) : current.source();
        Endpoint nextDestination = form.isDestinationEditable() ? Endpoints.parse(manager, form.destination()) : current.destination();
        current.validateEndpoints(nextSource, nextDestination);

        // Rotate repository password if entered (restic only); the invisible
        // co-repository tasks (their volumes are absent) keep the stale token:
        // their runs fail the authentication until re-synced (by the auto-heal
        // upon the next run, or by re-entering the password)
        if (form.resticMode() && !form.password().isEmpty()) {
          List<String> stale = manager.passwd((Restic) current, form.password());
          logs.log("Password updated for task " + AnsiFormat.tag(current.tag()));
          if (!stale.isEmpty()) {
            logs.log(AnsiFormat.error("Warning: the co-repository task(s) " + String.join(", ", stale)
              + " were not reachable - their stored passwords were not updated (their hosting volumes are not restored)."
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

        // Apply endpoint relocation after morph and pre-flight validation (handles
        // equalize baseline cleanup, VolumeVault relocation, and detached-volume commit flags).
        current.setEndpoints(nextSource, nextDestination);

        // Backend execution options (the resolution rides the task's profile route)
        current.setProcessOptions(form.execute());

        // Backup retention, check, prune, and cadence options
        if (current instanceof Backup backup) {
          ServiceSettings svc = form.serviceSettings();
          backup.setForget(svc.forget());
          backup.setCheck(svc.check());
          backup.setPrune(svc.prune());
          backup.setCadence(svc.cadence());
        }

        // Restore snapshot ID, scope, and check re-selection: '-' clears the explicit
        // scope (the default derived selection applies at run time); unchanged scopes skip re-probing.
        if (current instanceof Restore restore) {
          restore.setCheck(form.serviceSettings().check());
          restore.setSnapshotId(form.snapshotId());
          String requested = form.snapshotScope();
          String nextScope = "-".equals(requested) || requested.isEmpty() ? null : requested;
          if (!Objects.equals(nextScope, restore.snapshotTag())) {
            String pwd = form.password();
            if (pwd == null || pwd.isEmpty()) {
              try {
                pwd = restore.password();
              } catch (Exception ignored) {}
            }
            restore.setSnapshotTag("-".equals(requested)
              ? null
              : Restore.resolveSnapshotTag(manager, restore.source(), requested.isEmpty() ? null : requested, pwd));
          }
        }

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

        // The password-storage migration: the radio selection (mirroring the task's
        // current provider) differing from the actual one moves the token between the
        // volume vault and the OS keyring. The rotation above keeps the current
        // provider, so the migration runs after it deliberately.
        if (current.usesVault()) {
          boolean wantKeyring = form.keyring();
          boolean heldKeyring = current.vaultProvider().orElse(org.bitferry.vault.Vault.volume()) == org.bitferry.vault.Vault.keyring();
          // The gray-out artifact: a keyring-held token on a keyring-less host reads
          // as the vault (the radio is disabled and cannot be selected) - the apparent
          // difference is not a user decision, so the migration is skipped instead of
          // attempting the doomed retrieval
          if (heldKeyring && !org.bitferry.vault.KeyringVault.INSTANCE.available() && !wantKeyring) {
            logs.log("Password stays in the OS keyring (no usable keyring on this host to migrate it from)");
          } else if (wantKeyring != heldKeyring) {
            current.migrateVault(wantKeyring);
            logs.log("Password storage moved to the " + (wantKeyring ? "OS keyring" : "volume vault") + " for task " + AnsiFormat.tag(current.tag()));
          }
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

  /// Show the volume search roots dialog (ItemListDialog) and persist changes if accepted.
  static void manageSearchPaths(AppContext context, LogController logs) {
    List<String> current = org.bitferry.Paths.load();
    Optional<List<String>> result = ItemListDialog.show(context.ownerWindow, "Volume search paths", null, current, null, "Select volume search path");
    if (result.isEmpty()) return;
    List<String> updated = result.get();
    if (updated.equals(current)) {
      logs.log("Volume search path unchanged");
      return;
    }
    try {
      org.bitferry.Paths.save(updated);
    } catch (BitferryError e) {
      // The separator characters cannot be persisted unambiguously: refuse the entry
      // instead of silently splitting it into two on the following load
      alert(context, javafx.scene.control.Alert.AlertType.ERROR, "Invalid search path", e.getMessage());
      return;
    }
    logs.log("Volume search path updated: " + updated.size() + " directory(ies)");
    context.reload();
  }

  /// The repair dialog flow: pick one of the repair operations the task's backend
  /// provides ([Repair#operationsFor] - the backend-neutral registry), preview the
  /// operation's dry-run report, confirm, and execute for real. The unlock stays
  /// explicit and informed: a blind repair would remove the locks of the legitimately
  /// running clients defeating the very exclusion they provide.
  static void repairTask(AppContext context, LogController logs, Task task) {
    List<Repair.Operation> operations = Repair.operationsFor(task);
    if (operations.isEmpty()) {
      alert(context, javafx.scene.control.Alert.AlertType.INFORMATION, "Repair",
        "No repair operations are available for " + task.backend() + " tasks (yet).");
      return;
    }
    ChoiceDialog<Repair.Operation> operationPicker = new ChoiceDialog<>(operations.getFirst(), operations);
    if (context.ownerWindow != null) operationPicker.initOwner(context.ownerWindow);
    operationPicker.setTitle("Repair task " + task.tag());
    operationPicker.setHeaderText("Pick the repair operation");
    operationPicker.setContentText("Operation:");
    operationPicker.getDialogPane().setStyle("-fx-text-fill: #222222;");
    centerOnScreen(operationPicker, context.ownerWindow);
    operationPicker.showAndWait().ifPresent(operation -> previewRepair(context, logs, task, operation));
  }

  // The dry-run preview continuation: the captured report informs the confirmation
  // below (the destructive operations show what they are about to remove).
  private static void previewRepair(AppContext context, LogController logs, Task task, Repair.Operation operation) {
    runAsync(context, "Repair preview", () -> {
      List<String> preview = new ArrayList<>();
      boolean ok = context.manager().repair(task, operation, true, preview::add);
      Platform.runLater(() -> {
        if (!ok) {
          alert(context, Alert.AlertType.ERROR, "Repair preview failed",
            "Repair preview failed for task " + task.tag() + ": " + operation.label() + "."
              + (preview.isEmpty() ? "" : "\n\n" + String.join("\n", preview)));
          return;
        }
        offerRepairExecution(context, logs, task, operation, preview);
      });
    }, logs);
  }

  // The confirmation + execution continuation of the repair flow. The confirm alert
  // rides the prompter seam (the tests intercept it); the real run streams into a
  // dedicated run tab mirroring the runner UX.
  private static void offerRepairExecution(AppContext context, LogController logs, Task task, Repair.Operation operation, List<String> preview) {
    StringBuilder text = new StringBuilder();
    if (operation.destructive()) {
      text.append("This operation removes the locks of the possibly running clients (possibly on other hosts), "
        + "letting the exclusive operations race the repository. Proceed only when the holders are known dead.\n\n");
    }
    text.append("Preview (dry run):");
    if (preview.isEmpty()) {
      text.append("\n  (no output - nothing to remove)");
    } else {
      for (String line : preview) {
        text.append("\n  ").append(line);
      }
    }
    ButtonType proceed = new ButtonType("Proceed", ButtonBar.ButtonData.OK_DONE);
    Alert confirm = new Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION, text.toString(), proceed, ButtonType.CANCEL);
    confirm.setTitle("Confirm repair");
    confirm.setHeaderText("Repair task " + task.tag() + ": " + operation.label());
    if (context.ownerWindow != null) confirm.initOwner(context.ownerWindow);
    centerOnScreen(confirm, context.ownerWindow);
    dialogPrompter.apply(confirm).filter(proceed::equals).ifPresent(x ->
      runAsync(context, "Repair", () -> {
        Consumer<String> sink = logs.openRun("repair " + task.tag());
        boolean ok = context.manager().repair(task, operation, false, sink);
        sink.accept(ok
          ? "\u001B[32mRepair completed successfully.\u001B[0m"
          : "\u001B[31mRepair failed.\u001B[0m");
        logs.log("Repair " + (ok ? "completed" : "failed") + " for task " + AnsiFormat.tag(task.tag()) + ": " + operation.label());
      }, logs));
  }
}
