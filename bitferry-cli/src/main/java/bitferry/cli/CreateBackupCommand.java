package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
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

  @Option(names = "-f", description = "Apply default snapshot retention policy options.")
  boolean defaultForget;

  @Profiles(value = Backup.class, field = "FORGET")
  @Option(names = {"-F", "--forget"}, description = "Snapshot retention policy with profile @|bold,cyan (@default)|@ or options.")
  String forget;

  @Option(names = "-c", description = "Apply default repository checking options.")
  boolean defaultCheck;

  @Profiles(value = Backup.class, field = "CHECK")
  @Option(names = {"-C", "--check"}, description = "Repository checking with profile @|bold,cyan (@default, @full)|@ or options.")
  String check;

  @Option(names = "-s", description = "Apply default serve cadence.")
  boolean defaultServe;

  @Profiles(value = Backup.class, field = "CADENCE")
  @Option(names = {"-S", "--serve"}, description = "Serve cadence with profile @|bold,cyan (@default)|@, '<duration>,<count>' or '-' for none.")
  String serve;

  @Profiles(Backup.class)
  @Option(names = {"-X", "--execute"}, description = "Extra backend execution options (comma-separated), profile @|bold,cyan (@default)|@ or '-' for none.")
  String execute;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).")
  List<String> exclude;

  @Option(names = "-x", split = ",", description = "Exclude file extensions (comma-separated list).")
  List<String> excludeExts;

  @Option(names = {"-k", "--keyring"}, description = "Store the repository password in the OS keyring (fixed volumes only).")
  boolean keyring;

  @Option(names = "--manual", description = "Exclude the task from the mass runs (run it by its tag only).")
  boolean manual;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    // The shorthand and the explicit forms of the same setting are mutually exclusive
    if (defaultForget && forget != null) throw new BitferryError("Snapshot retention policy specified twice: use either -f or --forget");
    if (defaultCheck && check != null) throw new BitferryError("Repository checking specified twice: use either -c or --check");
    if (defaultServe && serve != null) throw new BitferryError("Serve cadence specified twice: use either -s or --serve");
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    // The bare paths encompassed by the intact volumes are attached to them
    Endpoint src = Endpoints.parse(m, source);
    Endpoint repo = Endpoints.parse(m, repository);
    CliUtils.ensureUniqueTask(m, src, repo);
    // The password is obtained after the endpoints are validated
    String password = CliUtils.obtainPassword();
    Boolean format = opts.force ? Boolean.TRUE : null;
    String f = defaultForget ? "@default" : forget;
    String c = defaultCheck ? "@default" : check;
    String sCadence = defaultServe ? "@default" : serve;
    List<String> exc = CliUtils.parsePatterns(exclude, excludeExts);
    Backup task = new Backup(m, src, repo, password, format, execute, f, c, sCadence, exc, keyring);
    if (manual) {
      task.setManual(true);
    }
    CliUtils.noteKeyringStorage(spec, task, keyring);
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("Task " + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit();
    // The simulated creation writes nothing (the storages hold no such task) and skips
    // the backend initialization: surface it - the silent exit would read as a success
    if (m.simulate()) {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint the task creation simulated (nothing was written, repository not initialized); re-run without --dry-run to create the task for real|@"));
    }
    return 0;
  }
}
