package io.bitferry.cli;

import io.bitferry.*;
import java.nio.file.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
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
    // The full manager restoration (not the narrow fresh one): the creation commits
    // the whole volume set, so the collected tombstones materialize their removal
    // from the other storages within this very command
    Manager m = CliUtils.restoreManager(opts);
    new Volume(m, root);
    m.commit();
    // The simulated creation writes nothing (the storages hold no such volume):
    // surface it - the silent exit would read as a success (mirroring the backup
    // creation's advisory)
    if (m.simulate()) {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint the volume creation simulated (nothing was written); re-run without --dry-run to create the volume for real|@"));
    }
    return 0;
  }
}
