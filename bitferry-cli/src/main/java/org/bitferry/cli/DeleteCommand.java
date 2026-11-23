package org.bitferry.cli;

import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Command group for deleting volumes and tasks.
@Command(
  name = "delete",
  aliases = {"d"},
  mixinStandardHelpOptions = true,
  description = "Delete volume/task entities.",
  subcommands = {
    DeleteVolumeCommand.class,
    DeleteTaskCommand.class
  }
)
public class DeleteCommand implements Runnable, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public DeleteCommand() {}

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
