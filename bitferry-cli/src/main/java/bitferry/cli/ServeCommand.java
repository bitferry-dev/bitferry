package bitferry.cli;

import bitferry.*;
import bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.*;
import picocli.CommandLine.Model.*;

/// Perform service tasks (integrity check, retention, pruning) on matched tasks or repositories.
@Command(name = "serve", aliases = {"s"}, mixinStandardHelpOptions = true, description = "Perform service tasks (integrity check, retention, pruning) on matched tasks or repositories.")
public class ServeCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public ServeCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(arity = "0..*", description = "Tags matching tasks to service.", paramLabel = "TAGS")
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
        err.println(Ansi.AUTO.string("@|faint " + manual + " task(s) excluded from the mass run (manual-only; serve them by their tags)|@"));
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
        String line = String.format(Locale.ROOT, "@|bold [%d/%d]|@ @|cyan %s|@  %s", i, n, t.tag(), CliUtils.formatTask(t));
        out.println(Ansi.AUTO.string(line));
      }
      // The task-level failures isolate to the failing task mirroring [Manager.serve]
      // and the GUI runner: a BitferryError (a vanished volume, a dropped vault token)
      // used to abort the whole batch here, skipping the remaining tasks, the per-task
      // status line, the summary and the post-service commit
      boolean ok;
      try {
        ok = t.serve(null);
      } catch (BitferryError e) {
        ok = false;
        if (!m.quiet) out.println(Ansi.AUTO.string("@|red     error: " + e.getMessage() + "|@"));
      } catch (RuntimeException e) {
        ok = false;
        if (!m.quiet) out.println(Ansi.AUTO.string("@|red     unexpected failure: " + e + "|@"));
      }
      outcome = ok && outcome;
      if (!m.quiet) {
        // The ASCII approximations on the non-unicode consoles: the encoder would
        // otherwise collapse the marks into the semantically lossy '?' (mirroring
        // [TaskBreakdown.formatStatus])
        String status = ok
          ? Ansi.AUTO.string(Symbols.unicode() ? "@|green     ✓ success|@" : "@|green     ok success|@")
          : Ansi.AUTO.string(Symbols.unicode() ? "@|red     ✗ failure|@" : "@|red     x failure|@");
        out.println(status);
      }
    }
    if (!m.quiet) {
      out.println();
      String summary = outcome
        ? String.format(Locale.ROOT, "@|green All %d task(s) serviced successfully.|@", n)
        : String.format(Locale.ROOT, "@|red Some of the %d task(s) failed servicing.|@", n);
      out.println(Ansi.AUTO.string(summary));
    }
    // Persist the post-service state changes (mirroring ExecuteCommand and Runner):
    // only the volumes whose state actually changed are rewritten. A failing commit warns
    // without failing the run outcome.
    if (m.isModified()) {
      try {
        m.commit();
      } catch (BitferryError e) {
        err.println(Ansi.AUTO.string("@|red     ✗ post-service commit failed: " + e.getMessage() + "|@"));
      }
    }
    return outcome ? 0 : 1;
  }
}
