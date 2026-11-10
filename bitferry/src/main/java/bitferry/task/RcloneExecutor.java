package bitferry.task;

import bitferry.*;
import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import tools.jackson.databind.*;

/// Rclone backend executor: builds the command line for the rclone-backed tasks (the
/// operation subcommand, the filters, the common options, the process options and the
/// crypt tail placing the ':crypt:' remote) and spawns rclone with the crypt password
/// environment.
final class RcloneExecutor {

  private final CommandExecutor commandExecutor;

  RcloneExecutor(CommandExecutor commandExecutor) {
    this.commandExecutor = commandExecutor;
  }

  /// Conformance test of the candidate encryption options against existing backend data.
  void conform(Rclone rclone) {
    if (rclone.encryption() instanceof Rclone.NoCrypt || rclone.encryption() == null) {
      return;
    }
    String encryptedSide;
    if (rclone.encryption() instanceof Rclone.Encrypt) {
      encryptedSide = rclone.destination().processOpts();
    } else if (rclone.encryption() instanceof Rclone.Decrypt) {
      encryptedSide = rclone.source().processOpts();
    } else {
      return;
    }

    Path encryptedDir = Path.of(encryptedSide);
    if (!Files.isDirectory(encryptedDir)) {
      return;
    }

    Set<String> rawPaths = new HashSet<>();
    try (Stream<Path> stream = Files.walk(encryptedDir)) {
      for (Path p : stream.toList()) {
        if (p.equals(encryptedDir)) continue;
        Path rel = encryptedDir.relativize(p);
        if (isStoragePath(rel)) continue;
        rawPaths.add(rel.toString().replace('\\', '/'));
      }
    } catch (IOException e) {
      throw new BitferryError("failed to inspect '" + encryptedSide + "': " + e.getMessage(), e);
    }

    if (rawPaths.isEmpty()) {
      return;
    }

    List<String> cmd = new ArrayList<>();
    cmd.add(ExecutorSupport.resolveExecutable("rclone"));
    cmd.add("lsjson");
    cmd.add("-R");
    cmd.add("--config");
    cmd.add(ExecutorSupport.isWindows() ? "NUL" : "/dev/null");
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE);
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE_IMAGE);
    cmd.addAll(rclone.encryption().options());
    cmd.add("--crypt-remote");
    cmd.add(encryptedSide);
    cmd.add(":crypt:");

    ExecutorSupport.echoCommandLine(rclone.manager(), cmd, null);

    List<String> outputLines = new ArrayList<>();
    int exitCode;
    try {
      exitCode = commandExecutor.execute(cmd, null, rclone.encryption().processEnvironment(), outputLines::add);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new BitferryError(refusalMessage(encryptedSide), e);
    }

    if (exitCode != 0 || outputLines.stream().anyMatch(RcloneExecutor::isDecryptionError)) {
      throw new BitferryError(refusalMessage(encryptedSide));
    }

    Set<String> cryptPaths = extractCryptPaths(outputLines);
    boolean filenameEncEnabled = isFilenameEncryptionEnabled(rclone.encryption().options());
    if (cryptPaths.isEmpty() || (filenameEncEnabled && cryptPaths.equals(rawPaths))) {
      throw new BitferryError(refusalMessage(encryptedSide));
    }
  }

  private static String refusalMessage(String encryptedSide) {
    return "the existing encrypted content in '" + encryptedSide + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)";
  }

  private static boolean isStoragePath(Path rel) {
    for (Path element : rel) {
      String name = element.toString();
      if (Bitferry.STORAGE.equals(name) || Bitferry.STORAGE_IMAGE.equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDecryptionError(String line) {
    if (line.contains(Bitferry.STORAGE) || line.contains(Bitferry.STORAGE_IMAGE)) {
      return false;
    }
    String l = line.toLowerCase(Locale.ROOT);
    return l.contains("undecryptable")
        || l.contains("failed to decrypt")
        || l.contains("bad pkcs")
        || l.contains("not an encrypted file")
        || l.contains("bad magic")
        || l.contains("illegal base32")
        || l.contains("illegal base32768");
  }

  private static boolean isFilenameEncryptionEnabled(List<String> options) {
    if (options != null) {
      for (int i = 0; i < options.size(); i++) {
        String opt = options.get(i);
        if ("--crypt-filename-encryption".equals(opt) && i + 1 < options.size()) {
          if ("off".equalsIgnoreCase(options.get(i + 1))) return false;
        } else if (opt.startsWith("--crypt-filename-encryption=")) {
          if ("off".equalsIgnoreCase(opt.substring("--crypt-filename-encryption=".length()))) return false;
        }
      }
    }
    return true;
  }

  private static Set<String> extractCryptPaths(List<String> outputLines) {
    List<String> jsonLines = new ArrayList<>();
    for (String line : outputLines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("20") && (trimmed.contains("NOTICE:") || trimmed.contains("ERROR:") || trimmed.contains("CRITICAL:") || trimmed.contains("WARNING:"))) {
        continue;
      }
      jsonLines.add(line);
    }
    String jsonText = String.join("\n", jsonLines).trim();
    if (jsonText.isEmpty()) {
      return Set.of();
    }
    try {
      JsonNode root = Storage.mapper().readTree(jsonText);
      Set<String> paths = new HashSet<>();
      if (root.isArray()) {
        for (JsonNode item : root) {
          if (item.has("Path")) {
            paths.add(item.get("Path").asString().replace('\\', '/'));
          }
        }
      }
      return paths;
    } catch (Exception e) {
      return Set.of();
    }
  }

  List<String> command(Rclone rclone) {
    Manager manager = rclone.manager();
    List<String> cmd = new ArrayList<>();
    cmd.add(ExecutorSupport.resolveExecutable("rclone"));

    // The backend operation arguments: the rclone subcommand and its modifiers
    cmd.addAll(rclone.operationArgs());

    // Include filters
    for (String inc : rclone.include()) {
      cmd.add("--filter");
      cmd.add("+ " + inc);
    }
    // Exclude Bitferry storage files
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE);
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE_IMAGE);
    // Exclude filters
    for (String exc : rclone.exclude()) {
      cmd.add("--filter");
      cmd.add("- " + exc);
    }

    // Common options
    cmd.add("--config");
    cmd.add(ExecutorSupport.isWindows() ? "NUL" : "/dev/null");
    if (manager.verbose()) {
      cmd.add("--verbose");
      cmd.add("--progress");
    }
    if (manager.quiet) {
      cmd.add("--quiet");
    }
    if (manager.simulate) {
      cmd.add("--dry-run");
    }

    // Process options
    cmd.addAll(rclone.processOptions());

    // The endpoints (or the crypt tail placing the ':crypt:' remote for the encrypted tasks)
    cmd.addAll(rclone.encryption().processOpts(rclone));

    return cmd;
  }

  List<List<String>> commands(Rclone rclone) {
    return List.of(command(rclone));
  }

  /// Process the rclone-backed task. Return true on success.
  boolean run(Rclone rclone, Consumer<String> output) {
    List<String> cmd = command(rclone);
    return ExecutorSupport.execute(commandExecutor, rclone.manager(), cmd, null, rclone.processEnvironment(), rclone.tag(), output);
  }
}
