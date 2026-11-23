package org.bitferry.cli;

import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Command group for creating task entities.
@Command(
  name = "task",
  aliases = {"t"},
  mixinStandardHelpOptions = true,
  description = "Create task entity.",
  subcommands = {
    CreateCopyCommand.class,
    CreateUpdateCommand.class,
    CreateReplicateCommand.class,
    CreateEqualizeCommand.class,
    CreateBackupCommand.class,
    CreateRestoreCommand.class
  }
)
public class CreateTaskCommand implements Runnable, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateTaskCommand() {}

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
