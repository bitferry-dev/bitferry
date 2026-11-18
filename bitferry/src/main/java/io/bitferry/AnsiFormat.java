package io.bitferry;

import io.bitferry.endpoint.Endpoint;
import io.bitferry.endpoint.LocalEndpoint;
import io.bitferry.endpoint.VolumeEndpoint;
import io.bitferry.task.Task;

/// Terminal formatting utilities for colorizing Bitferry log messages, tags,
/// paths, operations, endpoints, and intact/stale statuses. The structured
/// formatting (endpoints, tasks, directors) is produced in one of two markup
/// flavors: the raw ANSI escape sequences (the GUI log renders them through its
/// parser) and the picocli markup (the CLI resolves it through Ansi.AUTO keeping
/// the TTY detection). The single-flavor methods default to the raw ANSI.
public final class AnsiFormat {

  public static final String RESET = "\u001B[0m";
  public static final String BOLD = "\u001B[1m";
  public static final String DIM = "\u001B[2m";
  public static final String RED = "\u001B[31m";
  public static final String GREEN = "\u001B[32m";
  public static final String YELLOW = "\u001B[33m";
  public static final String BLUE = "\u001B[34m";
  public static final String MAGENTA = "\u001B[35m";
  public static final String CYAN = "\u001B[36m";
  public static final String BOLD_RED = "\u001B[1;31m";
  public static final String BOLD_GREEN = "\u001B[1;32m";
  public static final String BOLD_YELLOW = "\u001B[1;33m";
  public static final String BOLD_CYAN = "\u001B[1;36m";

  /// The markup flavor the formatted strings target.
  public enum Markup {
    /// The raw ANSI SGR escape sequences (always emitted, TTY or not).
    ANSI {
      @Override
      String styled(String style, String text) {
        return switch (style) {
          case "bold" -> BOLD + text + RESET;
          case "red" -> RED + text + RESET;
          case "green" -> GREEN + text + RESET;
          case "yellow" -> YELLOW + text + RESET;
          case "blue" -> BLUE + text + RESET;
          case "cyan" -> CYAN + text + RESET;
          case "bold,red" -> BOLD_RED + text + RESET;
          case "bold,cyan" -> BOLD_CYAN + text + RESET;
          default -> text;
        };
      }
    },
    /// The picocli markup (@|style text|@), resolved by Ansi.AUTO at the print time
    /// keeping the TTY detection.
    PICOCLI {
      @Override
      String styled(String style, String text) {
        return "@|" + style + " " + text + "|@";
      }
    };

    /// Wrap the text into the flavor's markup carrying the semantic style.
    abstract String styled(String style, String text);
  }

  private AnsiFormat() {}

  /// Format an entity tag (volume or task) in cyan.
  public static String tag(String tag) {
    if (tag == null) return "";
    return CYAN + tag + RESET;
  }

  /// Format a stale volume tag in bold red (the enclosing braces are the caller's).
  public static String staleTag(String tag) {
    if (tag == null) return "";
    return BOLD_RED + tag + RESET;
  }

  /// Format a path in blue.
  public static String path(Object path) {
    if (path == null) return "";
    return BLUE + path + RESET;
  }

  /// Format an operation verb in bold.
  public static String operation(String op) {
    if (op == null) return "";
    return BOLD + op + RESET;
  }

  /// Format a director arrow in the specified markup flavor (green for the
  /// unidirectional, yellow for the bidirectional flow); the display form carries
  /// the unicode-friendly arrow ([Task.displayDirector]).
  public static String director(String arrow, Markup markup) {
    if (arrow == null) return "";
    return markup.styled("<->".equals(arrow) ? "yellow" : "green", Task.displayDirector(arrow));
  }

  /// Format a director arrow (green for the unidirectional, yellow for the
  /// bidirectional flow) in the raw ANSI flavor.
  public static String director(String arrow) {
    return director(arrow, Markup.ANSI);
  }

  /// Format an endpoint with color markup in the specified flavor (blue for local
  /// paths, cyan for intact volume tags, and bold red in braces for stale volume tags).
  public static String endpoint(Endpoint endpoint, Markup markup) {
    if (endpoint == null) return "";
    if (endpoint instanceof LocalEndpoint l) {
      return markup.styled("blue", l.root().toString());
    } else if (endpoint instanceof VolumeEndpoint ve) {
      String path = ve.path().equals(".") ? "" : ve.path();
      if (ve.isIntact()) {
        return ":" + markup.styled("cyan", ve.vtag()) + ":" + path;
      } else {
        return ":{" + markup.styled("bold,red", ve.vtag()) + "}:" + path;
      }
    }
    return endpoint.describe();
  }

  /// Format an endpoint with ANSI color markup (blue for local paths, cyan for
  /// intact volume tags, and bold red in braces for stale volume tags).
  public static String endpoint(Endpoint endpoint) {
    return endpoint(endpoint, Markup.ANSI);
  }

  /// Format a full task line with colorized operation, source endpoint, director arrow,
  /// and destination endpoint in the specified markup flavor.
  public static String task(Task task, Markup markup) {
    if (task == null) return "";
    return String.format("%s %s %s %s",
      markup.styled("bold", task.displayOperation()),
      endpoint(task.source(), markup),
      director(task.director(), markup),
      endpoint(task.destination(), markup)
    );
  }

  /// Format a full task line with colorized operation, source endpoint, director arrow,
  /// and destination endpoint in the raw ANSI flavor.
  public static String task(Task task) {
    return task(task, Markup.ANSI);
  }

  /// Format the manager tasks summary indicating total, intact, and stale counts.
  /// The counts are colored only when non-zero (an all-stale manager renders the
  /// intact count uncolored).
  public static String tasksSummary(Manager manager) {
    if (manager == null) return "0 task(s)";
    int total = manager.liveTasks().size();
    if (total == 0) return "0 task(s)";
    long intact = manager.liveTasks().values().stream().filter(Task::isIntact).count();
    long stale = total - intact;
    String intactPart = intact > 0 ? GREEN + intact + " intact" + RESET : intact + " intact";
    if (stale == 0) {
      return total + " task(s) (" + intactPart + ")";
    } else {
      return total + " task(s) (" + intactPart + ", " + BOLD_RED + stale + " stale" + RESET + ")";
    }
  }

  /// Format an error message in red.
  public static String error(String message) {
    if (message == null) return "";
    return RED + message + RESET;
  }

  /// Format a warning message in yellow.
  public static String warning(String message) {
    if (message == null) return "";
    return YELLOW + message + RESET;
  }

  /// Format a success message in green.
  public static String success(String message) {
    if (message == null) return "";
    return GREEN + message + RESET;
  }

  /// Strip ANSI escape codes from the string.
  public static String strip(String text) {
    if (text == null || text.isEmpty()) return text;
    return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
  }
}
