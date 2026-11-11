package bitferry.task;

import bitferry.*;
import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.file.*;
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

  /// Process the restic-backed task. Return true on success.
  boolean run(Restic restic, Consumer<String> output) {
    if (restic instanceof Backup backup) {
      return runBackup(backup, output);
    } else if (restic instanceof Restore restore) {
      return runRestore(restore, output);
    }
    String message = "unsupported restic operation: " + restic.operation();
    LOG.log(Level.ERROR, () -> message);
    if (output != null) output.accept(message);
    return false;
  }

  /// Maintain the restic-backed task repository. Return true on success.
  boolean maintain(Restic restic, Consumer<String> output) {
    if (restic instanceof Backup backup) {
      return maintainBackup(backup, output);
    } else if (restic instanceof Restore restore) {
      return maintainRestore(restore, output);
    }
    String message = "unsupported restic operation for maintenance: " + restic.operation();
    LOG.log(Level.ERROR, () -> message);
    if (output != null) output.accept(message);
    return false;
  }

  List<List<String>> maintainCommands(Restic restic) {
    if (restic instanceof Backup backup) {
      return maintainCommands(backup);
    } else if (restic instanceof Restore restore) {
      return maintainCommands(restore);
    }
    return List.of();
  }

  /// Perform conformance check of the candidate repository password against the existing repository.
  void conform(Restic restic) {
    if (restic instanceof Backup backup && Boolean.TRUE.equals(backup.formatMode())) {
      return; // --force (wipe and recreate): skip conformance
    }
    String repo = repoPath(restic);
    Path repoDir = Path.of(repo);
    if (!Files.isDirectory(repoDir)) {
      return; // Repo directory absent: skip conformance
    }
    if (!hasRepository(repo)) {
      if (restic instanceof Restore) {
        throw new BitferryError("the repository at '" + repo + "' is not a valid restic repository");
      }
      return; // For backup, new repository will be initialized by format()
    }
    probeRepository(restic, repo);
  }

  /// Initialize (or wipe and recreate, or attach to) the repository of the backup task.
  void format(Backup backup) {
    Manager manager = backup.manager();
    if (manager.simulate) {
      LOG.log(Level.INFO, () -> "simulated repository initialization for task #" + backup.tag());
      return;
    }
    String repo = backup.destination().processOpts();
    if (Boolean.TRUE.equals(backup.formatMode())) {
      LOG.log(Level.INFO, () -> "wiping existing repository '" + repo + "' for task #" + backup.tag());
      wipeRepository(Path.of(repo));
    } else if (hasRepository(repo)) {
      probeRepository(backup, repo);
      LOG.log(Level.INFO, () -> "existing repository opened; attaching implicitly");
      return;
    }
    LOG.log(Level.INFO, () -> "initializing repository '" + repo + "' for task #" + backup.tag());
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
        throw new BitferryError("repository initialization failure for task #" + backup.tag() + " (exit code " + exitCode + ")");
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BitferryError("repository initialization failure for task #" + backup.tag() + ": " + e.getMessage(), e);
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
        throw new BitferryError("the existing repository at '" + repo + "' does not accept the provided password");
      }
      restic.markConformed();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BitferryError("repository probe failure for task #" + restic.tag() + ": " + e.getMessage(), e);
    }
  }

  static boolean hasRepository(String repo) {
    return Files.isRegularFile(Path.of(repo).resolve("config"));
  }

  private static String repoPath(Restic restic) {
    return restic instanceof Backup backup
      ? backup.destination().processOpts()
      : restic.source().processOpts();
  }

  // Remove the repository subdirectories upon the forced re-initialization
  private void wipeRepository(Path repo) {
    for (String sub : List.of("config", "data", "index", "keys", "locks", "snapshots")) {
      Path subDir = repo.resolve(sub);
      if (Files.exists(subDir)) {
        try (Stream<Path> stream = Files.walk(subDir)) {
          for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(entry);
          }
        } catch (IOException e) {
          LOG.log(Level.WARNING, () -> "failed to wipe '" + subDir + "': " + e.getMessage());
        }
      }
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

    // Exclude filters
    cmd.add("--exclude");
    cmd.add(Bitferry.STORAGE);
    cmd.add("--exclude");
    cmd.add(Bitferry.STORAGE_IMAGE);
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

  List<String> maintainCheckCommand(Backup backup) {
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

  List<String> maintainForgetCommand(Backup backup) {
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

  List<List<String>> maintainCommands(Backup backup) {
    List<List<String>> cmds = new ArrayList<>();
    cmds.add(ExecutorSupport.displayCommand(maintainCheckCommand(backup)));
    List<String> forget = maintainForgetCommand(backup);
    if (forget != null) cmds.add(ExecutorSupport.displayCommand(forget));
    cmds.add(ExecutorSupport.displayCommand(pruneCommand(backup)));
    return cmds;
  }

  List<String> maintainCheckCommand(Restore restore) {
    Manager manager = restore.manager();
    List<String> checkCmd = new ArrayList<>();
    checkCmd.add(resolveExecutable());
    checkCmd.add("check");
    checkCmd.addAll(commonOptions(manager));
    checkCmd.add("-r");
    checkCmd.add(restore.source().processOpts());
    return checkCmd;
  }

  List<List<String>> maintainCommands(Restore restore) {
    return List.of(ExecutorSupport.displayCommand(maintainCheckCommand(restore)));
  }

  List<String> restoreCommand(Restore restore) {
    Manager manager = restore.manager();
    List<String> cmd = new ArrayList<>();
    cmd.add(resolveExecutable());
    cmd.add("restore");
    cmd.add("latest");
    cmd.add("--target");
    cmd.add(restore.destination().processOpts());

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

  private boolean runBackup(Backup backup, Consumer<String> output) {
    Manager manager = backup.manager();
    String repo = backup.destination().processOpts();
    Path repoDir = Path.of(repo);
    Path cwd = Path.of(backup.source().processOpts());

    // Maintenance check before backup: if due or state missing/corrupt, run maintenance first
    if (backup.cadence() != null) {
      RepoState state = RepoState.load(repoDir);
      if (state.isDue(backup.cadence())) {
        LOG.log(Level.INFO, () -> "maintenance cadence triggered for task #" + backup.tag() + "; running maintenance before backup");
        if (output != null) {
          output.accept("maintenance cadence triggered for task #" + backup.tag() + "; running maintenance before backup");
        }
        boolean maintained = maintainBackup(backup, output);
        if (!maintained) {
          String msg = "maintenance failed before backup for task #" + backup.tag() + "; aborting backup";
          LOG.log(Level.ERROR, () -> msg);
          if (output != null) output.accept(msg);
          return false;
        }
      }
    }

    if (output != null) {
      output.accept("backing up '" + backup.source().processOpts() + "' into repository '" + repo + "'");
    }

    List<String> cmd = backupCommand(backup);
    Map<String, String> environment = backup.processEnvironment();
    boolean success = ExecutorSupport.execute(commandExecutor, manager, cmd, cwd, environment, backup.tag(), output);
    if (!success) return false;

    // Record completed backup in repository state
    if (!manager.simulate) {
      RepoState state = RepoState.load(repoDir);
      state.recordBackup();
      state.save(repoDir);
    }

    return true;
  }

  boolean maintainBackup(Backup backup, Consumer<String> output) {
    Manager manager = backup.manager();
    String repo = backup.destination().processOpts();
    Map<String, String> environment = backup.processEnvironment();

    if (output != null) {
      output.accept("maintaining repository '" + repo + "'");
    }

    // Step 1: Structural check (gatekeeper before any retention/prune)
    List<String> checkCmd = maintainCheckCommand(backup);
    if (output != null) {
      output.accept("checking repository '" + repo + "'");
    }
    boolean checkOk = ExecutorSupport.execute(commandExecutor, manager, checkCmd, null, environment, backup.tag(), output);
    if (!checkOk) {
      String msg = "maintenance aborted for task #" + backup.tag() + ": repository check failed";
      LOG.log(Level.ERROR, () -> msg);
      if (output != null) output.accept(msg);
      return false;
    }

    // Step 2: Forget (retention)
    List<String> forgetCmd = maintainForgetCommand(backup);
    if (forgetCmd != null) {
      if (output != null) {
        output.accept("applying retention policy to repository '" + repo + "'");
      }
      boolean forgetOk = ExecutorSupport.execute(commandExecutor, manager, forgetCmd, null, environment, backup.tag(), output);
      if (!forgetOk) {
        return false;
      }
    }

    // Step 3: Prune (reclaim space)
    List<String> pruneCmd = pruneCommand(backup);
    if (output != null) {
      output.accept("pruning repository '" + repo + "'");
    }
    boolean pruneOk = ExecutorSupport.execute(commandExecutor, manager, pruneCmd, null, environment, backup.tag(), output);
    if (!pruneOk) {
      return false;
    }

    // Step 4: Record maintenance in repository state
    if (!manager.simulate) {
      Path repoDir = Path.of(repo);
      RepoState state = RepoState.load(repoDir);
      state.recordMaintenance();
      state.save(repoDir);
    }
    return true;
  }

  boolean maintainRestore(Restore restore, Consumer<String> output) {
    Manager manager = restore.manager();
    String repo = restore.source().processOpts();
    Map<String, String> environment = restore.processEnvironment();

    if (output != null) {
      output.accept("checking repository '" + repo + "'");
    }

    List<String> checkCmd = maintainCheckCommand(restore);
    return ExecutorSupport.execute(commandExecutor, manager, checkCmd, null, environment, restore.tag(), output);
  }

  private boolean runRestore(Restore restore, Consumer<String> output) {
    Manager manager = restore.manager();
    String repo = restore.source().processOpts();
    String target = restore.destination().processOpts();

    if (output != null) {
      output.accept("restoring repository '" + repo + "' into '" + target + "'");
    }

    List<String> cmd = restoreCommand(restore);
    return ExecutorSupport.execute(commandExecutor, manager, cmd, null, restore.processEnvironment(), restore.tag(), output);
  }

  private static String resolveExecutable() {
    return ExecutorSupport.resolveExecutable("restic");
  }

  private static List<String> commonOptions(Manager manager) {
    List<String> opts = new ArrayList<>();
    if (manager.verbose()) opts.add("--verbose");
    if (manager.quiet) opts.add("--quiet");
    return opts;
  }
}
