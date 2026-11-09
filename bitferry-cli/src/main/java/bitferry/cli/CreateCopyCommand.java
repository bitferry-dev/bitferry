package bitferry.cli;

import bitferry.task.*;
import picocli.CommandLine.*;

/// Create unconditional one-way copy task.
@Command(name = "copy", aliases = {"c"}, mixinStandardHelpOptions = true, description = "Create copy task.")
public class CreateCopyCommand extends RcloneTaskCommand {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateCopyCommand() {}

  @Override
  protected TaskFactory taskFactory() {
    return Copy::new;
  }
}
