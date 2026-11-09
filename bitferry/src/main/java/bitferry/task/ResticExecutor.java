package bitferry.task;

import bitferry.Manager;
import bitferry.Bitferry;
import bitferry.BitferryError;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

  /// Initialize (or wipe and recreate, or attach to) the repository of the backup task.
  void format(Backup backup) {
    Manager manager = backup.manager();
    if (manager.simulate) {
      LOG.log(Level.INFO, () -> "simulated repository initialization for task #" + backup.tag());
      return;
    }
    String repo = backup.destination().processOpts();
    if (Boolean.FALSE.equals(backup.formatMode())) {
      LOG.log(Level.INFO, () -> "attaching task #" + backup.tag() + " to existing repository '" + repo + "'");
      return;
    }
    if (Boolean.TRUE.equals(backup.formatMode())) {
      LOG.log(Level.INFO, () -> "wiping existing repository '" + repo + "' for task #" + backup.tag());
      wipeRepository(Path.of(repo));
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

  private boolean runBackup(Backup backup, Consumer<String> output) {
    Manager manager = backup.manager();
    String executable = resolveExecutable();
    String repo = backup.destination().processOpts();
    Path cwd = Path.of(backup.source().processOpts());

    if (output != null) {
      output.accept("backing up '" + backup.source().processOpts() + "' into repository '" + repo + "'");
    }

    List<String> cmd = new ArrayList<>();
    cmd.add(executable);
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

    Map<String, String> environment = backup.processEnvironment();
    boolean success = ExecutorSupport.execute(commandExecutor, manager, cmd, cwd, environment, backup.tag(), output);
    if (!success) return false;

    // Optional integrity check
    if (backup.check() != null && !backup.check().isEmpty()) {
      if (output != null) {
        output.accept("checking repository '" + repo + "'");
      }
      List<String> checkCmd = new ArrayList<>();
      checkCmd.add(executable);
      checkCmd.add("check");
      checkCmd.addAll(backup.check());
      checkCmd.addAll(commonOptions(manager));
      checkCmd.add("-r");
      checkCmd.add(repo);
      if (!ExecutorSupport.execute(commandExecutor, manager, checkCmd, null, environment, backup.tag(), output)) {
        return false;
      }
    }

    // Optional retention forget
    if (backup.forget() != null && !backup.forget().isEmpty()) {
      if (output != null) {
        output.accept("applying retention policy to repository '" + repo + "'");
      }
      List<String> forgetCmd = new ArrayList<>();
      forgetCmd.add(executable);
      forgetCmd.add("forget");
      forgetCmd.add("--tag");
      forgetCmd.add("bitferry," + backup.tag());
      forgetCmd.addAll(backup.forget());
      forgetCmd.addAll(commonOptions(manager));
      if (manager.simulate) {
        forgetCmd.add("--dry-run");
      }
      forgetCmd.add("-r");
      forgetCmd.add(repo);
      if (!ExecutorSupport.execute(commandExecutor, manager, forgetCmd, null, environment, backup.tag(), output)) {
        return false;
      }
    }

    return true;
  }

  private boolean runRestore(Restore restore, Consumer<String> output) {
    Manager manager = restore.manager();
    String executable = resolveExecutable();
    String repo = restore.source().processOpts();
    String target = restore.destination().processOpts();

    if (output != null) {
      output.accept("restoring repository '" + repo + "' into '" + target + "'");
    }

    List<String> cmd = new ArrayList<>();
    cmd.add(executable);
    cmd.add("restore");
    cmd.add("latest");
    cmd.add("--target");
    cmd.add(target);

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
    cmd.add(repo);

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
