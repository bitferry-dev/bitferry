package bitferry.task;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/// ProcessBuilder-based command executor. The CLI pass-through mode inherits stdout
/// (the interactive progress display and the backend colors need the raw handle);
/// stderr is split by the platform: on the unix terminals it is inherited too (UTF-8
/// and the live progress are native there), while on Windows it is decoded as UTF-8
/// and re-printed through System.err - the raw child bytes would be rendered by the
/// console through its legacy code page, garbling the unicode filenames in the log
/// output - the jansi-wrapped System.err writes them through the wide-char console
/// API instead, code-page independently. The captured mode merges both streams and
/// forwards the UTF-8 lines to the output consumer in real time.
public final class SystemCommandExecutor implements CommandExecutor {

  public static final SystemCommandExecutor INSTANCE = new SystemCommandExecutor();

  private SystemCommandExecutor() {}

  @Override
  public int execute(List<String> command, Path workingDirectory, Map<String, String> environment, Consumer<String> output)
    throws IOException, InterruptedException {
    return execute(command, workingDirectory, environment, output, ExecutorSupport.isWindows());
  }

  /// The pumpStderr flag forces the Windows-style stderr handling (the UTF-8 decode
  /// and the System.err re-print) regardless of the platform, keeping the branch
  /// testable on the unix hosts.
  int execute(List<String> command, Path workingDirectory, Map<String, String> environment, Consumer<String> output, boolean pumpStderr)
    throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    if (environment != null && !environment.isEmpty()) {
      builder.environment().putAll(environment);
    }

    if (output == null) {
      if (pumpStderr) {
        // Windows CLI mode: stdin/stdout inherit the real terminal (the interactive
        // progress display and the backend colors need the raw handle), while stderr
        // is decoded as UTF-8 and re-printed through System.err: the raw child bytes
        // would be rendered by the console through its legacy code page, garbling the
        // unicode filenames in the log output - the jansi-wrapped System.err writes
        // them through the wide-char console API instead, code-page independently.
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        BackendProcesses.register(process);
        Thread pump = new Thread(() -> {
          try {
            InputStreamReader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8);
            char[] chunk = new char[512];
            int read;
            while ((read = reader.read(chunk)) != -1) {
              System.err.print(new String(chunk, 0, read));
              System.err.flush();
            }
          } catch (IOException ignored) {
            // The stream ends when the process is torn down; nothing to salvage
          }
        });
        pump.start();
        try {
          process.waitFor();
          pump.join(2000);
          return process.exitValue();
        } catch (InterruptedException e) {
          process.destroyForcibly();
          pump.interrupt();
          Thread.currentThread().interrupt();
          throw e;
        } finally {
          BackendProcesses.unregister(process);
        }
      }
      // Unix CLI mode: full inheritance - the terminal decodes UTF-8 natively and
      // preserves the backends' live progress displays
      builder.inheritIO();
      Process process = builder.start();
      BackendProcesses.register(process);
      try {
        return process.waitFor();
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw e;
      } finally {
        BackendProcesses.unregister(process);
      }
    } else {
      // GUI / captured mode: merge stdout and stderr, stream line-by-line in real time
      builder.redirectErrorStream(true);
      Process process = builder.start();
      BackendProcesses.register(process);
      try {
        try {
          process.getOutputStream().close();
        } catch (IOException ignored) {}
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
        } catch (RuntimeException e) {
          // A failing output consumer must not orphan the child (it keeps writing
          // into the unread pipe and may hang forever): tear it down before
          // propagating, escalating when the graceful signal is ignored
          process.destroy();
          if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
          }
          throw e;
        }
      } finally {
        BackendProcesses.unregister(process);
      }
    }
  }
}
