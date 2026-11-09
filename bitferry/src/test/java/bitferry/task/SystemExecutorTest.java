package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.vault.Passwords;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
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
    volume = new Volume(manager, dir.resolve("volume"));
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

    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of("*.txt"), List.of("*.bak"));
    boolean result = executor.run(task, null);

    assertTrue(result);
    assertEquals(1, recorder.invocations.size());
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);

    assertNull(inv.workingDir());
    assertNull(inv.output()); // CLI pass-through mode
    List<String> cmd = inv.command();

    assertEquals("rclone", cmd.get(0));
    assertEquals("sync", cmd.get(1));

    // Filters
    assertTrue(cmd.contains("+ *.txt"));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE));
    assertTrue(cmd.contains("- " + Bitferry.STORAGE_IMAGE));
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
  }

  @Test
  void rcloneManagerFlagsAreReflectedInArguments() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    manager.verbose(true);
    manager.simulate = true;

    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of(), List.of());
    executor.run(task, null);

    List<String> cmd = recorder.invocations.get(0).command();
    assertTrue(cmd.contains("--verbose"));
    assertTrue(cmd.contains("--progress"));
    assertTrue(cmd.contains("--dry-run"));
  }

  @Test
  void resticBackupSetsWorkingDirectoryAndRunsCheckAndForget() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, "default", "default", List.of(), List.of("*.tmp"));
    boolean result = executor.run(backup, null);

    assertTrue(result);
    // 3 invocations: backup, check, forget
    assertEquals(3, recorder.invocations.size());

    // 1. Backup invocation
    RecordingCommandExecutor.Invocation invBackup = recorder.invocations.get(0);
    assertEquals(Path.of(volumeSource().processOpts()), invBackup.workingDir());
    // The repository password travels through the environment
    assertEquals("secret", invBackup.environment().get("RESTIC_PASSWORD"));
    List<String> cmd1 = invBackup.command();
    assertEquals("restic", cmd1.get(0));
    assertEquals("backup", cmd1.get(1));
    assertEquals(".", cmd1.get(2));
    assertTrue(cmd1.contains("--tag"));
    assertTrue(cmd1.contains("bitferry," + backup.tag()));
    assertTrue(cmd1.contains("--exclude"));
    assertTrue(cmd1.contains("*.tmp"));
    assertTrue(cmd1.contains("-r"));
    assertEquals(destination().processOpts(), cmd1.get(cmd1.size() - 1));

    // 2. Check invocation
    RecordingCommandExecutor.Invocation invCheck = recorder.invocations.get(1);
    assertEquals("secret", invCheck.environment().get("RESTIC_PASSWORD"));
    List<String> cmd2 = invCheck.command();
    assertEquals("restic", cmd2.get(0));
    assertEquals("check", cmd2.get(1));
    assertTrue(cmd2.contains("--no-cache"));

    // 3. Forget invocation
    RecordingCommandExecutor.Invocation invForget = recorder.invocations.get(2);
    assertEquals("secret", invForget.environment().get("RESTIC_PASSWORD"));
    List<String> cmd3 = invForget.command();
    assertEquals("restic", cmd3.get(0));
    assertEquals("forget", cmd3.get(1));
    assertTrue(cmd3.contains("--prune"));
  }

  @Test
  void resticRestoreBuildsTargetAndIncludeFilters() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Restore restore = new Restore(manager, destination(), volumeRestored(), "secret", null, List.of("docs/**"), List.of("*.tmp"));
    boolean result = executor.run(restore, null);

    assertTrue(result);
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
    assertTrue(cmd.contains("*.tmp"));
  }

  @Test
  void outputCapturingForwardsLinesToConsumer() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    recorder.simulatedOutput = List.of("Transferred: 10/10", "100%", "Finished in 0.5s");
    SystemExecutor executor = new SystemExecutor(recorder);

    List<String> captured = new ArrayList<>();
    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of(), List.of());
    boolean result = executor.run(task, captured::add);

    assertTrue(result);
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
    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of(), List.of());
    boolean result = executor.run(task, captured::add);

    assertFalse(result);
    assertTrue(captured.stream().anyMatch(msg -> msg.contains("command failed with exit code 2")));
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

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of(), List.of());
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
  void backupFormatSkipsWhenAttached() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", false, null, null, null, List.of(), List.of());
    executor.format(backup);

    assertEquals(0, recorder.invocations.size());
  }

  @Test
  void realRcloneSynchronizeSyncsFilesSuccessfully() throws IOException {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for the integration check");
    Path srcDir = dir.resolve("real-src");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("hello.txt"), "hello bitferry");

    Path dstDir = dir.resolve("real-dst");
    Volume dstVol = new Volume(manager, dstDir);

    Endpoint srcEndpoint = Endpoints.parse(manager, "local:" + srcDir);
    Endpoint dstEndpoint = Endpoints.parse(manager, ":" + dstVol.tag() + ":backup");

    Synchronize task = new Synchronize(manager, srcEndpoint, dstEndpoint, null, List.of(), List.of());
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

    Synchronize task = new Synchronize(manager, volumeSource(), destination(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("default"));
    boolean result = executor.run(task, null);

    assertTrue(result);
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
    assertEquals("secret", Passwords.reveal(obscured));
    assertFalse(inv.environment().containsKey("RCLONE_CRYPT_PASSWORD2"));
  }

  @Test
  void rcloneDecryptReadsThroughTheCryptRemote() {
    RecordingCommandExecutor recorder = new RecordingCommandExecutor();
    SystemExecutor executor = new SystemExecutor(recorder);

    Copy task = new Copy(manager, destination(), volumeRestored(), null, List.of(), List.of());
    task.decrypt("secret", "second", Rclone.Encryption.options("default"));
    boolean result = executor.run(task, null);

    assertTrue(result);
    RecordingCommandExecutor.Invocation inv = recorder.invocations.get(0);
    List<String> cmd = inv.command();
    int remote = cmd.indexOf("--crypt-remote");
    assertTrue(remote > 0);
    // The encrypted source goes into --crypt-remote with the plain destination on the command line
    assertEquals(destination().processOpts(), cmd.get(remote + 1));
    assertEquals(":crypt:", cmd.get(remote + 2));
    assertEquals(volumeRestored().processOpts(), cmd.get(cmd.size() - 1));
    assertEquals("secret", Passwords.reveal(inv.environment().get("RCLONE_CRYPT_PASSWORD")));
    assertEquals("second", Passwords.reveal(inv.environment().get("RCLONE_CRYPT_PASSWORD2")));
  }

  @Test
  void realRcloneCryptRoundTripRestoresThePlaintext() throws IOException {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for the integration check");
    Path volDir = dir.resolve("crypt-vol");
    Files.createDirectories(volDir);
    Files.writeString(volDir.resolve("secret.txt"), "classified payload");
    Volume volume = new Volume(manager, volDir);

    // The encrypt task replicates the volume contents in the encrypted form
    Endpoint source = Endpoints.parse(manager, ":" + volume.tag() + ":");
    Endpoint encrypted = Endpoints.parse(manager, "local:" + dir.resolve("crypt-enc"));
    Synchronize encrypt = new Synchronize(manager, source, encrypted, null, List.of(), List.of());
    encrypt.encrypt("crypt-pass", null, Rclone.Encryption.options("default"));
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
    decrypt.decrypt("crypt-pass", null, Rclone.Encryption.options("default"));
    assertTrue(manager.process(decrypt.tag()));

    Path restored = volDir.resolve("decrypted").resolve("secret.txt");
    assertEquals("classified payload", Files.readString(restored));
  }
}
