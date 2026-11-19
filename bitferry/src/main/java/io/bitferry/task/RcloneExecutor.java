package io.bitferry.task;

import io.bitferry.*;
import java.io.*;
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
  /// The probe is limited to the top-level entries (the crypt remote encrypts the
  /// directory names too, so the top level suffices for the password and the filename
  /// encoding validation) keeping the large existing trees cheap to inspect.
  void conform(Rclone rclone) {
    // The encrypted side rides the encryption mode itself (null for the no encryption
    // mode: the probing is not applicable)
    String encryptedSide = rclone.encryption().encryptedSide(rclone);
    if (encryptedSide == null) {
      return;
    }

    Path encryptedDir = Path.of(encryptedSide);
    if (!Files.isDirectory(encryptedDir)) {
      return;
    }

    // The probe is depth-limited to the top level on both sides (this walk and the
    // rclone listing below): the crypt remote encrypts the directory names too, so any
    // non-storage top-level entry exercises the filename decryption, and the symmetric
    // limitation keeps the pass-through equality check below meaningful. The full-tree
    // walk is unnecessary for the validation and prohibitive on the large existing trees.
    Set<String> rawPaths = new HashSet<>();
    try (Stream<Path> stream = Files.list(encryptedDir)) {
      for (Path p : stream.toList()) {
        Path rel = encryptedDir.relativize(p);
        if (isStoragePath(rel)) continue;
        rawPaths.add(rel.toString().replace('\\', '/'));
      }
    } catch (IOException e) {
      throw new BitferryError("Failed to inspect " + encryptedSide + ": " + e.getMessage(), e);
    }

    if (rawPaths.isEmpty()) {
      return;
    }

    List<String> cmd = new ArrayList<>();
    cmd.add(ExecutorSupport.resolveExecutable("rclone"));
    cmd.add("lsjson");
    cmd.add("-R");
    // The listing is depth-limited symmetrically with the raw walk above (the top level):
    // an asymmetric limitation would make the pass-through equality check below meaningless
    cmd.add("--max-depth");
    cmd.add("1");
    cmd.add("--config");
    cmd.add(ExecutorSupport.isWindows() ? "NUL" : "/dev/null");
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE + "*");
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE + "*/**");
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
      throw new BitferryError(refusalMessage(encryptedSide) + probeDetail(outputLines, exitCode));
    }

    Set<String> cryptPaths = extractCryptPaths(outputLines);
    boolean filenameEncEnabled = isFilenameEncryptionEnabled(rclone.encryption().options());
    if (cryptPaths.isEmpty() || (filenameEncEnabled && cryptPaths.equals(rawPaths))) {
      throw new BitferryError(refusalMessage(encryptedSide) + probeDetail(outputLines, exitCode));
    }
  }

  /// The probe failure detail: the captured backend output tail appended to the error
  /// message. Any probe failure (a vanished directory, a backend crash) surfaces as the
  /// wrong-password refusal, so the actual backend report must stay visible for the
  /// misdiagnosis not to be dead-ended.
  private static String probeDetail(List<String> outputLines, int exitCode) {
    List<String> tail = outputLines.size() > 5
      ? outputLines.subList(outputLines.size() - 5, outputLines.size())
      : outputLines;
    StringBuilder detail = new StringBuilder(" [probe exit code ").append(exitCode);
    if (!tail.isEmpty()) {
      detail.append(", rclone reported: ").append(String.join(" | ", tail));
    }
    return detail.append(']').toString();
  }

  private static String refusalMessage(String encryptedSide) {
    return "the existing encrypted content in '" + encryptedSide + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)";
  }

  private static boolean isStoragePath(Path rel) {
    for (Path element : rel) {
      String name = element.toString();
      if (name.startsWith(Bitferry.STORAGE)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDecryptionError(String line) {
    if (line.contains(Bitferry.STORAGE)) {
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
      // The escapes are stripped before the notice filter: the captured runs force
      // the colors on (CLICOLOR_FORCE), so a colorized notice line begins with the
      // escape sequences and would slip past the prefix test into the JSON below
      String trimmed = AnsiFormat.strip(line).trim();
      if (trimmed.startsWith("20") && (trimmed.contains("NOTICE:") || trimmed.contains("ERROR:") || trimmed.contains("CRITICAL:") || trimmed.contains("WARNING:"))) {
        continue;
      }
      jsonLines.add(trimmed);
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

  /// Assemble the rclone command line. The passthrough flag reflects the execution
  /// mode (true: stdout inherited - the real terminal; false: captured for a consumer):
  /// the interactive progress display travels only with the passthrough runs, while
  /// the verbosity applies to both modes. The prepare flag gates the endpoint
  /// preparation side effects (the equalize bisync workdir creation, the stale
  /// session listing renames): the actual runs (and the dry-run simulations, which
  /// execute the real command shape) carry them, the command previews stay read-only
  /// - 'info task' must not touch the disk.
  List<String> command(Rclone rclone, boolean passthrough, boolean prepare) {
    Manager manager = rclone.manager();
    List<String> cmd = new ArrayList<>();
    cmd.add(ExecutorSupport.resolveExecutable("rclone"));

    // Endpoints
    List<String> endpoints = rclone.encryption().processOpts(rclone);
    if (prepare) {
      rclone.prepare(endpoints);
    }

    // The backend operation arguments: the rclone subcommand and its modifiers
    cmd.addAll(rclone.operationArgs());

    // Include filters
    for (String inc : rclone.include()) {
      cmd.add("--filter");
      cmd.add("+ " + inc);
    }
    // Exclude Bitferry storage files and per-task metadata
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE + "*");
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE + "*/**");
    // Exclude filters
    for (String exc : rclone.exclude()) {
      cmd.add("--filter");
      cmd.add("- " + exc);
    }

    // Common options
    cmd.add("--config");
    cmd.add(ExecutorSupport.isWindows() ? "NUL" : "/dev/null");
    // The coloring follows the consumer type: the captured runs (the GUI log parser
    // renders the escapes) force the colors on, the passthrough runs defer to the
    // rclone's own tty detection (colors on the real terminal, plain bytes into the
    // redirected files)
    if (!passthrough) {
      cmd.add("--color");
      cmd.add("ALWAYS");
    }
    // The verbosity applies to both execution modes; the interactive progress display
    // is requested only for the passthrough runs (stdout inherited, the real terminal):
    // over the captured pipes the rclone's own progress degrades to the periodic
    // single-line stats and would only add noise to the consumer
    if (manager.verbose()) {
      cmd.add("--verbose");
      if (passthrough) {
        cmd.add("--progress");
      }
    }
    if (manager.quiet) {
      cmd.add("--quiet");
    }
    if (manager.simulate) {
      cmd.add("--dry-run");
    }
    if (manager.force && !cmd.contains("--force")) {
      cmd.add("--force");
    }

    // Process options
    cmd.addAll(rclone.processOptions());

    // The endpoints (or the crypt tail placing the ':crypt:' remote for the encrypted tasks)
    cmd.addAll(endpoints);

    return cmd;
  }

  List<List<String>> commands(Rclone rclone) {
    // The preview renders the passthrough form: it is displayed by the CLI surfaces
    // only (info --commands, the task breakdown) mirroring what the CLI executes;
    // the GUI executes captured (no --progress under the verbose mode) and never
    // shows the command lines
    return List.of(ExecutorSupport.displayCommand(command(rclone, true, false)));
  }

  /// Process the rclone-backed task. Return true on success.
  boolean run(Rclone rclone, Consumer<String> output) {
    List<String> cmd = command(rclone, output == null, true);
    return ExecutorSupport.execute(commandExecutor, rclone.manager(), cmd, null, rclone.processEnvironment(), rclone.tag(), output);
  }

  List<String> serveCommand(Rclone rclone, boolean passthrough) {
    Manager manager = rclone.manager();
    List<String> cmd = new ArrayList<>();
    cmd.add(ExecutorSupport.resolveExecutable("rclone"));

    // Subcommand: check
    cmd.add("check");

    // Include filters
    for (String inc : rclone.include()) {
      cmd.add("--filter");
      cmd.add("+ " + inc);
    }
    // Exclude Bitferry storage files and per-task metadata
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE + "*");
    cmd.add("--filter");
    cmd.add("- " + Bitferry.STORAGE + "*/**");
    // Exclude filters
    for (String exc : rclone.exclude()) {
      cmd.add("--filter");
      cmd.add("- " + exc);
    }

    // Common options
    cmd.add("--config");
    cmd.add(ExecutorSupport.isWindows() ? "NUL" : "/dev/null");
    // The coloring follows the consumer type (mirroring the run commands): forced
    // for the captured runs, the rclone's own tty detection for the passthrough
    if (!passthrough) {
      cmd.add("--color");
      cmd.add("ALWAYS");
    }
    if (manager.verbose()) {
      cmd.add("--verbose");
    }
    if (manager.quiet) {
      cmd.add("--quiet");
    }

    // Process options
    cmd.addAll(rclone.serveOptions());

    // The endpoints (or the crypt tail placing the ':crypt:' remote for the encrypted tasks)
    cmd.addAll(rclone.encryption().processOpts(rclone));

    return cmd;
  }

  List<List<String>> serveCommands(Rclone rclone) {
    // The preview mirrors the passthrough execution form
    return List.of(ExecutorSupport.displayCommand(serveCommand(rclone, true)));
  }

  /// Perform service (integrity checking) on the rclone-backed task. Return true on success.
  boolean serve(Rclone rclone, Consumer<String> output) {
    List<String> cmd = serveCommand(rclone, output == null);
    return ExecutorSupport.execute(commandExecutor, rclone.manager(), cmd, null, rclone.processEnvironment(), rclone.tag(), output);
  }
}
