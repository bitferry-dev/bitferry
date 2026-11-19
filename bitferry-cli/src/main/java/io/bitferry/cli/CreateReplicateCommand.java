package io.bitferry.cli;

import io.bitferry.task.*;
import picocli.CommandLine.*;

/// Create one-way replication task.
@Command(name = "replicate", aliases = {"s"}, description = "Create one-way replication task.")
public class CreateReplicateCommand extends RcloneTaskCommand {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateReplicateCommand() {}

  @Override
  protected TaskFactory taskFactory() {
    return Replicate::new;
  }
}
