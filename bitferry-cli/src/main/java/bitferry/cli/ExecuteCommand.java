package bitferry.cli;

import bitferry.*;
import bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.*;
import picocli.CommandLine.Model.*;

/// Execute tasks matched by specified (partial) tags, or all intact tasks if no tags given.
@Command(name = "execute", aliases = {"x"}, mixinStandardHelpOptions = true, description = "Execute tasks matched by specified (partial) tags.")
public class ExecuteCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public ExecuteCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(arity = "0..*", description = "Tags matching tasks to execute.", paramLabel = "TAGS")
  List<String> tags;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    PrintWriter out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    PrintWriter err = spec != null ? spec.commandLine().getErr() : new PrintWriter(System.err, true);
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    // The tasks skipped due to the absent volumes are surfaced in verbose mode
    List<Task> stale = m.liveTasks().values().stream().filter(t -> !t.isIntact()).toList();
    if (!stale.isEmpty() && m.verbose()) {
      err.println(Ansi.AUTO.string("@|faint " + stale.size() + " task(s) skipped: their volumes are currently absent (see 'bitferry info -v')|@"));
    }
    // The mass-excluded tasks (manual-only) are surfaced in verbose mode
    if (tags == null || tags.isEmpty()) {
      long manual = m.liveTasks().values().stream().filter(Task::isIntact).filter(Task::isManual).count();
      if (manual > 0 && m.verbose()) {
        err.println(Ansi.AUTO.string("@|faint " + manual + " task(s) excluded from the mass run (manual-only; execute them by their tags)|@"));
      }
    }
    Map<String, Task> tasks = m.selectTasks(tags);
    // An empty selection succeeds silently
    if (tasks.isEmpty()) return 0;
    int n = tasks.size();
    boolean outcome = true;
    int i = 0;
    for (Task t : tasks.values()) {
      i++;
      if (!m.quiet) {
        String line = String.format("@|bold [%d/%d]|@ @|cyan %s|@  %s", i, n, t.tag(), CliUtils.formatTask(t));
        out.println(Ansi.AUTO.string(line));
      }
      boolean ok = t.process(null);
      outcome = ok && outcome;
      if (!m.quiet) {
        String status = ok ? "@|green     ✓ success|@" : "@|red     ✗ failure|@";
        out.println(Ansi.AUTO.string(status));
      }
    }
    if (!m.quiet) {
      out.println();
      String summary = outcome
        ? String.format("@|green All %d task(s) executed successfully.|@", n)
        : String.format("@|red Some of the %d task(s) failed to execute.|@", n);
      out.println(Ansi.AUTO.string(summary));
    }
    // Persist the run bookkeeping (e.g. the equalize bisync baseline marker): only the
    // volumes whose state actually changed are rewritten. A failing commit warns without
    // failing the run outcome: the unmaterialized bookkeeping flips re-materialize upon
    // the following commits.
    try {
      m.commit();
    } catch (BitferryError e) {
      err.println(Ansi.AUTO.string("@|red     ✗ post-run commit failed: " + e.getMessage() + "|@"));
    }
    return outcome ? 0 : 1;
  }
}
