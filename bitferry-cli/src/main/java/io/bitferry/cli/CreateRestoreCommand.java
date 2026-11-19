package io.bitferry.cli;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import io.bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
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
  @Option(names = {"-X", "--execute"}, description = "Extra backend execution options (comma-separated), profile @|bold,cyan (@default)|@ or '-' for none.")
  String execute;

  @Option(names = "--include", split = ",", description = "Include path specifications (comma-separated list).")
  List<String> include;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).")
  List<String> exclude;

  @Option(names = "-i", split = ",", description = "Include file extensions (comma-separated list).")
  List<String> includeExts;

  @Option(names = "-x", split = ",", description = "Exclude file extensions (comma-separated list).")
  List<String> excludeExts;

  @Option(names = {"-k", "--keyring"}, description = "Store the repository password in the OS keyring (fixed volumes only).")
  boolean keyring;

  @Option(names = "--source-task", description = "Source backup task tag scoping the restore (the newest snapshot of that task is selected; default: derived from the repository when unambiguous, otherwise the newest bitferry-tagged snapshot repo-wide).")
  String sourceTask;

  @Option(names = "--manual", description = "Exclude the task from the mass runs (run it by its tag only).")
  boolean manual;

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
    // The snapshot scope: the explicit (or uniquely derived) source backup task whose
    // snapshots the restore selects from - on a shared (co-repository) repository the
    // repo-wide fallback would restore whichever sibling task's data is the newest
    String scope = Restore.resolveSnapshotTag(m, repo, sourceTask);
    if (scope != null) {
      spec.commandLine().getOut().println("Snapshot scope: backup task " + scope);
    } else {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint no backup task bound to the repository found; the restore will select the newest bitferry-tagged snapshot repo-wide (--source-task pins it)|@"));
    }
    Restore task = new Restore(m, repo, dst, password, execute, inc, exc, keyring, scope);
    if (manual) {
      task.setManual(true);
    }
    CliUtils.noteKeyringStorage(spec, task, keyring);
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("Task " + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit();
    // The simulated creation writes nothing (the storages hold no such task) and skips
    // the backend-side actions: surface it - the silent exit would read as a success
    // (mirroring the backup creation's advisory)
    if (m.simulate()) {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint the task creation simulated (nothing was written); re-run without --dry-run to create the task for real|@"));
    }
    return 0;
  }
}
