package io.bitferry.cli;

import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Command group for modifying volume and task entities.
@Command(
  name = "modify",
  aliases = {"m"},
  mixinStandardHelpOptions = true,
  description = "Modify volume/task entities.",
  subcommands = {
    ModifyTaskCommand.class
  }
)
public class ModifyCommand implements Runnable, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public ModifyCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }
}
