package org.bitferry.cli;

import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Command group for creating volumes and tasks.
@Command(
  name = "create",
  aliases = {"c"},
  mixinStandardHelpOptions = true,
  description = "Create volume/task entity.",
  subcommands = {
    CreateVolumeCommand.class,
    CreateTaskCommand.class,
    CreateCopyCommand.class,
    CreateUpdateCommand.class,
    CreateReplicateCommand.class,
    CreateEqualizeCommand.class,
    CreateBackupCommand.class,
    CreateRestoreCommand.class
  }
)
public class CreateCommand implements Runnable, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateCommand() {}

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
