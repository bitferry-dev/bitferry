package org.bitferry.task;

import org.bitferry.Manager;
import org.bitferry.endpoint.Endpoint;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.function.*;

/// Task processing backend executor spawning the external backend utilities
/// (rclone, restic). The complete implementation arriving with the backend port
/// will resolve the executables through the RCLONE and RESTIC environment variables.
public interface Executor {

  /// Execute the task's backend invocations forwarding the combined output line by line
  /// to the specified callback (null for inherited stdio / CLI pass-through).
  Task.Outcome run(Task task, Consumer<String> output);

  /// Execute the task with inherited stdio (CLI pass-through).
  default Task.Outcome run(Task task) {
    return run(task, null);
  }

  /// Execute the rclone-backed task (the double-dispatch slot the task's runWith
  /// lands on).
  default Task.Outcome run(Rclone task, Consumer<String> output) {
    return run((Task) task, output);
  }

  /// Execute the restic-backed task.
  default Task.Outcome run(Restic task, Consumer<String> output) {
    return run((Task) task, output);
  }

  /// Execute the backup task (the retention/service semantics are backup specific).
  default Task.Outcome run(Backup task, Consumer<String> output) {
    return run((Restic) task, output);
  }

  /// Execute the restore task.
  default Task.Outcome run(Restore task, Consumer<String> output) {
    return run((Restic) task, output);
  }

  /// Perform one-time backend formatting upon task creation (e.g. restic init).
  default void format(Task task) {}

  /// Perform one-time backend formatting upon backup task creation (e.g. restic init).
  default void format(Backup task) {
    format((Task) task);
  }

  /// Perform encryption and backend target conformance validation upon task creation.
  default void conform(Task task) {}

  /// Perform encryption and backend target conformance validation for restic tasks upon creation.
  default void conform(Restic task) {
    conform((Task) task);
  }

  /// Perform encryption and backend target conformance validation for rclone tasks upon creation.
  default void conform(Rclone task) {
    conform((Task) task);
  }

  /// Perform the backup-specific conformance validation (the forced re-initialization
  /// skips it, the fresh repositories defer to the formatting, the existing ones probe).
  default void conform(Backup task) {
    conform((Restic) task);
  }

  /// Perform the restore-specific conformance validation (the restore tasks refuse the
  /// repositories the provided password does not open).
  default void conform(Restore task) {
    conform((Restic) task);
  }

  /// Change or update the repository password for a restic task, propagating the new
  /// token to the registered co-repository tasks. Return the tags of the invisible
  /// co-repository tasks whose stored tokens could not be updated (their volumes are
  /// absent) - the tokens those tasks hold are stale until re-synced.
  default List<String> passwd(Restic task, String newPassword) {
    return List.of();
  }

  /// Execute the given repair operation ([Repair]) on the task (e.g. removing the
  /// stale repository locks). The dry-run form only reports what would be done.
  /// Return true on success. The base reports the unsupported operation: only the
  /// backends providing the operations listed in [Repair#operationsFor] implement
  /// the dispatch.
  default boolean repair(Task task, Repair.Operation operation, boolean dryRun, Consumer<String> output) {
    String message = "Repair operation " + operation.id() + " is not supported for task " + task.tag() + " (" + task.backend() + ")";
    if (output != null) output.accept(message);
    return false;
  }

  /// Return the command lines about to execute for the task.
  default List<List<String>> commands(Task task) {
    return List.of();
  }

  /// Return the command lines about to execute for the rclone task.
  default List<List<String>> commands(Rclone task) {
    return commands((Task) task);
  }

  /// Return the command lines about to execute for the backup task.
  default List<List<String>> commands(Backup task) {
    return commands((Task) task);
  }

  /// Return the command lines about to execute for the restore task.
  default List<List<String>> commands(Restore task) {
    return commands((Task) task);
  }

  /// Return the command lines about to execute for the restic task.
  default List<List<String>> commands(Restic task) {
    return commands((Task) task);
  }

  /// List snapshots in the repository of a restic task.
  default List<Snapshot> snapshots(Restic task) {
    return List.of();
  }

  /// List snapshots in the specified repository endpoint using the provided password.
  default List<Snapshot> snapshots(Manager manager, Endpoint repository, String password) {
    return List.of();
  }

  /// Perform service (integrity verification, snapshot retention, and garbage collection)
  /// on the task or its target repository.
  default Task.Outcome serve(Task task, Consumer<String> output) {
    return Task.Outcome.SUCCESS;
  }

  /// Service the task with inherited stdio (CLI pass-through).
  default Task.Outcome serve(Task task) {
    return serve(task, null);
  }

  /// Service the rclone-backed task (the double-dispatch slot the task's serveWith
  /// lands on).
  default Task.Outcome serve(Rclone task, Consumer<String> output) {
    return serve((Task) task, output);
  }

  /// Service the restic-backed task.
  default Task.Outcome serve(Restic task, Consumer<String> output) {
    return serve((Task) task, output);
  }

  /// Service the backup task (the check, the retention and the prune sequence).
  default Task.Outcome serve(Backup task, Consumer<String> output) {
    return serve((Restic) task, output);
  }

  /// Service the restore task (the repository integrity check).
  default Task.Outcome serve(Restore task, Consumer<String> output) {
    return serve((Restic) task, output);
  }


  /// Return the backend command lines about to execute for servicing this task.
  default List<List<String>> serveCommands(Task task) {
    return List.of();
  }

  default List<List<String>> serveCommands(Backup task) {
    return serveCommands((Task) task);
  }

  default List<List<String>> serveCommands(Restore task) {
    return serveCommands((Task) task);
  }

  default List<List<String>> serveCommands(Rclone task) {
    return serveCommands((Task) task);
  }

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
    public Task.Outcome run(Task task, Consumer<String> output) {
      String message = "Backend execution is not implemented for task " + task.tag() + "; the complete backend port is upcoming";
      LOG.log(Level.WARNING, () -> message);
      if (output != null) output.accept(message);
      return Task.Outcome.FAILURE;
    }

    @Override
    public Task.Outcome serve(Task task, Consumer<String> output) {
      String message = "Backend service is not implemented for task " + task.tag() + "; the complete backend port is upcoming";
      LOG.log(Level.WARNING, () -> message);
      if (output != null) output.accept(message);
      return Task.Outcome.FAILURE;
    }
  }
}
