package io.bitferry.cli;

import io.bitferry.*;
import io.bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.*;

/// Modify parameters of an existing task.
@Command(name = "task", aliases = {"t"}, description = "Modify parameters of an existing task.")
public class ModifyTaskCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public ModifyTaskCommand() {}

  @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
  boolean help;

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Tag matching task to modify.", paramLabel = "TAG")
  String tag;

  @Option(names = {"-p", "--password"}, description = "Update the repository password.")
  boolean password;

  @Option(names = "--vault", description = "Migrate sensitive data storage to @|bold,cyan bitferry|@ (volume storage) or @|bold,cyan keyring|@ (OS keyring).", paramLabel = "PROVIDER")
  String vault;

  @Option(names = "-z", description = "Shorthand for --vault bitferry.")
  boolean bitferryVault;

  @Option(names = "-k", description = "Shorthand for --vault keyring.")
  boolean keyring;

  @Option(names = {"-t", "--type"}, description = "Operation type for rclone tasks (copy, update, replicate, equalize).", paramLabel = "TYPE")
  String type;

  @Option(names = "--forget", description = "Snapshot retention policy with profile @|bold,cyan (@default)|@, options, or @|bold,cyan -|@ to disable.", paramLabel = "PROFILE|OPTS")
  String forget;

  @Option(names = "--check", description = "Repository integrity check with profile @|bold,cyan (@default, @full)|@, options, or @|bold,cyan -|@ to disable.", paramLabel = "PROFILE|OPTS")
  String check;

  @Option(names = "--serve", description = "Serve cadence with profile @|bold,cyan (@default)|@, @|bold,cyan <duration>,<count>|@, or @|bold,cyan -|@ to disable.", paramLabel = "CADENCE")
  String serve;

  @Option(names = {"-b", "--backup-task"}, description = "Backup task tag scoping the restore snapshot selection, or @|bold,cyan -|@ for the default scope (derived from the repository when unambiguous, otherwise the newest bitferry-tagged snapshot repo-wide; restore tasks only).", paramLabel = "TAG")
  String backupTask;

  @Option(names = {"-x", "--execute"}, description = "Backend execution options, profile @|bold,cyan (@default)|@, or @|bold,cyan -|@ to clear.", paramLabel = "PROFILE|OPTS")
  String execute;

  @Option(names = "--include", split = ",", description = "Include path specifications (comma-separated list), or @|bold,cyan -|@ to clear.", paramLabel = "PATTERN")
  List<String> include;

  @Option(names = "--include-ext", split = ",", description = "Include file extensions (comma-separated list), or @|bold,cyan -|@ to clear.", paramLabel = "EXT")
  List<String> includeExts;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list), or @|bold,cyan -|@ to clear.", paramLabel = "PATTERN")
  List<String> exclude;

  @Option(names = "--exclude-ext", split = ",", description = "Exclude file extensions (comma-separated list), or @|bold,cyan -|@ to clear.", paramLabel = "EXT")
  List<String> excludeExts;

  @Option(names = "-m", description = "Shorthand for --execution manual (exclude the task from the mass runs; run it by its tag only).")
  boolean manualExecution;

  @Option(names = "--execution", description = "Mass-run participation: @|bold,cyan manual|@ (run it by its tag only) or @|bold,cyan automatic|@ (include in the mass runs).", paramLabel = "MODE")
  String execution;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    PrintWriter out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    CommonOptions opts = CliUtils.resolveOptions(spec, options);

    // The filter option pairs honor the global '-' clear marker: '-' in either half
    // wipes the specifications; the marker combined with the real patterns of its
    // pair (across the repeated occurrences too) is rejected
    boolean includeCleared = CliUtils.isCleared(include) || CliUtils.isCleared(includeExts);
    boolean includeSet = (include != null && !CliUtils.isCleared(include)) || (includeExts != null && !CliUtils.isCleared(includeExts));
    if (CliUtils.mixesClearMarker(include) || CliUtils.mixesClearMarker(includeExts) || (includeCleared && includeSet)) {
      throw new BitferryError("Cannot combine '-' (clear) with the include patterns");
    }
    boolean excludeCleared = CliUtils.isCleared(exclude) || CliUtils.isCleared(excludeExts);
    boolean excludeSet = (exclude != null && !CliUtils.isCleared(exclude)) || (excludeExts != null && !CliUtils.isCleared(excludeExts));
    if (CliUtils.mixesClearMarker(exclude) || CliUtils.mixesClearMarker(excludeExts) || (excludeCleared && excludeSet)) {
      throw new BitferryError("Cannot combine '-' (clear) with the exclude patterns");
    }

    // The mass-run participation is validated upfront (the bogus modes fail before any probing):
    // the shorthand and the explicit forms of the same setting are mutually exclusive
    if (manualExecution && execution != null) throw new BitferryError("Execution mode specified twice: use either -m or --execution");
    Boolean manual = manualExecution ? Boolean.TRUE : CliUtils.resolveExecution(execution);
    boolean hasExecution = manual != null;

    boolean hasForget = forget != null;
    boolean hasCheck = check != null;
    boolean hasServe = serve != null;
    boolean hasBackupTask = backupTask != null;
    boolean hasInclude = includeCleared || includeSet;
    boolean hasExclude = excludeCleared || excludeSet;
    boolean hasExecute = execute != null;
    boolean hasType = type != null;
    boolean hasManual = hasExecution;

    Boolean targetKeyring = CliUtils.resolveVaultMigration(vault, bitferryVault, keyring);
    boolean hasVault = targetKeyring != null;

    if (!password && !hasVault && !hasForget && !hasCheck && !hasServe && !hasBackupTask && !hasInclude && !hasExclude && !hasExecute && !hasType && !hasManual) {
      throw new BitferryError("No modifications specified for task " + tag);
    }

    Manager m = CliUtils.restoreManager(spec, opts);
    List<Task> selected = m.selectLiveTasks(List.of(tag));
    Task task = selected.get(0);

    // The option applicability guards run BEFORE the morph (and any other mutation):
    // the morph is destructive (an equalize task loses its bisync baseline on disk),
    // so a rejected option after it would leave the half-applied morph behind - the
    // guards' outcomes are identical pre- and post-morph (the morph never leaves the
    // rclone family, and the backup tasks reject the morph itself)
    if (hasForget || hasCheck || hasServe) {
      if (!task.supportsRetentionPolicy()) {
        throw new BitferryError("Forget, check, and serve options are only applicable to backup tasks");
      }
    }

    if (hasBackupTask && !(task instanceof Restore)) {
      throw new BitferryError("Backup task scope is only applicable to restore tasks");
    }

    // The include filters are rejected upfront (before any mutation): the capability
    // is structural (the [IncludeFilters] mixin on the rclone family and the restore
    // tasks), so the filter-less operations (the backup ones - restic backup filters
    // by exclusion only) carry neither the state nor the accessors. The clear ('-')
    // is the tolerated no-op there (only the set requests are rejected)
    if (includeSet && !(task instanceof IncludeFilters)) {
      throw new BitferryError("Include filters are not applicable to backup tasks (restic backup filters by exclusion only)");
    }

    if (hasVault && !task.usesVault()) {
      throw new BitferryError("Vault storage migration is only applicable to encrypted or credential-bearing tasks");
    }

    // The structural restic check (not the polymorphic predicate): the rotation
    // support is the restic backend's property, so the passwd call below can never
    // face a non-restic task
    if (password && !(task instanceof Restic)) {
      throw new BitferryError("Password update is only applicable to restic tasks (rclone crypt encryption is immutable on existing tasks)");
    }

    // The execution options are value-checked against the effective (the post-morph)
    // profile route BEFORE the destructive morph: the morph is irreversible on disk
    // (the equalize baseline deletion), so a failing set after it would strand the
    // half-applied morph with the storage uncommitted
    if (hasExecute) {
      Task.checkProcessOptions(execute, Task.processRouteFor(hasType ? type : task.operation()));
    }

    if (hasType) {
      task = task.morph(type);
    }

    if (password) {
      String newPassword = CliUtils.obtainPassword();
      List<String> stale = m.passwd((Restic) task, newPassword);
      if (!stale.isEmpty()) {
        spec.commandLine().getErr().println(Ansi.AUTO.string("@|bold,yellow Warning:|@ the co-repository task(s) " + String.join(", ", stale)
          + " were not reachable - their stored passwords were not updated (their volumes are not restored, or the tasks were deleted)."
          + " The unreachable tasks' runs will fail the authentication until the token is re-synced (by the auto-heal upon their next run, or by re-entering the password)"));
      }
    }

    if (hasVault) {
      task.migrateVault(targetKeyring);
      CliUtils.noteKeyringStorage(spec, task, targetKeyring);
    }

    if (hasExecute) {
      task.setProcessOptions(execute);
    }

    // The resolved policies
    task.applyPolicyOptions(forget, check, serve);

    // The snapshot scope re-selection (restore tasks only, guarded upfront): '-'
    // clears the explicit scope (the default derived selection applies at the run
    // time); the explicit tag must name a live backup task (the create-path rule)
    if (hasBackupTask) {
      ((Restore) task).setSnapshotTag("-".equals(backupTask) ? null : Restore.resolveSnapshotTag(m, task.source(), backupTask));
    }

    // The clear is the tolerated no-op for the capability-less tasks (the upfront
    // guard above has already rejected the set requests)
    if (includeCleared) {
      if (task instanceof IncludeFilters includable) includable.clearInclude();
    } else if (includeSet) {
      ((IncludeFilters) task).setInclude(CliUtils.parsePatterns(include, includeExts));
    }

    if (excludeCleared) {
      task.clearExclude();
    } else if (excludeSet) {
      task.setExclude(CliUtils.parsePatterns(exclude, excludeExts));
    }

    // The mass-run participation marker (the morph carries it over, so the position
    // relative to the morph is immaterial)
    if (hasExecution) {
      task.setManual(manual);
    }

    m.commit();
    if (!m.quiet) {
      out.println(Ansi.AUTO.string("@|green Task " + task.tag() + " modified successfully.|@"));
    }
    return 0;
  }
}
