package io.bitferry.cli;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import io.bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.*;

/// Create incremental backup task with snapshotting (Restic).
@Command(name = "backup", aliases = {"b"}, description = "Create incremental backup task.")
public class CreateBackupCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateBackupCommand() {}

  @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
  boolean help;

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Source endpoint.", paramLabel = "SOURCE")
  String source;

  @Parameters(index = "1", description = "Repository endpoint.", paramLabel = "REPOSITORY")
  String repository;

  @Profiles(value = Backup.class, field = "FORGET")
  @Option(names = "--forget", description = "Snapshot retention policy with profile @|bold,cyan (@default)|@, options, or @|bold,cyan -|@ for none (default: @|bold,cyan @default|@).", paramLabel = "PROFILE|OPTS")
  String forget = "@default";

  @Profiles(value = Backup.class, field = "CHECK")
  @Option(names = "--check", description = "Repository checking with profile @|bold,cyan (@default, @full)|@, options, or @|bold,cyan -|@ for none (default: @|bold,cyan @default|@).", paramLabel = "PROFILE|OPTS")
  String check = "@default";

  @Profiles(value = Backup.class, field = "CADENCE")
  @Option(names = "--serve", description = "Serve cadence with profile @|bold,cyan (@default)|@, @|bold,cyan <duration>,<count>|@, or @|bold,cyan -|@ for none (default: @|bold,cyan @default|@).", paramLabel = "CADENCE")
  String serve = "@default";

  @Profiles(Backup.class)
  @Option(names = {"-x", "--execute"}, description = "Extra backend execution options (comma-separated), profile @|bold,cyan (@default)|@ or @|bold,cyan -|@ for none.", paramLabel = "PROFILE|OPTS")
  String execute;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).", paramLabel = "PATTERN")
  List<String> exclude;

  @Option(names = "--exclude-ext", split = ",", description = "Exclude file extensions (comma-separated list).", paramLabel = "EXT")
  List<String> excludeExts;

  @Option(names = "--vault", description = "Vault provider for sensitive data: @|bold,cyan bitferry|@ (volume storage) or @|bold,cyan keyring|@ (OS keyring).", paramLabel = "PROVIDER")
  String vault;

  @Option(names = "-z", description = "Shorthand for --vault bitferry (store sensitive data in volume vault).")
  boolean bitferryVault;

  @Option(names = "-k", description = "Shorthand for --vault keyring (fixed volumes only).")
  boolean keyring;

  @Option(names = "-m", description = "Shorthand for --execution manual (exclude the task from the mass runs; run it by its tag only).")
  boolean manualExecution;

  @Option(names = "--execution", description = "Mass-run participation: @|bold,cyan manual|@ (run it by its tag only) or @|bold,cyan automatic|@ (include in the mass runs; the default).", paramLabel = "MODE")
  String execution;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    // The mass-run participation is validated upfront (the bogus modes fail before any probing):
    // the shorthand and the explicit forms of the same setting are mutually exclusive
    if (manualExecution && execution != null) throw new BitferryError("Execution mode specified twice: use either -m or --execution");
    Boolean manual = manualExecution ? Boolean.TRUE : CliUtils.resolveExecution(execution);
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(spec, opts);
    // The bare paths encompassed by the intact volumes are attached to them
    Endpoint src = Endpoints.parse(m, source);
    Endpoint repo = Endpoints.parse(m, repository);
    CliUtils.ensureUniqueTask(m, src, repo);
    // The password is obtained after the endpoints are validated
    String password = CliUtils.obtainPassword();
    Boolean format = opts.force ? Boolean.TRUE : null;
    List<String> exc = CliUtils.parsePatterns(exclude, excludeExts);
    boolean toKeyring = CliUtils.resolveKeyring(vault, bitferryVault, keyring, false);
    Backup task = new Backup(m, src, repo, password, format, execute, forget, check, serve, exc, toKeyring);
    if (Boolean.TRUE.equals(manual)) {
      task.setManual(true);
    }
    CliUtils.noteKeyringStorage(spec, task, toKeyring);
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("Task " + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit(task);
    // The simulated creation writes nothing (the storages hold no such task) and skips
    // the backend initialization: surface it - the silent exit would read as a success
    if (m.simulate()) {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint the task creation simulated (nothing was written, repository not initialized); re-run without --dry-run to create the task for real|@"));
    }
    return 0;
  }
}
