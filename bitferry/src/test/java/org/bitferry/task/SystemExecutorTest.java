package org.bitferry.task;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.vault.Passwords;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class SystemExecutorTest {

  @TempDir
  Path dir;

  Manager manager;
  Volume volume;

  @BeforeEach
  void setUp() {
    manager = new Manager();
    volume = Volume.create(manager, dir.resolve("volume"));
  }

  private Endpoint source() {
    return Endpoints.parse(manager, "local:" + dir.resolve("data"));
  }

  private Endpoint destination() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":backup");
  }

  // The volume-root endpoint serving as the volume-bound source for the backup tasks
  private Endpoint volumeSource() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":");
  }

  // The volume-bound restored path endpoint serving as the volume-bound destination for the restore tasks
  private Endpoint volumeRestored() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":restored");
  }

  // The portable backend availability probe resolving the executable the same way
  // the system backend executor does (through the RCLONE/RESTIC environment variables)
  private static boolean available(String executable) {
    String env = System.getenv(executable.toUpperCase());
    String command = env != null && !env.isBlank() ? env : executable;
    try {
      Process process = new ProcessBuilder(command, "version").start();
      return process.waitFor() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Test
  void bundledBackendsResolveBeforeThePathLookup() throws IOException {
    Function<String, String> noEnv = name -> null;

    // The jlink image layout: the executables next to the launcher in bin/
    Path image = dir.resolve("image");
    Files.createDirectories(image.resolve("bin"));
    Files.writeString(image.resolve("bin").resolve(SystemExecutor.executableName("rclone")), "stub");
    assertEquals(image.resolve("bin").resolve(SystemExecutor.executableName("rclone")).toString(),
      SystemExecutor.resolveExecutable("rclone", noEnv, image));

    // The jpackage image layout (Windows): the executables in the image root
    Path runtime = image.resolve("runtime");
    Files.createDirectories(runtime);
    Files.writeString(image.resolve(SystemExecutor.executableName("restic")), "stub");
    assertEquals(image.resolve(SystemExecutor.executableName("restic")).toString(),
      SystemExecutor.resolveExecutable("restic", noEnv, runtime));

    // The environment override wins over the bundled executables
    assertEquals("explicit", SystemExecutor.resolveExecutable("rclone", name -> "explicit", image));

    // Nothing bundled anywhere falls back to the bare name for the PATH lookup
    assertEquals("restic", SystemExecutor.resolveExecutable("restic", noEnv, dir.resolve("nowhere")));
  }

  @Test
  void launcherPropertyBeatsEnvironmentAndBundledBackends() throws IOException {
    Function<String, String> noEnv = name -> null;
    Function<String, String> envOverride = name -> "explicit";

    // The launcher-provided bitferry.rclone property wins over the bundled candidates
    // and over the environment override
    System.setProperty("bitferry.rclone", dir.resolve("launched").toString());
    try {
      assertEquals(dir.resolve("launched").toString(),
        SystemExecutor.resolveExecutable("rclone", noEnv, image()));
      assertEquals(dir.resolve("launched").toString(),
        SystemExecutor.resolveExecutable("rclone", envOverride, image()));
    } finally {
      System.clearProperty("bitferry.rclone");
    }

    // A blank property defers to the environment override
    System.setProperty("bitferry.rclone", "  ");
    try {
      assertEquals("explicit", SystemExecutor.resolveExecutable("rclone", envOverride, image()));
    } finally {
      System.clearProperty("bitferry.rclone");
    }

    // Restic keeps its own property namespace
    System.setProperty("bitferry.restic", dir.resolve("launched").toString());
    try {
      assertEquals(dir.resolve("launched").toString(),
        SystemExecutor.resolveExecutable("restic", noEnv, image()));
    } finally {
      System.clearProperty("bitferry.restic");
    }
  }

  @Test
  void bundledToolPathReplacedWithGenericNameInDisplayAndCommands() throws IOException {
    Function<String, String> noEnv = name -> null;
    Function<String, String> envOverride = name -> "/custom/" + name.toLowerCase();

    // The bundled launcher property replaces the long path with the generic backend name
    String appImageRclone = "/tmp/.mount_bitferhOfdml/usr/app/lib/app/../../bin/rclone";
    System.setProperty("bitferry.rclone", appImageRclone);
    try {
      assertTrue(SystemExecutor.isBundled(appImageRclone, "rclone"));
      assertEquals("rclone", SystemExecutor.displayExecutable(appImageRclone));
      assertEquals("rclone sync /a /b", SystemExecutor.displayCommandLine(List.of(appImageRclone, "sync", "/a", "/b")));
    } finally {
      System.clearProperty("bitferry.rclone");
    }

    String appImageRestic = "/tmp/.mount_bitferhOfdml/usr/app/bin/restic";
    System.setProperty("bitferry.restic", appImageRestic);
    try {
      assertTrue(SystemExecutor.isBundled(appImageRestic, "restic"));
      assertEquals("restic", SystemExecutor.displayExecutable(appImageRestic));
      assertEquals("restic backup .", SystemExecutor.displayCommandLine(List.of(appImageRestic, "backup", ".")));
    } finally {
      System.clearProperty("bitferry.restic");
    }

    // Bundled directory layout detection
    Path img = image();
    Path bundledRclone = img.resolve("bin").resolve(SystemExecutor.executableName("rclone"));
    assertTrue(SystemExecutor.isBundled(bundledRclone.toString(), "rclone", noEnv, img));
    assertEquals("rclone", SystemExecutor.displayExecutable(bundledRclone.toString(), noEnv, img));

    // Explicit environment override keeps the user-specified executable verbatim
    assertFalse(SystemExecutor.isBundled("/custom/rclone", "rclone", envOverride, img));
    assertEquals("/custom/rclone", SystemExecutor.displayExecutable("/custom/rclone", envOverride, img));

    // Arbitrary external executable is not bundled
    assertFalse(SystemExecutor.isBundled("/usr/local/bin/custom-tool", "rclone", noEnv, img));
    assertEquals("/usr/local/bin/custom-tool", SystemExecutor.displayExecutable("/usr/local/bin/custom-tool", noEnv, img));
  }

  @Test
  void verboseEchoAndCommandsUseGenericToolNameForBundledBinaries() {
    String bundledRclone = "/tmp/.mount_bitferhOfdml/usr/app/lib/app/../../bin/rclone";
    String bundledRestic = "/tmp/.mount_bitferhOfdml/usr/app/bin/restic";
    System.setProperty("bitferry.rclone", bundledRclone);
    System.setProperty("bitferry.restic", bundledRestic);

    try {
      RecordingCommandExecutor recorder = new RecordingCommandExecutor();
      SystemExecutor executor = new SystemExecutor(recorder);
      manager.verbose(true);

      // Rclone task: commands() uses generic "rclone"
      Replicate sync = new Replicate(manager, source(), destination(), null, List.of(), List.of());
      List<List<String>> cmds = sync.commands();
      assertEquals(1, cmds.size());
      assertEquals("rclone", cmds.get(0).get(0));

      // Execution echo prints generic "rclone", but actual execution receives the full bundled path
      List<String> output = new ArrayList<>();
      Task.Outcome success = executor.run(sync, output::add);
      assertEquals(Task.Outcome.SUCCESS, success);
      assertEquals(1, recorder.invocations.size());
      assertEquals(bundledRclone, recorder.invocations.get(0).command().get(0));
      // The verbose echo is color-highlighted (the captured logs render the escapes)
      assertTrue(output.stream().anyMatch(line -> line.startsWith(AnsiFormat.CYAN + "rclone sync ")),
        "Expected output to echo 'rclone sync ...' but got: " + output);
      assertFalse(output.stream().anyMatch(line -> line.contains(".mount_bitfer")),
        "Bundled path should not appear in verbose echo output");

      // Restic backup task: commands() uses generic "restic"
      recorder.invocations.clear();
      output.clear();
      Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
      List<List<String>> backupCmds = backup.commands();
      assertFalse(backupCmds.isEmpty());
      assertEquals("restic", backupCmds.get(0).get(0));

      success = executor.run(backup, output::add);
      assertEquals(Task.Outcome.SUCCESS, success);
      assertFalse(recorder.invocations.isEmpty());
      assertEquals(bundledRestic, recorder.invocations.get(0).command().get(0));
      assertTrue(output.stream().anyMatch(line -> line.startsWith(AnsiFormat.CYAN + "restic backup ")),
        "Expected output to echo 'restic backup ...' but got: " + output);
      assertFalse(output.stream().anyMatch(line -> line.contains(".mount_bitfer")),
        "Bundled path should not appear in verbose echo output");
    } finally {
      System.clearProperty("bitferry.rclone");
      System.clearProperty("bitferry.restic");
    }
  }

  // The image layout stub shared by the resolution tests
  private Path image() throws IOException {
    Path image = dir.resolve("image");
    Files.createDirectories(image.resolve("bin"));
    return image;
  }

  // Recording command executor capturing invocations for assertion
  static class RecordingCommandExecutor implements CommandExecutor {
    record Invocation(List<String> command, Path workingDir, Map<String, String> environment, Consumer<String> output) {}
    final List<Invocation> invocations = new ArrayList<>();
    int returnCode = 0;
    List<String> simulatedOutput = List.of();

    @Override
    public int execute(List<String> command, Path workingDirectory, Map<String, String> environment, Consumer<String> output) throws IOException {
      invocations.add(new Invocation(command, workingDirectory, environment, output));
      if (output != null) {
        for (String line : simulatedOutput) {
          output.accept(line);
        }
      }
      return returnCode;
    }
  }

  @Test
  void rcloneSyncBuildsExpectedCommandAndFilters() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Replicate task = new Replicate(manager, source(), destination(), null, List.of("*.txt"), List.of("*.bak"));
    Task.Outcome result = executor.run(task, null);

    assertEquals(Task.Outcome.SUCCESS, result);
    assertEquals(1, recorder.invocations.size());
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);

    assertNull(inv.workingDir());
    assertNull(inv.output()); // CLI pass-through mode
    List<String> cmd = inv.command();

    assertEquals("rclone", cmd.get(0));
    assertEquals("sync", cmd.get(1));

    // Filters
    assertTrue(cmd.contains("+ *.txt"));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE + "*"));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE + "*/**"));
    assertTrue(cmd.contains("- *.bak"));

    // Default metadata & config
    assertTrue(cmd.contains("--config"));
    assertTrue(cmd.contains("--metadata"));

    // Endpoints
    assertEquals(source().processOpts(), cmd.get(cmd.size() - 2));
    assertEquals(destination().processOpts(), cmd.get(cmd.size() - 1));
  }

  @Test
  void rcloneOperationsSelectCorrectSubcommands() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Copy copy = new Copy(manager, source(), destination(), null, List.of(), List.of());
    executor.run(copy, null);
    assertEquals("copy", recorder.invocations.get(0).command().get(1));

    Update update = new Update(manager, source(), destination(), null, List.of(), List.of());
    executor.run(update, null);
    assertEquals("copy", recorder.invocations.get(1).command().get(1));
    assertEquals("--update", recorder.invocations.get(1).command().get(2));

    Equalize equalize = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    executor.run(equalize, null);
    assertEquals("bisync", recorder.invocations.get(2).command().get(1));
    assertEquals("--resync", recorder.invocations.get(2).command().get(2));
    assertTrue(recorder.invocations.get(2).command().contains("--workdir"));
    assertTrue(recorder.invocations.get(2).command().contains(equalize.workdir().toString()));
  }

  @Test
  void simulatedEqualizeUsesDisposableWorkdirForFreshBaseline() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);
    manager.simulate = true;
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    Path real = task.workdir();

    assertEquals(Task.Outcome.SUCCESS, executor.run(task, null));

    List<String> command = recorder.invocations.get(0).command();
    Path temporary = Path.of(command.get(command.indexOf("--workdir") + 1));
    assertNotEquals(real, temporary);
    assertTrue(command.contains("--dry-run"));
    assertTrue(command.contains("--resync"));
    assertFalse(Files.exists(real), "A fresh simulation must not create the persistent workdir");
    assertFalse(Files.exists(temporary), "The temporary workdir must be removed after a successful run");
    assertEquals(real, task.workdir(), "The task must address the persistent workdir again after the run");
  }

  @Test
  void simulatedEqualizeCopiesBaselineWithoutChangingPersistentState() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);
    manager.simulate = true;
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    Path real = task.workdir();
    Files.createDirectories(real);
    Path path1 = real.resolve("old-session.path1.lst");
    Path path2 = real.resolve("old-session.path2.lst");
    Files.writeString(path1, "original path1 baseline");
    Files.writeString(path2, "original path2 baseline");

    assertEquals(Task.Outcome.SUCCESS, executor.run(task, null));

    List<String> command = recorder.invocations.get(0).command();
    Path temporary = Path.of(command.get(command.indexOf("--workdir") + 1));
    assertNotEquals(real, temporary);
    assertTrue(command.contains("--dry-run"));
    assertFalse(command.contains("--resync"), "The copied baseline must select plain bisync mode");
    assertEquals("original path1 baseline", Files.readString(path1));
    assertEquals("original path2 baseline", Files.readString(path2));
    try (var files = Files.list(real)) {
      assertEquals(Set.of(path1.getFileName().toString(), path2.getFileName().toString()),
        files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
        "Session-name preparation must rename only the copied listings");
    }
    assertFalse(Files.exists(temporary), "The copied baseline must be removed after the run");
    assertEquals(real, task.workdir());
  }

  @Test
  void simulatedEqualizeCleansUpTemporaryWorkdirAfterFailure() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1;
    SystemExecutor executor = new SystemExecutor(recorder);
    manager.simulate = true;
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    Path real = task.workdir();

    assertEquals(Task.Outcome.FAILURE, executor.run(task, null));

    List<String> command = recorder.invocations.get(0).command();
    Path temporary = Path.of(command.get(command.indexOf("--workdir") + 1));
    assertNotEquals(real, temporary);
    assertFalse(Files.exists(real));
    assertFalse(Files.exists(temporary), "The temporary workdir must be removed after a failed run");
    assertEquals(real, task.workdir());
  }

  @Test
  void simulatedEqualizeTemporaryWorkdirIsThreadIsolated() throws Exception {
    manager.simulate = true;
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    Path real = task.workdir();
    Path[] observedInOtherThread = new Path[1];
    SystemExecutor executor = new SystemExecutor((command, workingDirectory, environment, output) -> {
      Thread probe = new Thread(() -> observedInOtherThread[0] = task.workdir());
      probe.start();
      probe.join();
      return 0;
    });

    assertEquals(Task.Outcome.SUCCESS, executor.run(task, null));
    assertEquals(real, observedInOtherThread[0], "Concurrent readers must see the persistent workdir during simulation");
    assertEquals(real, task.workdir());
  }

  @Test
  void rcloneManagerFlagsAreReflectedInArguments() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    manager.verbose(true);
    manager.simulate = true;

    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    executor.run(task, null);

    List<String> cmd = recorder.invocations.get(0).command();
    assertTrue(cmd.contains("--verbose"));
    assertTrue(cmd.contains("--progress"));
    assertTrue(cmd.contains("--dry-run"));
  }

  @Test
  void rcloneVerboseCapturedModeOmitsProgress() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    manager.verbose(true);

    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    executor.run(task, line -> {});

    List<String> cmd = recorder.invocations.get(0).command();
    assertTrue(cmd.contains("--verbose"));
    assertFalse(cmd.contains("--progress"));
  }

  @Test
  void rcloneColorFlagsFollowTheConsumerType() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());

    // The passthrough runs (the CLI terminal): the backend's own tty detection
    // rules — no forced color flag
    executor.run(task, null);
    assertFalse(recorder.invocations.get(0).command().contains("--color"));

    // The captured runs (the GUI log parser renders the escapes): the colors are forced
    executor.run(task, line -> {});
    assertTrue(recorder.invocations.get(1).command().contains("--color"));
    assertTrue(recorder.invocations.get(1).command().contains("ALWAYS"));
  }

  @Test
  void serveColorFlagsFollowTheConsumerType() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Copy task = new Copy(manager, source(), destination(), null, List.of(), List.of());

    executor.serve(task, null);
    assertFalse(recorder.invocations.get(0).command().contains("--color"));

    executor.serve(task, line -> {});
    assertTrue(recorder.invocations.get(1).command().contains("--color"));
    assertTrue(recorder.invocations.get(1).command().contains("ALWAYS"));
  }

  public static final class EnvProbe {
    public static void main(String[] args) throws IOException {
      String report = "CLICOLOR_FORCE=" + Objects.toString(System.getenv("CLICOLOR_FORCE"), "unset")
        + " TERM=" + Objects.toString(System.getenv("TERM"), "unset");
      if (args.length > 0) {
        Files.writeString(Path.of(args[0]), report);
      } else {
        System.out.println(report);
      }
    }
  }

  private static List<String> envProbeCommand(String... args) {
    String java = Path.of(System.getProperty("java.home"), "bin", ExecutorSupport.isWindows() ? "java.exe" : "java").toString();
    List<String> cmd = new ArrayList<>();
    cmd.add(java);
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(EnvProbe.class.getName());
    cmd.addAll(Arrays.asList(args));
    return cmd;
  }

  @Test
  void capturedModeForcesColorsPassthroughLeavesEnvironmentUntouched() throws Exception {
    Path envFile = Files.createTempFile("bitferry-env", ".txt");
    String expectedPassthrough = "CLICOLOR_FORCE=" + Objects.toString(System.getenv("CLICOLOR_FORCE"), "unset")
      + " TERM=" + Objects.toString(System.getenv("TERM"), "unset");
    try {
      // The passthrough inherits the real environment: nothing is forced
      int code = SystemCommandExecutor.INSTANCE.execute(
        envProbeCommand(envFile.toAbsolutePath().toString()),
        null, null, null);
      assertEquals(0, code);
      assertEquals(expectedPassthrough, Files.readString(envFile).trim());

      // The captured mode forces the emulated capable terminal for the rendering consumer
      List<String> lines = new ArrayList<>();
      code = SystemCommandExecutor.INSTANCE.execute(
        envProbeCommand(),
        null, null, lines::add);
      assertEquals(0, code);
      assertEquals("CLICOLOR_FORCE=1 TERM=xterm-256color", lines.getFirst());
    } finally {
      Files.deleteIfExists(envFile);
    }
  }

  @Test
  void resticBackupSetsWorkingDirectoryAndRunsCheckAndForget() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, "@default", "@default", List.of("*.tmp"));
    manager.registerTask(backup);
    Task.Outcome result = executor.run(backup, null);

    assertEquals(Task.Outcome.SUCCESS, result);
    // 4 invocations: service check, forget, prune before backup, then backup
    assertEquals(4, recorder.invocations.size());

    // 1. Service Check invocation
    RecordingCommandExecutor.Invocation invCheck = recorder.invocations.get(0);
    assertEquals("secret", invCheck.environment().get("RESTIC_PASSWORD"));
    List<String> cmdCheck = invCheck.command();
    assertEquals("restic", cmdCheck.get(0));
    assertEquals("check", cmdCheck.get(1));
    assertFalse(cmdCheck.contains("--no-cache"));

    // 2. Service Forget invocation
    RecordingCommandExecutor.Invocation invForget = recorder.invocations.get(1);
    assertEquals("secret", invForget.environment().get("RESTIC_PASSWORD"));
    List<String> cmdForget = invForget.command();
    assertEquals("restic", cmdForget.get(0));
    assertEquals("forget", cmdForget.get(1));
    assertFalse(cmdForget.contains("--prune"));

    // 3. Service Prune invocation
    RecordingCommandExecutor.Invocation invPrune = recorder.invocations.get(2);
    assertEquals("secret", invPrune.environment().get("RESTIC_PASSWORD"));
    List<String> cmdPrune = invPrune.command();
    assertEquals("restic", cmdPrune.get(0));
    assertEquals("prune", cmdPrune.get(1));

    // 4. Backup invocation
    RecordingCommandExecutor.Invocation invBackup = recorder.invocations.get(3);
    assertEquals(Path.of(volumeSource().processOpts()), invBackup.workingDir());
    // The repository password travels through the environment
    assertEquals("secret", invBackup.environment().get("RESTIC_PASSWORD"));
    List<String> cmdBackup = invBackup.command();
    assertEquals("restic", cmdBackup.get(0));
    assertEquals("backup", cmdBackup.get(1));
    assertEquals(".", cmdBackup.get(2));
    assertTrue(cmdBackup.contains("--tag"));
    assertTrue(cmdBackup.contains("bitferry," + backup.tag()));
    assertTrue(cmdBackup.contains("--exclude"));
    assertTrue(cmdBackup.contains(Bitferry.STORAGE + "*"));
    assertTrue(cmdBackup.contains(Bitferry.STORAGE + "*/**"));
    assertTrue(cmdBackup.contains("*.tmp"));
    assertTrue(cmdBackup.contains("-r"));
    assertEquals(destination().processOpts(), cmdBackup.get(cmdBackup.size() - 1));
  }

  @Test
  void resticRestoreBuildsTargetAndIncludeFilters() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Restore restore = new Restore(manager, destination(), volumeRestored(), "secret", null, List.of("docs/**"), List.of("*.tmp"));
    Task.Outcome result = executor.run(restore, null);

    assertEquals(Task.Outcome.SUCCESS, result);
    assertEquals(1, recorder.invocations.size());
    assertEquals("secret", recorder.invocations.get(0).environment().get("RESTIC_PASSWORD"));
    List<String> cmd = recorder.invocations.get(0).command();

    assertEquals("restic", cmd.get(0));
    assertEquals("restore", cmd.get(1));
    assertEquals("latest", cmd.get(2));
    assertTrue(cmd.contains("--target"));
    assertEquals(volumeRestored().processOpts(), cmd.get(cmd.indexOf("--target") + 1));
    assertTrue(cmd.contains("--include"));
    assertTrue(cmd.contains("docs/**"));
    assertTrue(cmd.contains("--exclude"));
    assertTrue(cmd.contains(Bitferry.STORAGE + "*"));
    assertTrue(cmd.contains(Bitferry.STORAGE + "*/**"));
    assertTrue(cmd.contains("*.tmp"));
  }

  @Test
  void taskCommandsReturnExpectedCommandLines() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);
    manager.backendExecutor(executor);

    Replicate sync = new Replicate(manager, source(), destination(), null, List.of("*.txt"), List.of("*.bak"));
    List<List<String>> syncCmds = sync.commands();
    assertEquals(1, syncCmds.size());
    List<String> cmd = syncCmds.get(0);
    assertEquals("rclone", cmd.get(0));
    assertEquals("sync", cmd.get(1));
    assertTrue(cmd.contains("+ *.txt"));
    assertTrue(cmd.contains("- *.bak"));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE + "*"));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE + "*/**"));

    Restore restore = new Restore(manager, destination(), volumeRestored(), "secret", null, List.of("docs/**"), List.of("*.tmp"));
    List<List<String>> restoreCmds = restore.commands();
    assertEquals(1, restoreCmds.size());
    List<String> rcmd = restoreCmds.get(0);
    assertEquals("restic", rcmd.get(0));
    assertEquals("restore", rcmd.get(1));
    assertEquals("latest", rcmd.get(2));
    assertTrue(rcmd.contains("--target"));
    assertEquals(volumeRestored().processOpts(), rcmd.get(rcmd.indexOf("--target") + 1));
    assertTrue(rcmd.contains(Bitferry.STORAGE + "*"));
    assertTrue(rcmd.contains(Bitferry.STORAGE + "*/**"));

    // Backup commands return routine backup command
    Backup backupDefault = new Backup(manager, volumeSource(), destination(), "secret", null, null, "@default", "@default", List.of("*.tmp"));
    List<List<String>> backupCmds = backupDefault.commands();
    assertEquals(1, backupCmds.size());
    assertEquals("backup", backupCmds.get(0).get(1));
    assertTrue(backupCmds.get(0).contains(Bitferry.STORAGE + "*"));
    assertTrue(backupCmds.get(0).contains(Bitferry.STORAGE + "*/**"));

    // Backup serveCommands return check, forget, prune
    List<List<String>> serveCmds = backupDefault.serveCommands();
    assertEquals(3, serveCmds.size());
    assertEquals("check", serveCmds.get(0).get(1));
    assertEquals("forget", serveCmds.get(1).get(1));
    assertEquals("prune", serveCmds.get(2).get(1));

    // Backup skipping check and forget
    Backup backupSkip = new Backup(manager, volumeSource(), destination(), "secret", null, null, "-", "-", List.of());
    List<List<String>> backupSkipCmds = backupSkip.commands();
    assertEquals(1, backupSkipCmds.size());
    assertEquals("backup", backupSkipCmds.get(0).get(1));

    // Equalize serveCommands strips bisync-only flags from rclone check
    Equalize equalize = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    List<List<String>> eqServeCmds = equalize.serveCommands();
    assertEquals(1, eqServeCmds.size());
    List<String> eqCheck = eqServeCmds.get(0);
    assertEquals("rclone", eqCheck.get(0));
    assertEquals("check", eqCheck.get(1));
    assertFalse(eqCheck.contains("--recover"));
    assertFalse(eqCheck.contains("--resilient"));
    assertFalse(eqCheck.contains("--force"));
    assertTrue(eqCheck.contains("--metadata"));
  }

  @Test
  void equalizeServeCommandFiltersOutBisyncSpecificFlags() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);
    manager.backendExecutor(executor);

    Equalize equalize = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    assertTrue(equalize.processOptions().contains("--recover"));
    assertTrue(equalize.processOptions().contains("--resilient"));
    assertTrue(equalize.processOptions().contains("--force"));

    List<String> serveOpts = equalize.serveOptions();
    assertFalse(serveOpts.contains("--recover"));
    assertFalse(serveOpts.contains("--resilient"));
    assertFalse(serveOpts.contains("--force"));
    assertTrue(serveOpts.contains("--metadata"));

    Task.Outcome ok = executor.serve(equalize, null);
    assertEquals(Task.Outcome.SUCCESS, ok);
    assertEquals(1, recorder.invocations.size());
    List<String> cmd = recorder.invocations.get(0).command();
    assertEquals("rclone", cmd.get(0));
    assertEquals("check", cmd.get(1));
    assertFalse(cmd.contains("--recover"));
    assertFalse(cmd.contains("--resilient"));
    assertFalse(cmd.contains("--force"));
    assertTrue(cmd.contains("--metadata"));
  }

  @Test
  void equalizeServeExecutesSuccessfullyWithRealRclone() throws IOException {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for integration check");
    Path src = dir.resolve("eq-src");
    Path dst = dir.resolve("eq-dst");
    Files.createDirectories(src);
    Files.createDirectories(dst);
    Files.writeString(src.resolve("file.txt"), "content");
    Files.writeString(dst.resolve("file.txt"), "content");

    Equalize equalize = new Equalize(manager, Endpoints.parse(manager, "local:" + src), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    SystemExecutor executor = new SystemExecutor(SystemCommandExecutor.INSTANCE);
    manager.backendExecutor(executor);
    List<String> output = new ArrayList<>();
    Task.Outcome ok = executor.serve(equalize, output::add);
    assertEquals(Task.Outcome.SUCCESS, ok, "Expected rclone check to succeed, but got: " + output);
  }

  @Test
  void outputCapturingForwardsLinesToConsumer() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.simulatedOutput = List.of("Transferred: 10/10", "100%", "Finished in 0.5s");
    SystemExecutor executor = new SystemExecutor(recorder);

    List<String> captured = new ArrayList<>();
    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    Task.Outcome result = executor.run(task, captured::add);

    assertEquals(Task.Outcome.SUCCESS, result);
    assertTrue(captured.contains("Transferred: 10/10"));
    assertTrue(captured.contains("100%"));
    assertTrue(captured.contains("Finished in 0.5s"));
  }

  @Test
  void nonZeroExitCodeReportsErrorAndReturnsFalse() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 2; // Failure exit code
    SystemExecutor executor = new SystemExecutor(recorder);

    List<String> captured = new ArrayList<>();
    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    Task.Outcome result = executor.run(task, captured::add);

    assertEquals(Task.Outcome.FAILURE, result);
    assertTrue(captured.stream().anyMatch(msg -> msg.contains("Command failed with exit code 2")));
  }

  @Test
  void systemCommandExecutorRunsEchoWithCapturedOutput() throws Exception {
    CommandExecutor executor = CommandExecutor.system();
    List<String> lines = new ArrayList<>();

    // Run portable java command
    int exitCode = executor.execute(
      List.of(ProcessHandle.current().info().command().orElse("java"), "-version"),
      null,
      Map.of(),
      lines::add
    );

    assertEquals(0, exitCode);
    assertFalse(lines.isEmpty());
  }

  @Test
  void backupFormatInitializesRepository() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
    executor.format(backup);

    assertEquals(1, recorder.invocations.size());
    assertEquals("secret", recorder.invocations.get(0).environment().get("RESTIC_PASSWORD"));
    List<String> cmd = recorder.invocations.get(0).command();
    assertEquals("restic", cmd.get(0));
    assertEquals("init", cmd.get(1));
    assertTrue(cmd.contains("-r"));
    assertEquals(destination().processOpts(), cmd.get(cmd.indexOf("-r") + 1));
  }

  @Test
  void backupCreationThroughCommitInitializesRepository() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    manager.backendExecutor(new SystemExecutor(recorder));

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
    manager.commit(backup);

    List<List<String>> initInvocations = recorder.invocations.stream()
      .map(RecordingCommandExecutor.Invocation::command)
      .filter(cmd -> cmd.size() > 1 && "init".equals(cmd.get(1)))
      .toList();
    assertEquals(1, initInvocations.size(), "restic init must run exactly once upon the backup task creation");
    assertTrue(manager.task(backup.tag()) != null, "The successfully initialized task must stay registered");
  }

  @Test
  void backupCreationWithLocalDestinationInitializesRepository() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    manager.backendExecutor(new SystemExecutor(recorder));

    Path repoDir = dir.resolve("outside-repo");
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, null, null, List.of());
    manager.commit(backup);

    List<List<String>> initInvocations = recorder.invocations.stream()
      .map(RecordingCommandExecutor.Invocation::command)
      .filter(cmd -> cmd.size() > 1 && "init".equals(cmd.get(1)))
      .toList();
    assertEquals(1, initInvocations.size(), "restic init must run for the out-of-volume local destination");
    assertTrue(manager.task(backup.tag()) != null);
  }

  @Test
  void simulatedCreationFollowedByRealCommitInitializesRepository() {
    // Create the task in the simulation mode: the format stays pending (the pristine
    // state is not consumed); the following real commit must initialize the repository
    manager.simulate = true;
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    manager.backendExecutor(new SystemExecutor(recorder));

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
    manager.commit(backup);
    assertTrue(recorder.invocations.isEmpty(), "Simulation must not spawn any commands");

    manager.simulate = false;
    manager.commit();
    List<List<String>> initInvocations = recorder.invocations.stream()
      .map(RecordingCommandExecutor.Invocation::command)
      .filter(cmd -> cmd.size() > 1 && "init".equals(cmd.get(1)))
      .toList();
    assertEquals(1, initInvocations.size(), "The following real commit must initialize the pending repository");
  }

  @Test
  void simulatedCreationMaterializesUponTheFirstRealRun() {
    // The GUI shape of the pending creation: the task is born under the simulation
    // mode (nothing written anywhere), then executed for real - the pending one-time
    // initialization must materialize (the restic init) and the task must persist
    manager.simulate = true;
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    manager.backendExecutor(new SystemExecutor(recorder));

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
    manager.commit(backup);
    assertTrue(recorder.invocations.isEmpty(), "Simulation must not spawn any commands");

    manager.simulate = false;
    assertTrue(manager.process(backup.tag()), "The first real run must succeed");

    List<List<String>> initInvocations = recorder.invocations.stream()
      .map(RecordingCommandExecutor.Invocation::command)
      .filter(cmd -> cmd.size() > 1 && "init".equals(cmd.get(1)))
      .toList();
    assertEquals(1, initInvocations.size(), "The pending initialization must run before the backup itself");

    // The materialization must persist the task into the volume storage
    Path storage = dir.resolve("volume").resolve(Bitferry.STORAGE);
    assertTrue(Files.isRegularFile(storage), "The volume storage must be written by the materialization");
    String dump = null;
    try {
      dump = Files.readString(storage);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    assertTrue(dump.contains(backup.tag()), "The materialized task must be present in the volume storage");
  }

  @Test
  void simulatedRunOfPendingTaskKeepsTheDeferral() throws IOException {
    // The simulation stays a pure dry run even for the pending tasks: no commands,
    // no persistence
    Files.deleteIfExists(dir.resolve("volume").resolve(Bitferry.STORAGE));
    manager.simulate = true;
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    manager.backendExecutor(new SystemExecutor(recorder));

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
    manager.commit(backup);
    assertTrue(manager.process(backup.tag()), "The simulated run must succeed");
    // The simulated run exercises the backend in the dry-run form (the check, the
    // retention, the backup itself) - but never the pending repository initialization
    List<List<String>> initInvocations = recorder.invocations.stream()
      .map(RecordingCommandExecutor.Invocation::command)
      .filter(cmd -> cmd.size() > 1 && "init".equals(cmd.get(1)))
      .toList();
    assertTrue(initInvocations.isEmpty(), "The simulated run must not initialize the pending repository");
    assertFalse(Files.isRegularFile(dir.resolve("volume").resolve(Bitferry.STORAGE)), "The simulated run must not persist anything");
  }

  // The two backup tasks sharing one repository: the same destination endpoint, the
  // distinct sources. Task a holds the default retention policy, task b optionally
  // overrides it via the arguments.
  private Backup sharedRepoTask(String sourcePath, String forget, String serve) {
    Endpoint source = sourcePath == null ? volumeSource() : Endpoints.parse(manager, ":" + volume.tag() + ":" + sourcePath);
    Backup task = new Backup(manager, source, destination(), "secret", null, null, forget, null, serve, List.of());
    manager.registerTask(task);
    return task;
  }

  // Seed the per-task repository state file: fresh for task a, due for task b when
  // requested
  private void seedRepoState(Backup a, Backup b, boolean bDue) throws IOException {
    Path repoDir = Path.of(destination().processOpts());
    Files.createDirectories(repoDir);
    String stateJson = """
      {"tasks": {
        "%s": {"maintenance": "%s", "backups_since_maintenance": 0},
        "%s": %s
      }}""".formatted(a.tag(), Instant.now(), b.tag(), bDue ? "{}" : "{\"maintenance\": \"" + Instant.now() + "\", \"backups_since_maintenance\": 0}");
    Files.writeString(repoDir.resolve(ResticRepositoryState.FILENAME), stateJson);
  }

  @Test
  void sharedRepoServiceAppliesEveryRetentionPolicy() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup a = sharedRepoTask(null, "@default", "@default");
    Backup b = sharedRepoTask("other", "@default", "@default");
    seedRepoState(a, b, true); // b is due: the service must fire and cover both tasks

    assertEquals(Task.Outcome.SUCCESS, executor.run(a, null));
    List<String> subcommands = recorder.invocations.stream()
      .map(inv -> inv.command().get(1))
      .toList();
    // The service covers the whole repository: one check, one forget per co-repository
    // task, one prune - then the backup itself
    assertEquals(List.of("check", "forget", "forget", "prune", "backup"), subcommands);
    List<String> forgetA = recorder.invocations.get(1).command();
    List<String> forgetB = recorder.invocations.get(2).command();
    assertTrue(forgetA.contains("bitferry," + a.tag()), "the first forget scopes to task a");
    assertTrue(forgetB.contains("bitferry," + b.tag()), "the second forget scopes to task b - no policy starves");

    // Both entries reset by the service; task a's own backup recorded afterwards
    ResticRepositoryState state = ResticRepositoryState.load(Path.of(destination().processOpts()));
    assertFalse(state.isDue(b.tag(), b.cadence()), "task b's entry must be reset by the service");
    assertNotNull(state.entry(b.tag()).maintenance());
    assertEquals(0, state.entry(b.tag()).backups());
    assertNotNull(state.entry(a.tag()).maintenance());
  }

  @Test
  void sharedRepoServiceSkipsTasksWithoutRetentionPolicy() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup a = sharedRepoTask(null, "@default", "@default");
    Backup b = sharedRepoTask("other", null, "@default"); // b holds no retention policy
    seedRepoState(a, b, true);

    assertEquals(Task.Outcome.SUCCESS, executor.run(a, null));
    List<String> subcommands = recorder.invocations.stream()
      .map(inv -> inv.command().get(1))
      .toList();
    // b's forget is skipped (no policy), but its entry is still reset: the repository
    // service (check + prune) covers it too
    assertEquals(List.of("check", "forget", "prune", "backup"), subcommands);

    ResticRepositoryState state = ResticRepositoryState.load(Path.of(destination().processOpts()));
    assertNotNull(state.entry(b.tag()).maintenance());
    assertEquals(0, state.entry(b.tag()).backups());
  }

  @Test
  void siblingCadenceTriggersServiceBeforeOwnBackup() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup a = sharedRepoTask(null, "@default", "@default");
    Backup b = sharedRepoTask("other", "@default", "@default");
    seedRepoState(a, b, true); // a is fresh, b is due

    assertEquals(Task.Outcome.SUCCESS, executor.run(a, null));
    List<String> subcommands = recorder.invocations.stream()
      .map(inv -> inv.command().get(1))
      .toList();
    // The service runs before the backup although task a's own entry is fresh: the
    // cadence check inspects all the co-repository entries
    assertEquals(List.of("check", "forget", "forget", "prune", "backup"), subcommands);
  }

  @Test
  void siblingCadenceTriggersServiceWhenRunningBackupHasNoCadence() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    // Task a has no cadence configured (manual only), but sibling b is due
    Backup a = sharedRepoTask(null, "@default", null);
    Backup b = sharedRepoTask("other", "@default", "@default");
    seedRepoState(a, b, true);

    assertEquals(Task.Outcome.SUCCESS, executor.run(a, null));
    List<String> subcommands = recorder.invocations.stream()
      .map(inv -> inv.command().get(1))
      .toList();
    // The service must still run before backup because sibling b's cadence is due
    assertEquals(List.of("check", "forget", "forget", "prune", "backup"), subcommands);
  }

  @Test
  void recordBackupTouchesOwnEntryOnly() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup a = sharedRepoTask(null, "@default", "@default");
    Backup b = sharedRepoTask("other", "@default", "@default");
    seedRepoState(a, b, false); // both fresh: no service

    assertEquals(Task.Outcome.SUCCESS, executor.run(a, null));
    List<String> subcommands = recorder.invocations.stream()
      .map(inv -> inv.command().get(1))
      .toList();
    assertEquals(List.of("backup"), subcommands, "no service must run while every entry is fresh");

    ResticRepositoryState state = ResticRepositoryState.load(Path.of(destination().processOpts()));
    assertEquals(1, state.entry(a.tag()).backups());
    assertEquals(0, state.entry(b.tag()).backups(), "the sibling entry must stay untouched");
  }

  @Test
  void legacyRepoStateMigratesUponFirstRun() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup a = sharedRepoTask(null, "@default", "@default");
    Backup b = sharedRepoTask("other", "@default", "@default");

    // The pre-envelope single entry under the pre-rename plain name: both tasks share
    // a fresh baseline (no service due)
    Path repoDir = Path.of(destination().processOpts());
    Files.createDirectories(repoDir);
    String legacyJson = """
      {"backup": null, "maintenance": "%s", "backups_since_maintenance": 0}""".formatted(Instant.now());
    Files.writeString(repoDir.resolve(ResticRepositoryState.LEGACY_FILENAME), legacyJson);

    assertEquals(Task.Outcome.SUCCESS, executor.run(a, null));

    // The envelope file replaced the legacy one; both tasks seeded from the baseline
    assertTrue(Files.isRegularFile(repoDir.resolve(ResticRepositoryState.FILENAME)));
    assertFalse(Files.isRegularFile(repoDir.resolve(ResticRepositoryState.LEGACY_FILENAME)));
    ResticRepositoryState state = ResticRepositoryState.load(repoDir);
    assertNotNull(state.entry(a.tag()).maintenance());
    assertNotNull(state.entry(b.tag()).maintenance());
    assertEquals(1, state.entry(a.tag()).backups());
  }

  @Test
  void passwordRotationWarnsAboutInvisibleCoRepositoryTasks() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    new SystemExecutor(recorder);
    manager.backendExecutor(new SystemExecutor(recorder));

    Backup a = sharedRepoTask(null, "@default", "@default");
    Backup b = sharedRepoTask("other", "@default", "@default");

    // A repository state recording an invisible sibling: a task that once touched the
    // repository but whose volume is not restored in this session
    Path repoDir = Path.of(destination().processOpts());
    Files.createDirectories(repoDir);
    Files.writeString(repoDir.resolve("config"), "restic config stub");
    Files.writeString(repoDir.resolve(ResticRepositoryState.FILENAME),
      """
      {"tasks": {"%s": {}, "%s": {}, "ghost": {}}}""".formatted(a.tag(), b.tag()));

    List<String> stale = manager.passwd(a, "newpass");

    assertEquals(List.of("ghost"), stale, "only the invisible sibling must be reported stale");
    assertEquals("newpass", a.password());
    assertEquals("newpass", b.password(), "the registered sibling must receive the propagated token");
  }

  @Test
  void staleTokenAutoHealsFromCoRepositorySiblingAndRetries() throws IOException {
    // The auth seam: the invocations carrying the stale password fail (the restic
    // authentication failure shape), the fresh ones succeed
    List<List<String>> commands = new ArrayList<>();
    CommandExecutor auth = (command, workingDirectory, environment, output) -> {
      commands.add(command);
      return "stale".equals(environment.get("RESTIC_PASSWORD")) ? 1 : 0;
    };
    SystemExecutor executor = new SystemExecutor(auth);

    // Task a holds the stale token (the password was rotated by task b's session
    // while a's volume was absent); b holds the current one
    Backup a = sharedRepoTask(null, "@default", "@default");
    // Re-store a's token as stale (the constructor has stored it into the vault)
    a.updatePassword("stale");
    Backup b = sharedRepoTask("other", "@default", "@default");
    b.updatePassword("fresh");
    seedRepoState(a, b, false); // both entries fresh: the service must not run

    assertEquals(Task.Outcome.SUCCESS, executor.run(a, null), "the run must succeed after the auto-heal");

    long backupRuns = commands.stream().filter(cmd -> cmd.size() > 1 && "backup".equals(cmd.get(1))).count();
    assertEquals(2, backupRuns, "the failed attempt plus the single retry after the re-sync");
    assertEquals("fresh", a.password(), "the stale token must be re-synced from the sibling");
    ResticRepositoryState state = ResticRepositoryState.load(Path.of(destination().processOpts()));
    assertEquals(1, state.entry(a.tag()).backups(), "the completed retry must be recorded");
    assertEquals(0, state.entry(b.tag()).backups());
  }

  @Test
  void realRcloneReplicateSyncsFilesSuccessfully() throws IOException {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for the integration check");
    Path srcDir = dir.resolve("real-src");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("hello.txt"), "hello bitferry");

    Path dstDir = dir.resolve("real-dst");
    Volume dstVol = Volume.create(manager, dstDir);

    Endpoint srcEndpoint = Endpoints.parse(manager, "local:" + srcDir);
    Endpoint dstEndpoint = Endpoints.parse(manager, ":" + dstVol.tag() + ":backup");

    Replicate task = new Replicate(manager, srcEndpoint, dstEndpoint, null, List.of(), List.of());
    manager.commit(task);
    List<String> outputLines = new ArrayList<>();
    boolean success = manager.process(outputLines::add, task.tag());

    assertTrue(success);
    Path syncedFile = dstDir.resolve("backup").resolve("hello.txt");
    assertTrue(Files.exists(syncedFile));
    assertEquals("hello bitferry", Files.readString(syncedFile));
  }

  @Test
  void rcloneEncryptBuildsTheCryptTailAndEnvironment() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Replicate task = new Replicate(manager, volumeSource(), destination(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));
    Task.Outcome result = executor.run(task, null);

    assertEquals(Task.Outcome.SUCCESS, result);
    assertEquals(1, recorder.invocations.size());
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);

    // The crypt tail replaces the plain endpoints placing the ':crypt:' remote
    List<String> cmd = inv.command();
    int remote = cmd.indexOf("--crypt-remote");
    assertTrue(remote > 0);
    assertEquals(destination().processOpts(), cmd.get(remote + 1));
    assertEquals(volumeSource().processOpts(), cmd.get(remote + 2));
    assertEquals(":crypt:", cmd.get(cmd.size() - 1));
    assertTrue(cmd.contains("--crypt-filename-encoding"));
    assertTrue(cmd.contains("base32"));

    // The crypt password travels obscured through the environment
    String obscured = inv.environment().get("RCLONE_CRYPT_PASSWORD");
    assertNotNull(obscured);
    assertEquals("secret", Passwords.rcloneReveal(obscured));
    assertFalse(inv.environment().containsKey("RCLONE_CRYPT_PASSWORD2"));
  }

  @Test
  void rcloneDecryptReadsThroughTheCryptRemote() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Copy task = new Copy(manager, destination(), volumeRestored(), null, List.of(), List.of());
    task.decrypt("secret", "second", Rclone.Encryption.options("@default"));
    Task.Outcome result = executor.run(task, null);

    assertEquals(Task.Outcome.SUCCESS, result);
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);
    List<String> cmd = inv.command();
    int remote = cmd.indexOf("--crypt-remote");
    assertTrue(remote > 0);
    // The encrypted source goes into --crypt-remote with the plain destination on the command line
    assertEquals(destination().processOpts(), cmd.get(remote + 1));
    assertEquals(":crypt:", cmd.get(remote + 2));
    assertEquals(volumeRestored().processOpts(), cmd.get(cmd.size() - 1));
    assertEquals("secret", Passwords.rcloneReveal(inv.environment().get("RCLONE_CRYPT_PASSWORD")));
    assertEquals("second", Passwords.rcloneReveal(inv.environment().get("RCLONE_CRYPT_PASSWORD2")));
  }

  @Test
  void realRcloneCryptRoundTripRestoresThePlaintext() throws IOException {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for the integration check");
    Path volDir = dir.resolve("crypt-vol");
    Files.createDirectories(volDir);
    Files.writeString(volDir.resolve("secret.txt"), "classified payload");
    Volume volume = Volume.create(manager, volDir);

    // The encrypt task replicates the volume contents in the encrypted form
    Endpoint source = Endpoints.parse(manager, ":" + volume.tag() + ":");
    Endpoint encrypted = Endpoints.parse(manager, "local:" + dir.resolve("crypt-enc"));
    Replicate encrypt = new Replicate(manager, source, encrypted, null, List.of(), List.of());
    encrypt.encrypt("crypt-pass", null, Rclone.Encryption.options("@default"));
    manager.commit(encrypt);
    assertTrue(manager.process(encrypt.tag()));

    // The encrypted replica holds no plaintext names nor contents
    try (var entries = Files.list(dir.resolve("crypt-enc"))) {
      List<Path> files = entries.toList();
      assertEquals(1, files.size());
      assertNotEquals("secret.txt", files.getFirst().getFileName().toString());
      // The contents are AES-encrypted bytes; ISO-8859-1 keeps the byte-to-char mapping total
      assertFalse(Files.readString(files.getFirst(), StandardCharsets.ISO_8859_1).contains("classified"));
    }

    // The decrypt task restores the plaintext out of the encrypted source
    Copy decrypt = new Copy(manager, encrypted, Endpoints.parse(manager, ":" + volume.tag() + ":decrypted"), null, List.of(), List.of());
    decrypt.decrypt("crypt-pass", null, Rclone.Encryption.options("@default"));
    manager.commit(decrypt);
    assertTrue(manager.process(decrypt.tag()));

    Path restored = volDir.resolve("decrypted").resolve("secret.txt");
    assertEquals("classified payload", Files.readString(restored));
  }

  @Test
  void resticConformSkipsWhenRepoAbsent() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
    executor.conform(backup);

    assertEquals(0, recorder.invocations.size());
  }

  @Test
  void resticConformProbesExistingRepo() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repo = dir.resolve("restic-repo");
    Files.createDirectories(repo);
    Files.writeString(repo.resolve("config"), "dummy-config");

    Backup backup = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repo), "secret", null, null, null, null, List.of());
    executor.conform(backup);

    assertEquals(1, recorder.invocations.size());
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);
    assertEquals("restic", inv.command().get(0));
    assertTrue(inv.command().contains("-r"));
    assertEquals(repo.toString(), inv.command().get(inv.command().indexOf("-r") + 1));
    assertTrue(inv.command().contains("cat"));
    assertTrue(inv.command().contains("config"));
    assertEquals("secret", inv.environment().get("RESTIC_PASSWORD"));
  }

  @Test
  void resticConformSkipsOnForce() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repo = dir.resolve("restic-repo-force");
    Files.createDirectories(repo);
    Files.writeString(repo.resolve("config"), "dummy-config");

    Backup backup = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repo), "secret", true, null, null, null, List.of());
    executor.conform(backup);

    assertEquals(0, recorder.invocations.size());
  }

  @Test
  void resticConformRefusesOnProbeFailure() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1;
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repo = dir.resolve("restic-repo-fail");
    Files.createDirectories(repo);
    Files.writeString(repo.resolve("config"), "dummy-config");

    Backup backup = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repo), "secret", null, null, null, null, List.of());
    BitferryError error = assertThrows(BitferryError.class, () -> executor.conform(backup));
    assertTrue(error.getMessage().contains("does not accept the provided password"));
    assertFalse(error.getMessage().contains("--attach"));
  }

  @Test
  void resticRestoreConformRefusesWrongPassword() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1;
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repo = dir.resolve("restic-repo-restore-fail");
    Files.createDirectories(repo);
    Files.writeString(repo.resolve("config"), "dummy-config");

    Restore restore = new Restore(manager, Endpoints.parse(manager, "local:" + repo), volumeRestored(), "secret", null, List.of(), List.of());
    BitferryError error = assertThrows(BitferryError.class, () -> executor.conform(restore));
    assertTrue(error.getMessage().contains("does not accept the provided password"));
    assertFalse(error.getMessage().contains("--attach"));
  }

  @Test
  void resticRestoreConformRefusesNonResticDirectory() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path notRepo = dir.resolve("not-a-restic-repo");
    Files.createDirectories(notRepo);
    Files.writeString(notRepo.resolve("some-file.txt"), "hello");

    Restore restore = new Restore(manager, Endpoints.parse(manager, "local:" + notRepo), volumeRestored(), "secret", null, List.of(), List.of());
    BitferryError error = assertThrows(BitferryError.class, () -> executor.conform(restore));
    assertTrue(error.getMessage().contains("restic repository"));
  }

  @Test
  void resticFormatImplicitlyAttachesWhenRepoExistsAndCorrectPassword() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repo = dir.resolve("restic-repo-implicit");
    Files.createDirectories(repo);
    Files.writeString(repo.resolve("config"), "dummy-config");

    Backup backup = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repo), "secret", null, null, null, null, List.of());
    executor.format(backup);

    assertEquals(1, recorder.invocations.size());
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);
    assertTrue(inv.command().contains("cat"));
    assertTrue(inv.command().contains("config"));
    assertFalse(inv.command().contains("init"));
  }

  @Test
  void rcloneConformSkipsWhenNoCrypt() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path dst = dir.resolve("dst-nocrypt");
    Files.createDirectories(dst);
    Files.writeString(dst.resolve("file.txt"), "hello");

    Replicate task = new Replicate(manager, volumeSource(), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    executor.conform(task);

    assertEquals(0, recorder.invocations.size());
  }

  @Test
  void rcloneConformSkipsWhenDirEmptyOrAbsent() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path dst = dir.resolve("dst-absent");
    Replicate task = new Replicate(manager, volumeSource(), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));
    executor.conform(task);

    assertEquals(0, recorder.invocations.size());
  }

  @Test
  void rcloneConformSkipsWhenOnlyStorageFilesPresent() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path dst = dir.resolve("dst-storage-only");
    Files.createDirectories(dst);
    Files.writeString(dst.resolve(Bitferry.STORAGE), "storage");
    Files.writeString(dst.resolve(Bitferry.STORAGE_IMAGE), "image");

    Replicate task = new Replicate(manager, volumeSource(), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));
    executor.conform(task);

    assertEquals(0, recorder.invocations.size());
  }

  @Test
  void rcloneConformProbesAndAssertsCommandShape() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.simulatedOutput = List.of("[", "{\"Path\":\"plaintext.txt\"}", "]");
    SystemExecutor executor = new SystemExecutor(recorder);

    Path dst = dir.resolve("dst-probe-shape");
    Files.createDirectories(dst);
    Files.writeString(dst.resolve("encrypted_name"), "encrypted-bytes");

    Replicate task = new Replicate(manager, volumeSource(), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    task.encrypt("secret", "secret2", Rclone.Encryption.options("@default"));
    executor.conform(task);

    assertEquals(2, recorder.invocations.size());
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);
    List<String> cmd = inv.command();
    assertEquals("rclone", cmd.get(0));
    assertEquals("lsjson", cmd.get(1));
    assertEquals("-R", cmd.get(2));
    assertTrue(cmd.contains("--config"));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE + "*"));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE + "*/**"));
    assertTrue(cmd.contains("--crypt-filename-encoding"));
    assertTrue(cmd.contains("base32"));
    assertTrue(cmd.contains("--crypt-filename-encryption"));
    assertTrue(cmd.contains("standard"));
    assertTrue(cmd.contains("--crypt-remote"));
    assertEquals(dst.toString(), cmd.get(cmd.indexOf("--crypt-remote") + 1));
    assertEquals(":crypt:", cmd.get(cmd.size() - 1));
    assertEquals("secret", Passwords.rcloneReveal(inv.environment().get("RCLONE_CRYPT_PASSWORD")));
    assertEquals("secret2", Passwords.rcloneReveal(inv.environment().get("RCLONE_CRYPT_PASSWORD2")));

    RecordingCommandExecutor.Invocation inv1 = recorder.invocations.get(1);
    List<String> cmd1 = inv1.command();
    assertEquals("rclone", cmd1.get(0));
    assertEquals("cat", cmd1.get(1));
    assertTrue(cmd1.contains("--head"));
    assertTrue(cmd1.contains("1"));
    assertEquals(":crypt:", cmd1.get(cmd1.size() - 1));
  }

  @Test
  void rcloneConformRefusesOnProbeFailure() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1;
    SystemExecutor executor = new SystemExecutor(recorder);

    Path dst = dir.resolve("dst-probe-fail");
    Files.createDirectories(dst);
    Files.writeString(dst.resolve("enc1"), "bytes");

    Replicate task = new Replicate(manager, volumeSource(), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));

    BitferryError error = assertThrows(BitferryError.class, () -> executor.conform(task));
    assertTrue(error.getMessage().startsWith("the existing encrypted content in '" + dst + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"), () -> error.getMessage());
    // The probe diagnostics (the exit code and the captured backend output tail) ride on the refusal
    assertTrue(error.getMessage().contains("[probe exit code"), () -> error.getMessage());
  }

  @Test
  void rcloneConformRefusesOnDecryptionError() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.simulatedOutput = List.of(
      "[",
      "2026/11/10 14:00:00 NOTICE: enc1: Skipping undecryptable file name: bad PKCS#7 padding - too long",
      "]"
    );
    SystemExecutor executor = new SystemExecutor(recorder);

    Path dst = dir.resolve("dst-probe-decrypt-err");
    Files.createDirectories(dst);
    Files.writeString(dst.resolve("enc1"), "bytes");

    Replicate task = new Replicate(manager, volumeSource(), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));

    BitferryError error = assertThrows(BitferryError.class, () -> executor.conform(task));
    assertTrue(error.getMessage().startsWith("the existing encrypted content in '" + dst + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"), () -> error.getMessage());
    // The probe diagnostics (the exit code and the captured backend output tail) ride on the refusal
    assertTrue(error.getMessage().contains("[probe exit code"), () -> error.getMessage());
  }

  @Test
  void rcloneConformRefusesWhenCryptNamesMatchRawNames() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.simulatedOutput = List.of(
      "[",
      "{\"Path\":\"raw-file.txt\"}",
      "]"
    );
    SystemExecutor executor = new SystemExecutor(recorder);

    Path dst = dir.resolve("dst-probe-same-name");
    Files.createDirectories(dst);
    Files.writeString(dst.resolve("raw-file.txt"), "plain-content");

    Replicate task = new Replicate(manager, volumeSource(), Endpoints.parse(manager, "local:" + dst), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));

    BitferryError error = assertThrows(BitferryError.class, () -> executor.conform(task));
    assertTrue(error.getMessage().startsWith("the existing encrypted content in '" + dst + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"), () -> error.getMessage());
    // The probe diagnostics (the exit code and the captured backend output tail) ride on the refusal
    assertTrue(error.getMessage().contains("[probe exit code"), () -> error.getMessage());
  }

  @Test
  void realResticConformanceRejectsWrongPasswordAndAcceptsCorrectPassword() throws Exception {
    Assumptions.assumeTrue(available("restic"), "restic not available for the integration check");
    Path repoDir = dir.resolve("real-restic-repo");

    // Initialize restic repo with password A
    ProcessBuilder pb = new ProcessBuilder("restic", "init", "-r", repoDir.toString());
    pb.environment().put("RESTIC_PASSWORD", "correct-password");
    Process p = pb.start();
    assertEquals(0, p.waitFor());

    Path srcDir = dir.resolve("real-restic-src");
    Files.createDirectories(srcDir);
    Volume vol = Volume.create(manager, srcDir);
    Endpoint src = Endpoints.parse(manager, ":" + vol.tag() + ":");
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);

    // 1. Wrong password backup creation is refused and task is deregistered
    Backup wrongBackup = new Backup(manager, src, repo, "wrong-password", null, null, null, null, List.of());
    BitferryError err = assertThrows(BitferryError.class, () -> manager.commit(wrongBackup));
    assertTrue(err.getMessage().contains("does not accept the provided password"), err.getMessage());
    assertFalse(err.getMessage().contains("--attach"), err.getMessage());
    assertFalse(manager.tasks().containsKey(wrongBackup.tag()));

    // 2. Correct password backup creation succeeds and persists
    Backup correctBackup = new Backup(manager, src, repo, "correct-password", null, null, null, null, List.of());
    manager.commit(correctBackup);
    assertTrue(manager.tasks().containsKey(correctBackup.tag()));

    Manager restored = new Manager().restore(srcDir);
    assertTrue(restored.tasks().containsKey(correctBackup.tag()));

    // 4. Force (--force) skips conformance, wipes and reinitializes with new password.
    // The previous co-repository sibling is deleted first: the forced wipe refuses to
    // destroy the snapshots of a live sibling task (the guard protects the shared
    // repositories - the deletion is the documented way out)
    manager.task(correctBackup.tag()).delete();
    Backup forceBackup = new Backup(manager, src, repo, "new-force-password", true, null, null, null, List.of());
    manager.commit(forceBackup);
    assertTrue(manager.tasks().containsKey(forceBackup.tag()));

    // 5. Restore with wrong password is refused
    Restore wrongRestore = new Restore(manager, repo, src, "bad-password", null, List.of(), List.of());
    err = assertThrows(BitferryError.class, () -> manager.commit(wrongRestore));
    assertTrue(err.getMessage().contains("does not accept the provided password"), err.getMessage());
    assertFalse(err.getMessage().contains("--attach"), err.getMessage());
    assertFalse(manager.tasks().containsKey(wrongRestore.tag()));

    // 6. Restore with correct password ("new-force-password") succeeds
    Restore correctRestore = new Restore(manager, repo, src, "new-force-password", null, List.of(), List.of());
    manager.commit(correctRestore);
    assertTrue(manager.tasks().containsKey(correctRestore.tag()));
  }

  @Test
  void routineBackupDoesNotTriggerServiceWhenNotDue() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repoDir = dir.resolve("routine-backup-repo");
    Files.createDirectories(repoDir);
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);

    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, "@default", "@default", "7d,5", List.of());

    // Baseline state: service done recently, count low
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of(backup.tag()));
    state.recordServe(List.of(backup.tag()));
    state.recordBackup(backup.tag());
    state.save(repoDir);

    Task.Outcome ok = executor.run(backup, null);
    assertEquals(Task.Outcome.SUCCESS, ok);

    // Only 1 invocation: backup (service was not due)
    assertEquals(1, recorder.invocations.size());
    assertEquals("backup", recorder.invocations.get(0).command().get(1));

    // Backups count incremented
    ResticRepositoryState updated = ResticRepositoryState.load(repoDir);
    assertEquals(2, updated.entry(backup.tag()).backups());
  }

  @Test
  void routineBackupTriggersServiceWhenDueAndResetsCount() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repoDir = dir.resolve("due-backup-repo");
    Files.createDirectories(repoDir);
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);

    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, "@default", "@default", "7d,5", List.of());
    manager.registerTask(backup);

    // Threshold crossed: 5 backups done since service
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of(backup.tag()));
    state.recordServe(List.of(backup.tag()));
    for (int i = 0; i < 5; i++) state.recordBackup(backup.tag());
    state.save(repoDir);

    Task.Outcome ok = executor.run(backup, null);
    assertEquals(Task.Outcome.SUCCESS, ok);

    // 4 invocations: check -> forget -> prune -> backup
    assertEquals(4, recorder.invocations.size());
    assertEquals("check", recorder.invocations.get(0).command().get(1));
    assertEquals("forget", recorder.invocations.get(1).command().get(1));
    assertEquals("prune", recorder.invocations.get(2).command().get(1));
    assertEquals("backup", recorder.invocations.get(3).command().get(1));

    // Count was reset to 0 by service, then incremented to 1 by the completed backup
    ResticRepositoryState updated = ResticRepositoryState.load(repoDir);
    assertEquals(1, updated.entry(backup.tag()).backups());
  }

  @Test
  void serviceAbortsBeforeForgetAndPruneWhenCheckFails() throws IOException {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1; // Check command fails
    SystemExecutor executor = new SystemExecutor(recorder);

    Path repoDir = dir.resolve("failing-check-repo");
    Files.createDirectories(repoDir);
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);

    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, "@default", "@default", "7d,5", List.of());
    Task.Outcome ok = executor.serve(backup, null);
    assertEquals(Task.Outcome.FAILURE, ok);

    // Halted immediately after failed check: forget and prune never executed (the
    // extra invocation is the auto-heal diagnostic probe - no sibling exists here,
    // so the heal declines and the check is not retried)
    List<List<String>> commands = recorder.invocations.stream()
      .map(RecordingCommandExecutor.Invocation::command)
      .toList();
    // The first invocation is the check; the second is the diagnostic probe (the
    // testPassword shape: restic -r <repo> cat config)
    assertEquals("check", commands.getFirst().get(1));
    assertTrue(commands.get(1).contains("cat") && commands.get(1).contains("config"),
      "the second invocation is the diagnostic probe: " + commands.get(1));
    assertEquals(1, commands.stream().filter(c -> c.size() > 1 && "check".equals(c.get(1))).count(),
      "the check must not be retried without a healing sibling");
    assertEquals(2, commands.size(), "nothing runs after the declined heal");
  }

  @Test
  void batchServiceFaultIsolation() throws IOException {
    // Failing task 1 check should not prevent task 2 service
    List<RecordingCommandExecutor.Invocation> invocations = new ArrayList<>();
    CommandExecutor dynamicExecutor = (command, workingDirectory, environment, output) -> {
      invocations.add(new RecordingCommandExecutor.Invocation(command, workingDirectory, environment, output));
      if (command.stream().anyMatch(tok -> tok.contains("failing-task"))) {
        return 1; // Fails task 1
      }
      return 0; // Succeeds task 2
    };

    SystemExecutor executor = new SystemExecutor(dynamicExecutor);
    manager.backendExecutor(executor);

    Path repo1 = dir.resolve("failing-task");
    Path repo2 = dir.resolve("healthy-task");
    Files.createDirectories(repo1);
    Files.createDirectories(repo2);

    Backup b1 = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repo1), "sec1", null, null, null, null, "7d,5", List.of());
    Backup b2 = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repo2), "sec2", null, null, null, null, "7d,5", List.of());
    manager.registerTask(b1);
    manager.registerTask(b2);

    boolean overall = manager.serve(List.of(b1.tag(), b2.tag()), null);
    assertFalse(overall);

    // b1 check ran (and failed, aborting its prune)
    assertTrue(invocations.stream().anyMatch(inv -> inv.command().contains(repo1.toString())));
    // b2 was still serviced!
    assertTrue(invocations.stream().anyMatch(inv -> inv.command().contains(repo2.toString())));
  }

  @Test
  void rcloneServiceRunsCheck() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Replicate sync = new Replicate(manager, source(), destination(), null, List.of("*.txt"), List.of("*.bak"));
    Task.Outcome ok = executor.serve(sync, null);
    assertEquals(Task.Outcome.SUCCESS, ok);

    assertEquals(1, recorder.invocations.size());
    List<String> cmd = recorder.invocations.get(0).command();
    assertEquals("rclone", cmd.get(0));
    assertEquals("check", cmd.get(1));
    assertTrue(cmd.contains("+ *.txt"));
    assertTrue(cmd.contains("- *.bak"));
  }

  @Test
  void resticPasswdRotatesKeyWhenCurrentValidAndNewDifferent() throws IOException {
    Path repoDir = dir.resolve("passwd-repo-rotate");
    Files.createDirectories(repoDir);
    Files.writeString(repoDir.resolve("config"), "dummy-config");

    List<RecordingCommandExecutor.Invocation> invocations = new ArrayList<>();
    CommandExecutor dynamicExecutor = (command, workingDirectory, environment, output) -> {
      invocations.add(new RecordingCommandExecutor.Invocation(command, workingDirectory, environment, output));
      if (command.contains("cat")) {
        // If probing with new password, fail; if probing with old password, succeed
        return "new-secret".equals(environment.get("RESTIC_PASSWORD")) ? 1 : 0;
      }
      if (command.contains("key") && command.contains("passwd")) {
        return 0; // passwd succeeds
      }
      return 0;
    };

    SystemExecutor executor = new SystemExecutor(dynamicExecutor);
    Backup backup = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repoDir), "old-secret", null, null, null, null, List.of());

    executor.passwd(backup, "new-secret");

    assertEquals("new-secret", backup.password());
    // 3 invocations: test new -> test old -> key passwd
    assertEquals(3, invocations.size());
    assertEquals("cat", invocations.get(0).command().get(3));
    assertEquals("new-secret", invocations.get(0).environment().get("RESTIC_PASSWORD"));

    assertEquals("cat", invocations.get(1).command().get(3));
    assertEquals("old-secret", invocations.get(1).environment().get("RESTIC_PASSWORD"));

    assertEquals("key", invocations.get(2).command().get(1));
    assertEquals("passwd", invocations.get(2).command().get(2));
    assertEquals("old-secret", invocations.get(2).environment().get("RESTIC_PASSWORD"));
    assertTrue(invocations.get(2).command().contains("--new-password-file"));
  }

  @Test
  void resticPasswdSkipsKeyRotationWhenNewPasswordAlreadyMatches() throws IOException {
    Path repoDir = dir.resolve("passwd-repo-matches");
    Files.createDirectories(repoDir);
    Files.writeString(repoDir.resolve("config"), "dummy-config");

    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 0; // Probing with new password immediately succeeds
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup backup = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repoDir), "old-secret", null, null, null, null, List.of());

    executor.passwd(backup, "new-secret");

    assertEquals("new-secret", backup.password());
    assertEquals(1, recorder.invocations.size());
    assertEquals("cat", recorder.invocations.get(0).command().get(3));
    assertFalse(recorder.invocations.stream().anyMatch(inv -> inv.command().contains("passwd")));
  }

  @Test
  void resticPasswdFailsWhenOldPasswordInvalid() throws IOException {
    Path repoDir = dir.resolve("passwd-repo-invalid-old");
    Files.createDirectories(repoDir);
    Files.writeString(repoDir.resolve("config"), "dummy-config");

    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1; // Both fail
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup backup = new Backup(manager, volumeSource(), Endpoints.parse(manager, "local:" + repoDir), "old-secret", null, null, null, null, List.of());

    BitferryError err = assertThrows(BitferryError.class, () -> executor.passwd(backup, "new-secret"));
    assertTrue(err.getMessage().contains("is not accepted by the Restic repository"));
    assertEquals("old-secret", backup.password());
  }

  @Test
  void resticPasswdFailsForRestoreWhenRepoDoesNotAcceptNewPassword() throws IOException {
    Path repoDir = dir.resolve("passwd-restore-fail");
    Files.createDirectories(repoDir);
    Files.writeString(repoDir.resolve("config"), "dummy-config");

    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1; // Probing with new password fails
    SystemExecutor executor = new SystemExecutor(recorder);

    Restore restore = new Restore(manager, Endpoints.parse(manager, "local:" + repoDir), volumeRestored(), "old-secret", null, List.of(), List.of());

    BitferryError err = assertThrows(BitferryError.class, () -> executor.passwd(restore, "new-secret"));
    assertTrue(err.getMessage().contains("does not accept the provided password"));
  }

  @Test
  void resticPasswdSynchronizesPairedRestoreTask() throws IOException {
    Path repoDir = dir.resolve("passwd-repo-sync-paired");
    Files.createDirectories(repoDir);
    Files.writeString(repoDir.resolve("config"), "dummy-config");

    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 0; // Probe with new password succeeds
    SystemExecutor executor = new SystemExecutor(recorder);

    Endpoint repoEndpoint = Endpoints.parse(manager, "local:" + repoDir);
    Backup backup = new Backup(manager, volumeSource(), repoEndpoint, "old-secret", null, null, null, null, List.of());
    Restore restore = new Restore(manager, repoEndpoint, volumeRestored(), "old-secret", null, List.of(), List.of());
    manager.registerTask(backup);
    manager.registerTask(restore);

    executor.passwd(backup, "new-secret");

    assertEquals("new-secret", backup.password());
    assertEquals("new-secret", restore.password());
  }

  @Test
  void repairUnlockBuildsTheExpectedCommand() throws IOException {
    Path repoDir = Files.createDirectories(dir.resolve("repo"));
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, null, null, List.of());
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    // The stale-only unlock: no --remove-all, no --dry-run, the repo bound via -r,
    // the password riding the environment like every restic invocation
    assertTrue(executor.repair(backup, Repair.UNLOCK_STALE, false, null));
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);
    assertEquals("restic", inv.command().get(0));
    assertEquals("unlock", inv.command().get(1));
    assertFalse(inv.command().contains("--remove-all"));
    assertFalse(inv.command().contains("--dry-run"));
    assertEquals(repoDir.toString(), inv.command().get(inv.command().size() - 1));
    assertEquals("secret", inv.environment().get("RESTIC_PASSWORD"));
  }

  @Test
  void repairFlagsFlowIntoTheUnlockCommand() throws IOException {
    Path repoDir = Files.createDirectories(dir.resolve("repo"));
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, null, null, List.of());
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    // The remove-all variant carries its flag, the dry-run preview its own
    assertTrue(executor.repair(backup, Repair.UNLOCK_ALL, false, null));
    assertTrue(recorder.invocations.get(0).command().contains("--remove-all"));
    assertFalse(recorder.invocations.get(0).command().contains("--dry-run"));

    assertTrue(executor.repair(backup, Repair.UNLOCK_STALE, true, null));
    assertTrue(recorder.invocations.get(1).command().contains("list"));
    assertTrue(recorder.invocations.get(1).command().contains("locks"));
    assertFalse(recorder.invocations.get(1).command().contains("--remove-all"));

    // The simulation mode degrades to the dry-run listing (mirroring the backup/forget flags)
    manager.simulate = true;
    assertTrue(executor.repair(backup, Repair.UNLOCK_STALE, false, null));
    assertTrue(recorder.invocations.get(2).command().contains("list"));
    assertTrue(recorder.invocations.get(2).command().contains("locks"));
  }

  @Test
  void repairFailuresAndUnsupportedBackendsSurface() throws IOException {
    Path repoDir = Files.createDirectories(dir.resolve("repo"));
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, null, null, List.of());
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.returnCode = 1;
    SystemExecutor executor = new SystemExecutor(recorder);
    List<String> output = new ArrayList<>();
    assertFalse(executor.repair(backup, Repair.UNLOCK_STALE, false, output::add));
    assertTrue(output.stream().anyMatch(l -> l.contains("Command failed with exit code 1")), () -> "missing failure: " + output);

    // The operation outside the task's backend reports and fails (the registry maps
    // the restic operations onto the restic tasks only)
    Replicate replicate = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    output.clear();
    assertFalse(executor.repair(replicate, Repair.UNLOCK_STALE, false, output::add));
    assertTrue(output.stream().anyMatch(l -> l.contains("not supported")), () -> "missing refusal: " + output);
  }

  @Test
  void repairOperationsFollowTheTaskBackend() throws IOException {
    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of());
    Restore restore = new Restore(manager, destination(), volumeRestored(), "secret", null, List.of(), List.of());
    Replicate replicate = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    assertEquals(List.of(Repair.UNLOCK_STALE, Repair.UNLOCK_ALL), Repair.operationsFor(backup));
    assertEquals(List.of(Repair.UNLOCK_STALE, Repair.UNLOCK_ALL), Repair.operationsFor(restore));
    assertEquals(List.of(), Repair.operationsFor(replicate));
  }

  @Test
  void resticServiceOutputsTheStepAndCommandLinesInVerboseMode() throws IOException {
    // The verbose captured runs (the GUI run log) carry the step narration and the
    // color-highlighted command lines
    Path repoDir = Files.createDirectories(dir.resolve("repo"));
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, null, null, List.of());
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    manager.backendExecutor(new SystemExecutor(recorder));
    // The committed (live) task: the serve path must not carry the one-time init
    manager.commit(backup);
    manager.registerTask(backup);
    manager.verbose(true);

    List<String> output = new ArrayList<>();
    manager.serve(List.of(backup.tag()), output::add);
    assertEquals(3, recorder.invocations.size(), () -> "invocations: " + recorder.invocations);
    assertEquals("init", recorder.invocations.get(0).command().get(1));
    assertEquals("check", recorder.invocations.get(1).command().get(1));
    assertEquals("prune", recorder.invocations.get(2).command().get(1));
    assertTrue(output.stream().anyMatch(l -> l.contains("Checking repository")), () -> "missing step line: " + output);
    assertTrue(output.stream().anyMatch(l -> l.contains("Pruning repository")), () -> "missing step line: " + output);
    assertTrue(output.stream().anyMatch(l -> l.startsWith(AnsiFormat.CYAN + "restic check")), () -> "missing check command echo: " + output);
    assertTrue(output.stream().anyMatch(l -> l.startsWith(AnsiFormat.CYAN + "restic prune")), () -> "missing prune command echo: " + output);
    assertTrue(output.stream().anyMatch(l -> l.endsWith(AnsiFormat.RESET)), () -> "the echo must be color-highlighted: " + output);
  }

  @Test
  void resticServiceStaysQuietWithoutVerbose() throws IOException {
    // The normal run log carries the runner's marks and the diagnostics only: the
    // step narration and the command echoes stay verbose-gated
    Path repoDir = Files.createDirectories(dir.resolve("repo"));
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
    Backup backup = new Backup(manager, volumeSource(), repo, "secret", null, null, null, null, List.of());
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    manager.backendExecutor(new SystemExecutor(recorder));
    manager.commit(backup);
    manager.registerTask(backup);
    assertFalse(manager.verbose());

    List<String> output = new ArrayList<>();
    manager.serve(List.of(backup.tag()), output::add);
    assertEquals(3, recorder.invocations.size(), () -> "invocations: " + recorder.invocations);
    assertEquals("init", recorder.invocations.get(0).command().get(1));
    assertEquals("check", recorder.invocations.get(1).command().get(1));
    assertEquals("prune", recorder.invocations.get(2).command().get(1));
    assertTrue(output.isEmpty(), () -> "the normal run log must stay quiet: " + output);
  }
}
