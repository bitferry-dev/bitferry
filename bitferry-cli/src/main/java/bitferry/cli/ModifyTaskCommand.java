package bitferry.cli;

import bitferry.*;
import bitferry.task.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.*;

/// Modify parameters of an existing task.
@Command(name = "task", aliases = {"t"}, mixinStandardHelpOptions = true, description = "Modify parameters of an existing task.")
public class ModifyTaskCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  public ModifyTaskCommand() {}

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Tag matching task to modify.", paramLabel = "TAG")
  String tag;

  @Option(names = {"-p", "--password"}, description = "Update the repository password.")
  boolean password;

  @Option(names = {"-t", "--type"}, description = "Operation type for rclone tasks (copy, update, synchronize, equalize).", paramLabel = "TYPE")
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

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  @Override
  public Integer call() {
    PrintWriter out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    CommonOptions opts = CliUtils.resolveOptions(spec, options);

    if (defaultForget && forget != null) throw new BitferryError("cannot specify both -f and -F");
    if (defaultCheck && check != null) throw new BitferryError("cannot specify both -c and -C");
    if (defaultServe && serve != null) throw new BitferryError("cannot specify both -s and -S");
    if (clearInclude && (include != null || includeExts != null)) throw new BitferryError("cannot specify both --include/--include-ext and --clear-include");
    if (clearExclude && (exclude != null || excludeExts != null)) throw new BitferryError("cannot specify both --exclude/--exclude-ext and --clear-exclude");

    boolean hasForget = defaultForget || forget != null;
    boolean hasCheck = defaultCheck || check != null;
    boolean hasServe = defaultServe || serve != null;
    boolean hasInclude = clearInclude || include != null || includeExts != null;
    boolean hasExclude = clearExclude || exclude != null || excludeExts != null;
    boolean hasExecute = execute != null;
    boolean hasType = type != null;

    if (!password && !hasForget && !hasCheck && !hasServe && !hasInclude && !hasExclude && !hasExecute && !hasType) {
      throw new BitferryError("no modifications specified for task " + tag);
    }

    Manager m = CliUtils.restoreManager(opts);
    List<Task> selected = m.selectLiveTasks(List.of(tag));
    Task task = selected.get(0);

    if (hasType) {
      task = task.morph(type);
    }

    if (hasForget || hasCheck || hasServe) {
      if (!task.supportsRetentionPolicy()) {
        throw new BitferryError("forget, check, and serve options are only applicable to backup tasks");
      }
    }

    // The include filters are rejected upfront (before any mutation): restic backup
    // has no include patterns (the exclusion only), so they would be silently ignored
    if ((include != null || includeExts != null) && !task.acceptsIncludeFilters()) {
      throw new BitferryError("include filters are not applicable to backup tasks (restic backup filters by exclusion only)");
    }

    if (password) {
      if (!task.supportsPasswordRotation()) {
        throw new BitferryError("rclone crypt parameters and passwords cannot be modified on an existing task; recreation is required");
      }
      String newPassword = CliUtils.obtainPassword();
      m.passwd((Restic) task, newPassword);
    }

    if (hasExecute) {
      task.setProcessOptions(execute);
    }

    // The resolved policies (the -f/-c/-s shorthands expand to the @default profiles)
    task.applyPolicyOptions(
      defaultForget ? "@default" : forget,
      defaultCheck ? "@default" : check,
      defaultServe ? "@default" : serve);

    if (clearInclude) {
      task.clearInclude();
    } else if (include != null || includeExts != null) {
      task.setInclude(CliUtils.parsePatterns(include, includeExts));
    }

    if (clearExclude) {
      task.clearExclude();
    } else if (exclude != null || excludeExts != null) {
      task.setExclude(CliUtils.parsePatterns(exclude, excludeExts));
    }

    m.commit();
    if (!m.quiet) {
      out.println(Ansi.AUTO.string("@|green Task " + task.tag() + " modified successfully.|@"));
    }
    return 0;
  }
}
