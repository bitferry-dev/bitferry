package bitferry.task;

import bitferry.Bitferry;
import bitferry.Manager;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/// Rclone backend executor: builds the command line for the rclone-backed tasks (the
/// operation subcommand, the filters, the common options, the process options and the
/// crypt tail placing the ':crypt:' remote) and spawns rclone with the crypt password
/// environment.
final class RcloneExecutor {

  private final CommandExecutor commandExecutor;

  RcloneExecutor(CommandExecutor commandExecutor) {
    this.commandExecutor = commandExecutor;
  }

  /// Process the rclone-backed task. Return true on success.
  boolean run(Rclone rclone, Consumer<String> output) {
    Manager manager = rclone.manager();
    List<String> cmd = new ArrayList<>();
    cmd.add(ExecutorSupport.resolveExecutable("rclone"));

    // The backend operation arguments: the rclone subcommand and its modifiers
    cmd.addAll(rclone.operationArgs());

    // Include filters
    for (String inc : rclone.include()) {
      cmd.add("--filter");
      cmd.add("+ " + inc);
    }
    // Exclude Bitferry storage files
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE);
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE_IMAGE);
    // Exclude filters
    for (String exc : rclone.exclude()) {
      cmd.add("--filter");
      cmd.add("- " + exc);
    }

    // Common options
    cmd.add("--config");
    cmd.add(ExecutorSupport.isWindows() ? "NUL" : "/dev/null");
    if (manager.verbose()) {
      cmd.add("--verbose");
      cmd.add("--progress");
    }
    if (manager.quiet) {
      cmd.add("--quiet");
    }
    if (manager.simulate) {
      cmd.add("--dry-run");
    }

    // Process options
    cmd.addAll(rclone.processOptions());

    // The endpoints (or the crypt tail placing the ':crypt:' remote for the encrypted tasks)
    cmd.addAll(rclone.encryption().processOpts(rclone));

    // The crypt password environment (empty for the unencrypted tasks)
    return ExecutorSupport.execute(commandExecutor, manager, cmd, null, rclone.processEnvironment(), rclone.tag(), output);
  }
}
