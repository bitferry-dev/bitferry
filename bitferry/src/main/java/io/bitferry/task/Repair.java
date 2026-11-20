package io.bitferry.task;

import java.util.List;

/// The repair operations registry: the backend-neutral source for the frontends'
/// repair surfaces (the GUI repair dialog, the CLI repair command). Every operation
/// carries its stable id (the executor dispatch key), the display label, the user
/// description, and the destructive flag gating the explicit confirmation. The
/// operations are per-task: the applicable set rides the task's backend
/// ([Repair#operationsFor]), so the frontends stay backend-agnostic and the
/// future backend operations plug in without touching them.
public final class Repair {

  /// One selectable repair operation.
  ///
  /// @param id          the stable dispatch key (the executor chain switches on it)
  /// @param label       the display label for the pickers
  /// @param description the user-facing explanation (the implications and the tradeoffs)
  /// @param destructive whether the operation requires an explicit dangerous-action
  ///                    confirmation (the CLI gates it behind --force mirroring the
  ///                    task deletion)
  public record Operation(String id, String label, String description, boolean destructive) {

    /// The pickers render the label alone; the description travels separately.
    @Override
    public String toString() {
      return label;
    }
  }

  /// Remove the repository locks restic considers stale (their holding process is no
  /// longer running on the same host): the safe repair for the locks left behind by
  /// the terminated backends (an aborted run on the platforms where the backend
  /// termination is immediate and the lock cleanup never happens).
  public static final Operation UNLOCK_STALE = new Operation("unlock-stale",
    "remove stale locks",
    "Remove the repository locks Restic considers stale (their holding process is no longer running on this host); "
      + "the locks of the running clients (here or on other hosts) stay",
    false);

  /// Remove every repository lock regardless of staleness: the repair for the
  /// foreign-host locks of the dead processes (restic never auto-classifies the
  /// other hosts' locks as stale). Destructive: the lock of a legitimately running
  /// client is removed along, and two exclusive operations may then race the
  /// repository - the exact corruption the lock exists to prevent.
  public static final Operation UNLOCK_ALL = new Operation("unlock-all",
    "remove all locks",
    "Remove every Restic repository lock, including the ones of the possibly running clients on this or other hosts; "
      + "destructive - proceed only when the holders are known dead",
    true);

  private Repair() {}

  /// Return the repair operations applicable to the specified task (empty for the
  /// tasks whose backend provides none). The restic tasks carry the repository lock
  /// operations; the future backend operations extend this mapping.
  public static List<Operation> operationsFor(Task task) {
    if (task instanceof Restic) {
      return List.of(UNLOCK_STALE, UNLOCK_ALL);
    }
    return List.of();
  }
}
