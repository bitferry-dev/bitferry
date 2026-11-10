package bitferry.cli;

import bitferry.*;
import java.io.*;
import picocli.*;
import picocli.CommandLine.Help.*;

/// The bitferry command line interface entry point.
public final class Main {

  private Main() {}

  /// Construct and configure a [CommandLine] instance for Bitferry.
  public static CommandLine createCommandLine() {
    BitferryCommand root = new BitferryCommand();
    CommandLine cmd = new CommandLine(root);
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
    CommandLine cmd = createCommandLine();
    int exitCode = cmd.execute(args);
    System.exit(exitCode);
  }
}
