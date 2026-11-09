package bitferry.cli;

import bitferry.*;
import java.nio.file.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Create a new Bitferry volume entity.
@Command(name = "volume", aliases = {"v"}, mixinStandardHelpOptions = true, description = "Create volume entity.")
public class CreateVolumeCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateVolumeCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Root directory for a new Bitferry volume.", paramLabel = "ROOT")
  Path root;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.createManager(opts);
    new Volume(m, root);
    m.commit();
    return 0;
  }
}
