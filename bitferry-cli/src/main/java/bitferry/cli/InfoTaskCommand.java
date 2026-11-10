package bitferry.cli;

import bitferry.*;
import bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Print the command line about to execute for specified tasks designated by tags.
@Command(name = "task", aliases = {"t"}, mixinStandardHelpOptions = true, description = "Print the command line about to execute for specified tasks designated by tags.")
public class InfoTaskCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public InfoTaskCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(arity = "1..*", description = "Tags matching tasks to inspect.", paramLabel = "TAGS")
  List<String> tags;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    PrintWriter out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    Map<String, Task> tasks = m.selectTasks(tags);
    for (Task t : tasks.values()) {
      for (List<String> cmd : t.commands()) {
        out.println(String.join(" ", cmd));
      }
    }
    return 0;
  }
}
