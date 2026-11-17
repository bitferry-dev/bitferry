package bitferry.task;

import bitferry.*;
import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/// Restic backend executor: builds the restic invocations for the restic-backed tasks
/// (the repository backup with the optional integrity checking and retention policy,
/// the snapshot restore) and the repository initialization upon the task creation,
/// spawning restic with the repository password environment.
final class ResticExecutor {

  private static final System.Logger LOG = System.getLogger(ResticExecutor.class.getName());

  private final CommandExecutor commandExecutor;

  ResticExecutor(CommandExecutor commandExecutor) {
    this.commandExecutor = commandExecutor;
  }







  /// Perform the backup conformance check: the forced re-initialization skips it, the
  /// fresh repositories defer to the formatting, the existing ones probe the password.
  void conformBackup(Backup backup) {
    if (Boolean.TRUE.equals(backup.formatMode())) {
      return; // --force (wipe and recreate): skip conformance
    }
    String repo = backup.repoEndpoint().processOpts();
    if (!Files.isDirectory(Path.of(repo))) {
      return; // Repo directory absent: skip conformance
    }
    if (!hasRepository(repo)) {
      return; // New repository will be initialized by format()
    }
    probeRepository(backup, repo);
  }

  /// Perform the restore conformance check: the restore tasks refuse the repositories
  /// the provided password does not open.
  void conformRestore(Restore restore) {
    String repo = restore.repoEndpoint().processOpts();
    if (!Files.isDirectory(Path.of(repo))) {
      return; // Repo directory absent: skip conformance
    }
    if (!hasRepository(repo)) {
      throw new BitferryError("Invalid restic repository at " + repo);
    }
    probeRepository(restore, repo);
  }

  /// Initialize (or wipe and recreate, or attach to) the repository of the backup task.
  void format(Backup backup) {
    Manager manager = backup.manager();
    if (manager.simulate) {
      LOG.log(Level.INFO, () -> "Simulated repository initialization for task " + backup.tag());
      return;
    }
    String repo = backup.destination().processOpts();
    if (Boolean.TRUE.equals(backup.formatMode())) {
      LOG.log(Level.INFO, () -> "Wiping existing repository for task " + backup.tag() + " in " + repo);
      wipeRepository(Path.of(repo));
    } else if (hasRepository(repo)) {
      probeRepository(backup, repo);
      LOG.log(Level.INFO, () -> "Existing repository opened; attaching implicitly");
      return;
    }
    LOG.log(Level.INFO, () -> "Initializing repository for task " + backup.tag() + " in " + repo);
    List<String> cmd = new ArrayList<>();
    cmd.add(resolveExecutable());
    cmd.add("init");
    cmd.add("-r");
    cmd.add(repo);
    cmd.addAll(commonOptions(manager));

    try {
      ExecutorSupport.echoCommandLine(manager, cmd, null);
      int exitCode = commandExecutor.execute(cmd, null, backup.processEnvironment(), null);
      if (exitCode != 0) {
        throw new BitferryError("Restic repository initialization failure for task " + backup.tag() + " (exit code " + exitCode + ")");
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BitferryError("Restic repository initialization failure for task " + backup.tag() + ": " + e.getMessage(), e);
    }
  }

  private void probeRepository(Restic restic, String repo) {
    if (restic.isConformed()) return;
    Manager manager = restic.manager();
    List<String> cmd = new ArrayList<>();
    cmd.add(resolveExecutable());
    cmd.add("-r");
    cmd.add(repo);
    cmd.add("cat");
    cmd.add("config");
    cmd.addAll(commonOptions(manager));

    try {
      ExecutorSupport.echoCommandLine(manager, cmd, null);
      List<String> output = new ArrayList<>();
      int exitCode = commandExecutor.execute(cmd, null, restic.processEnvironment(), output::add);
      if (exitCode != 0) {
        throw new BitferryError("Restic repository at " + repo + " does not accept the provided password");
      }
      restic.markConformed();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BitferryError("Restic repository probe failure for task " + restic.tag() + ": " + e.getMessage(), e);
    }
  }

  static boolean hasRepository(String repo) {
    return Files.isRegularFile(Path.of(repo).resolve("config"));
  }

  /// Change or update the repository password of a restic task, propagating the new
  /// token to every registered co-repository task. Return the tags of the invisible
  /// co-repository tasks whose stored tokens could NOT be updated: the maintenance
  /// state records the tag of every task that ever touched the repository, so the
  /// recorded tags missing from the registry are the siblings (their hosting volumes
  /// are absent) left holding the stale token - their runs fail the authentication
  /// until the token is re-synced (by the auto-heal or by re-entering the password).
  List<String> passwd(Restic restic, String newPassword) {
    if (newPassword == null || newPassword.isEmpty()) {
      throw new BitferryError("New password must be specified");
    }
    String repo = repoPath(restic);
    Path repoDir = Path.of(repo);
    if (!Files.isDirectory(repoDir) || !hasRepository(repo)) {
      throw new BitferryError("Invalid Restic repository at " + repo);
    }
    Manager manager = restic.manager();
    if (manager.simulate) {
      LOG.log(Level.INFO, () -> "Simulated password change for task " + restic.tag());
      return List.of();
    }
    boolean alreadyMatches = testPassword(manager, repo, newPassword);
    if (!alreadyMatches) {
      if (!restic.mayRotateRepositoryKey()) {
        throw new BitferryError("Restic repository at " + repo + " does not accept the provided password");
      }
      boolean currentValid = testPassword(manager, repo, restic.password());
      if (!currentValid) {
        throw new BitferryError("Current stored password for task " + restic.tag() + " is not accepted by the Restic repository at " + repo);
      }
      rotateKey(manager, repo, restic.password(), newPassword);
    }
    restic.updatePassword(newPassword);

    List<String> registered = new ArrayList<>();
    registered.add(restic.tag());
    for (Task t : manager.tasks().values()) {
      if (t != restic && t.isIntact() && t instanceof Restic otherRestic) {
        if (Restic.sameRepository(repo, repoPath(otherRestic))) {
          otherRestic.updatePassword(newPassword);
          registered.add(otherRestic.tag());
        }
      }
    }

    // The invisible co-repository tasks: the state tags not registered in this session
    ResticRepositoryState state = ResticRepositoryState.load(repoDir);
    if (state.normalize(registered)) state.save(repoDir);
    List<String> stale = state.tags().stream()
      .filter(tag -> !registered.contains(tag))
      .toList();
    if (!stale.isEmpty()) {
      LOG.log(Level.WARNING, () -> "The stored passwords of the co-repository task(s) " + String.join(", ", stale)
        + " were not updated (their volumes are not restored); their runs will fail the authentication until the token is re-synced");
    }
    return stale;
  }

  /// Re-sync the stored repository token of a task when it went stale: the repository
  /// password may have been rotated by a co-repository task while this task's volume
  /// was absent. The adoption is conservative: the task's own password must fail the
  /// repository first, and another registered co-repository task's password must
  /// verifiably open it. Return true when the token was replaced (the caller retries
  /// the failed invocation).
  private boolean healStaleToken(Restic task, String repo) {
    Manager manager = task.manager();
    String own;
    try {
      own = task.password();
    } catch (BitferryError e) {
      return false; // No token at all: the adoption cannot diagnose, let the failure stand
    }
    if (testPassword(manager, repo, own)) {
      return false; // The own token opens the repository: the failure is not an authentication one
    }
    for (Task t : manager.tasks().values()) {
      if (t == task || !(t.isIntact() && t instanceof Restic sibling)) continue;
      if (!Restic.sameRepository(repo, repoPath(sibling))) continue;
      String candidate;
      try {
        candidate = sibling.password();
      } catch (BitferryError e) {
        continue; // The sibling holds no token: skip it
      }
      if (testPassword(manager, repo, candidate)) {
        task.updatePassword(candidate);
        LOG.log(Level.WARNING, () -> "Task " + task.tag() + " stored a stale repository password (rotated by the co-repository task "
          + sibling.tag() + "); the token was re-synced automatically");
        return true;
      }
    }
    return false;
  }

  private boolean testPassword(Manager manager, String repo, String password) {
    List<String> cmd = new ArrayList<>();
    cmd.add(resolveExecutable());
    cmd.add("-r");
    cmd.add(repo);
    cmd.add("cat");
    cmd.add("config");
    cmd.addAll(commonOptions(manager));
    try {
      ExecutorSupport.echoCommandLine(manager, cmd, null);
      List<String> output = new ArrayList<>();
      int exitCode = commandExecutor.execute(cmd, null, Map.of("RESTIC_PASSWORD", password), output::add);
      return exitCode == 0;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return false;
    }
  }

  private void rotateKey(Manager manager, String repo, String oldPassword, String newPassword) {
    Path tempFile = null;
    try {
      tempFile = Files.createTempFile("bitferry-pw-", ".tmp");
      restrictToOwner(tempFile);
      Files.writeString(tempFile, newPassword);
      // The crash-resistant cleanup fallback: a graceful exit removes the file even
      // when the rotation aborted before reaching the finally block
      tempFile.toFile().deleteOnExit();

      List<String> cmd = new ArrayList<>();
      cmd.add(resolveExecutable());
      cmd.add("key");
      cmd.add("passwd");
      cmd.add("-r");
      cmd.add(repo);
      cmd.add("--new-password-file");
      cmd.add(tempFile.toString());
      cmd.addAll(commonOptions(manager));

      ExecutorSupport.echoCommandLine(manager, cmd, null);
      List<String> output = new ArrayList<>();
      int exitCode = commandExecutor.execute(cmd, null, Map.of("RESTIC_PASSWORD", oldPassword), output::add);
      if (exitCode != 0) {
        throw new BitferryError("Restic key passwd failed (exit code " + exitCode + "): " + String.join("\n", output));
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BitferryError("Failed to change repository password: " + e.getMessage(), e);
    } finally {
      if (tempFile != null) {
        final Path leaked = tempFile;
        try {
          Files.deleteIfExists(leaked);
        } catch (IOException e) {
          LOG.log(Level.WARNING, () -> "Failed to remove the temporary password file '" + leaked + "' - remove it manually");
        }
      }
    }
  }

  /// Restrict the temporary password file to its owner: the POSIX mode bits where
  /// available, the owner-only ACL on the non-POSIX file systems (Windows). The
  /// plaintext password must never be readable by the other accounts, whatever
  /// the platform's default temporary directory permissions are.
  private static void restrictToOwner(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      return;
    } catch (UnsupportedOperationException ignored) {
      // Not a POSIX file system (e.g. Windows): fall through to the ACL handling
    }
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    if (view == null) throw new BitferryError("Cannot restrict the temporary password file permissions: no ACL support");
    try {
      UserPrincipal owner = view.getOwner();
      // The single allowed entry: the owner gets the full control; every other
      // account (and the inherited entries) gets nothing
      AclEntry ownerEntry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
        .build();
      view.setAcl(List.of(ownerEntry));
    } catch (IOException e) {
      throw new BitferryError("Failed to restrict the temporary password file permissions: " + e.getMessage(), e);
    }
  }

  private static String repoPath(Restic restic) {
    return restic.repoEndpoint().processOpts();
  }

  // Remove the repository subdirectories upon the forced re-initialization. Any wipe
  // failure aborts the caller: `restic init` over the partially wiped repository would
  // layer the new repository config over the orphaned data of the old one.
  private void wipeRepository(Path repo) {
    List<String> failures = new ArrayList<>();
    // The maintenance state files travel with the wipe too: the stale cadence
    // entries over the freshly recreated repository would trigger an immediate
    // spurious service
    for (String sub : List.of("config", "data", "index", "keys", "locks", "snapshots",
      ResticRepositoryState.FILENAME, ResticRepositoryState.LEGACY_FILENAME)) {
      Path subDir = repo.resolve(sub);
      if (Files.exists(subDir)) {
        try (Stream<Path> stream = Files.walk(subDir)) {
          for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
            try {
              Files.deleteIfExists(entry);
            } catch (IOException e) {
              failures.add(entry + ": " + e.getMessage());
            }
          }
        } catch (IOException e) {
          failures.add(subDir + ": " + e.getMessage());
        }
      }
    }
    if (!failures.isEmpty()) {
      throw new BitferryError("Failed to wipe Restic repository " + repo + ": " + String.join("; ", failures));
    }
  }

  List<String> backupCommand(Backup backup) {
    Manager manager = backup.manager();
    String repo = backup.destination().processOpts();

    List<String> cmd = new ArrayList<>();
    cmd.add(resolveExecutable());
    cmd.add("backup");
    cmd.add(".");
    cmd.add("--tag");
    cmd.add("bitferry," + backup.tag());

    // Exclude Bitferry storage files and per-task metadata
    cmd.add("--exclude");
    cmd.add(Bitferry.STORAGE + "*");
    cmd.add("--exclude");
    cmd.add(Bitferry.STORAGE + "*/**");
    for (String exc : backup.exclude()) {
      cmd.add("--exclude");
      cmd.add(exc);
    }

    // Process options
    cmd.addAll(backup.processOptions());

    // Common options
    cmd.addAll(commonOptions(manager));

    // Dry run
    if (manager.simulate) {
      cmd.add("--dry-run");
    }

    // Repo
    cmd.add("-r");
    cmd.add(repo);
    return cmd;
  }

  List<List<String>> commands(Backup backup) {
    return List.of(ExecutorSupport.displayCommand(backupCommand(backup)));
  }

  List<String> serveCheckCommand(Backup backup) {
    Manager manager = backup.manager();
    List<String> checkCmd = new ArrayList<>();
    checkCmd.add(resolveExecutable());
    checkCmd.add("check");
    if (backup.check() != null) {
      checkCmd.addAll(backup.check());
    }
    checkCmd.addAll(commonOptions(manager));
    checkCmd.add("-r");
    checkCmd.add(backup.destination().processOpts());
    return checkCmd;
  }

  List<String> serveForgetCommand(Backup backup) {
    if (backup.forget() == null || backup.forget().isEmpty()) return null;
    Manager manager = backup.manager();
    List<String> forgetCmd = new ArrayList<>();
    forgetCmd.add(resolveExecutable());
    forgetCmd.add("forget");
    forgetCmd.add("--tag");
    forgetCmd.add("bitferry," + backup.tag());
    for (String opt : backup.forget()) {
      if (!"--prune".equals(opt)) {
        forgetCmd.add(opt);
      }
    }
    forgetCmd.addAll(commonOptions(manager));
    if (manager.simulate) {
      forgetCmd.add("--dry-run");
    }
    forgetCmd.add("-r");
    forgetCmd.add(backup.destination().processOpts());
    return forgetCmd;
  }

  List<String> pruneCommand(Backup backup) {
    Manager manager = backup.manager();
    List<String> pruneCmd = new ArrayList<>();
    pruneCmd.add(resolveExecutable());
    pruneCmd.add("prune");
    pruneCmd.addAll(commonOptions(manager));
    if (manager.simulate) {
      pruneCmd.add("--dry-run");
    }
    pruneCmd.add("-r");
    pruneCmd.add(backup.destination().processOpts());
    return pruneCmd;
  }

  List<List<String>> serveCommands(Backup backup) {
    List<List<String>> cmds = new ArrayList<>();
    cmds.add(ExecutorSupport.displayCommand(serveCheckCommand(backup)));
    List<String> forget = serveForgetCommand(backup);
    if (forget != null) cmds.add(ExecutorSupport.displayCommand(forget));
    cmds.add(ExecutorSupport.displayCommand(pruneCommand(backup)));
    return cmds;
  }

  List<String> serveCheckCommand(Restore restore) {
    Manager manager = restore.manager();
    List<String> checkCmd = new ArrayList<>();
    checkCmd.add(resolveExecutable());
    checkCmd.add("check");
    checkCmd.addAll(commonOptions(manager));
    checkCmd.add("-r");
    checkCmd.add(restore.source().processOpts());
    return checkCmd;
  }

  List<List<String>> serveCommands(Restore restore) {
    return List.of(ExecutorSupport.displayCommand(serveCheckCommand(restore)));
  }

  List<String> restoreCommand(Restore restore) {
    Manager manager = restore.manager();
    List<String> cmd = new ArrayList<>();
    cmd.add(resolveExecutable());
    cmd.add("restore");
    cmd.add("latest");
    cmd.add("--target");
    cmd.add(restore.destination().processOpts());

    // Exclude Bitferry storage files and per-task metadata
    cmd.add("--exclude");
    cmd.add(Bitferry.STORAGE + "*");
    cmd.add("--exclude");
    cmd.add(Bitferry.STORAGE + "*/**");

    for (String inc : restore.include()) {
      cmd.add("--include");
      cmd.add(inc);
    }
    for (String exc : restore.exclude()) {
      cmd.add("--exclude");
      cmd.add(exc);
    }

    cmd.addAll(restore.processOptions());
    cmd.addAll(commonOptions(manager));
    if (manager.simulate) {
      cmd.add("--dry-run");
    }
    cmd.add("-r");
    cmd.add(restore.source().processOpts());
    return cmd;
  }

  List<List<String>> commands(Restore restore) {
    return List.of(ExecutorSupport.displayCommand(restoreCommand(restore)));
  }

  /// The backup tasks sharing this task's repository (per [Restic#sameRepository]),
  /// this task included. The restore tasks do not participate: they hold neither
  /// a cadence nor a retention policy.
  private List<Backup> coRepoTasks(Backup backup) {
    String repo = repoPath(backup);
    List<Backup> sharing = new ArrayList<>();
    for (Task t : backup.manager().tasks().values()) {
      if (t.isIntact() && t instanceof Backup other && Restic.sameRepository(repo, repoPath(other))) {
        sharing.add(other);
      }
    }
    return sharing;
  }

  boolean runBackup(Backup backup, Consumer<String> output) {
    Manager manager = backup.manager();
    String repo = backup.destination().processOpts();
    Path repoDir = Path.of(repo);
    Path cwd = Path.of(backup.source().processOpts());

    // Normalize the repository state upon the first touch: the legacy single-entry
    // shape (and the legacy file name) migrates into the per-task entries of the
    // co-repository tasks
    List<Backup> coRepo = coRepoTasks(backup);
    ResticRepositoryState state = ResticRepositoryState.load(repoDir);
    if (!manager.simulate && state.normalize(coRepo.stream().map(Task::tag).toList())) {
      state.save(repoDir);
    }

    // Service check before backup: the service covers the whole repository (every
    // co-repository retention policy), so it runs when any co-repository task's
    // cadence is due - not only this task's own one
    if (backup.cadence() != null) {
      boolean due = coRepo.stream().anyMatch(t -> state.isDue(t.tag(), t.cadence()));
      if (due) {
        LOG.log(Level.INFO, () -> "Serve cadence triggered for task " + backup.tag() + "; running service before backup");
        if (output != null) {
          output.accept("Serve cadence triggered for task " + backup.tag() + "; running service before backup");
        }
        boolean served = serveBackup(backup, output);
        if (!served) {
          String msg = "Service failed before backup for task " + backup.tag() + "; aborting backup";
          LOG.log(Level.ERROR, () -> msg);
          if (output != null) output.accept(msg);
          return false;
        }
      }
    }

    if (output != null) {
      output.accept("Backing up " + backup.source().processOpts() + " into repository " + repo);
    }

    List<String> cmd = backupCommand(backup);
    boolean success = ExecutorSupport.execute(commandExecutor, manager, cmd, cwd, backup.processEnvironment(), backup.tag(), output);
    if (!success) {
      // The stored token may have gone stale (the password rotated by a co-repository
      // task while this volume was absent): re-sync from a sibling and retry once
      if (!healStaleToken(backup, repo)) return false;
      if (output != null) output.accept("Retrying the backup after the stale token re-sync");
      success = ExecutorSupport.execute(commandExecutor, manager, cmd, cwd, backup.processEnvironment(), backup.tag(), output);
      if (!success) return false;
    }

    // Record completed backup in the task's own repository state entry (reloaded
    // fresh: the service pass above may have reset the co-repository entries)
    if (!manager.simulate) {
      ResticRepositoryState fresh = ResticRepositoryState.load(repoDir);
      fresh.recordBackup(backup.tag());
      fresh.save(repoDir);
    }

    return true;
  }

  boolean serveBackup(Backup backup, Consumer<String> output) {
    Manager manager = backup.manager();
    String repo = backup.destination().processOpts();
    Map<String, String> environment = backup.processEnvironment();

    if (output != null) {
      output.accept("Servicing repository " + repo);
    }

    // The co-repository tasks: the service applies every sharing task's retention
    // policy (the per-task forget) and resets every sharing task's entry - whichever
    // task triggered the service, no policy starves
    List<Backup> coRepo = coRepoTasks(backup);
    List<String> tags = coRepo.stream().map(Task::tag).toList();

    // Normalize the repository state upon the first touch: the legacy single-entry
    // shape (and the legacy file name) migrates into the per-task entries
    Path repoDir = Path.of(repo);
    ResticRepositoryState state = ResticRepositoryState.load(repoDir);
    if (!manager.simulate && state.normalize(tags)) {
      state.save(repoDir);
    }

    // Step 1: Structural check (gatekeeper before any retention/prune)
    List<String> checkCmd = serveCheckCommand(backup);
    if (output != null) {
      output.accept("Checking repository " + repo);
    }
    boolean checkOk = ExecutorSupport.execute(commandExecutor, manager, checkCmd, null, environment, backup.tag(), output);
    if (!checkOk && healStaleToken(backup, repo)) {
      // The stored token went stale (rotated by a co-repository task while this volume
      // was absent): the re-synced token carries the rest of the service pass too
      environment = backup.processEnvironment();
      if (output != null) output.accept("Retrying the check after the stale token re-sync");
      checkOk = ExecutorSupport.execute(commandExecutor, manager, checkCmd, null, environment, backup.tag(), output);
    }
    if (!checkOk) {
      String msg = "Service aborted for task " + backup.tag() + ": repository check failed";
      LOG.log(Level.ERROR, () -> msg);
      if (output != null) output.accept(msg);
      return false;
    }

    // Step 2: Retention per co-repository task (each forget scopes to its own tag)
    for (Backup task : coRepo) {
      List<String> forgetCmd = serveForgetCommand(task);
      if (forgetCmd == null) continue; // The task holds no retention policy
      if (output != null) {
        output.accept("Applying retention policy of task " + task.tag() + " to repository " + repo);
      }
      boolean forgetOk = ExecutorSupport.execute(commandExecutor, manager, forgetCmd, null, environment, backup.tag(), output);
      if (!forgetOk) {
        return false;
      }
    }

    // Step 3: Prune (reclaim space)
    List<String> pruneCmd = pruneCommand(backup);
    if (output != null) {
      output.accept("Pruning repository " + repo);
    }
    boolean pruneOk = ExecutorSupport.execute(commandExecutor, manager, pruneCmd, null, environment, backup.tag(), output);
    if (!pruneOk) {
      return false;
    }

    // Step 4: Reset every co-repository task's maintenance entry (reloaded fresh)
    if (!manager.simulate) {
      ResticRepositoryState fresh = ResticRepositoryState.load(repoDir);
      fresh.recordServe(tags);
      fresh.save(repoDir);
    }
    return true;
  }

  boolean serveRestore(Restore restore, Consumer<String> output) {
    Manager manager = restore.manager();
    String repo = restore.source().processOpts();
    Map<String, String> environment = restore.processEnvironment();

    if (output != null) {
      output.accept("Checking repository " + repo);
    }

    List<String> checkCmd = serveCheckCommand(restore);
    boolean ok = ExecutorSupport.execute(commandExecutor, manager, checkCmd, null, environment, restore.tag(), output);
    if (!ok && healStaleToken(restore, repo)) {
      environment = restore.processEnvironment();
      ok = ExecutorSupport.execute(commandExecutor, manager, checkCmd, null, environment, restore.tag(), output);
    }
    return ok;
  }

  boolean runRestore(Restore restore, Consumer<String> output) {
    Manager manager = restore.manager();
    String repo = restore.source().processOpts();
    String target = restore.destination().processOpts();

    if (output != null) {
      output.accept("Restoring repository " + repo + " into " + target);
    }

    List<String> cmd = restoreCommand(restore);
    boolean success = ExecutorSupport.execute(commandExecutor, manager, cmd, null, restore.processEnvironment(), restore.tag(), output);
    if (!success && healStaleToken(restore, repo)) {
      success = ExecutorSupport.execute(commandExecutor, manager, cmd, null, restore.processEnvironment(), restore.tag(), output);
    }
    return success;
  }

  private static String resolveExecutable() {
    return ExecutorSupport.resolveExecutable("restic");
  }

  /// The verbosity options shared by every restic invocation. The flags apply to
  /// both execution modes by design (the passthrough terminal and the captured
  /// consumer). No progress option is ever requested: restic's interactive progress
  /// bar self-gates on stderr being a terminal, and the captured mode merges stderr
  /// into the pipe, turning the bar off.
  private static List<String> commonOptions(Manager manager) {
    List<String> opts = new ArrayList<>();
    if (manager.verbose()) opts.add("--verbose");
    if (manager.quiet) opts.add("--quiet");
    return opts;
  }
}
