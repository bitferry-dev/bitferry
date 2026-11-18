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
    return CliUtils.runBatch(spec, options, tags,
      t -> t.serve(null),
      "serve",
      "@|green All %d task(s) serviced successfully.|@",
      "@|red Some of the %d task(s) failed servicing.|@",
      "post-service commit failed");
  }
}
