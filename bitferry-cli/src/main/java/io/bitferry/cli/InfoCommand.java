package io.bitferry.cli;

import io.bitferry.*;
import io.bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.*;
import picocli.CommandLine.Model.*;

/// Print information on available volumes and tasks.
@Command(
  name = "info",
  aliases = {"i"},
  mixinStandardHelpOptions = true,
  description = "Print information on available volumes and tasks.",
  subcommands = {
    InfoTaskCommand.class
  }
)
public class InfoCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public InfoCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    return runWith(CliUtils.resolveOptions(spec, options));
  }

  /// Execute the info command with the resolved options.
  public int runWith(CommonOptions resolvedOptions) {
    PrintWriter out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    Manager m = CliUtils.restoreManager(resolvedOptions);
    Map<String, Volume> vs = m.volumes();
    List<Task> intactTasks = vs.values().stream()
      .flatMap(v -> v.intactTasks().values().stream())
      .distinct()
      .sorted(Comparator.comparing(Task::tag))
      .toList();
    List<Task> staleTasks = vs.values().stream()
      .flatMap(v -> v.staleTasks().values().stream())
      .distinct()
      .sorted(Comparator.comparing(Task::tag))
      .toList();

    if (vs.isEmpty() && intactTasks.isEmpty()) {
      out.println(Ansi.AUTO.string("@|faint No volumes or tasks found.|@"));
      return 0;
    }
    if (!vs.isEmpty()) {
      out.println();
      out.println(Ansi.AUTO.string("@|bold # Intact volumes|@"));
      for (Volume v : vs.values().stream().sorted(Comparator.comparing(Volume::tag)).toList()) {
        out.println(Ansi.AUTO.string(String.format("  @|cyan %s|@    %s", v.tag(), v.root())));
      }
    }
    if (!intactTasks.isEmpty()) {
      out.println();
      out.println(Ansi.AUTO.string("@|bold # Intact tasks|@"));
      for (Task t : intactTasks) {
        out.println(Ansi.AUTO.string(String.format("  @|cyan %s|@    %s", t.tag(), CliUtils.formatTask(t))));
      }
    }
    if (!staleTasks.isEmpty() && m.verbose()) {
      out.println();
      out.println(Ansi.AUTO.string("@|bold,red # Stale tasks|@"));
      for (Task t : staleTasks) {
        out.println(Ansi.AUTO.string(String.format("  @|cyan %s|@    %s", t.tag(), CliUtils.formatTask(t))));
      }
    }
    return 0;
  }
}

