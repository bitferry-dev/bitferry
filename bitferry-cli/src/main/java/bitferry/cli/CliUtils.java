package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import bitferry.vault.KeyringVault;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;
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

  /// Obtain the password by prompting from the terminal with hidden input and confirmation.
  /// The passwords are never accepted from the command line arguments or the piped standard
  /// input channel so that they do not leak into the shell history or the process listings.
  public static String obtainPassword() {
    String password = passwordSource.get();
    if (password == null || password.isEmpty()) throw new BitferryError("Password must be specified");
    return password;
  }

  /// Surface the keyring storage outcome as a faint advisory note: the explicit
  /// request demoted by the missing keyring, or the removable-media portability
  /// tradeoff of the honored request.
  public static void noteKeyringStorage(picocli.CommandLine.Model.CommandSpec spec, Task task, boolean keyring) {
    if (!keyring) return;
    PrintWriter err = spec != null ? spec.commandLine().getErr() : new PrintWriter(System.err, true);
    if (!KeyringVault.INSTANCE.available()) {
      err.println(Ansi.AUTO.string("@|faint the OS keyring is not usable on this host - the password token is kept in the volume vault|@"));
      return;
    }
    try {
      if (!task.vaultVolume().isFixed()) {
        err.println(Ansi.AUTO.string("@|faint the vault volume resides on removable media - the keyring-stored password token will not travel with it|@"));
      }
    } catch (BitferryError ignored) {
      // No volume-resident end: the task persistence check reports it separately
    }
  }

  // The terminal prompting: hidden input with confirmation
  private static String readPasswordFromTerminal() {
    Console console = System.console();
    if (console == null) throw new BitferryError("Password prompting requires an interactive terminal; run the deployed bitferry executable (deploy it with ./gradlew :bitferry-cli:installDist) -- the gradle run task does not attach a terminal to the application, and the piped or redirected standard input is deliberately not accepted");
    char[] pwdChars = console.readPassword("Password: ");
    char[] confirmChars = console.readPassword("Repeat password: ");
    try {
      if (!Arrays.equals(pwdChars, confirmChars)) throw new BitferryError("Passwords do not match");
      return new String(pwdChars);
    } finally {
      if (pwdChars != null) Arrays.fill(pwdChars, '\0');
      if (confirmChars != null) Arrays.fill(confirmChars, '\0');
    }
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
    m.quiet = opts.quiet;
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

  /// Create, configure and restore a Manager instance by probing available volumes.
  /// The probing policy of the CLI: the explicit --path roots, the BITFERRY_PATH
  /// directories, the user's home directory and all the mounted file systems.
  public static Manager restoreManager(CommonOptions opts) {
    Manager m = createManager(opts);
    List<Path> roots = opts.paths == null ? new ArrayList<>() : new ArrayList<>(opts.paths);
    String env = System.getenv("BITFERRY_PATH");
    if (env != null && !env.isBlank()) {
      for (String entry : env.split(Pattern.quote(File.pathSeparator))) roots.add(Path.of(entry));
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

  /// Parse and combine comma-separated filter specs and file extension lists.
  public static List<String> parsePatterns(List<String> specs, List<String> exts) {
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
            if (!part.isEmpty()) patterns.add("*." + part);
          }
        }
      }
    }
    return patterns;
  }

  /// Format an endpoint with ANSI color markup for CLI display.
  public static String formatEndpoint(Endpoint endpoint) {
    if (endpoint instanceof LocalEndpoint l) {
      return Ansi.AUTO.string("@|blue " + l.root() + "|@");
    } else if (endpoint instanceof VolumeEndpoint ve) {
      // The volume root's '.' path is omitted in the listings
      String path = ve.path().equals(".") ? "" : ve.path();
      if (ve.isIntact()) {
        return Ansi.AUTO.string(":@|cyan " + ve.vtag() + "|@:" + path);
      } else {
        return Ansi.AUTO.string(":{@|bold,red " + ve.vtag() + "|@}:" + path);
      }
    }
    return endpoint.describe();
  }

  /// Format the task director arrow with ANSI color markup: the bidirectional arrow
  /// renders yellow (the color keyed on the arrow the task carries).
  public static String formatDirector(Task task) {
    String arrow = task.director();
    return Ansi.AUTO.string("@|" + ("<->".equals(arrow) ? "yellow" : "green") + " " + arrow + "|@");
  }

  /// Format a task with ANSI color markup.
  public static String formatTask(Task task) {
    return String.format("%s %s %s %s",
      Ansi.AUTO.string("@|bold " + task.displayOperation() + "|@"),
      formatEndpoint(task.source()),
      formatDirector(task),
      formatEndpoint(task.destination())
    );
  }
}
