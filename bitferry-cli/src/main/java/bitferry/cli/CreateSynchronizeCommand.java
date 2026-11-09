package bitferry.cli;

import bitferry.task.*;
import picocli.CommandLine.*;

/// Create one-way synchronization task.
@Command(name = "synchronize", aliases = {"sync", "s"}, mixinStandardHelpOptions = true, description = "Create one-way synchronization task.")
public class CreateSynchronizeCommand extends RcloneTaskCommand {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateSynchronizeCommand() {}

  @Override
  protected TaskFactory taskFactory() {
    return Synchronize::new;
  }
}
