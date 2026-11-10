package bitferry.task;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
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
  public boolean run(Task task, Consumer<String> output) {
    LOG.log(Level.INFO, () -> "processing task #" + task.tag());
    if (task instanceof Rclone rclone) {
      return rcloneExecutor.run(rclone, output);
    } else if (task instanceof Restic restic) {
      return resticExecutor.run(restic, output);
    }
    String message = "unsupported task backend for task #" + task.tag() + ": " + task.getClass().getName();
    LOG.log(Level.ERROR, () -> message);
    if (output != null) output.accept(message);
    return false;
  }

  @Override
  public void format(Backup backup) {
    resticExecutor.format(backup);
  }

  @Override
  public void conform(Restic restic) {
    resticExecutor.conform(restic);
  }

  @Override
  public void conform(Rclone rclone) {
    rcloneExecutor.conform(rclone);
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
}
