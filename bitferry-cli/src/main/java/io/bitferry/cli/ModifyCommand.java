package io.bitferry.cli;

import java.nio.file.*;
import java.util.*;
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

  /// The additional volume probe roots: the volume discovery tuning rides the
  /// bare modify command (it is neither task- nor volume-specific, so it does
  /// not repeat on the subcommands; consumed through [CliUtils#restoreManager]).
  @Option(names = "--path", split = ",", description = "Additional directory paths to probe for volumes.", paramLabel = "PATH")
  public List<Path> paths;

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
