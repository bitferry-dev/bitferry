package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Create repository content restore task (Restic).
@Command(name = "restore", aliases = {"r"}, mixinStandardHelpOptions = true, description = "Create repository content restore task.")
public class CreateRestoreCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateRestoreCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Repository endpoint.", paramLabel = "REPOSITORY")
  String repository;

  @Parameters(index = "1", description = "Destination endpoint.", paramLabel = "DESTINATION")
  String destination;

  @Profiles(Restore.class)
  @Option(names = {"-X", "--execute"}, description = "Extra backend execution options (comma-separated), profile (@default) or '-' for none.")
  String execute;

  @Option(names = "--include", split = ",", description = "Include path specifications (comma-separated list).")
  List<String> include;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).")
  List<String> exclude;

  @Option(names = "-i", split = ",", description = "Include file extensions (comma-separated list).")
  List<String> includeExts;

  @Option(names = "-x", split = ",", description = "Exclude file extensions (comma-separated list).")
  List<String> excludeExts;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    // The bare paths encompassed by the intact volumes are attached to them
    Endpoint repo = Endpoints.parse(m, repository);
    Endpoint dst = Endpoints.parse(m, destination);
    CliUtils.ensureUniqueTask(m, repo, dst);
    // The password is obtained after the endpoints are validated
    String password = CliUtils.obtainPassword();
    List<String> inc = CliUtils.parsePatterns(include, includeExts);
    List<String> exc = CliUtils.parsePatterns(exclude, excludeExts);
    Restore task = new Restore(m, repo, dst, password, execute, inc, exc);
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("task #" + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit();
    return 0;
  }
}
