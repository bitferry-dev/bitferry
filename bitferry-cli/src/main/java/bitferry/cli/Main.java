package bitferry.cli;

import bitferry.*;
import java.io.*;
import java.util.*;
import org.fusesource.jansi.AnsiConsole;
import picocli.*;
import picocli.CommandLine.Help.*;

/// The bitferry command line interface entry point.
public final class Main {

  private Main() {}

  /// Check whether the debug flag was matched at any command level of the parse result.
  public static boolean isDebug(picocli.CommandLine.ParseResult parseResult) {
    if (parseResult == null) return false;
    for (CommandLine cl : parseResult.asCommandLineList()) {
      picocli.CommandLine.ParseResult pr = cl.getParseResult();
      if (pr != null && pr.hasMatchedOption("--debug")) {
        return true;
      }
    }
    return false;
  }

  /// Construct and configure a [CommandLine] instance for Bitferry.
  public static CommandLine createCommandLine() {
    BitferryCommand root = new BitferryCommand();
    CommandLine cmd = new CommandLine(root);
    cmd.setHelpFactory(VerboseHelp.INSTANCE);
    cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
      if (ex instanceof BitferryError) {
        commandLine.getErr().println(Ansi.AUTO.string("@|bold,red Error:|@ " + ex.getMessage()));
        // The debug flag may be supplied at any command level of the hierarchy
        boolean debug = parseResult != null && isDebug(parseResult);
        if (debug && ex.getCause() != null) {
          ex.printStackTrace(commandLine.getErr());
        }
        return 1;
      }
      commandLine.getErr().println(Ansi.AUTO.string("@|bold,red Error:|@ " + ex.getMessage()));
      ex.printStackTrace(commandLine.getErr());
      return 1;
    });
    cmd.setParameterExceptionHandler((ex, args) -> {
      PrintWriter err = ex.getCommandLine() != null ? ex.getCommandLine().getErr() : new PrintWriter(System.err, true);
      CommandLine.UnmatchedArgumentException.printSuggestions(ex, err);
      err.println(Ansi.AUTO.string("@|bold,red Error:|@ " + ex.getMessage()));
      err.println("Try 'bitferry --help' for usage.");
      return 2;
    });
    return cmd;
  }

  public static void main(String[] args) {
    // The jansi console translates the ANSI escapes and carries the unicode through
    // the Windows console API (WriteConsoleW): this is what turns the colors and the
    // unicode glyphs on in the Windows terminals (picocli's detection recognizes an
    // installed jansi console). On the unix terminals it is a pass-through. It is
    // installed before the logging configuration so that the JUL handlers bind the
    // jansi-wrapped streams (not the raw ones they replace).
    AnsiConsole.systemInstall();
    // The compact single-line console logging in place of the verbose JUL blocks
    ConsoleLog.compact();
    int exitCode;
    try {
      CommandLine cmd = createCommandLine();
      exitCode = cmd.execute(args);
    } finally {
      AnsiConsole.systemUninstall();
    }
    System.exit(exitCode);
  }
}
