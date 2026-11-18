package io.bitferry.cli;

import io.bitferry.*;
import io.bitferry.task.*;
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
    return CliUtils.runBatch(spec, options, tags,
      t -> t.process(null),
      "execute",
      "@|green All %d task(s) executed successfully.|@",
      "@|red Some of the %d task(s) failed to execute.|@",
      "post-run commit failed");
  }
}
