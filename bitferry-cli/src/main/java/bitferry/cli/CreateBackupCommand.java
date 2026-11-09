package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Create incremental backup task with snapshotting (Restic).
@Command(name = "backup", aliases = {"b"}, mixinStandardHelpOptions = true, description = "Create incremental backup task.")
public class CreateBackupCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateBackupCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Source endpoint.", paramLabel = "SOURCE")
  String source;

  @Parameters(index = "1", description = "Repository endpoint.", paramLabel = "REPOSITORY")
  String repository;

  @Option(names = {"-a", "--attach"}, description = "Attach to existing repository.")
  boolean attach;

  @Option(names = "-f", description = "Apply default snapshot retention policy options.")
  boolean defaultForget;

  @Option(names = {"-F", "--forget"}, description = "Snapshot retention policy with profile (default) or options.")
  String forget;

  @Option(names = "-c", description = "Apply default repository checking options.")
  boolean defaultCheck;

  @Option(names = {"-C", "--check"}, description = "Repository checking with profile (default, full) or options.")
  String check;

  @Option(names = {"-X", "--process"}, description = "Extra backend processing options.")
  String process;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).")
  List<String> exclude;

  @Option(names = "-x", split = ",", description = "Exclude file extensions (comma-separated list).")
  List<String> excludeExts;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    // The shorthand and the explicit forms of the same setting are mutually exclusive
    if (defaultForget && forget != null) throw new BitferryError("snapshot retention policy specified twice: use either -f or --forget");
    if (defaultCheck && check != null) throw new BitferryError("repository checking specified twice: use either -c or --check");
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    // The bare paths encompassed by the intact volumes are attached to them
    Endpoint src = Endpoints.parse(m, source);
    Endpoint repo = Endpoints.parse(m, repository);
    CliUtils.ensureUniqueTask(m, src, repo);
    // The password is obtained after the endpoints are validated
    String password = CliUtils.obtainPassword();
    Boolean format = opts.force ? Boolean.TRUE : (attach ? Boolean.FALSE : null);
    String f = defaultForget ? "default" : forget;
    String c = defaultCheck ? "default" : check;
    List<String> exc = CliUtils.parsePatterns(exclude, excludeExts);
    Backup task = new Backup(m, src, repo, password, format, process, f, c, List.of(), exc);
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("task #" + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit();
    return 0;
  }
}
