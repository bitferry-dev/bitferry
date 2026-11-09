package bitferry.task;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/// Pluggable command execution interface for spawning processes with stdout/stderr pass-through or line capturing.
public interface CommandExecutor {

  /// Execute an external command.
  /// When output is null, stdout and stderr are inherited from the parent process (CLI mode).
  /// When output is non-null, combined stdout/stderr is captured line-by-line and delivered to the consumer (GUI mode).
  int execute(List<String> command, Path workingDirectory, Map<String, String> environment, Consumer<String> output)
    throws IOException, InterruptedException;

  /// Default system process executor based on Java's ProcessBuilder.
  static CommandExecutor system() {
    return SystemCommandExecutor.INSTANCE;
  }
}
