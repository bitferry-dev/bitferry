package org.bitferry.cli;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.*;

/// Create repository content restore task (Restic).
@Command(name = "restore", aliases = {"r"}, description = "Create repository content restore task.")
public class CreateRestoreCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public CreateRestoreCommand() {}

  @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
  boolean help;

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Repository endpoint.", paramLabel = "REPOSITORY")
  String repository;

  @Parameters(index = "1", description = "Destination endpoint.", paramLabel = "DESTINATION")
  String destination;

  @Profiles(value = Restore.class, field = "CHECK")
  @Option(names = "--check", description = "Repository checking with profile @|bold,cyan (@default, @full)|@, options, or @|bold,cyan -|@ to clear extra options (default: @|bold,cyan @default|@).", paramLabel = "PROFILE|OPTS")
  String check = "@default";

  @Profiles(Restore.class)
  @Option(names = {"-x", "--execute"}, description = "Extra backend execution options (comma-separated), profile @|bold,cyan (@default)|@ or @|bold,cyan -|@ for none.", paramLabel = "PROFILE|OPTS")
  String execute;

  @Option(names = "--include", split = ",", description = "Include path specifications (comma-separated list).", paramLabel = "PATTERN")
  List<String> include;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).", paramLabel = "PATTERN")
  List<String> exclude;

  @Option(names = "--include-ext", split = ",", description = "Include file extensions (comma-separated list).", paramLabel = "EXT")
  List<String> includeExts;

  @Option(names = "--exclude-ext", split = ",", description = "Exclude file extensions (comma-separated list).", paramLabel = "EXT")
  List<String> excludeExts;

  @Option(names = "--vault", description = "Vault provider for sensitive data: @|bold,cyan bitferry|@ (volume storage) or @|bold,cyan keyring|@ (OS keyring).", paramLabel = "PROVIDER")
  String vault;

  @Option(names = "-z", description = "Shorthand for --vault bitferry (store sensitive data in volume vault).")
  boolean bitferryVault;

  @Option(names = "-k", description = "Shorthand for --vault keyring (intended for fixed volumes; honored on the removable ones with a portability advisory).")
  boolean keyring;

  @Option(names = {"-b", "--backup-task"}, description = "Backup task tag scoping the restore (the newest snapshot of that task is selected), or @|bold,cyan -|@ for the default scope (derived from the repository when unambiguous, otherwise the newest bitferry-tagged snapshot repo-wide).", paramLabel = "TAG")
  String backupTask;

  @Option(names = "--snapshot", description = "Snapshot ID to restore, or @|bold,cyan latest|@ / @|bold,cyan -|@ (default: @|bold,cyan latest|@). Accepts @|bold,cyan snapshotID:subfolder|@.", paramLabel = "ID")
  String snapshot;

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
    Endpoint repo = Endpoints.parse(m, repository);
    Endpoint dst = Endpoints.parse(m, destination);
    CliUtils.ensureUniqueTask(m, repo, dst);
    // The password is obtained after the endpoints are validated
    String password = CliUtils.obtainPassword();
    List<String> inc = CliUtils.parsePatterns(include, includeExts);
    List<String> exc = CliUtils.parsePatterns(exclude, excludeExts);
    // The snapshot scope: the explicit (or uniquely derived) source backup task whose
    // snapshots the restore selects from - on a shared (co-repository) repository the
    // repo-wide fallback would restore whichever sibling task's data is the newest.
    // The '-' marker declines the explicit scoping (the default resolution applies).
    // When an explicit concrete snapshot ID is specified without --backup-task, skip
    // ambiguous derivation since --tag is unused for explicit snapshot IDs.
    boolean isLatest = Restore.isLatestSnapshot(snapshot);
    String scope = (isLatest || backupTask != null)
      ? Restore.resolveSnapshotTag(m, repo, "-".equals(backupTask) ? null : backupTask, password)
      : null;
    if (!isLatest) {
      spec.commandLine().getOut().println("Snapshot ID: " + Restore.normalizeSnapshotId(snapshot));
    } else if (scope != null) {
      spec.commandLine().getOut().println("Snapshot scope: backup task " + scope);
    } else {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint no backup task bound to the repository found; the restore will select the newest bitferry-tagged snapshot repo-wide (--backup-task pins it)|@"));
    }
    boolean toKeyring = CliUtils.resolveKeyring(vault, bitferryVault, keyring, false);
    Restore task = new Restore(m, repo, dst, password, execute, check, inc, exc, toKeyring, scope, snapshot);
    if (Boolean.TRUE.equals(manual)) {
      task.setManual(true);
    }
    CliUtils.noteKeyringStorage(spec, task, toKeyring);
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("Task " + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit(task);
    // The simulated creation writes nothing (the storages hold no such task) and skips
    // the backend-side actions: surface it - the silent exit would read as a success
    // (mirroring the backup creation's advisory)
    if (m.simulate()) {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint the task creation simulated (nothing was written); re-run without --dry-run to create the task for real|@"));
    }
    return 0;
  }
}
