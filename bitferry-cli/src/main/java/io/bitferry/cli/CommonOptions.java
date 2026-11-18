package io.bitferry.cli;

import java.nio.file.*;
import java.util.*;
import picocli.CommandLine.*;

/// Common command line options that can be supplied before or after subcommands.
public class CommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CommonOptions() {}

  @Option(names = {"-q", "--quiet"}, description = "Be quiet (produce the least output).")
  public boolean quiet;

  @Option(names = {"-v", "--verbose"}, description = "Be verbose (produce more output).")
  public boolean verbose;

  @Option(names = "--debug", description = "Be extra verbose (produce the most output including exception traces).")
  public boolean debug;

  @Option(names = {"-n", "--dry-run"}, description = "Make no on-disk changes (simulation mode).")
  public boolean simulate;

  @Option(names = "--force", description = "Allow potentially dangerous operations.")
  public boolean force;

  @Option(names = "--path", split = ",", description = "Additional directory paths to probe for volumes.")
  public List<Path> paths;

  /// Merge this options instance with parent options following the most-verbose-wins rule.
  public CommonOptions merge(CommonOptions parent) {
    if (parent == null) return this;
    CommonOptions merged = new CommonOptions();
    merged.quiet = this.quiet || parent.quiet;
    merged.verbose = this.verbose || parent.verbose;
    merged.debug = this.debug || parent.debug;
    merged.simulate = this.simulate || parent.simulate;
    merged.force = this.force || parent.force;
    if (this.paths != null || parent.paths != null) {
      merged.paths = new ArrayList<>();
      if (parent.paths != null) merged.paths.addAll(parent.paths);
      if (this.paths != null) merged.paths.addAll(this.paths);
    }
    return merged;
  }
}
