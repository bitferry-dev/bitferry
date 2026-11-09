package bitferry.task;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/// ProcessBuilder-based command executor supporting stdout/stderr pass-through or real-time line capturing.
public final class SystemCommandExecutor implements CommandExecutor {

  public static final SystemCommandExecutor INSTANCE = new SystemCommandExecutor();

  private SystemCommandExecutor() {}

  @Override
  public int execute(List<String> command, Path workingDirectory, Map<String, String> environment, Consumer<String> output)
    throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    if (environment != null && !environment.isEmpty()) {
      builder.environment().putAll(environment);
    }

    if (output == null) {
      // CLI mode: inherit stdout and stderr directly
      builder.inheritIO();
      Process process = builder.start();
      try {
        return process.waitFor();
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw e;
      }
    } else {
      // GUI / captured mode: merge stdout and stderr, stream line-by-line in real time
      builder.redirectErrorStream(true);
      Process process = builder.start();
      try {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            output.accept(line);
          }
        }
        return process.waitFor();
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw e;
      }
    }
  }
}
