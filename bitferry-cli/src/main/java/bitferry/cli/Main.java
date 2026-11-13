package bitferry.cli;

import bitferry.*;
import java.io.*;
import org.fusesource.jansi.AnsiConsole;
import picocli.*;
import picocli.CommandLine.Help.*;

/// The bitferry command line interface entry point.
public final class Main {

  private Main() {}

  /// Construct and configure a [CommandLine] instance for Bitferry.
  public static CommandLine createCommandLine() {
    BitferryCommand root = new BitferryCommand();
    CommandLine cmd = new CommandLine(root);
    cmd.setHelpFactory(VerboseHelp.INSTANCE);
    cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
      if (ex instanceof BitferryError) {
        commandLine.getErr().println(Ansi.AUTO.string("@|bold,red Error:|@ " + ex.getMessage()));
        boolean debug = parseResult != null && parseResult.hasMatchedOption("--debug");
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
    // The compact single-line console logging in place of the verbose JUL blocks
    ConsoleLog.compact();
    // The jansi console translates the ANSI escapes and carries the unicode through
    // the Windows console API (WriteConsoleW): this is what turns the colors and the
    // unicode glyphs on in the Windows terminals (picocli's detection recognizes an
    // installed jansi console). On the unix terminals it is a pass-through.
    AnsiConsole.systemInstall();
    try {
      CommandLine cmd = createCommandLine();
      int exitCode = cmd.execute(args);
      System.exit(exitCode);
    } finally {
      AnsiConsole.systemUninstall();
    }
  }
}
