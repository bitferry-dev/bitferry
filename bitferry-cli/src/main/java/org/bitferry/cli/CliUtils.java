package org.bitferry.cli;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import org.bitferry.vault.KeyringVault;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;
import org.fusesource.jansi.AnsiPrintStream;
import picocli.CommandLine.Help.*;
import picocli.CommandLine.Model.*;

/// Shared helpers for Picocli command implementations.
public final class CliUtils {

  private CliUtils() {}

  /// Reject the accidental re-creation of a task covering the specified endpoint pair
  /// with the error carrying the covering task's tag.
  public static void ensureUniqueTask(Manager manager, Endpoint source, Endpoint destination) {
    Task existing = manager.findTask(source, destination);
    if (existing != null) {
      throw new BitferryError("Task " + existing.tag() + " already covers " + existing.describe() + "; delete it first or specify different endpoints");
    }
  }

  /// The terminal password acquisition seam (swappable by the tests).
  static java.util.function.Supplier<String> passwordSource = CliUtils::readPasswordFromTerminal;

  /// The terminal salt acquisition seam (swappable by the tests).
  static java.util.function.Supplier<String> saltSource = CliUtils::readSaltFromTerminal;

  /// Probe whether the console encoding can carry the unicode glyphs (the direction
  /// arrows, the status marks). The Windows consoles always can (jansi routes the
  /// output through the wide-char console API, code-page independently); the unix
  /// consoles and the redirected streams follow the locale-derived encoding — the
  /// same property jansi's print streams encode with (stdout.encoding), so the
  /// decision matches the bytes actually emitted. The redirected streams default
  /// to UTF-8 on the modern JDKs, allowing the unicode in the log files.
  static boolean unicodeCapable() {
    if (System.out instanceof AnsiPrintStream printStream) {
      switch (printStream.getType()) {
        case Native, VirtualTerminal -> { return true; }  // The Windows wide-char / VT console paths
        case Unsupported -> { return false; }
        default -> {} // Emulation (the unix tty) and Redirected (files, pipes): the encoding decides
      }
    }
    String encoding = System.getProperty("stdout.encoding");
    if (encoding == null || encoding.isBlank()) encoding = System.getProperty("file.encoding", "UTF-8");
    try {
      return java.nio.charset.Charset.forName(encoding).newEncoder().canEncode('\u2192');
    } catch (java.nio.charset.IllegalCharsetNameException | java.nio.charset.UnsupportedCharsetException e) {
      return false;
    }
  }

  /// Obtain the password by prompting from the terminal with hidden input and confirmation.
  /// The passwords are never accepted from the command line arguments or the piped standard
  /// input channel so that they do not leak into the shell history or the process listings.
  public static String obtainPassword() {
    String password = passwordSource.get();
    if (password == null || password.isEmpty()) throw new BitferryError("Password must be specified");
    return password;
  }

  /// Obtain the salt by prompting from the terminal with hidden input and confirmation.
  public static String obtainSalt() {
    String salt = saltSource.get();
    if (salt == null || salt.isEmpty()) throw new BitferryError("Salt must be specified when -s/--salt is requested");
    return salt;
  }

  /// Resolve the requested vault provider into whether the OS keyring was selected.
  /// Defaults to [defaultKeyring] when neither option is specified.
  public static boolean resolveKeyring(String vault, boolean bitferryVault, boolean keyring, boolean defaultKeyring) {
    return resolveVaultSelection(vault, bitferryVault, keyring, defaultKeyring);
  }

  /// Resolve the requested vault migration target, returning null when no migration was requested.
  public static Boolean resolveVaultMigration(String vault, boolean bitferryVault, boolean keyring) {
    return resolveVaultSelection(vault, bitferryVault, keyring, null);
  }

  // The shared vault-selection resolution: the conflicting combinations are rejected,
  // the explicit --vault form and the -z/-k shorthands resolve to the keyring membership,
  // and the unspecified request falls back to the supplied default (null denoting "no
  // request" for the migration resolution)
  private static Boolean resolveVaultSelection(String vault, boolean bitferryVault, boolean keyring, Boolean defaultKeyring) {
    if (bitferryVault && keyring) {
      throw new BitferryError("Conflicting vault storage options: cannot specify both -z and -k");
    }
    if (vault != null) {
      if (bitferryVault || keyring) {
        throw new BitferryError("Cannot combine --vault with -z or -k");
      }
      if ("keyring".equalsIgnoreCase(vault)) return true;
      if ("bitferry".equalsIgnoreCase(vault) || "volume".equalsIgnoreCase(vault)) return false;
      throw new BitferryError("Unsupported vault provider '" + vault + "'; must be 'bitferry' or 'keyring'");
    }
    if (bitferryVault) return false;
    if (keyring) return true;
    return defaultKeyring;
  }

  /// Resolve the requested mass-run participation mode, returning null when the
  /// mode was not specified. The bare -m alias rides the option's fallback value
  /// ('manual'), so the resolution sees the fully resolved mode here.
  public static Boolean resolveExecution(String execution) {
    if (execution == null) return null;
    if ("manual".equalsIgnoreCase(execution)) return true;
    if ("automatic".equalsIgnoreCase(execution)) return false;
    throw new BitferryError("Unsupported execution mode '" + execution + "'; must be 'manual' or 'automatic'");
  }

  /// Surface the keyring storage outcome as a faint advisory note: the explicit
  /// request demoted by the missing keyring, or the removable-media portability
  /// tradeoff of the honored request.
  public static void noteKeyringStorage(picocli.CommandLine.Model.CommandSpec spec, Task task, boolean keyring) {
    if (!keyring) return;
    PrintWriter err = spec != null ? spec.commandLine().getErr() : new PrintWriter(System.err, true);
    if (!KeyringVault.INSTANCE.available()) {
      err.println(Ansi.AUTO.string("@|faint the OS keyring is not usable on this host - sensitive data is kept in the volume vault|@"));
      return;
    }
    try {
      if (!task.vaultVolume().isFixed()) {
        err.println(Ansi.AUTO.string("@|faint the vault volume resides on removable media - the keyring-stored sensitive data will not travel with it|@"));
      }
    } catch (BitferryError ignored) {
      // No volume-resident end: the task persistence check reports it separately
    }
  }

  // The terminal prompting: hidden input with confirmation (the mismatch retries a
  // bounded number of times - a single confirm-prompt typo must not kill the command)
  // WINDOWS: confirm the hidden-input retry prompts render under cmd/PowerShell/
  // Windows Terminal (the System.console() path there)
  private static String readPasswordFromTerminal() {
    Console console = System.console();
    if (console == null) throw new BitferryError("Password prompting requires an interactive terminal; run the deployed bitferry executable (deploy it with ./gradlew :bitferry-cli:installDist) -- the gradle run task does not attach a terminal to the application, and the piped or redirected standard input is deliberately not accepted");
    for (int attempt = 1; attempt <= 3; attempt++) {
      char[] pwdChars = console.readPassword("Password: ");
      char[] confirmChars = console.readPassword("Repeat password: ");
      try {
        // The end-of-stream (Ctrl+D) yields null arrays: treat it as "no password
        // entered" so obtainPassword raises its clean BitferryError - converting a
        // null array used to NPE past that check with a bare 'Error: null'
        if (pwdChars == null || confirmChars == null) return null;
        if (Arrays.equals(pwdChars, confirmChars)) return new String(pwdChars);
        console.printf("Passwords do not match; try again (attempt %d of 3)%n", attempt);
      } finally {
        if (pwdChars != null) Arrays.fill(pwdChars, '\0');
        if (confirmChars != null) Arrays.fill(confirmChars, '\0');
      }
    }
    throw new BitferryError("Passwords do not match");
  }

  private static String readSaltFromTerminal() {
    Console console = System.console();
    if (console == null) throw new BitferryError("Salt prompting requires an interactive terminal; run the deployed bitferry executable");
    for (int attempt = 1; attempt <= 3; attempt++) {
      char[] saltChars = console.readPassword("Salt: ");
      char[] confirmChars = console.readPassword("Repeat salt: ");
      try {
        if (saltChars == null || confirmChars == null) return null;
        if (Arrays.equals(saltChars, confirmChars)) return new String(saltChars);
        console.printf("Salts do not match; try again (attempt %d of 3)%n", attempt);
      } finally {
        if (saltChars != null) Arrays.fill(saltChars, '\0');
        if (confirmChars != null) Arrays.fill(confirmChars, '\0');
      }
    }
    throw new BitferryError("Salts do not match");
  }

  /// Resolve merged common options from the command spec hierarchy. Every level of the
  /// hierarchy (the root, the intermediate groups and the leaf) may carry its own common
  /// options mixin; all of them are merged following the most-verbose-wins rule so that
  /// an option supplied to any command in the chain is honored by the executed leaf.
  public static CommonOptions resolveOptions(CommandSpec spec, CommonOptions local) {
    if (spec == null) return local;
    // The ancestors collected root-first so that the higher levels take precedence
    // in the path probing order; the level owning the local instance is skipped
    // (already merged in as the base).
    Deque<CommonOptions> ancestors = new ArrayDeque<>();
    for (CommandSpec level = spec; level != null; level = level.parent()) {
      if (level.userObject() instanceof HasCommonOptions holder && holder.commonOptions() != local) {
        ancestors.addFirst(holder.commonOptions());
      }
    }
    CommonOptions merged = local;
    for (CommonOptions ancestor : ancestors) {
      merged = merged.merge(ancestor);
    }
    return merged;
  }

  /// Create and configure an unrestored Manager instance.
  public static Manager createManager(CommonOptions opts) {
    Manager m = new Manager();
    m.quiet = opts.quiet && !opts.verbose && !opts.debug;
    m.simulate = opts.simulate;
    m.force = opts.force;
    if (opts.debug) {
      m.verbosity = Verbosity.DEBUG;
    } else if (opts.verbose) {
      m.verbosity = Verbosity.VERBOSE;
    } else if (opts.quiet) {
      m.verbosity = Verbosity.QUIET;
    } else {
      m.verbosity = Verbosity.NORMAL;
    }
    configureLogging(m.verbosity);
    return m;
  }

  /// The probe roots explicitly supplied on the modify command line: --path is
  /// declared on the bare modify command only (the volume discovery tuning rides
  /// the entity modification surface), so the resolution walks the whole spec
  /// hierarchy looking for it (the leaf flows read it transparently).
  public static List<Path> probePaths(CommandSpec spec) {
    for (CommandSpec level = spec; level != null; level = level.parent()) {
      if (level.userObject() instanceof ModifyCommand modify && modify.paths != null) {
        return modify.paths;
      }
    }
    return List.of();
  }

  /// Create, configure and restore a Manager instance by probing available volumes.
  /// The probing policy of the CLI: the explicit --path roots, the BITFERRY_PATH
  /// directories, the bitferry.path system property (the JVM-level injection mirroring
  /// the GUI's AppContext), the user's home directory and all the mounted file systems.
  public static Manager restoreManager(CommandSpec spec, CommonOptions opts) {
    Manager m = createManager(opts);
    List<Path> explicit = probePaths(spec);
    List<Path> roots = explicit.isEmpty() ? new ArrayList<>() : new ArrayList<>(explicit);
    String env = System.getenv("BITFERRY_PATH");
    if (env != null && !env.isBlank()) {
      roots.addAll(org.bitferry.Paths.parseEntries(env));
    }
    roots.addAll(org.bitferry.Paths.parseEntries(System.getProperty("bitferry.path")));
    for (String entry : org.bitferry.Paths.load()) {
      try {
        roots.add(Path.of(entry));
      } catch (InvalidPathException ignored) {
        // Skip invalid path entries
      }
    }
    roots.add(Path.of(System.getProperty("user.home")));
    roots.addAll(Manager.mountPoints());
    return m.restore(roots);
  }

  /// Configure root logging level corresponding to the specified verbosity.
  /// The verbose mode keeps the informational logging reserved for the debug
  /// mode (only the WARNING and higher messages travel through the logger);
  /// the executed command lines are echoed by the backend executor directly.
  public static void configureLogging(Verbosity verbosity) {
    Logger root = LogManager.getLogManager().getLogger("");
    Level level = switch (verbosity) {
      case QUIET -> Level.OFF;
      case NORMAL, VERBOSE -> Level.WARNING;
      case DEBUG -> Level.ALL;
    };
    root.setLevel(level);
    for (Handler handler : root.getHandlers()) {
      handler.setLevel(level);
    }
  }

  /// Whether the option value list carries only the '-' clear marker: the global
  /// convention for the empty lists (the pattern/ext option pairs accept '-' where
  /// the scalar options do, mirroring the '-' of --execute/--forget/--check/--serve).
  /// The repeated occurrences accumulate into one list, so the detection is
  /// element-wise rather than positional.
  public static boolean isCleared(List<String> specs) {
    return specs != null && !specs.isEmpty() && specs.stream().allMatch("-"::equals);
  }

  /// Whether the option value list mixes the '-' clear marker with the real values
  /// (the mutually exclusive request).
  public static boolean mixesClearMarker(List<String> specs) {
    return specs != null && specs.stream().anyMatch("-"::equals) && specs.stream().anyMatch(s -> !"-".equals(s));
  }

  /// Parse and combine comma-separated filter specs and file extension lists.
  /// The '-' clear marker in either half yields the empty list (the filters
  /// wiped/absent); combining the marker with the real patterns is rejected.
  public static List<String> parsePatterns(List<String> specs, List<String> exts) {
    boolean cleared = isCleared(specs) || isCleared(exts);
    boolean specified = (specs != null && !isCleared(specs)) || (exts != null && !isCleared(exts));
    if (mixesClearMarker(specs) || mixesClearMarker(exts) || (cleared && specified)) {
      throw new BitferryError("Cannot combine '-' (clear) with the specified patterns");
    }
    if (cleared) return List.of();
    List<String> patterns = new ArrayList<>();
    if (specs != null) {
      for (String spec : specs) {
        if (spec != null) {
          for (String part : spec.split(",")) {
            part = part.trim();
            if (!part.isEmpty()) patterns.add(part);
          }
        }
      }
    }
    if (exts != null) {
      for (String ext : exts) {
        if (ext != null) {
          for (String part : ext.split(",")) {
            part = part.trim();
            // The leading dot is tolerated: '--include-ext .txt' and '--include-ext txt' mean the same
            while (part.startsWith(".")) part = part.substring(1);
            if (!part.isEmpty()) patterns.add("*." + part);
          }
        }
      }
    }
    return patterns;
  }

  /// Format an endpoint with the picocli color markup: the presentation rules are
  /// shared with the GUI flavor through [AnsiFormat], the markup resolves through
  /// Ansi.AUTO at the print time keeping the TTY detection.
  public static String formatEndpoint(Endpoint endpoint) {
    return AnsiFormat.endpoint(endpoint, AnsiFormat.Markup.PICOCLI);
  }

  /// Format a task with the picocli color markup (the shared presentation rules).
  public static String formatTask(Task task) {
    return AnsiFormat.task(task, AnsiFormat.Markup.PICOCLI);
  }

  /// The batch runner seam: the per-task backend operation (the [Task#process] run
  /// or the [Task#serve] service) the batch commands dispatch over.
  @FunctionalInterface
  public interface BatchOperation {
    Task.Outcome run(Task task);
  }

  public static int runBatch(CommandSpec spec, CommonOptions options, List<String> tags, BatchOperation operation,
                             String advisoryVerb, String allSummary, String someSummary, String commitFailureLabel) {
    return runBatch(spec, options, tags, operation, advisoryVerb, allSummary,
      "@|yellow Some of the %d task(s) completed with warnings (partial success).|@",
      someSummary, commitFailureLabel);
  }

  /// Run the batch over the tasks matched by the specified (partial) tags (or all
  /// the intact tasks when no tags given): the stale-volume and the manual-only
  /// verbose advisories, the numbered per-task dispatch with the isolated failures,
  /// the ✓/⚠/✗ marks and the summary, then the conditional post-batch commit.
  public static int runBatch(CommandSpec spec, CommonOptions options, List<String> tags, BatchOperation operation,
                             String advisoryVerb, String allSummary, String partialSummary, String someSummary, String commitFailureLabel) {
    PrintWriter out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);
    PrintWriter err = spec != null ? spec.commandLine().getErr() : new PrintWriter(System.err, true);
    CommonOptions opts = resolveOptions(spec, options);
    Manager m = restoreManager(spec, opts);
    // The tasks skipped due to the absent volumes are surfaced in verbose mode
    List<Task> stale = m.liveTasks().values().stream().filter(t -> !t.isIntact()).toList();
    if (!stale.isEmpty() && m.verbose()) {
      err.println(Ansi.AUTO.string("@|faint " + stale.size() + " task(s) skipped: their volumes are currently absent (see 'bitferry info -v')|@"));
    }
    // The mass-excluded tasks (manual-only) are surfaced in verbose mode
    if (tags == null || tags.isEmpty()) {
      long manual = m.liveTasks().values().stream().filter(Task::isIntact).filter(Task::isManual).count();
      if (manual > 0 && m.verbose()) {
        err.println(Ansi.AUTO.string("@|faint " + manual + " task(s) excluded from the mass run (manual-only; " + advisoryVerb + " them by their tags)|@"));
      }
    }
    Map<String, Task> tasks = m.selectTasks(tags);
    // An empty selection succeeds silently
    if (tasks.isEmpty()) return 0;
    int n = tasks.size();
    Task.Outcome overallOutcome = Task.Outcome.SUCCESS;
    int i = 0;
    for (Task t : tasks.values()) {
      i++;
      if (!m.quiet) {
        String line = String.format(Locale.ROOT, "@|bold [%d/%d]|@ @|cyan %s|@  %s", i, n, t.tag(), formatTask(t));
        out.println(Ansi.AUTO.string(line));
      }
      Task.Outcome outcome;
      try {
        outcome = operation.run(t);
      } catch (BitferryError e) {
        outcome = Task.Outcome.FAILURE;
        err.println(Ansi.AUTO.string("@|red     error: " + e.getMessage() + "|@"));
      } catch (RuntimeException e) {
        outcome = Task.Outcome.FAILURE;
        err.println(Ansi.AUTO.string("@|red     unexpected failure: " + e + "|@"));
        if (m.verbosity == Verbosity.DEBUG) e.printStackTrace(err);
      }
      overallOutcome = overallOutcome.and(outcome);
      if (!m.quiet) {
        String status = switch (outcome) {
          case SUCCESS -> Ansi.AUTO.string(Symbols.unicode() ? "@|green ✓ success|@" : "@|green ok success|@");
          case PARTIAL -> Ansi.AUTO.string(Symbols.unicode() ? "@|yellow ⚠ partial|@" : "@|yellow ~ partial|@");
          case FAILURE -> Ansi.AUTO.string(Symbols.unicode() ? "@|red ✗ failure|@" : "@|red x failure|@");
        };
        out.println(status);
      }
    }
    if (!m.quiet) {
      out.println();
      String summary = switch (overallOutcome) {
        case SUCCESS -> String.format(Locale.ROOT, allSummary, n);
        case PARTIAL -> String.format(Locale.ROOT, partialSummary, n);
        case FAILURE -> String.format(Locale.ROOT, someSummary, n);
      };
      // The simulation reminder rides the summary: a dry run's "executed successfully"
      // reads exactly like a real one, and the durable bookkeeping (the repository
      // state, the bisync baselines) recorded nothing
      if (m.simulate) summary = summary + " (simulated)";
      out.println(Ansi.AUTO.string(summary));
    }
    if (m.isModified()) {
      try {
        m.commit();
      } catch (BitferryError e) {
        err.println(Ansi.AUTO.string("@|red     ✗ " + commitFailureLabel + ": " + e.getMessage() + "|@"));
      }
    }
    return switch (overallOutcome) {
      case SUCCESS -> 0;
      case PARTIAL -> 3;
      case FAILURE -> 1;
    };
  }
}
