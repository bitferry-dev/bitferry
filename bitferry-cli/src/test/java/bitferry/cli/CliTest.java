package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.VolumeEndpoint;
import bitferry.task.Backup;
import bitferry.task.Rclone;
import bitferry.task.Restore;
import bitferry.task.Task;
import bitferry.vault.Passwords;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import picocli.*;
import tools.jackson.databind.*;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

  @TempDir
  Path tempDir;

  private CommandLine cmd;
  private StringWriter out;
  private StringWriter err;

  @BeforeEach
  void setUp() {
    cmd = Main.createCommandLine();
    // The plain (ANSI-free) color scheme keeps the captured help output assertion-friendly
    // regardless of the platform's terminal ANSI detection
    cmd.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
  }

  // Execute the command with the terminal password acquisition seam serving the specified password
  private int executeWithPassword(String password, String... args) {
    java.util.function.Supplier<String> original = CliUtils.passwordSource;
    CliUtils.passwordSource = () -> password;
    try {
      return cmd.execute(args);
    } finally {
      CliUtils.passwordSource = original;
    }
  }

  private Manager managerFor(Path volumeDir) {
    return new Manager().restore(volumeDir);
  }

  private String volumeTag(Manager manager, Path volumeDir) {
    return manager.volumes().values().stream()
      .filter(v -> v.root().equals(Bitferry.canonicalize(volumeDir)))
      .findFirst().orElseThrow().tag();
  }

  @Test
  void versionFlagPrintsVersion() {
    int exitCode = cmd.execute("-V");
    assertEquals(0, exitCode);
    assertTrue(out.toString().contains("bitferry " + Bitferry.VERSION));
  }

  @Test
  void helpFlagPrintsUsage() {
    int exitCode = cmd.execute("--help");
    assertEquals(0, exitCode);
    assertTrue(out.toString().contains("Usage: bitferry"));
    assertTrue(out.toString().contains("info"));
    assertTrue(out.toString().contains("create"));
    assertTrue(out.toString().contains("delete"));
    assertTrue(out.toString().contains("execute"));
  }

  @Test
  void helpPagesPrintProfilesOnlyInVerboseMode() {
    // Non-verbose help: mentions profiles inline but does not dump profile contents
    CommandLine backupCmd = Main.createCommandLine();
    StringWriter backupOut = new StringWriter();
    backupCmd.setOut(new PrintWriter(backupOut));
    assertEquals(0, backupCmd.execute("create", "task", "backup", "--help"));
    String bHelp = backupOut.toString();
    assertTrue(bHelp.contains("profile (@default)"));
    assertTrue(bHelp.contains("profile (@default, @full)"));
    assertTrue(bHelp.contains("-M, --maintain"));
    assertFalse(bHelp.contains("@default: --no-cache"));
    assertFalse(bHelp.contains("@full:    --no-cache"));
    assertFalse(bHelp.contains("--prune"));

    // Verbose help (--help -v): dumps profile contents
    CommandLine backupVerboseCmd = Main.createCommandLine();
    StringWriter backupVerboseOut = new StringWriter();
    backupVerboseCmd.setOut(new PrintWriter(backupVerboseOut));
    assertEquals(0, backupVerboseCmd.execute("create", "task", "backup", "--help", "-v"));
    String bVerboseHelp = backupVerboseOut.toString();
    assertTrue(bVerboseHelp.contains("@full:    --read-data"));
    assertTrue(bVerboseHelp.contains("@default:"));
    assertTrue(bVerboseHelp.contains("@default: --prune"));
    assertTrue(bVerboseHelp.contains("7d,5"));
    assertFalse(bVerboseHelp.contains("--no-cache"));

    // Verbose help with combined flag (-vh)
    CommandLine backupCombinedCmd = Main.createCommandLine();
    StringWriter backupCombinedOut = new StringWriter();
    backupCombinedCmd.setOut(new PrintWriter(backupCombinedOut));
    assertEquals(0, backupCombinedCmd.execute("create", "task", "backup", "-vh"));
    assertTrue(backupCombinedOut.toString().contains("@default: --prune"));
    assertFalse(backupCombinedOut.toString().contains("--no-cache"));

    // Verbose help with option before subcommand (-v create task backup -h)
    CommandLine backupParentVerboseCmd = Main.createCommandLine();
    StringWriter backupParentVerboseOut = new StringWriter();
    backupParentVerboseCmd.setOut(new PrintWriter(backupParentVerboseOut));
    assertEquals(0, backupParentVerboseCmd.execute("-v", "create", "task", "backup", "-h"));
    assertTrue(backupParentVerboseOut.toString().contains("@default: --prune"));
    assertFalse(backupParentVerboseOut.toString().contains("--no-cache"));

    // Synchronize help: non-verbose vs verbose
    CommandLine syncCmd = Main.createCommandLine();
    StringWriter syncOut = new StringWriter();
    syncCmd.setOut(new PrintWriter(syncOut));
    assertEquals(0, syncCmd.execute("create", "task", "synchronize", "--help"));
    String sHelp = syncOut.toString();
    assertTrue(sHelp.contains("profile (@default, @extended)"));
    assertFalse(sHelp.contains("@default:  --crypt-filename-encoding"));

    CommandLine syncVerboseCmd = Main.createCommandLine();
    StringWriter syncVerboseOut = new StringWriter();
    syncVerboseCmd.setOut(new PrintWriter(syncVerboseOut));
    assertEquals(0, syncVerboseCmd.execute("create", "task", "synchronize", "--help", "--verbose"));
    String sVerboseHelp = syncVerboseOut.toString();
    assertTrue(sVerboseHelp.contains("@default:  --crypt-filename-encoding base32"));
    assertTrue(sVerboseHelp.contains("@extended: --crypt-filename-encoding base32768"));
    assertTrue(sVerboseHelp.contains("@default: --metadata"));

    // Restore help: non-verbose vs verbose
    CommandLine restoreCmd = Main.createCommandLine();
    StringWriter restoreOut = new StringWriter();
    restoreCmd.setOut(new PrintWriter(restoreOut));
    assertEquals(0, restoreCmd.execute("create", "task", "restore", "--help"));
    String rHelp = restoreOut.toString();
    assertTrue(rHelp.contains("profile (@default)"));
    assertFalse(rHelp.contains("@default: --sparse"));

    CommandLine restoreVerboseCmd = Main.createCommandLine();
    StringWriter restoreVerboseOut = new StringWriter();
    restoreVerboseCmd.setOut(new PrintWriter(restoreVerboseOut));
    assertEquals(0, restoreVerboseCmd.execute("create", "task", "restore", "-v", "--help"));
    String rVerboseHelp = restoreVerboseOut.toString();
    assertTrue(rVerboseHelp.contains("@default: --sparse"));
    assertFalse(rVerboseHelp.contains("--no-cache"));
  }

  @Test
  void profilesAnnotationResolvesAndRendersWithoutHardcodedCommandMappings() {
    @CommandLine.Command(name = "dummy", mixinStandardHelpOptions = true)
    class DummyCommand implements Runnable {
      @CommandLine.Option(names = {"-v", "--verbose"})
      boolean verbose;

      @Profiles(value = Backup.class, field = "CHECK")
      @CommandLine.Option(names = "--custom-check")
      String check;

      @Profiles(Rclone.class)
      @CommandLine.Option(names = "--custom-exec")
      String exec;

      @Override
      public void run() {}
    }

    CommandLine dummyCmd = new CommandLine(new DummyCommand());
    dummyCmd.setHelpFactory(VerboseHelp.INSTANCE);
    StringWriter verboseOut = new StringWriter();
    dummyCmd.setOut(new PrintWriter(verboseOut));
    assertEquals(0, dummyCmd.execute("--help", "-v"));
    String helpText = verboseOut.toString();
    assertTrue(helpText.contains("@full:    --read-data"));
    assertTrue(helpText.contains("@default:"));
    assertTrue(helpText.contains("@default: --metadata"));
    assertFalse(helpText.contains("--no-cache"));
  }

  @Test
  void noArgumentsRunsInfoAction() {
    int exitCode = cmd.execute();
    assertEquals(0, exitCode);
    String output = out.toString();
    assertTrue(output.contains("No volumes or tasks found") || output.contains("Intact volumes"));
  }

  @Test
  void createVolumeAndShowLifecycle() throws IOException {
    Path volDir = tempDir.resolve("my_volume");
    Files.createDirectories(volDir);

    int exitCode = cmd.execute("create", "volume", volDir.toString());
    assertEquals(0, exitCode);
    assertTrue(Files.isRegularFile(volDir.resolve(Bitferry.STORAGE)));

    Manager manager = new Manager();
    manager.restore(volDir);
    assertTrue(manager.volumes().values().stream().anyMatch(v -> v.root().equals(Bitferry.canonicalize(volDir))));
  }

  @Test
  void executeNotesTheTasksSkippedForTheAbsentVolumes() throws IOException {
    // Two volumes with a task between them; the execution then probes only the first
    // one (the removable media peer is unplugged), so the task goes stale
    Path firstDir = tempDir.resolve("skip_first");
    Path secondDir = tempDir.resolve("skip_second");
    Files.createDirectories(firstDir);
    Files.createDirectories(secondDir);
    assertEquals(0, cmd.execute("create", "volume", firstDir.toString()));
    assertEquals(0, cmd.execute("create", "volume", secondDir.toString()));
    Manager m = managerFor(firstDir);
    String ftag = volumeTag(m, firstDir);
    String stag = volumeTag(managerFor(secondDir), secondDir);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("--path", firstDir + "," + secondDir, "create", "task", "synchronize", ":" + ftag + ":", ":" + stag + ":backup"),
      () -> "stderr: " + err.toString());

    // The absent peer volume makes the task stale: it is neither executed nor
    // silently forgotten - the skip is noted on the stderr
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code = cmd.execute("--path", firstDir.toString(), "-n", "execute");
    assertEquals(0, code, () -> "stderr: " + err.toString());
    assertTrue(err.toString().contains("task(s) skipped"), () -> "stderr: " + err.toString());
  }

  @Test
  void createAndExecuteTaskLifecycle() throws IOException {
    Path volDir = tempDir.resolve("src_vol");
    Path dstDir = tempDir.resolve("dst_dir");
    Files.createDirectories(volDir);
    Files.createDirectories(dstDir);

    // Create volume using alias 'c v'
    int code1 = cmd.execute("c", "v", volDir.toString());
    assertEquals(0, code1);

    Manager m = new Manager().restore(volDir);
    String vtag = m.volumes().values().stream()
      .filter(v -> v.root().equals(Bitferry.canonicalize(volDir)))
      .findFirst().orElseThrow().tag();

    // Create copy task with --path
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code2 = cmd.execute("--path", volDir.toString(), "create", "task", "copy", ":" + vtag + ":", "local:" + dstDir);
    assertEquals(0, code2, () -> "stderr: " + err.toString());

    // Verify task is saved in the volume
    Manager reloaded = new Manager().restore(volDir);
    var task = reloaded.tasks().values().stream()
      .filter(t -> t.refersVolume(vtag))
      .findFirst().orElseThrow();
    assertEquals("copy", task.operation());

    // Delete task via CLI
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code3 = cmd.execute("--path", volDir.toString(), "delete", "task", task.tag());
    assertEquals(0, code3, () -> "stderr: " + err.toString());

    // The tombstone persists in the storage until the garbage collection removes it,
    // while the task itself is invisible for all the operations
    Manager afterDelete = new Manager().restore(volDir);
    Task leftover = afterDelete.task(task.tag());
    assertNotNull(leftover);
    assertTrue(leftover.isDeleted());
    assertTrue(afterDelete.selectTasks().isEmpty());
    // The garbage collection physically removes the tombstone upon the following commit
    afterDelete.commit();
    Manager finalState = new Manager().restore(volDir);
    assertTrue(finalState.tasks().values().stream().noneMatch(t -> t.tag().equals(task.tag())));
  }

  @Test
  void createBackupTaskLifecycle() throws IOException {
    Path volDir = tempDir.resolve("backup_vol");
    Path repoDir = tempDir.resolve("repo_dir");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir);

    // Create volume
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));

    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create backup task obtaining the password from the piped standard input
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("Our secret password", "--path", volDir.toString(), "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // The obscured password token is stored within the volume storage
    JsonNode storage = Storage.readJson(volDir.resolve(Bitferry.STORAGE));
    JsonNode vault = storage.get("vault");
    assertNotNull(vault);
    String token = vault.properties().iterator().next().getValue().asString();
    assertNotEquals("Our secret password", token);

    // The restored task retrieves the password from the volume vault
    Manager reloaded = managerFor(volDir);
    Backup task = (Backup) reloaded.tasks().values().stream()
      .filter(t -> t.refersVolume(vtag))
      .findFirst().orElseThrow();
    assertEquals("backup", task.operation());
    assertEquals("Our secret password", task.password());
  }

  @Test
  void createRestoreTaskLifecycle() throws IOException {
    Path volDir = tempDir.resolve("restore_vol");
    Files.createDirectories(volDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("restore-pass", "--path", volDir.toString(), "create", "task", "restore", ":" + vtag + ":repo", ":" + vtag + ":restored");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // The password token travels in the volume hosting the unencrypted (destination) end
    Manager reloaded = managerFor(volDir);
    Restore task = (Restore) reloaded.tasks().values().stream()
      .filter(t -> t.refersVolume(vtag))
      .findFirst().orElseThrow();
    assertEquals("restore", task.operation());
    assertEquals("restore-pass", task.password());
  }

  @Test
  void createEncryptedTaskLifecycle() throws IOException {
    Path volDir = tempDir.resolve("enc_vol");
    Path encDir = tempDir.resolve("enc_dir");
    Files.createDirectories(volDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // The encrypted task is created with the password piped through the standard input
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("crypt-secret", "--path", volDir.toString(), "create", "task", "synchronize", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // The descriptor carries the encrypt section while the vault holds the obscured token
    JsonNode storage = Storage.readJson(volDir.resolve(Bitferry.STORAGE));
    JsonNode taskData = storage.get("tasks").get(0);
    assertEquals("synchronize", taskData.get("operation").asString());
    assertTrue(taskData.get("rclone").has("encrypt"));
    assertNotNull(storage.get("vault"));

    // The restored task keeps the encryption mode and the password
    Manager reloaded = managerFor(volDir);
    Rclone task = (Rclone) reloaded.tasks().values().stream()
      .filter(t -> t.refersVolume(vtag))
      .findFirst().orElseThrow();
    assertInstanceOf(Rclone.Encrypt.class, task.encryption());
    assertEquals("crypt-secret", task.encryption().password());
    assertEquals("encrypt+synchronize", task.displayOperation());

    // Printing task info surfaces the encrypt+synchronize operation status
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info"));
    assertTrue(out.toString().contains("encrypt+synchronize"));
  }

  @Test
  void encryptedTaskWithLocalSourceIsRejected() throws IOException {
    Path volDir = tempDir.resolve("enc_rej_vol");
    Path plainDir = tempDir.resolve("plain_dir");
    Files.createDirectories(volDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // The unencrypted (source) end of the encrypt task must reside within a volume
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("crypt-secret", "--path", volDir.toString(), "create", "task", "synchronize", "local:" + plainDir, ":" + vtag + ":backup", "-e");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("encryption requires the unencrypted endpoint to reside within a Bitferry volume"));
  }

  @Test
  void backupCreationWithoutPasswordIsRejected() throws IOException {
    Path volDir = tempDir.resolve("nopass_vol");
    Files.createDirectories(volDir);
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // The password source yielding nothing is rejected
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword(null, "--path", volDir.toString(), "create", "task", "backup", ":" + vtag + ":", volDir.resolve("repo").toString(), "-a");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("password must be specified"), () -> "stderr: " + err.toString());
  }

  @Test
  void bareVolumeRootPathsBindToTheVolumes() throws IOException {
    // The scenario of the bare volume root paths fed as the task endpoints
    // (e.g. the directories discovered through BITFERRY_PATH)
    Path srcVol = tempDir.resolve("bind_src");
    Path dstVol = tempDir.resolve("bind_dst");
    Files.createDirectories(srcVol);
    Files.createDirectories(dstVol);
    assertEquals(0, cmd.execute("create", "volume", srcVol.toString()));
    assertEquals(0, cmd.execute("create", "volume", dstVol.toString()));
    String srcTag = volumeTag(managerFor(srcVol), srcVol);
    String dstTag = volumeTag(managerFor(dstVol), dstVol);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = cmd.execute("--path", srcVol.toString(), "--path", dstVol.toString(),
      "create", "task", "update", "-v", srcVol.toString(), dstVol.toString());
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // The task is persisted redundantly within both volume storages
    // with the volume-bound endpoints
    Manager fromSrc = managerFor(srcVol);
    Task task = fromSrc.tasks().values().stream()
      .filter(t -> t.operation().equals("update"))
      .findFirst().orElseThrow();
    assertInstanceOf(VolumeEndpoint.class, task.source());
    assertInstanceOf(VolumeEndpoint.class, task.destination());
    assertEquals(srcTag, ((VolumeEndpoint) task.source()).vtag());
    assertEquals(".", ((VolumeEndpoint) task.source()).path());
    assertEquals(dstTag, ((VolumeEndpoint) task.destination()).vtag());
    assertTrue(managerFor(dstVol).tasks().values().stream()
      .anyMatch(t -> t.tag().equals(task.tag())));
  }

  @Test
  void orphanLocalTaskIsStillRejected() throws IOException {
    // The local paths outside any volume leave the task nowhere to be persisted
    Path volDir = tempDir.resolve("orphan_vol");
    Path plainA = tempDir.resolve("plain_a");
    Path plainB = tempDir.resolve("plain_b");
    Files.createDirectories(volDir);
    Files.createDirectories(plainA);
    Files.createDirectories(plainB);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = cmd.execute("create", "task", "update", "local:" + plainA, "local:" + plainB);
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("does not belong to any registered volume"));
  }

  @Test
  void duplicateTaskCreationIsRejectedWithTheExistingTag() throws IOException {
    Path volDir = tempDir.resolve("dup_vol");
    Path dstDir = tempDir.resolve("dup_dst");
    Files.createDirectories(volDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    String[] creation = {"--path", volDir.toString(), "create", "task", "copy", ":" + vtag + ":", "local:" + dstDir};
    assertEquals(0, cmd.execute(creation), () -> "stderr: " + err.toString());
    String tag = managerFor(volDir).tasks().values().stream().findFirst().orElseThrow().tag();

    // The repeated creation is rejected carrying the covering task's tag
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertNotEquals(0, cmd.execute(creation));
    assertTrue(err.toString().contains("task #" + tag + " already covers"), () -> "stderr: " + err.toString());

    // The re-creation succeeds once the covering task is deleted
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", tag), () -> "stderr: " + err.toString());
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute(creation), () -> "stderr: " + err.toString());
  }

  @Test
  void invalidSubcommandReturnsCode2() {
    int exitCode = cmd.execute("nonexistent-command");
    assertEquals(2, exitCode);
  }

  @Test
  void shortAliasesWorkIdentically() throws IOException {
    Path volDir = tempDir.resolve("alias_vol");
    Files.createDirectories(volDir);

    // 'c v' alias
    assertEquals(0, cmd.execute("c", "v", volDir.toString()));
    assertTrue(Files.isRegularFile(volDir.resolve(Bitferry.STORAGE)));

    // The 'i' alias
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info"));
    assertEquals(0, cmd.execute("i"));

    // The 'c t b' alias creates the backup task (attached, keeping the test hermetic)
    String vtag = volumeTag(managerFor(volDir), volDir);
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, executeWithPassword("alias-pass", "--path", volDir.toString(), "c", "t", "b", ":" + vtag + ":", "local:" + tempDir.resolve("alias_repo"), "-a"), () -> "stderr: " + err.toString());

    // The 'c t r' alias creates the restore task
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, executeWithPassword("alias-pass", "--path", volDir.toString(), "c", "t", "r", ":" + vtag + ":repo", ":" + vtag + ":restored"), () -> "stderr: " + err.toString());

    Manager reloaded = managerFor(volDir);
    Set<String> operations = reloaded.tasks().values().stream().map(Task::operation).collect(java.util.stream.Collectors.toSet());
    assertTrue(operations.contains("backup"));
    assertTrue(operations.contains("restore"));
  }

  @Test
  void commonOptionsMergeCombinesPaths() {
    CommonOptions parent = new CommonOptions();
    parent.paths = List.of(Path.of("/parent/path"));
    CommonOptions child = new CommonOptions();
    child.paths = List.of(Path.of("/child/path"));
    CommonOptions merged = child.merge(parent);
    assertEquals(List.of(Path.of("/parent/path"), Path.of("/child/path")), merged.paths);
  }

  @Test
  void createEncryptedTaskConformanceRefusesIncompatibleBackend() throws Exception {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for integration check");
    Path volDir = tempDir.resolve("cli_enc_vol");
    Path plainDir = tempDir.resolve("cli_enc_plain");
    Path encDir = tempDir.resolve("cli_enc_dest");
    Files.createDirectories(volDir);
    Files.createDirectories(plainDir);
    Files.writeString(plainDir.resolve("test.txt"), "hello cli");

    // Pre-create encrypted repository with password A
    ProcessBuilder pb = new ProcessBuilder(
      "rclone", "sync", plainDir.toString(), ":crypt:",
      "--crypt-remote", encDir.toString(),
      "--crypt-filename-encryption", "standard",
      "--crypt-filename-encoding", "base32",
      "--config", "/dev/null"
    );
    pb.environment().put("RCLONE_CRYPT_PASSWORD", Passwords.obscure("passwordA"));
    Process p = pb.start();
    assertEquals(0, p.waitFor());

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Creation with wrong password fails and is not persisted
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("wrongPassword", "--path", volDir.toString(), "create", "task", "synchronize", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(1, code);
    assertTrue(err.toString().contains("the existing encrypted content in '" + encDir + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"));

    Manager reloaded = managerFor(volDir);
    assertTrue(reloaded.tasks().isEmpty());

    // 2. Creation with correct password succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("passwordA", "--path", volDir.toString(), "create", "task", "synchronize", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    reloaded = managerFor(volDir);
    assertEquals(1, reloaded.tasks().size());
  }

  @Test
  void createResticTaskConformanceRefusesWrongPassword() throws Exception {
    Assumptions.assumeTrue(available("restic"), "restic not available for integration check");
    Path volDir = tempDir.resolve("cli_restic_vol");
    Path repoDir = tempDir.resolve("cli_restic_repo");
    Files.createDirectories(volDir);

    ProcessBuilder pb = new ProcessBuilder("restic", "init", "-r", repoDir.toString());
    pb.environment().put("RESTIC_PASSWORD", "correctSecret");
    Process p = pb.start();
    assertEquals(0, p.waitFor());

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Wrong password backup is refused
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("badSecret", "--path", volDir.toString(), "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(1, code);
    assertTrue(err.toString().contains("the existing repository at '" + repoDir + "' does not accept the provided password"));
    assertTrue(err.toString().contains("--attach"));
    assertTrue(managerFor(volDir).tasks().isEmpty());

    // 2. Wrong password with -a/--attach is refused
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("badSecret", "--path", volDir.toString(), "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a");
    assertEquals(1, code);
    assertTrue(err.toString().contains("does not accept the provided password"));
    assertTrue(managerFor(volDir).tasks().isEmpty());

    // 3. Correct password backup succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("correctSecret", "--path", volDir.toString(), "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err.toString());
    assertEquals(1, managerFor(volDir).tasks().size());

    // Delete the backup task to allow re-creating over the same endpoints with --force
    Task existingTask = managerFor(volDir).tasks().values().iterator().next();
    cmd = Main.createCommandLine();
    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", existingTask.tag()));

    // 4. Force (--force) proceeds over existing repo with a new password
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("brandNewSecret", "--force", "--path", volDir.toString(), "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // 5. Restore with bad password is refused
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("badSecret", "--path", volDir.toString(), "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restored");
    assertEquals(1, code);
    assertTrue(err.toString().contains("does not accept the provided password"));

    // 6. Restore with correct password ("brandNewSecret") succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("brandNewSecret", "--path", volDir.toString(), "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restored");
    assertEquals(0, code, () -> "stderr: " + err.toString());
  }

  @Test
  void infoTaskPrintsCommandLinesForSpecifiedTasks() throws Exception {
    Path volDir = tempDir.resolve("cli_info_vol");
    Path dataDir = tempDir.resolve("cli_info_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    // Create volume
    CommandLine cmd = Main.createCommandLine();
    assertEquals(0, cmd.execute("c", "v", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create synchronize task
    cmd = Main.createCommandLine();
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "create", "task", "synchronize", ":" + vtag + ":", "local:" + dataDir, "--include", "*.txt"));

    Manager m = managerFor(volDir);
    Task task = m.tasks().values().iterator().next();
    String ttag = task.tag();

    // 1. info task <tag> prints rclone sync command
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err1 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err1));
    int code = cmd.execute("--path", volDir.toString(), "info", "task", ttag);
    assertEquals(0, code, () -> "stderr: " + err1.toString());
    String output = out.toString().trim();
    assertTrue(output.contains("rclone sync"), () -> "expected rclone sync in: " + output);
    assertTrue(output.contains("+ *.txt"), () -> "expected + *.txt in: " + output);
    assertTrue(output.contains("- " + Bitferry.STORAGE), () -> "expected storage exclude in: " + output);
    assertTrue(output.contains("# Task #" + ttag + ": synchronize"));
    assertTrue(output.contains("Status:        ✓ intact"));
    assertTrue(output.contains("Backend:       rclone"));
    assertTrue(output.contains("Operation:     synchronize"));
    assertTrue(output.contains("Encryption:    none"));
    assertTrue(output.contains("# Filters"));
    assertTrue(output.contains("Include:       *.txt"));
    assertTrue(output.contains("# Commands"));
    assertTrue(output.contains("Execute:"));
    assertTrue(output.contains("Maintain:"));
    assertTrue(output.contains("rclone check"));

    // 2. Alias: i t <tag>
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err2 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err2));
    code = cmd.execute("--path", volDir.toString(), "i", "t", ttag);
    assertEquals(0, code, () -> "stderr: " + err2.toString());
    assertEquals(output, out.toString().trim());

    // 2b. Bare commands mode: info task <tag> -c and --commands
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info", "task", ttag, "-c"));
    String bareOutput = out.toString().trim();
    assertTrue(bareOutput.startsWith("rclone sync"));
    assertFalse(bareOutput.contains("# Task"));
    assertFalse(bareOutput.contains("# Filters"));
    assertFalse(bareOutput.contains("# Commands"));

    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info", "task", ttag, "--commands"));
    assertEquals(bareOutput, out.toString().trim());

    // 3. info task with no tags requires TAGS parameter (exit code 2)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err3 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err3));
    code = cmd.execute("--path", volDir.toString(), "info", "task");
    assertEquals(2, code);
    assertTrue(err3.toString().contains("Missing required parameter: 'TAGS'"));

    // 4. info task with non-existent tag fails with exit code 1
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("--path", volDir.toString(), "info", "task", "nonexistent");
    assertEquals(1, code);
    assertTrue(err.toString().contains("no entries matching specified tag part <nonexistent>"));

    // 5. Existing 'info' and 'i' without subcommand still print intact volumes/tasks
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("--path", volDir.toString(), "info");
    assertEquals(0, code);
    assertTrue(out.toString().contains("# Intact volumes"));
    assertTrue(out.toString().contains("# Intact tasks"));

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("--path", volDir.toString(), "i");
    assertEquals(0, code);
    assertTrue(out.toString().contains("# Intact volumes"));
    assertTrue(out.toString().contains("# Intact tasks"));
  }

  @Test
  void executeCommandAndExecutionOptions() throws Exception {
    Path volDir = tempDir.resolve("cli_exec_vol");
    Path dataDir = tempDir.resolve("cli_exec_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    // Create volume
    CommandLine cmd = Main.createCommandLine();
    assertEquals(0, cmd.execute("c", "v", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create task with --execute option
    cmd = Main.createCommandLine();
    StringWriter out = new StringWriter();
    StringWriter err1 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err1));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "create", "task", "synchronize", ":" + vtag + ":", "local:" + dataDir, "--execute", "--transfers,4"),
      () -> "stderr: " + err1.toString());

    Manager m = managerFor(volDir);
    Task task = m.tasks().values().iterator().next();
    assertEquals(List.of("--transfers", "4"), Task.strings(task.serialize().get("rclone").get("execute"), false));

    // Execute with 'execute' command (-n simulation)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err2 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err2));
    int code = cmd.execute("--path", volDir.toString(), "-n", "execute", task.tag());
    assertEquals(0, code, () -> "stderr: " + err2.toString());
    assertTrue(out.toString().contains("All 1 task(s) executed successfully."));

    // Execute with 'x' alias (-n simulation)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err3 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err3));
    code = cmd.execute("--path", volDir.toString(), "-n", "x", task.tag());
    assertEquals(0, code, () -> "stderr: " + err3.toString());
    assertTrue(out.toString().contains("All 1 task(s) executed successfully."));
  }

  @Test
  void createBackupWithAtProfilesAndShorthands() throws IOException {
    Path volDir = tempDir.resolve("backup_profiles_vol");
    Path repoDir = tempDir.resolve("backup_profiles_repo");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Create backup with explicit @-prefixed profiles: -F @default -C @full
    int code = executeWithPassword("pass1", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a", "-F", "@default", "-C", "@full");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Backup task1 = (Backup) m.tasks().values().iterator().next();
    assertEquals(Backup.FORGET.get("default"), Task.strings(task1.serialize().get("restic").get("forget"), false));
    assertEquals(Backup.CHECK.get("full"), Task.strings(task1.serialize().get("restic").get("check"), false));
    assertNull(task1.serialize().get("restic").get("execute"));

    // Delete task1 to test shorthand creation cleanly
    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", task1.tag()));

    // 2. Create backup with shorthands -f -c
    code = executeWithPassword("pass1", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a", "-f", "-c");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task2 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals(Backup.FORGET.get("default"), Task.strings(task2.serialize().get("restic").get("forget"), false));
    assertEquals(Backup.CHECK.get("default"), Task.strings(task2.serialize().get("restic").get("check"), false));

    // Delete task2
    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", task2.tag()));

    // 3. Create backup with directly specified raw options without @
    code = executeWithPassword("pass1", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a",
      "-F", "--prune,--keep-daily,7", "-C", "--no-cache,--read-data", "-X", "--no-cache");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task3 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals(List.of("--prune", "--keep-daily", "7"), Task.strings(task3.serialize().get("restic").get("forget"), false));
    assertEquals(List.of("--no-cache", "--read-data"), Task.strings(task3.serialize().get("restic").get("check"), false));
    assertEquals(List.of("--no-cache"), Task.strings(task3.serialize().get("restic").get("execute"), false));
  }

  @Test
  void createRcloneWithAtProfilesAndShorthands() throws IOException {
    Path volDir = tempDir.resolve("rclone_profiles_vol");
    Path dataDir = tempDir.resolve("rclone_profiles_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Explicit profile @extended and @default execute
    int code = executeWithPassword("cryptpass", "--path", volDir.toString(),
      "create", "task", "synchronize", ":" + vtag + ":", "local:" + dataDir,
      "-E", "@extended", "-X", "@default");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Task task1 = m.tasks().values().iterator().next();
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(task1.serialize().get("rclone").get("encrypt"), false));
    assertEquals(Rclone.PROCESS.get("default"), Task.strings(task1.serialize().get("rclone").get("execute"), false));

    // Delete task1
    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", task1.tag()));

    // 2. Shorthand -e -u (extended encryption)
    code = executeWithPassword("cryptpass", "--path", volDir.toString(),
      "create", "task", "synchronize", ":" + vtag + ":", "local:" + dataDir, "-e", "-u");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Task task2 = m.selectTasks().values().iterator().next();
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(task2.serialize().get("rclone").get("encrypt"), false));

    // Delete task2
    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", task2.tag()));

    // 3. Shorthand -e (default encryption) with directly specified -X --fast-list
    code = executeWithPassword("cryptpass", "--path", volDir.toString(),
      "create", "task", "synchronize", ":" + vtag + ":", "local:" + dataDir, "-e", "-X", "--fast-list");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Task task3 = m.selectTasks().values().iterator().next();
    assertEquals(Rclone.PROFILE.get("default"), Task.strings(task3.serialize().get("rclone").get("encrypt"), false));
    assertEquals(List.of("--fast-list"), Task.strings(task3.serialize().get("rclone").get("execute"), false));
  }

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
  void verboseHelpEnablesPicocliAutoWidth() {
    var spec = Main.createCommandLine().getCommandSpec();
    var colorScheme = new picocli.CommandLine.Help.ColorScheme.Builder().build();
    VerboseHelp.INSTANCE.create(spec, colorScheme);
    assertTrue(spec.usageMessage().autoWidth());
  }

  @Test
  void createBackupWithCadenceOptions() throws IOException {
    Path volDir = tempDir.resolve("cadence_vol");
    Path repoDir = tempDir.resolve("cadence_repo");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Explicit -M 14d,10
    int code = executeWithPassword("secret1", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a", "-M", "14d,10");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Backup task1 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals("14d,10", task1.cadence().format());
    assertEquals("14d,10", task1.serialize().get("restic").get("maintain").asString());

    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", task1.tag()));

    // 2. Shorthand -m sets @default (7d,5)
    code = executeWithPassword("secret1", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a", "-m");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task2 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals("7d,5", task2.cadence().format());
    assertNull(task2.serialize().get("restic"));

    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", task2.tag()));

    // 3. Disable with -M -
    code = executeWithPassword("secret1", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a", "-M", "-");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task3 = (Backup) m.selectTasks().values().iterator().next();
    assertNull(task3.cadence());
    assertEquals("-", task3.serialize().get("restic").get("maintain").asString());

    assertEquals(0, cmd.execute("--path", volDir.toString(), "delete", "task", task3.tag()));

    // 4. Mutual exclusivity: -m and -M both specified
    code = executeWithPassword("secret1", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a", "-m", "-M", "14d");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("maintenance cadence specified twice"));
  }

  @Test
  void maintainCommandExecution() throws IOException {
    Path volDir = tempDir.resolve("maint_vol");
    Path dataDir = tempDir.resolve("maint_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create a synchronize task
    assertEquals(0, cmd.execute("--path", volDir.toString(),
      "create", "task", "synchronize", ":" + vtag + ":", "local:" + dataDir));

    Task task = managerFor(volDir).selectTasks().values().iterator().next();

    // Run maintain in simulation mode
    assertEquals(0, cmd.execute("--path", volDir.toString(), "-n", "maintain", task.tag()),
      () -> "stderr: " + err);
    assertTrue(out.toString().contains("maintained successfully") || out.toString().contains("1/1"));

    // Run maintain via alias 'm'
    out.getBuffer().setLength(0);
    assertEquals(0, cmd.execute("--path", volDir.toString(), "-n", "m", task.tag()),
      () -> "stderr: " + err);
    assertTrue(out.toString().contains("maintained successfully") || out.toString().contains("1/1"));
  }

  @Test
  void taskInfoPrintsEncryptionStatusForEncryptAndDecrypt() throws IOException {
    Path volDir = tempDir.resolve("enc_info_vol");
    Path encDir = tempDir.resolve("enc_info_data");
    Files.createDirectories(volDir);
    Files.createDirectories(encDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Create an encrypt synchronize task: :vtag: -> local:enc
    int code = executeWithPassword("pass1", "--path", volDir.toString(),
      "create", "task", "synchronize", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // 2. Create a decrypt copy task: local:enc -> :vtag:dec
    code = executeWithPassword("pass1", "--path", volDir.toString(),
      "create", "task", "copy", "local:" + encDir, ":" + vtag + ":dec", "-d");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // 3. Verify 'info' command prints encrypt+synchronize and decrypt+copy
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info"));
    String infoOutput = out.toString();
    assertTrue(infoOutput.contains("encrypt+synchronize"), () -> "Expected 'encrypt+synchronize' in info output:\n" + infoOutput);
    assertTrue(infoOutput.contains("decrypt+copy"), () -> "Expected 'decrypt+copy' in info output:\n" + infoOutput);

    // 4. Verify 'execute' command banner prints encrypt+synchronize and decrypt+copy
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "-n", "execute"));
    String execOutput = out.toString();
    assertTrue(execOutput.contains("encrypt+synchronize"), () -> "Expected 'encrypt+synchronize' in execute output:\n" + execOutput);
    assertTrue(execOutput.contains("decrypt+copy"), () -> "Expected 'decrypt+copy' in execute output:\n" + execOutput);
  }

  @Test
  void infoTaskBreakdownEncryptedRclone() throws IOException {
    Path volDir = tempDir.resolve("enc_breakdown_vol");
    Path encDir = tempDir.resolve("enc_breakdown_data");
    Files.createDirectories(volDir);
    Files.createDirectories(encDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    int code = executeWithPassword("secret123", "--path", volDir.toString(),
      "create", "task", "synchronize", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Task task = m.tasks().values().iterator().next();
    String ttag = task.tag();

    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info", "task", ttag));
    String breakdown = out.toString();

    assertTrue(breakdown.contains("# Task #" + ttag + ": encrypt+synchronize"));
    assertTrue(breakdown.contains("Status:        ✓ intact"));
    assertTrue(breakdown.contains("Backend:       rclone"));
    assertTrue(breakdown.contains("Operation:     synchronize"));
    assertTrue(breakdown.contains("Encryption:    encrypt"));
    assertTrue(breakdown.contains("Vault Volume:  #" + vtag));
    assertTrue(breakdown.contains("# Commands"));
    assertTrue(breakdown.contains("Execute:"));
    assertTrue(breakdown.contains("Maintain:"));
    assertTrue(breakdown.contains("rclone check"));

    // Bare mode emits only rclone command line
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info", "task", ttag, "-c"));
    String bare = out.toString().trim();
    assertTrue(bare.startsWith("rclone sync"));
    assertFalse(bare.contains("# Task"));
  }

  @Test
  void infoTaskBreakdownResticBackupAndRestore() throws IOException {
    Path volDir = tempDir.resolve("restic_breakdown_vol");
    Path repoDir = tempDir.resolve("restic_breakdown_repo");
    Path restoreDir = tempDir.resolve("restic_breakdown_restore");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir);
    Files.createDirectories(restoreDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Create backup task (attached mode with retention and check)
    int code = executeWithPassword("repo-pass", "--path", volDir.toString(),
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a", "-f", "-c", "--exclude", "*.tmp");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Task backupTask = m.tasks().values().stream().filter(t -> t instanceof Backup).findFirst().orElseThrow();
    String backupTag = backupTask.tag();

    // 2. Info task on backup
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info", "task", backupTag));
    String bBreakdown = out.toString();

    assertTrue(bBreakdown.contains("# Task #" + backupTag + ": backup"));
    assertTrue(bBreakdown.contains("Status:        ✓ intact"));
    assertTrue(bBreakdown.contains("Backend:       restic"));
    assertTrue(bBreakdown.contains("Operation:     backup"));
    assertTrue(bBreakdown.contains("Repository:"));
    assertTrue(bBreakdown.contains("Vault Volume:  #" + vtag));
    assertTrue(bBreakdown.contains("# Maintenance & Retention"));
    assertTrue(bBreakdown.contains("Cadence:       7d,5 (profile: @default)"));
    assertTrue(bBreakdown.contains("Status:        due"));
    assertTrue(bBreakdown.contains("Last Backup:   never"));
    assertTrue(bBreakdown.contains("Last Maint:    never"));
    assertTrue(bBreakdown.contains("Retention:     --prune --keep-within-hourly 24h"));
    assertTrue(bBreakdown.contains("Check:         @default (structural)"));
    assertTrue(bBreakdown.contains("# Filters"));
    assertTrue(bBreakdown.contains("Exclude:       *.tmp"));
    assertTrue(bBreakdown.contains("# Commands"));
    assertTrue(bBreakdown.contains("Execute:"));
    assertTrue(bBreakdown.contains("restic backup"));
    assertTrue(bBreakdown.contains("Maintain:"));
    assertTrue(bBreakdown.contains("restic check"));
    assertTrue(bBreakdown.contains("restic prune"));

    // Bare mode on backup
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info", "task", backupTag, "-c"));
    String bBare = out.toString().trim();
    assertTrue(bBare.startsWith("restic backup"));
    assertFalse(bBare.contains("# Task"));

    // 3. Create restore task
    code = executeWithPassword("repo-pass", "--path", volDir.toString(),
      "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restore");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Task restoreTask = m.tasks().values().stream().filter(t -> t instanceof Restore).findFirst().orElseThrow();
    String restoreTag = restoreTask.tag();

    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("--path", volDir.toString(), "info", "task", restoreTag));
    String rBreakdown = out.toString();

    assertTrue(rBreakdown.contains("# Task #" + restoreTag + ": restore"));
    assertTrue(rBreakdown.contains("Backend:       restic"));
    assertTrue(rBreakdown.contains("Operation:     restore"));
    assertTrue(rBreakdown.contains("Repository:"));
    assertTrue(rBreakdown.contains("Target:"));
    assertTrue(rBreakdown.contains("Vault Volume:  #" + vtag));
    assertTrue(rBreakdown.contains("# Commands"));
    assertTrue(rBreakdown.contains("Execute:"));
    assertTrue(rBreakdown.contains("restic restore"));
    assertTrue(rBreakdown.contains("Maintain:"));
    assertTrue(rBreakdown.contains("restic check"));
  }
}
