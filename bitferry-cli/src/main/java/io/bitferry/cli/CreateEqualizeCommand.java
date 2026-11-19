package io.bitferry.cli;

import io.bitferry.task.*;
import picocli.CommandLine.*;

/// Create two-way synchronization task.
@Command(name = "equalize", aliases = {"e"}, description = "Create two-way synchronization task.")
public class CreateEqualizeCommand extends RcloneTaskCommand {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateEqualizeCommand() {}

  @Override
  protected TaskFactory taskFactory() {
    return Equalize::new;
  }
}
