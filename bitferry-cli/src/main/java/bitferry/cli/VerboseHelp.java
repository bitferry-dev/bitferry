package bitferry.cli;

import bitferry.task.*;
import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.Help.IOptionRenderer;
import picocli.CommandLine.IHelpFactory;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/// Custom help factory and renderer that expands profile contents in option descriptions
/// only when verbose mode (-v, --verbose) is specified.
public final class VerboseHelp implements IHelpFactory {

  public static final VerboseHelp INSTANCE = new VerboseHelp();

  private VerboseHelp() {}

  @Override
  public Help create(CommandSpec commandSpec, ColorScheme colorScheme) {
    return new CustomHelp(commandSpec, colorScheme, isVerbose(commandSpec));
  }

  /// Check whether verbose mode was requested on any command in the parse result hierarchy.
  public static boolean isVerbose(CommandSpec commandSpec) {
    if (commandSpec == null || commandSpec.commandLine() == null) {
      return false;
    }
    CommandLine root = commandSpec.commandLine();
    while (root.getParent() != null) {
      root = root.getParent();
    }
    CommandLine.ParseResult pr = root.getParseResult();
    if (pr == null) {
      return false;
    }
    for (CommandLine cl : pr.asCommandLineList()) {
      CommandLine.ParseResult subPr = cl.getParseResult();
      if (subPr != null && (subPr.hasMatchedOption("-v") || subPr.hasMatchedOption("--verbose"))) {
        return true;
      }
    }
    return false;
  }

  /// Resolve extra profile description lines for the given option.
  public static List<String> profileLines(CommandSpec commandSpec, OptionSpec opt) {
    if (opt == null) return List.of();
    Object userObject = commandSpec != null ? commandSpec.userObject() : null;
    List<String> names = Arrays.asList(opt.names());
    List<String> extra = new ArrayList<>();
    if (userObject instanceof CreateBackupCommand) {
      if (names.contains("--forget")) {
        extra.add("  @default: " + String.join(" ", Backup.FORGET.get("default")));
      } else if (names.contains("--check")) {
        extra.add("  @default: " + String.join(" ", Backup.CHECK.get("default")));
        extra.add("  @full:    " + String.join(" ", Backup.CHECK.get("full")));
      } else if (names.contains("--execute")) {
        extra.add("  @default: " + String.join(" ", Backup.PROCESS.get("default")));
      }
    } else if (userObject instanceof CreateRestoreCommand) {
      if (names.contains("--execute")) {
        extra.add("  @default: " + String.join(" ", Restore.PROCESS.get("default")));
      }
    } else if (userObject instanceof RcloneTaskCommand) {
      if (names.contains("--execute")) {
        extra.add("  @default: " + String.join(" ", Rclone.PROCESS.get("default")));
      } else if (names.contains("--encrypt") || names.contains("--decrypt")) {
        extra.add("  @default:  " + String.join(" ", Rclone.PROFILE.get("default")));
        extra.add("  @extended: " + String.join(" ", Rclone.PROFILE.get("extended")));
      }
    }
    return extra;
  }

  private static class CustomHelp extends Help {

    private final boolean verbose;

    public CustomHelp(CommandSpec commandSpec, ColorScheme colorScheme, boolean verbose) {
      super(commandSpec, colorScheme);
      this.verbose = verbose;
    }

    @Override
    public IOptionRenderer createDefaultOptionRenderer() {
      IOptionRenderer defaultRenderer = super.createDefaultOptionRenderer();
      return (opt, paramLabelRenderer, colorScheme) -> {
        if (verbose) {
          List<String> extra = profileLines(commandSpec(), opt);
          if (!extra.isEmpty()) {
            List<String> combined = new ArrayList<>();
            if (opt.description() != null) {
              combined.addAll(Arrays.asList(opt.description()));
            }
            combined.addAll(extra);
            opt = opt.toBuilder().description(combined.toArray(new String[0])).build();
          }
        }
        return defaultRenderer.render(opt, paramLabelRenderer, colorScheme);
      };
    }
  }
}
