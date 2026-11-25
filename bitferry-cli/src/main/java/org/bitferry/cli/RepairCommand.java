package org.bitferry.cli;

import org.bitferry.*;
import org.bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.*;

/// Execute backend repair operations on the matched tasks (e.g. removing the stale
/// restic repository locks left behind by the terminated runs). The operations come
/// from the backend-neutral registry ([Repair#operationsFor]); the destructive ones
/// are gated behind --force mirroring the task deletion.
@Command(name = "repair", mixinStandardHelpOptions = true, description = "Execute backend repair operations on the matched tasks (e.g. removing the stale repository locks).")
public class RepairCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public RepairCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Option(names = "--all", description = "Remove every lock regardless of staleness (destructive; requires --force) instead of the stale ones only.")
  boolean all;

  @Parameters(arity = "1..*", description = "Tags matching tasks to repair.", paramLabel = "TAGS")
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
    Manager m = CliUtils.restoreManager(spec, opts);
    var selected = m.selectTasks(tags).values();

    // The resolution and the validation run upfront (mirroring the modify command):
    // a rejected task must not leave the earlier tasks half-repaired
    record Planned(Task task, Repair.Operation operation) {}
    List<Planned> planned = new ArrayList<>();
    for (Task t : selected) {
      List<Repair.Operation> operations = Repair.operationsFor(t);
      if (operations.isEmpty()) {
        throw new BitferryError("Task " + t.tag() + ": no repair operations are available for " + t.backend() + " tasks");
      }
      Repair.Operation operation = all ? Repair.UNLOCK_ALL : operations.getFirst();
      if (!operations.contains(operation)) {
        throw new BitferryError("Task " + t.tag() + ": the backend provides no '" + operation.id() + "' repair operation");
      }
      if (operation.destructive() && !m.force && !m.simulate) {
        throw new BitferryError("Repair operation '" + operation.label() + "' is destructive (it removes the locks of the possibly running clients); it requires --force");
      }
      planned.add(new Planned(t, operation));
    }

    boolean outcome = true;
    int i = 0;
    for (Planned p : planned) {
      i++;
      if (!m.quiet) {
        out.println(Ansi.AUTO.string("@|bold [" + i + "/" + planned.size() + "]|@ @|cyan " + p.task().tag() + "|@  " + p.operation().label()));
      }
      // The dry-run preview informs the destructive decision (the captured run lists
      // the locks that would be removed); the real run keeps the captured channel so
      // the lock-conflict hint of the executor stays visible
      List<String> preview = new ArrayList<>();
      boolean previewOk = m.repair(p.task(), p.operation(), true, preview::add);
      for (String line : preview) {
        out.println("  " + line);
      }
      if (preview.isEmpty() && !m.quiet) {
        out.println(Ansi.AUTO.string("  @|faint (no output - nothing to remove)|@"));
      }
      if (!previewOk) {
        outcome = false;
        if (!m.quiet) {
          String status = Ansi.AUTO.string(Symbols.unicode() ? "@|red ✗ failure|@" : "@|red x failure|@");
          out.println(status);
        }
        continue;
      }
      if (m.simulate) {
        if (!m.quiet) {
          out.println(Ansi.AUTO.string("@|faint Simulated (nothing was modified)|@"));
        }
        continue;
      }
      boolean ok = m.repair(p.task(), p.operation(), false, out::println);
      outcome = ok && outcome;
      if (!m.quiet) {
        // The ASCII approximations on the non-unicode consoles (mirroring the batch runner)
        String status = ok
          ? Ansi.AUTO.string(Symbols.unicode() ? "@|green ✓ success|@" : "@|green ok success|@")
          : Ansi.AUTO.string(Symbols.unicode() ? "@|red ✗ failure|@" : "@|red x failure|@");
        out.println(status);
      }
    }
    return outcome ? 0 : 1;
  }
}
