package bitferry;

import bitferry.endpoint.Endpoint;
import bitferry.endpoint.LocalEndpoint;
import bitferry.endpoint.VolumeEndpoint;
import bitferry.task.Task;

/// ANSI terminal formatting utilities for colorizing Bitferry log messages, tags,
/// paths, operations, endpoints, and intact/stale statuses.
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

  private AnsiFormat() {}

  /// Format an entity tag (volume or task) in cyan.
  public static String tag(String tag) {
    if (tag == null) return "";
    return CYAN + tag + RESET;
  }

  /// Format a stale volume tag in bold red with braces.
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

  /// Format a director arrow (green for -->, yellow for <->).
  public static String director(String arrow) {
    if (arrow == null) return "";
    return ("<->".equals(arrow) ? YELLOW : GREEN) + arrow + RESET;
  }

  /// Format an endpoint with ANSI color markup (blue for local paths, cyan for
  /// intact volume tags, and bold red in braces for stale volume tags).
  public static String endpoint(Endpoint endpoint) {
    if (endpoint == null) return "";
    if (endpoint instanceof LocalEndpoint l) {
      return BLUE + l.root() + RESET;
    } else if (endpoint instanceof VolumeEndpoint ve) {
      String path = ve.path().equals(".") ? "" : ve.path();
      if (ve.isIntact()) {
        return ":" + CYAN + ve.vtag() + RESET + ":" + path;
      } else {
        return ":{" + BOLD_RED + ve.vtag() + RESET + "}:" + path;
      }
    }
    return endpoint.describe();
  }

  /// Format a full task line with colorized operation, source endpoint, director arrow,
  /// and destination endpoint.
  public static String task(Task task) {
    if (task == null) return "";
    return String.format("%s %s %s %s",
      operation(task.displayOperation()),
      endpoint(task.source()),
      director(task.director()),
      endpoint(task.destination())
    );
  }

  /// Format the manager tasks summary indicating total, intact, and stale counts.
  public static String tasksSummary(Manager manager) {
    if (manager == null) return "0 task(s)";
    int total = manager.liveTasks().size();
    if (total == 0) return "0 task(s)";
    long intact = manager.liveTasks().values().stream().filter(Task::isIntact).count();
    long stale = total - intact;
    if (stale == 0) {
      return total + " task(s) (" + GREEN + intact + " intact" + RESET + ")";
    } else {
      return total + " task(s) (" + GREEN + intact + " intact" + RESET + ", " + BOLD_RED + stale + " stale" + RESET + ")";
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
