package org.bitferry.cli;

import org.bitferry.*;
import org.bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.*;

/// Print complete breakdown of specified tasks designated by tags.
@Command(name = "task", aliases = {"t"}, mixinStandardHelpOptions = true, description = "Print complete breakdown of specified tasks designated by tags.")
public class InfoTaskCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public InfoTaskCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Option(names = {"-c", "--commands"}, description = "Print only the backend command lines (bare format).")
  boolean commandsOnly;

  @Parameters(arity = "1..*", description = "Tags matching tasks to inspect.", paramLabel = "TAGS")
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
    Map<String, Task> tasks = m.selectTasks(tags);
    boolean first = true;
    for (Task t : tasks.values()) {
      if (commandsOnly) {
        // The per-task degradation: a task whose backend cannot build the commands
        // (e.g. a backup on an absent destination volume) reports '(unavailable)'
        // instead of aborting the whole listing (mirroring TaskBreakdown.appendCommands)
        try {
          for (List<String> cmd : t.commands()) {
            // The display form quotes the tokens carrying whitespace: the echoed line
            // stays pasteable into a shell (mirroring ExecutorSupport.echoCommandLine)
            out.println(ExecutorSupport.displayCommandLine(cmd));
          }
        } catch (BitferryError e) {
          err.println(Ansi.AUTO.string("@|yellow (unavailable: " + e.getMessage() + ")|@"));
        }
      } else {
        if (!first) {
          out.println();
        }
        first = false;
        out.print(TaskBreakdown.format(t));
      }
    }
    out.flush();
    return 0;
  }
}
