package io.bitferry.cli;

import io.bitferry.task.*;
import picocli.CommandLine.*;

/// Create update task.
@Command(name = "update", aliases = {"u"}, mixinStandardHelpOptions = true, description = "Create update task.")
public class CreateUpdateCommand extends RcloneTaskCommand {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateUpdateCommand() {}

  @Override
  protected TaskFactory taskFactory() {
    return Update::new;
  }
}
