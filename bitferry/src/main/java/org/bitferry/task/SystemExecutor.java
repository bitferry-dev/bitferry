package org.bitferry.task;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/// The default backend executor routing the task processing to the per-tool executors:
/// [RcloneExecutor] for the rclone-backed tasks and [ResticExecutor] for the
/// restic-backed ones. The tool executors build the backend command lines and spawn
/// the external utilities through the shared [CommandExecutor] seam.
public class SystemExecutor implements Executor {

  private static final System.Logger LOG = System.getLogger(SystemExecutor.class.getName());

  private final CommandExecutor commandExecutor;
  private final RcloneExecutor rcloneExecutor;
  private final ResticExecutor resticExecutor;

  public SystemExecutor() {
    this(CommandExecutor.system());
  }

  public SystemExecutor(CommandExecutor commandExecutor) {
    this.commandExecutor = Objects.requireNonNull(commandExecutor);
    this.rcloneExecutor = new RcloneExecutor(commandExecutor);
    this.resticExecutor = new ResticExecutor(commandExecutor);
  }

  public CommandExecutor commandExecutor() {
    return commandExecutor;
  }

  @Override
  public Task.Outcome run(Task task, Consumer<String> output) {
    LOG.log(Level.INFO, () -> "Processing task " + task.tag());
    // The double dispatch: the task lands on its per-tool slot
    return task.runWith(this, output);
  }

  @Override
  public Task.Outcome serve(Task task, Consumer<String> output) {
    LOG.log(Level.INFO, () -> "Servicing task " + task.tag());
    return task.serveWith(this, output);
  }

  @Override
  public Task.Outcome run(Rclone task, Consumer<String> output) {
    return rcloneExecutor.run(task, output);
  }

  @Override
  public Task.Outcome serve(Rclone task, Consumer<String> output) {
    return rcloneExecutor.serve(task, output);
  }

  @Override
  public Task.Outcome run(Backup task, Consumer<String> output) {
    return resticExecutor.runBackup(task, output);
  }

  @Override
  public Task.Outcome run(Restore task, Consumer<String> output) {
    return resticExecutor.runRestore(task, output);
  }

  @Override
  public Task.Outcome serve(Backup task, Consumer<String> output) {
    return resticExecutor.serveBackup(task, output);
  }

  @Override
  public Task.Outcome serve(Restore task, Consumer<String> output) {
    return resticExecutor.serveRestore(task, output);
  }

  @Override
  public void conform(Backup task) {
    resticExecutor.conformBackup(task);
  }

  @Override
  public void conform(Restore task) {
    resticExecutor.conformRestore(task);
  }

  @Override
  public void format(Backup backup) {
    resticExecutor.format(backup);
  }

  @Override
  public void conform(Rclone rclone) {
    rcloneExecutor.conform(rclone);
  }

  @Override
  public List<String> passwd(Restic restic, String newPassword) {
    return resticExecutor.passwd(restic, newPassword);
  }

  @Override
  public boolean repair(Task task, Repair.Operation operation, boolean dryRun, Consumer<String> output) {
    // The dispatch rides the operation id: the restic operations land on the restic
    // executor, the future backend operations extend the mapping
    if (!(task instanceof Restic restic)) {
      String message = "Repair operation " + operation.id() + " is not supported for task " + task.tag() + " (" + task.backend() + ")";
      LOG.log(Level.WARNING, () -> message);
      if (output != null) output.accept(message);
      return false;
    }
    return switch (operation.id()) {
      case "unlock-stale" -> resticExecutor.unlock(restic, false, dryRun, output);
      case "unlock-all" -> resticExecutor.unlock(restic, true, dryRun, output);
      default -> {
        String message = "Unknown repair operation " + operation.id() + " for task " + task.tag();
        LOG.log(Level.WARNING, () -> message);
        if (output != null) output.accept(message);
        yield false;
      }
    };
  }

  @Override
  public List<List<String>> commands(Rclone rclone) {
    return rcloneExecutor.commands(rclone);
  }

  @Override
  public List<List<String>> commands(Backup backup) {
    return resticExecutor.commands(backup);
  }

  @Override
  public List<List<String>> commands(Restore restore) {
    return resticExecutor.commands(restore);
  }

  @Override
  public List<List<String>> serveCommands(Rclone rclone) {
    return rcloneExecutor.serveCommands(rclone);
  }

  @Override
  public List<List<String>> serveCommands(Backup backup) {
    return resticExecutor.serveCommands(backup);
  }

  @Override
  public List<List<String>> serveCommands(Restore restore) {
    return resticExecutor.serveCommands(restore);
  }

  /// @see ExecutorSupport#executableName(String)
  static String executableName(String backend) {
    return ExecutorSupport.executableName(backend);
  }

  /// @see ExecutorSupport#resolveExecutable(String, Function, Path)
  static String resolveExecutable(String backend, Function<String, String> environment, Path javaHome) {
    return ExecutorSupport.resolveExecutable(backend, environment, javaHome);
  }

  /// @see ExecutorSupport#resolveExecutable(String)
  static String resolveExecutable(String backend) {
    return ExecutorSupport.resolveExecutable(backend);
  }

  /// @see ExecutorSupport#isBundled(String, String, Function, Path)
  static boolean isBundled(String executable, String backend, Function<String, String> environment, Path javaHome) {
    return ExecutorSupport.isBundled(executable, backend, environment, javaHome);
  }

  /// @see ExecutorSupport#isBundled(String, String)
  static boolean isBundled(String executable, String backend) {
    return ExecutorSupport.isBundled(executable, backend);
  }

  /// @see ExecutorSupport#displayExecutable(String, Function, Path)
  static String displayExecutable(String executable, Function<String, String> environment, Path javaHome) {
    return ExecutorSupport.displayExecutable(executable, environment, javaHome);
  }

  /// @see ExecutorSupport#displayExecutable(String)
  static String displayExecutable(String executable) {
    return ExecutorSupport.displayExecutable(executable);
  }

  /// @see ExecutorSupport#displayCommandLine(List)
  static String displayCommandLine(List<String> command) {
    return ExecutorSupport.displayCommandLine(command);
  }
}
