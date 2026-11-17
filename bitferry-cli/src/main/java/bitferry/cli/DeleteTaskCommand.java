package bitferry.cli;

import bitferry.*;
import bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Delete existing tasks whose tags match specified tags.
@Command(name = "task", aliases = {"t"}, mixinStandardHelpOptions = true, description = "Delete existing tasks whose tags match specified tags.")
public class DeleteTaskCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public DeleteTaskCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(arity = "1..*", description = "Tags matching tasks to delete.", paramLabel = "TAGS")
  List<String> tags;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    // The deletion is irreversible (the deletion tombstone propagates to all the
    // referring storages; only the payload data is deliberately preserved) and the
    // selection is fuzzy (a substring match), so the accidental one-keystroke
    // destruction is gated behind --force (the GUI flow confirms interactively instead)
    if (!m.force) {
      throw new BitferryError("Task deletion requires --force (it tombstones the task in every referring storage); preview the selection with --dry-run");
    }
    for (Task t : m.selectLiveTasks(tags)) {
      t.delete();
    }
    m.commit();
    return 0;
  }
}
