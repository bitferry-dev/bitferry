package bitferry.task;

import java.lang.System.Logger.Level;
import java.util.function.*;

/// Task processing backend executor spawning the external backend utilities
/// (rclone, restic). The complete implementation arriving with the backend port
/// will resolve the executables through the RCLONE and RESTIC environment variables.
public interface Executor {

  /// Execute the task's backend invocations forwarding the combined output line by line
  /// to the specified callback (null for inherited stdio / CLI pass-through). Return true on success.
  boolean run(Task task, Consumer<String> output);

  /// Execute the task with inherited stdio (CLI pass-through).
  default boolean run(Task task) {
    return run(task, null);
  }

  /// Perform one-time backend formatting upon task creation (e.g. restic init).
  default void format(Task task) {}

  /// Default system process backend executor.
  static Executor system() {
    return new SystemExecutor();
  }

  /// System backend executor with a pluggable command runner.
  static Executor system(CommandExecutor commandExecutor) {
    return new SystemExecutor(commandExecutor);
  }

  /// The stub executor reporting missing backend execution and failing.
  static Executor stub() {
    return Stub.INSTANCE;
  }

  /// The stub executor logging the unimplemented processing and failing.
  final class Stub implements Executor {

    public static final Stub INSTANCE = new Stub();

    private static final System.Logger LOG = System.getLogger(Stub.class.getName());

    private Stub() {}

    @Override
    public boolean run(Task task, Consumer<String> output) {
      String message = "backend execution is not implemented for task #" + task.tag() + "; the complete backend port is upcoming";
      LOG.log(Level.WARNING, () -> message);
      if (output != null) output.accept(message);
      return false;
    }
  }
}
