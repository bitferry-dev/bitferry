package bitferry.task;

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
  public boolean serve(Task task, Consumer<String> output) {
    LOG.log(Level.INFO, () -> "servicing task #" + task.tag());
    if (task instanceof Rclone rclone) {
      return rcloneExecutor.serve(rclone, output);
    } else if (task instanceof Restic restic) {
      return resticExecutor.serve(restic, output);
    }
    String message = "unsupported task backend for service of task #" + task.tag() + ": " + task.getClass().getName();
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

  @Override
  public void passwd(Restic restic, String newPassword) {
    resticExecutor.passwd(restic, newPassword);
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
  public List<List<String>> commands(Task task) {
    // The dispatch is mandatory here: the callers hold the static Task type (the
    // overload resolution is static), so without this override the previews would
    // silently fall into the empty-interface default
    if (task instanceof Rclone rclone) {
      return rcloneExecutor.commands(rclone);
    } else if (task instanceof Backup backup) {
      return resticExecutor.commands(backup);
    } else if (task instanceof Restore restore) {
      return resticExecutor.commands(restore);
    }
    return List.of();
  }

  @Override
  public List<List<String>> serveCommands(Task task) {
    if (task instanceof Rclone rclone) {
      return rcloneExecutor.serveCommands(rclone);
    } else if (task instanceof Restic restic) {
      return resticExecutor.serveCommands(restic);
    }
    return List.of();
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
