package bitferry.cli;

import bitferry.task.*;
import picocli.CommandLine.*;

/// Create two-way synchronization task.
@Command(name = "equalize", aliases = {"bisync", "e"}, mixinStandardHelpOptions = true, description = "Create two-way synchronization task.")
public class CreateEqualizeCommand extends RcloneTaskCommand {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateEqualizeCommand() {}

  @Override
  protected TaskFactory taskFactory() {
    return Equalize::new;
  }
}
