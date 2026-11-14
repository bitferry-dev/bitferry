package bitferry.cli;

import bitferry.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Delete existing volumes whose tags match specified tags.
@Command(name = "volume", aliases = {"v"}, mixinStandardHelpOptions = true, description = "Delete existing volumes whose tags match specified tags.")
public class DeleteVolumeCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public DeleteVolumeCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(arity = "1..*", description = "Tags matching volumes to delete.", paramLabel = "TAGS")
  List<String> tags;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    for (Volume v : m.selectVolumes(tags)) {
      v.delete();
    }
    m.commit();
    return 0;
  }
}
