package bitferry.cli;

import bitferry.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Top-level Bitferry command line interface.
@Command(
  name = "bitferry",
  mixinStandardHelpOptions = true,
  versionProvider = BitferryCommand.VersionProvider.class,
  description = "File synchronization/backup automation tool.",
  subcommands = {
    InfoCommand.class,
    CreateCommand.class,
    DeleteCommand.class,
    ExecuteCommand.class
  }
)
public class BitferryCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public BitferryCommand() {}

  /// The runtime version information provider (the annotation values must be the
  /// compile-time constants while the library version is resolved from the
  /// build-provided version resource at runtime).
  // Explicit no-arg constructor: picocli instantiates the version provider reflectively.
  public static class VersionProvider implements IVersionProvider {

    public VersionProvider() {}

    @Override
    public String[] getVersion() {
      return new String[]{"bitferry " + Bitferry.VERSION};
    }
  }

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    // Invoked without subcommands: default to the info action
    InfoCommand info = new InfoCommand();
    info.spec = this.spec;
    return info.runWith(options);
  }
}
