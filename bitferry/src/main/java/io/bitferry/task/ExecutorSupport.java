package io.bitferry.task;

import io.bitferry.Manager;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/// Shared plumbing for the backend executors: the command line echo, the command
/// execution through the executor seam with the outcome mapping, and the backend
/// executable resolution.
public final class ExecutorSupport {

  private static final System.Logger LOG = System.getLogger(ExecutorSupport.class.getName());

  private ExecutorSupport() {}

  /// Echo the command line being executed in the verbose (and debug) mode: the
  /// informational logging stays reserved for the debug mode, so the command
  /// lines travel directly (to the captured-output consumer when present,
  /// stderr otherwise) bypassing the logger.
  static void echoCommandLine(Manager manager, List<String> command, Consumer<String> output) {
    if (!manager.verbose()) return;
    String commandLine = displayCommandLine(command);
    if (output != null) output.accept(commandLine);
    else System.err.println(commandLine);
  }

  /// Format the command line for display, replacing bundled backend paths with generic
  /// names. The tokens carrying whitespace (or quotes, or empty) are quoted so the
  /// echoed line stays unambiguous and pasteable into a shell - the filter patterns
  /// ('+ *.png' style) and the spaced paths are everyday occurrences.
  public static String displayCommandLine(List<String> command) {
    return displayCommand(command).stream().map(ExecutorSupport::displayToken).collect(java.util.stream.Collectors.joining(" "));
  }

  /// Quote a single display token when it cannot survive a bare join with spaces.
  /// Only the embedded double quotes are escaped: inside the double quotes of the
  /// POSIX shells the backslashes stay literal, keeping the Windows paths readable.
  private static String displayToken(String token) {
    boolean bare = !token.isEmpty() && token.chars().noneMatch(Character::isWhitespace) && !token.contains("\"");
    return bare ? token : "\"" + token.replace("\"", "\\\"") + "\"";
  }

  /// Return a command line tokens copy with bundled backend paths replaced with generic names.
  static List<String> displayCommand(List<String> command) {
    String home = System.getProperty("java.home", ".");
    return displayCommand(command, System::getenv, Path.of(home).toAbsolutePath().normalize());
  }

  static List<String> displayCommand(List<String> command, Function<String, String> environment, Path javaHome) {
    if (command == null || command.isEmpty()) return List.of();
    String exec = command.get(0);
    String display = displayExecutable(exec, environment, javaHome);
    if (Objects.equals(exec, display)) return command;
    List<String> result = new ArrayList<>(command);
    result.set(0, display);
    return result;
  }

  /// Replace a bundled backend executable path with its generic tool name (e.g. "rclone", "restic").
  static String displayExecutable(String executable) {
    String home = System.getProperty("java.home", ".");
    return displayExecutable(executable, System::getenv, Path.of(home).toAbsolutePath().normalize());
  }

  static String displayExecutable(String executable, Function<String, String> environment, Path javaHome) {
    if (executable == null || executable.isBlank()) return executable;
    for (String backend : List.of("rclone", "restic")) {
      if (isBundled(executable, backend, environment, javaHome)) {
        return backend;
      }
    }
    return executable;
  }

  /// Return true if the given executable path is the distribution-bundled backend executable.
  static boolean isBundled(String executable, String backend) {
    String home = System.getProperty("java.home", ".");
    return isBundled(executable, backend, System::getenv, Path.of(home).toAbsolutePath().normalize());
  }

  static boolean isBundled(String executable, String backend, Function<String, String> environment, Path javaHome) {
    if (executable == null || backend == null) return false;
    String property = System.getProperty("bitferry." + backend);
    if (property != null && !property.isBlank() && samePath(executable, property)) return true;
    String override = environment.apply(backend.toUpperCase());
    if (override != null && !override.isBlank()) return false;
    for (Path dir : bundledDirectories(javaHome)) {
      Path candidate = dir.resolve(executableName(backend));
      if (samePath(executable, candidate.toString())) return true;
    }
    return false;
  }

  private static boolean samePath(String p1, String p2) {
    if (Objects.equals(p1, p2)) return true;
    try {
      return Path.of(p1).toAbsolutePath().normalize().equals(Path.of(p2).toAbsolutePath().normalize());
    } catch (Exception e) {
      return false;
    }
  }

  /// Execute the command through the executor seam echoing the command line and
  /// reporting the failures to the logger and the output consumer. Return true
  /// on the zero exit code.
  static boolean execute(CommandExecutor executor, Manager manager, List<String> command, Path workingDirectory,
                         Map<String, String> environment, String taskTag, Consumer<String> output) {
    return execute(executor, manager, command, workingDirectory, environment, taskTag, output, new int[0]);
  }

  /// The variant tolerating the additional (non-zero) exit codes: they report success
  /// with a warning line instead of a failure (e.g. the restic backup exits 3 when the
  /// snapshot was written but some source files could not be read - a partial success,
  /// not an outright failure: the outright failure would skew the maintenance cadence).
  static boolean execute(CommandExecutor executor, Manager manager, List<String> command, Path workingDirectory,
                         Map<String, String> environment, String taskTag, Consumer<String> output, int... toleratedExitCodes) {
    echoCommandLine(manager, command, output);
    try {
      int exitCode = executor.execute(command, workingDirectory, environment, output);
      if (exitCode != 0) {
        boolean tolerated = false;
        for (int code : toleratedExitCodes) {
          if (code == exitCode) tolerated = true;
        }
        if (tolerated) {
          String message = "Command completed with exit code " + exitCode + " for task " + taskTag + " (partial success)";
          LOG.log(Level.WARNING, () -> message);
          if (output != null) output.accept(message);
          return true;
        }
        String message = "Command failed with exit code " + exitCode + " for task " + taskTag;
        LOG.log(Level.ERROR, () -> message);
        if (output != null) output.accept(message);
        return false;
      }
      return true;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      String message = "Task " + taskTag + " processing failure: " + e.getMessage();
      LOG.log(Level.ERROR, () -> message);
      if (output != null) output.accept(message);
      return false;
    }
  }

  /// Resolve the backend executable: the {@code bitferry.<backend>} system property
  /// (pointed at the bundled executable by the packaged launchers) first, then the
  /// explicit RCLONE/RESTIC environment override, then the executable bundled with
  /// the distribution (placed alongside the launcher by the packaging), then the
  /// bare name for the PATH lookup.
  static String resolveExecutable(String backend, Function<String, String> environment, Path javaHome) {
    String property = System.getProperty("bitferry." + backend);
    if (property != null && !property.isBlank()) return property;
    String override = environment.apply(backend.toUpperCase());
    if (override != null && !override.isBlank()) return override;
    for (Path directory : bundledDirectories(javaHome)) {
      Path executable = directory.resolve(executableName(backend));
      if (Files.isRegularFile(executable)) return executable.toString();
    }
    return backend;
  }

  /// The directory candidates of the distribution-bundled executables relative
  /// to the running runtime image: the launcher directory of the jlink image,
  /// the image root of the jpackage image and the AppImage root.
  static List<Path> bundledDirectories(Path javaHome) {
    List<Path> directories = new ArrayList<>();
    directories.add(javaHome.resolve("bin")); // The jlink image launcher directory
    Path parent = javaHome.getParent();
    if (parent != null) {
      directories.add(parent); // The jpackage image root (Windows)
      directories.add(parent.getParent()); // The AppImage root (Linux)
    }
    return directories.stream().filter(Objects::nonNull).distinct().toList();
  }

  static String executableName(String backend) {
    return isWindows() ? backend + ".exe" : backend;
  }

  /// Resolve the backend executable probing the launcher system properties, the
  /// process environment and the runtime image of the running JVM.
  static String resolveExecutable(String backend) {
    String home = System.getProperty("java.home", ".");
    return resolveExecutable(backend, System::getenv, Path.of(home).toAbsolutePath().normalize());
  }

  static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
