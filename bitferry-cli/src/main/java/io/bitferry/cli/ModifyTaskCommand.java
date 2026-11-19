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

  @Option(names = {"-V", "--vault"}, description = "Migrate sensitive data storage to 'bitferry' (volume storage) or 'keyring' (OS keyring).", paramLabel = "PROVIDER")
  String vault;

  @Option(names = {"-K", "--keyring"}, description = "Shorthand for --vault keyring.")
  boolean keyring;

  @Option(names = "--no-keyring", description = "Shorthand for --vault bitferry.")
  boolean noKeyring;

  @Option(names = {"-t", "--type"}, description = "Operation type for rclone tasks (copy, update, replicate, equalize).", paramLabel = "TYPE")
  String type;

  @Option(names = "-f", description = "Apply default snapshot retention policy options.")
  boolean defaultForget;

  @Option(names = {"-F", "--forget"}, description = "Snapshot retention policy options (or '-' to disable).", paramLabel = "PROFILE|OPTS")
  String forget;

  @Option(names = "-c", description = "Apply default repository integrity check options.")
  boolean defaultCheck;

  @Option(names = {"-C", "--check"}, description = "Repository integrity check options (or '-' to disable).", paramLabel = "PROFILE|OPTS")
  String check;

  @Option(names = "-s", description = "Apply default serve cadence (@default).")
  boolean defaultServe;

  @Option(names = {"-S", "--serve"}, description = "Serve cadence with profile (@default) or '<duration>,<count>' (or '-' to disable).", paramLabel = "CADENCE")
  String serve;

  @Option(names = {"-X", "--execute"}, description = "Backend execution options (or '-' to clear).", paramLabel = "PROFILE|OPTS")
  String execute;

  @Option(names = "--include", split = ",", description = "Include path specifications (comma-separated list).")
  List<String> include;

  @Option(names = "--include-ext", split = ",", description = "Include file extensions (comma-separated list).")
  List<String> includeExts;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).")
  List<String> exclude;

  @Option(names = "--exclude-ext", split = ",", description = "Exclude file extensions (comma-separated list).")
  List<String> excludeExts;

  @Option(names = "--clear-include", description = "Clear include path specifications.")
  boolean clearInclude;

  @Option(names = "--clear-exclude", description = "Clear exclude path specifications.")
  boolean clearExclude;

  @Option(names = "--manual", description = "Exclude the task from the mass runs (run it by its tag only).")
  boolean manual;

  @Option(names = "--no-manual", description = "Include the task in the mass runs.")
  boolean noManual;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    PrintWriter out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    CommonOptions opts = CliUtils.resolveOptions(spec, options);

    if (defaultForget && forget != null) throw new BitferryError("Cannot specify both -f and -F");
    if (defaultCheck && check != null) throw new BitferryError("Cannot specify both -c and -C");
    if (defaultServe && serve != null) throw new BitferryError("Cannot specify both -s and -S");
    if (clearInclude && (include != null || includeExts != null)) throw new BitferryError("Cannot specify both --include/--include-ext and --clear-include");
    if (clearExclude && (exclude != null || excludeExts != null)) throw new BitferryError("Cannot specify both --exclude/--exclude-ext and --clear-exclude");
    if (manual && noManual) throw new BitferryError("Cannot specify both --manual and --no-manual");

    boolean hasForget = defaultForget || forget != null;
    boolean hasCheck = defaultCheck || check != null;
    boolean hasServe = defaultServe || serve != null;
    boolean hasInclude = clearInclude || include != null || includeExts != null;
    boolean hasExclude = clearExclude || exclude != null || excludeExts != null;
    boolean hasExecute = execute != null;
    boolean hasType = type != null;
    boolean hasManual = manual || noManual;

    Boolean targetKeyring = CliUtils.resolveVaultMigration(vault, keyring, noKeyring);
    boolean hasVault = targetKeyring != null;

    if (!password && !hasVault && !hasForget && !hasCheck && !hasServe && !hasInclude && !hasExclude && !hasExecute && !hasType && !hasManual) {
      throw new BitferryError("No modifications specified for task " + tag);
    }

    Manager m = CliUtils.restoreManager(opts);
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

    // The include filters are rejected upfront (before any mutation): the capability
    // is structural (the [IncludeFilters] mixin on the rclone family and the restore
    // tasks), so the filter-less operations (the backup ones - restic backup filters
    // by exclusion only) carry neither the state nor the accessors
    if ((include != null || includeExts != null) && !(task instanceof IncludeFilters)) {
      throw new BitferryError("Include filters are not applicable to backup tasks (restic backup filters by exclusion only)");
    }

    if (hasVault && !task.usesVault()) {
      throw new BitferryError("Vault storage migration is only applicable to encrypted or credential-bearing tasks");
    }

    if (password && !task.supportsPasswordRotation()) {
      throw new BitferryError("Password update is only applicable to backup tasks (rclone crypt encryption is immutable on existing tasks)");
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

    // The resolved policies (the -f/-c/-s shorthands expand to the @default profiles)
    task.applyPolicyOptions(
      defaultForget ? "@default" : forget,
      defaultCheck ? "@default" : check,
      defaultServe ? "@default" : serve);

    // The clear is the tolerated no-op for the capability-less tasks (the upfront
    // guard above has already rejected the set requests)
    if (clearInclude) {
      if (task instanceof IncludeFilters includable) includable.clearInclude();
    } else if (include != null || includeExts != null) {
      ((IncludeFilters) task).setInclude(CliUtils.parsePatterns(include, includeExts));
    }

    if (clearExclude) {
      task.clearExclude();
    } else if (exclude != null || excludeExts != null) {
      task.setExclude(CliUtils.parsePatterns(exclude, excludeExts));
    }

    // The mass-run participation marker (the morph carries it over, so the position
    // relative to the morph is immaterial)
    if (manual) {
      task.setManual(true);
    } else if (noManual) {
      task.setManual(false);
    }

    m.commit();
    if (!m.quiet) {
      out.println(Ansi.AUTO.string("@|green Task " + task.tag() + " modified successfully.|@"));
    }
    return 0;
  }
}
