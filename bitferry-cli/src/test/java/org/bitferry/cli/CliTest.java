package org.bitferry.cli;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import org.bitferry.vault.*;
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
  private String originalUserHome;

  @BeforeEach
  void setUp() {
    originalUserHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    cmd = Main.createCommandLine();
    // The plain (ANSI-free) color scheme keeps the captured help output assertion-friendly
    // regardless of the platform's terminal ANSI detection
    cmd.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
  }

  @AfterEach
  void tearDown() {
    if (originalUserHome != null) {
      System.setProperty("user.home", originalUserHome);
    }
    System.clearProperty("bitferry.path");
  }

  // Register the extra volume probe roots for the subsequent CLI runs: --path is
  // declared on the bare modify command only, so the other commands pick their
  // extra roots through the bitferry.path system property (the same channel the
  // production restoreManager plumbing reads)
  private void probeRoot(Path... dirs) {
    String current = System.getProperty("bitferry.path");
    String addition = java.util.Arrays.stream(dirs).map(Path::toString)
      .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    System.setProperty("bitferry.path", current == null || current.isBlank()
      ? addition
      : current + File.pathSeparator + addition);
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
    String help = out.toString();
    assertTrue(help.contains("Usage: bitferry"));
    assertTrue(help.contains("info"));
    assertTrue(help.contains("create"));
    assertTrue(help.contains("delete"));
    assertTrue(help.contains("execute"));
    int projectHome = help.indexOf("Project home: https://bitferry.org");
    int description = help.indexOf("File synchronization/backup automation tool.");
    assertTrue(projectHome >= 0);
    assertTrue(description > projectHome, "the project home must precede the description");
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
    assertTrue(bHelp.replaceAll("\\s+", " ").contains("profile (@default, @full)"));
    assertTrue(bHelp.contains("--serve"));
    assertFalse(bHelp.contains("@default: --no-cache"));
    assertFalse(bHelp.contains("@full:    --no-cache"));
    assertFalse(bHelp.contains("--keep-within-hourly"));

    // Verbose help (--help -v): dumps profile contents
    CommandLine backupVerboseCmd = Main.createCommandLine();
    StringWriter backupVerboseOut = new StringWriter();
    backupVerboseCmd.setOut(new PrintWriter(backupVerboseOut));
    assertEquals(0, backupVerboseCmd.execute("create", "task", "backup", "--help", "-v"));
    String bVerboseHelp = backupVerboseOut.toString();
    assertTrue(bVerboseHelp.contains("@full:    --read-data"));
    assertTrue(bVerboseHelp.contains("@default:"));
    assertTrue(bVerboseHelp.contains("@default: --keep-within-hourly"));
    assertTrue(bVerboseHelp.contains("7d,5"));
    assertFalse(bVerboseHelp.contains("--no-cache"));

    // Verbose help with combined flag (-vh)
    CommandLine backupCombinedCmd = Main.createCommandLine();
    StringWriter backupCombinedOut = new StringWriter();
    backupCombinedCmd.setOut(new PrintWriter(backupCombinedOut));
    assertEquals(0, backupCombinedCmd.execute("create", "task", "backup", "-vh"));
    assertTrue(backupCombinedOut.toString().contains("@default: --keep-within-hourly"));
    assertFalse(backupCombinedOut.toString().contains("--no-cache"));

    // Verbose help with option before subcommand (-v create task backup -h)
    CommandLine backupParentVerboseCmd = Main.createCommandLine();
    StringWriter backupParentVerboseOut = new StringWriter();
    backupParentVerboseCmd.setOut(new PrintWriter(backupParentVerboseOut));
    assertEquals(0, backupParentVerboseCmd.execute("-v", "create", "task", "backup", "-h"));
    assertTrue(backupParentVerboseOut.toString().contains("@default: --keep-within-hourly"));
    assertFalse(backupParentVerboseOut.toString().contains("--no-cache"));

    // Replicate help: non-verbose vs verbose
    CommandLine syncCmd = Main.createCommandLine();
    StringWriter syncOut = new StringWriter();
    syncCmd.setOut(new PrintWriter(syncOut));
    assertEquals(0, syncCmd.execute("create", "task", "replicate", "--help"));
    String sHelp = syncOut.toString();
    assertTrue(sHelp.replaceAll("\\s+", " ").contains("profile (@default, @extended)"));
    assertFalse(sHelp.contains("@default:  --crypt-filename-encoding"));

    CommandLine syncVerboseCmd = Main.createCommandLine();
    StringWriter syncVerboseOut = new StringWriter();
    syncVerboseCmd.setOut(new PrintWriter(syncVerboseOut));
    assertEquals(0, syncVerboseCmd.execute("create", "task", "replicate", "--help", "--verbose"));
    String sVerboseHelp = syncVerboseOut.toString();
    assertTrue(sVerboseHelp.contains("@default:  --crypt-filename-encoding base32"));
    assertTrue(sVerboseHelp.contains("@extended: --crypt-filename-encoding base32768"));
    assertTrue(sVerboseHelp.contains("@default: --metadata"));

    // The legacy names are gone ('mirror'/'m'/'synchronize'/'sync' for replicate,
    // 'bisync' for equalize): the unknown names are rejected as subcommands, and
    // 's' is the one short alias of replicate
    for (String legacy : List.of("mirror", "m", "synchronize", "sync", "bisync")) {
      CommandLine goneCmd = Main.createCommandLine();
      StringWriter goneErr = new StringWriter();
      goneCmd.setErr(new PrintWriter(goneErr));
      assertNotEquals(0, goneCmd.execute("create", "task", legacy), legacy);
    }
    CommandLine shortSyncCmd = Main.createCommandLine();
    StringWriter shortSyncOut = new StringWriter();
    shortSyncCmd.setOut(new PrintWriter(shortSyncOut));
    assertEquals(0, shortSyncCmd.execute("create", "task", "s", "--help"));
    assertEquals(sHelp, shortSyncOut.toString());

    // Restore help: non-verbose vs verbose
    CommandLine restoreCmd = Main.createCommandLine();
    StringWriter restoreOut = new StringWriter();
    restoreCmd.setOut(new PrintWriter(restoreOut));
    assertEquals(0, restoreCmd.execute("create", "task", "restore", "--help"));
    String rHelp = restoreOut.toString();
    assertTrue(rHelp.contains("profile (@default)"));
    assertFalse(rHelp.contains("@default: --sparse"));
    // The 'r' alias stays reserved for the restore task (identical help)
    CommandLine shortRestoreCmd = Main.createCommandLine();
    StringWriter shortRestoreOut = new StringWriter();
    shortRestoreCmd.setOut(new PrintWriter(shortRestoreOut));
    assertEquals(0, shortRestoreCmd.execute("create", "task", "r", "--help"));
    assertEquals(rHelp, shortRestoreOut.toString());

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
    probeRoot(volDir);
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
    // Both volumes are probed while wiring the task between them
    probeRoot(firstDir, secondDir);
    Manager m = managerFor(firstDir);
    String ftag = volumeTag(m, firstDir);
    String stag = volumeTag(managerFor(secondDir), secondDir);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("create", "task", "replicate", ":" + ftag + ":", ":" + stag + ":backup"),
      () -> "stderr: " + err.toString());

    // The execution probes the first root only (the removable media peer is unplugged),
    // so the task goes stale: in normal mode nothing is printed to stderr;
    // in verbose mode (-v) the skipped tasks are surfaced
    System.setProperty("bitferry.path", firstDir.toString());
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code = cmd.execute("-n", "execute");
    assertEquals(0, code, () -> "stderr: " + err.toString());
    assertFalse(err.toString().contains("task(s) skipped"), () -> "stderr: " + err.toString());

    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("-n", "-v", "execute");
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
    probeRoot(volDir);
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
    int code2 = cmd.execute("create", "task", "copy", ":" + vtag + ":", "local:" + dstDir);
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
    // The deletion is gated behind --force (the irreversible tombstone propagation
    // and the fuzzy substring selection): the un-forced invocation refuses and the
    // task stays registered
    assertNotEquals(0, cmd.execute("delete", "task", task.tag()));
    assertTrue(err.toString().contains("--force"), () -> "stderr: " + err.toString());
    assertNotNull(managerFor(volDir).task(task.tag()));
    int code3 = cmd.execute("delete", "--force", "task", task.tag());
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
    probeRoot(volDir);

    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create backup task obtaining the password from the piped standard input
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("Our secret password", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // The obscured password token is stored within the volume storage as JSON
    JsonNode storage = Storage.readJson(volDir.resolve(Bitferry.STORAGE));
    JsonNode vault = storage.get("vault");
    assertNotNull(vault);
    String token = vault.properties().iterator().next().getValue().get("password").asString();
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("restore-pass", "create", "task", "restore", ":" + vtag + ":repo", ":" + vtag + ":restored");
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
  void createBackupTaskWithKeyringRequestFallsBackGracefully() throws IOException {
    // The keyring-less hosts (the CI ones) exercise the fallback: the requested
    // keyring storage is unavailable, so the volume vault keeps serving. On the
    // hosts providing a real keyring the test is skipped (the fallback path is
    // not reachable and the keyring must not be polluted with the test entries).
    Assumptions.assumeTrue(!KeyringVault.INSTANCE.available(), "a usable OS keyring is present");

    Path volDir = tempDir.resolve("keyring_vol");
    Path repoDir = tempDir.resolve("keyring_repo");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("Our secret password", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-k");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // The fallback keeps the obscured token in the volume storage
    JsonNode storage = Storage.readJson(volDir.resolve(Bitferry.STORAGE));
    JsonNode vault = storage.get("vault");
    assertNotNull(vault, "the volume vault must serve when the keyring is unavailable");
    String token = vault.properties().iterator().next().getValue().get("password").asString();
    assertNotEquals("Our secret password", token);

    // The restored task retrieves the password across the retrieval chain
    Manager reloaded = managerFor(volDir);
    Backup task = (Backup) reloaded.tasks().values().stream()
      .filter(t -> t.refersVolume(vtag))
      .findFirst().orElseThrow();
    assertEquals("Our secret password", task.password());
  }

  @Test
  void createEncryptedTaskLifecycle() throws IOException {
    Path volDir = tempDir.resolve("enc_vol");
    Path encDir = tempDir.resolve("enc_dir");
    Files.createDirectories(volDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // The encrypted task is created with the password piped through the standard input
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("crypt-secret", "create", "task", "replicate", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // The descriptor carries the encrypt section while the vault holds the obscured token
    JsonNode storage = Storage.readJson(volDir.resolve(Bitferry.STORAGE));
    JsonNode taskData = storage.get("tasks").get(0);
    assertEquals("replicate", taskData.get("operation").asString());
    assertTrue(taskData.get("rclone").has("encrypt"));
    assertNotNull(storage.get("vault"));

    // The restored task keeps the encryption mode and the password
    Manager reloaded = managerFor(volDir);
    Rclone task = (Rclone) reloaded.tasks().values().stream()
      .filter(t -> t.refersVolume(vtag))
      .findFirst().orElseThrow();
    assertInstanceOf(Rclone.Encrypt.class, task.encryption());
    assertEquals("crypt-secret", task.encryption().password());
    assertEquals("encrypt+replicate", task.displayOperation());

    // Printing task info surfaces the encrypt+replicate operation status
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info"));
    assertTrue(out.toString().contains("encrypt+replicate"));
  }

  @Test
  void encryptedTaskWithLocalSourceIsRejected() throws IOException {
    Path volDir = tempDir.resolve("enc_rej_vol");
    Path plainDir = tempDir.resolve("plain_dir");
    Files.createDirectories(volDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // The unencrypted (source) end of the encrypt task must reside within a volume
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("crypt-secret", "create", "task", "replicate", "local:" + plainDir, ":" + vtag + ":backup", "-e");
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("unencrypted endpoint to reside"));
  }

  @Test
  void backupCreationWithoutPasswordIsRejected() throws IOException {
    Path volDir = tempDir.resolve("nopass_vol");
    Files.createDirectories(volDir);
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // The password source yielding nothing is rejected
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword(null, "create", "task", "backup", ":" + vtag + ":", volDir.resolve("repo").toString());
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("password must be specified"), () -> "stderr: " + err.toString());
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
    probeRoot(srcVol);
    assertEquals(0, cmd.execute("create", "volume", dstVol.toString()));
    probeRoot(dstVol);
    String srcTag = volumeTag(managerFor(srcVol), srcVol);
    String dstTag = volumeTag(managerFor(dstVol), dstVol);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = cmd.execute(
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
    probeRoot(volDir);
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    String[] creation = {"create", "task", "copy", ":" + vtag + ":", "local:" + dstDir};
    assertEquals(0, cmd.execute(creation), () -> "stderr: " + err.toString());
    String tag = managerFor(volDir).tasks().values().stream().findFirst().orElseThrow().tag();

    // The repeated creation is rejected carrying the covering task's tag
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertNotEquals(0, cmd.execute(creation));
    assertTrue(err.toString().toLowerCase().contains("task " + tag + " already covers"), () -> "stderr: " + err.toString());

    // The re-creation succeeds once the covering task is deleted
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("delete", "--force", "task", tag), () -> "stderr: " + err.toString());
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
  void backupTaskScopeOption() throws IOException {
    Path volDir = tempDir.resolve("scope_vol");
    Path volDir2 = tempDir.resolve("scope_vol2");
    Path repoDir = tempDir.resolve("scope_repo");
    Files.createDirectories(volDir);
    Files.createDirectories(volDir2);
    Files.createDirectories(repoDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    assertEquals(0, cmd.execute("create", "volume", volDir2.toString()));
    probeRoot(volDir2);
    String vtag = volumeTag(managerFor(volDir), volDir);
    String vtag2 = volumeTag(managerFor(volDir2), volDir2);

    // Two backup tasks sharing the repository: the scope derivation is ambiguous
    // without the explicit backup task
    int code = executeWithPassword("pass1",
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err);
    code = executeWithPassword("pass1",
      "create", "task", "backup", ":" + vtag2 + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err);
    Manager m = managerFor(volDir);
    String backupTag = m.tasks().values().stream().filter(t -> t instanceof Backup)
      .map(Task::tag).findFirst().orElseThrow();
    String backupTag2 = managerFor(volDir2).tasks().values().stream().filter(t -> t instanceof Backup)
      .map(Task::tag).findFirst().orElseThrow();

    // 1. The explicit -b scope pins the snapshot selection
    code = executeWithPassword("pass1",
      "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restored", "-b", backupTag);
    assertEquals(0, code, () -> "stderr: " + err);
    m = managerFor(volDir);
    Restore restore = m.tasks().values().stream().filter(t -> t instanceof Restore)
      .map(t -> (Restore) t).findFirst().orElseThrow();
    assertEquals(backupTag, restore.snapshotTag());

    // 2. The '-' marker declines the explicit scoping: with two backup tasks serving
    // the repository the default derivation is ambiguous (rejected with the guidance)
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("pass1",
      "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restored2", "--backup-task", "-");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("served by multiple backup tasks"), () -> "stderr: " + err);

    // 3. Modify re-scopes the restore: switch to the sibling backup task, then clear
    // (both probing roots are supplied: the sibling backup lives on the other volume)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir + "," + volDir2, "t", restore.tag(), "--backup-task", backupTag2);
    assertEquals(0, code, () -> "stderr: " + err);
    assertEquals(backupTag2, managerFor(volDir).selectTasks(restore.tag()).get(restore.tag()).serialize().get("snapshotTag").asString());

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir + "," + volDir2, "t", restore.tag(), "-b", "-");
    assertEquals(0, code, () -> "stderr: " + err);
    assertFalse(managerFor(volDir).selectTasks(restore.tag()).get(restore.tag()).serialize().has("snapshotTag"));

    // 4. The guards: the scope rides the restore tasks only, the tag must name a
    // live backup task
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", backupTag, "-b", backupTag2);
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Backup task scope is only applicable to restore tasks"), () -> "stderr: " + err);

    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", restore.tag(), "-b", "NOPE");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("does not name a live backup task"), () -> "stderr: " + err);

    // 5. The removed --source-task spelling is rejected
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("pass1",
      "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restored3", "--source-task", backupTag);
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("'--source-task'"));
  }

  @Test
  void shortAliasesWorkIdentically() throws IOException {
    Path volDir = tempDir.resolve("alias_vol");
    Files.createDirectories(volDir);

    // 'c v' alias
    assertEquals(0, cmd.execute("c", "v", volDir.toString()));
    probeRoot(volDir);
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
    assertEquals(0, executeWithPassword("alias-pass", "c", "t", "b", ":" + vtag + ":", "local:" + tempDir.resolve("alias_repo")), () -> "stderr: " + err.toString());

    // The 'c t r' alias creates the restore task
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, executeWithPassword("alias-pass", "c", "t", "r", ":" + vtag + ":repo", ":" + vtag + ":restored"), () -> "stderr: " + err.toString());

    Manager reloaded = managerFor(volDir);
    Set<String> operations = reloaded.tasks().values().stream().map(Task::operation).collect(java.util.stream.Collectors.toSet());
    assertTrue(operations.contains("backup"));
    assertTrue(operations.contains("restore"));
  }

  @Test
  void pathOptionLivesOnTheModifyCommand() {
    // --path is declared on the bare modify command only: it parses before the
    // task subcommand, the comma-joined values split into separate entries, and
    // the modify flows pick it up through CliUtils.restoreManager
    CommandLine.ParseResult parsed = cmd.parseArgs("modify", "--path", "/a,/b", "task", "TAG");
    CommandLine.ParseResult modifyResult = parsed.subcommand();
    ModifyCommand modify = (ModifyCommand) modifyResult.commandSpec().userObject();
    assertEquals(List.of(Path.of("/a"), Path.of("/b")), modify.paths);
    assertEquals(2, CliUtils.probePaths(modifyResult.commandSpec()).size());
    // Neither the root nor the leaf declares it
    assertFalse(parsed.hasMatchedOption("--path"), "--path must not be declared on the root");
    assertFalse(modifyResult.subcommand().hasMatchedOption("--path"), "--path must not be declared on the leaf");
  }

  @Test
  void commonOptionsResolveAcrossTheWholeCommandHierarchy() {
    // The options supplied to the intermediate group commands (between the root and the
    // leaf) must be honored by the executed leaf: resolveOptions walks the whole spec
    // hierarchy, not just the root. The regression guard for the dropped --dry-run on
    // the destructive paths (e.g. delete -n volume TAG).
    CommandLine.ParseResult parsed = cmd.parseArgs("delete", "-n", "--force", "volume", "UNKNOWN-TAG");
    CommandLine.ParseResult leafResult = parsed.subcommand();
    while (leafResult.subcommand() != null) leafResult = leafResult.subcommand();
    CommandLine.Model.CommandSpec leafSpec = leafResult.commandSpec();
    DeleteVolumeCommand leaf = (DeleteVolumeCommand) leafSpec.userObject();
    CommonOptions resolved = CliUtils.resolveOptions(leafSpec, leaf.options);
    assertTrue(resolved.simulate, "-n given to the 'delete' group must reach the leaf");
    assertTrue(resolved.force, "--force given to the 'delete' group must reach the leaf");
    // The root-level options keep working too
    parsed = cmd.parseArgs("-n", "delete", "volume", "UNKNOWN-TAG");
    leafResult = parsed.subcommand();
    while (leafResult.subcommand() != null) leafResult = leafResult.subcommand();
    leafSpec = leafResult.commandSpec();
    leaf = (DeleteVolumeCommand) leafSpec.userObject();
    assertTrue(CliUtils.resolveOptions(leafSpec, leaf.options).simulate, "-n at the root must reach the leaf");
  }

  @Test
  void debugFlagResolvesAcrossTheWholeCommandHierarchy() {
    assertTrue(Main.isDebug(cmd.parseArgs("info", "--debug", "task", "UNKNOWN-TAG")), "--debug at the group level must be seen");
    assertTrue(Main.isDebug(cmd.parseArgs("--debug", "info", "task", "UNKNOWN-TAG")), "--debug at the root must be seen");
    assertFalse(Main.isDebug(cmd.parseArgs("info", "task", "UNKNOWN-TAG")));
  }

  @Test
  void encryptionOptionsRejectShorthandExplicitConflicts() throws IOException {
    // The shorthand and the explicit profile forms of the same setting are mutually exclusive
    int code = executeWithPassword("ignored", "create", "task", "copy", "some/src", "some/dst", "-e", "--encrypt", "@extended");
    assertEquals(1, code);
    assertTrue(err.toString().toLowerCase().contains("destination encryption specified twice"), () -> "stderr: " + err.toString());
    err.getBuffer().setLength(0);
    code = executeWithPassword("ignored", "create", "task", "copy", "some/src", "some/dst", "-d", "--decrypt", "@extended");
    assertEquals(1, code);
    assertTrue(err.toString().toLowerCase().contains("source decryption specified twice"), () -> "stderr: " + err.toString());
    err.getBuffer().setLength(0);
    code = executeWithPassword("ignored", "create", "task", "copy", "some/src", "some/dst", "-u", "--encrypt", "@extended");
    assertEquals(1, code);
    assertTrue(err.toString().contains("conflicts with the explicitly specified encryption profile"), () -> "stderr: " + err.toString());
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

    // Pre-create encrypted repository with password A (an empty config file: portable
    // across the platforms, unlike the unix-only /dev/null)
    Path emptyConfig = tempDir.resolve("empty_rclone.conf");
    Files.writeString(emptyConfig, "");
    ProcessBuilder pb = new ProcessBuilder(
      "rclone", "sync", plainDir.toString(), ":crypt:",
      "--crypt-remote", encDir.toString(),
      "--crypt-filename-encryption", "standard",
      "--crypt-filename-encoding", "base32",
      "--config", emptyConfig.toString()
    );
    pb.environment().put("RCLONE_CRYPT_PASSWORD", Passwords.rcloneObscure("passwordA"));
    Process p = pb.start();
    assertEquals(0, p.waitFor());

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Creation with wrong password fails and is not persisted
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("wrongPassword", "create", "task", "replicate", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(1, code);
    assertTrue(err.toString().toLowerCase().contains("is not compatible with the provided encryption options"));

    Manager reloaded = managerFor(volDir);
    assertTrue(reloaded.tasks().isEmpty());

    // 2. Creation with correct password succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("passwordA", "create", "task", "replicate", ":" + vtag + ":", "local:" + encDir, "-e");
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Wrong password backup is refused
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("badSecret", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(1, code);
    assertTrue(err.toString().toLowerCase().contains("does not accept the provided password"));
    assertFalse(err.toString().contains("--attach"));
    assertTrue(managerFor(volDir).tasks().isEmpty());

    // 2. -a/--attach is rejected as an unknown option
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("badSecret", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-a");
    assertEquals(2, code);
    assertTrue(err.toString().contains("Unknown option: '-a'"));
    assertTrue(managerFor(volDir).tasks().isEmpty());

    // 3. Correct password backup succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("correctSecret", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err.toString());
    assertEquals(1, managerFor(volDir).tasks().size());

    // Delete the backup task to allow re-creating over the same endpoints with --force
    Task existingTask = managerFor(volDir).tasks().values().iterator().next();
    cmd = Main.createCommandLine();
    assertEquals(0, cmd.execute("delete", "--force", "task", existingTask.tag()));

    // 4. Force (--force) proceeds over existing repo with a new password
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("brandNewSecret", "--force", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // 4b. Force (--force) against a not-yet-existing repository directory proceeds to init
    Path nonExistentRepo = tempDir.resolve("cli_restic_non_existent_repo");
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("freshSecret", "--force", "create", "task", "backup", ":" + vtag + ":", "local:" + nonExistentRepo);
    assertEquals(0, code, () -> "stderr: " + err.toString());
    assertTrue(Files.isRegularFile(nonExistentRepo.resolve("config")));

    // 5. Restore with bad password is refused
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("badSecret", "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restored");
    assertEquals(1, code);
    assertTrue(err.toString().contains("does not accept the provided password"));

    // 6. Restore with correct password ("brandNewSecret") succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("brandNewSecret", "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restored");
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create replicate task
    cmd = Main.createCommandLine();
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir, "--include", "*.txt"));

    Manager m = managerFor(volDir);
    Task task = m.tasks().values().iterator().next();
    String ttag = task.tag();

    // 1. info task <tag> prints rclone sync command
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err1 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err1));
    int code = cmd.execute("info", "task", ttag);
    assertEquals(0, code, () -> "stderr: " + err1.toString());
    String output = out.toString().trim();
    assertTrue(output.contains("rclone sync"), () -> "expected rclone sync in: " + output);
    assertTrue(output.contains("+ *.txt"), () -> "expected + *.txt in: " + output);
    assertTrue(output.contains("- " + Bitferry.STORAGE), () -> "expected storage exclude in: " + output);
    assertTrue(output.contains("# Task " + ttag + ": replicate"));
    assertTrue(output.contains("Status:        ✓ intact"));
    assertTrue(output.contains("Backend:       rclone"));
    assertTrue(output.contains("Operation:     replicate"));
    assertTrue(output.contains("Encryption:    none"));
    assertTrue(output.contains("# Filters"));
    assertTrue(output.contains("Include:       *.txt"));
    assertTrue(output.contains("# Commands"));
    assertTrue(output.contains("Execute:"));
    assertTrue(output.contains("Serve:"));
    assertTrue(output.contains("rclone check"));

    // 2. Alias: i t <tag>
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err2 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err2));
    code = cmd.execute("i", "t", ttag);
    assertEquals(0, code, () -> "stderr: " + err2.toString());
    assertEquals(output, out.toString().trim());

    // 2b. Bare commands mode: info task <tag> -c and --commands
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info", "task", ttag, "-c"));
    String bareOutput = out.toString().trim();
    assertTrue(bareOutput.startsWith("rclone sync"));
    assertFalse(bareOutput.contains("# Task"));
    assertFalse(bareOutput.contains("# Filters"));
    assertFalse(bareOutput.contains("# Commands"));

    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info", "task", ttag, "--commands"));
    assertEquals(bareOutput, out.toString().trim());

    // 3. info task with no tags requires TAGS parameter (exit code 2)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err3 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err3));
    code = cmd.execute("info", "task");
    assertEquals(2, code);
    assertTrue(err3.toString().contains("Missing required parameter: 'TAGS'"));

    // 4. info task with non-existent tag fails with exit code 1
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("info", "task", "nonexistent");
    assertEquals(1, code);
    assertTrue(err.toString().toLowerCase().contains("no entries matching specified tag part"));

    // 5. Existing 'info' and 'i' without subcommand still print intact volumes/tasks
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("info");
    assertEquals(0, code);
    assertTrue(out.toString().contains("# Intact volumes"));
    assertTrue(out.toString().contains("# Intact tasks"));

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("i");
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create task with --execute option
    cmd = Main.createCommandLine();
    StringWriter out = new StringWriter();
    StringWriter err1 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err1));
    assertEquals(0, cmd.execute("create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir, "--execute", "--transfers,4"),
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
    int code = cmd.execute("-n", "execute", task.tag());
    assertEquals(0, code, () -> "stderr: " + err2.toString());
    assertTrue(out.toString().contains("All 1 task(s) executed successfully."));

    // Execute with 'x' alias (-n simulation)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    StringWriter err3 = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err3));
    code = cmd.execute("-n", "x", task.tag());
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Create backup with explicit @-prefixed profiles: --forget @default --check @full
    int code = executeWithPassword("pass1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "--forget", "@default", "--check", "@full");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Backup task1 = (Backup) m.tasks().values().iterator().next();
    assertEquals(Backup.FORGET.get("default"), Task.strings(task1.serialize().get("restic").get("forget"), false));
    assertEquals(Backup.CHECK.get("full"), Task.strings(task1.serialize().get("restic").get("check"), false));
    assertNull(task1.serialize().get("restic").get("execute"));

    // Delete task1 to test shorthand creation cleanly
    assertEquals(0, cmd.execute("delete", "--force", "task", task1.tag()));

    // 2. Create backup with default profiles (omitted options apply @default)
    code = executeWithPassword("pass1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task2 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals(Backup.FORGET.get("default"), Task.strings(task2.serialize().get("restic").get("forget"), false));
    // Empty @default check and prune omit their JSON keys via orNull
    assertEquals(Backup.CHECK.get("default"), Task.strings(task2.serialize().get("restic").get("check"), true));
    assertEquals(Backup.PRUNE.get("default"), Task.strings(task2.serialize().get("restic").get("prune"), true));
    assertNull(task2.serialize().get("restic").get("check"));
    assertNull(task2.serialize().get("restic").get("prune"));
    assertEquals("7d,5", task2.cadence().format());

    // Delete task2
    assertEquals(0, cmd.execute("delete", "--force", "task", task2.tag()));

    // 2b. Create backup with policies explicitly disabled via '-'
    code = executeWithPassword("pass1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "--forget", "-", "--check", "-", "--prune", "-", "--serve", "-");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup taskDisabled = (Backup) m.selectTasks().values().iterator().next();
    assertNull(taskDisabled.serialize().get("restic"));
    assertNull(taskDisabled.cadence());

    // Delete taskDisabled
    assertEquals(0, cmd.execute("delete", "--force", "task", taskDisabled.tag()));

    // 3. Create backup with directly specified raw options without @
    code = executeWithPassword("pass1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir,
      "--forget", "--prune,--keep-daily,7", "--check", "--no-cache,--read-data", "--prune", "--max-unused,5%", "-x", "--no-cache");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task3 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals(List.of("--prune", "--keep-daily", "7"), Task.strings(task3.serialize().get("restic").get("forget"), false));
    assertEquals(List.of("--no-cache", "--read-data"), Task.strings(task3.serialize().get("restic").get("check"), false));
    assertEquals(List.of("--max-unused", "5%"), Task.strings(task3.serialize().get("restic").get("prune"), false));
    assertEquals(List.of("--no-cache"), Task.strings(task3.serialize().get("restic").get("execute"), false));
  }

  @Test
  void createRcloneWithAtProfilesAndShorthands() throws IOException {
    Path volDir = tempDir.resolve("rclone_profiles_vol");
    Path dataDir = tempDir.resolve("rclone_profiles_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Explicit profile @extended and @default execute
    int code = executeWithPassword("cryptpass", 
      "create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir,
      "--encrypt", "@extended", "-x", "@default");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Task task1 = m.tasks().values().iterator().next();
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(task1.serialize().get("rclone").get("encrypt"), false));
    assertEquals(Rclone.PROCESS.get("default"), Task.strings(task1.serialize().get("rclone").get("execute"), false));

    // Delete task1
    assertEquals(0, cmd.execute("delete", "--force", "task", task1.tag()));

    // 2. Shorthand -eu (extended encryption coalesced)
    code = executeWithPassword("cryptpass", 
      "create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir, "-eu");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Task task2 = m.selectTasks().values().iterator().next();
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(task2.serialize().get("rclone").get("encrypt"), false));

    // Delete task2
    assertEquals(0, cmd.execute("delete", "--force", "task", task2.tag()));

    // 3. Shorthand -e (default encryption) with directly specified -X --fast-list
    code = executeWithPassword("cryptpass", 
      "create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir, "-e", "-x", "--fast-list");
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Explicit --serve 14d,10
    int code = executeWithPassword("secret1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "--serve", "14d,10");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Backup task1 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals("14d,10", task1.cadence().format());
    assertEquals("PT336H", task1.serialize().get("restic").get("cadence").get("duration").asString());
    assertEquals(10, task1.serialize().get("restic").get("cadence").get("backups").asInt());

    assertEquals(0, cmd.execute("delete", "--force", "task", task1.tag()));

    // 2. Omitted cadence defaults to @default (7d,5)
    code = executeWithPassword("secret1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task2 = (Backup) m.selectTasks().values().iterator().next();
    assertEquals("7d,5", task2.cadence().format());

    assertEquals(0, cmd.execute("delete", "--force", "task", task2.tag()));

    // 3. Disable with --serve -
    code = executeWithPassword("secret1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "--serve", "-");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Backup task3 = (Backup) m.selectTasks().values().iterator().next();
    assertNull(task3.cadence());
    assertNull(task3.serialize().get("restic").get("cadence"));

    assertEquals(0, cmd.execute("delete", "--force", "task", task3.tag()));

    // 4. -s (the salt shorthand, rclone task creation only) is unknown on backup tasks
    code = executeWithPassword("secret1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "-s");
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("unknown option: '-s'"));
  }

  @Test
  void serveCommandExecution() throws IOException {
    Path volDir = tempDir.resolve("serve_vol");
    Path dataDir = tempDir.resolve("serve_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create a replicate task
    assertEquals(0, cmd.execute(
      "create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir));

    Task task = managerFor(volDir).selectTasks().values().iterator().next();

    // Run serve in simulation mode
    assertEquals(0, cmd.execute("-n", "serve", task.tag()),
      () -> "stderr: " + err);
    assertTrue(out.toString().contains("serviced successfully") || out.toString().contains("1/1"));

    // Run serve via alias 's'
    out.getBuffer().setLength(0);
    assertEquals(0, cmd.execute("-n", "s", task.tag()),
      () -> "stderr: " + err);
    assertTrue(out.toString().contains("serviced successfully") || out.toString().contains("1/1"));

    // Old 'maintain' command is rejected (no backward hooks)
    out.getBuffer().setLength(0);
    err.getBuffer().setLength(0);
    int code = cmd.execute("-n", "maintain", task.tag());
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Unmatched argument") || err.toString().contains("maintain"));
  }

  @Test
  void taskInfoPrintsEncryptionStatusForEncryptAndDecrypt() throws IOException {
    Path volDir = tempDir.resolve("enc_info_vol");
    Path encDir = tempDir.resolve("enc_info_data");
    Files.createDirectories(volDir);
    Files.createDirectories(encDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Create an encrypt replicate task: :vtag: -> local:enc
    int code = executeWithPassword("pass1", 
      "create", "task", "replicate", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // 2. Create a decrypt copy task: local:enc -> :vtag:dec
    code = executeWithPassword("pass1", 
      "create", "task", "copy", "local:" + encDir, ":" + vtag + ":dec", "-d");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    // 3. Verify 'info' command prints encrypt+replicate and decrypt+copy
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info"));
    String infoOutput = out.toString();
    assertTrue(infoOutput.contains("encrypt+replicate"), () -> "Expected 'encrypt+replicate' in info output:\n" + infoOutput);
    assertTrue(infoOutput.contains("decrypt+copy"), () -> "Expected 'decrypt+copy' in info output:\n" + infoOutput);

    // 4. Verify 'execute' command banner prints encrypt+replicate and decrypt+copy
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("-n", "execute"));
    String execOutput = out.toString();
    assertTrue(execOutput.contains("encrypt+replicate"), () -> "Expected 'encrypt+replicate' in execute output:\n" + execOutput);
    assertTrue(execOutput.contains("decrypt+copy"), () -> "Expected 'decrypt+copy' in execute output:\n" + execOutput);
  }

  @Test
  void infoTaskBreakdownEncryptedRclone() throws IOException {
    Path volDir = tempDir.resolve("enc_breakdown_vol");
    Path encDir = tempDir.resolve("enc_breakdown_data");
    Files.createDirectories(volDir);
    Files.createDirectories(encDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    int code = executeWithPassword("secret123", 
      "create", "task", "replicate", ":" + vtag + ":", "local:" + encDir, "-e");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Task task = m.tasks().values().iterator().next();
    String ttag = task.tag();

    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info", "task", ttag));
    String breakdown = out.toString();

    assertTrue(breakdown.contains("# Task " + ttag + ": encrypt+replicate"));
    assertTrue(breakdown.contains("Status:        ✓ intact"));
    assertTrue(breakdown.contains("Backend:       rclone"));
    assertTrue(breakdown.contains("Operation:     replicate"));
    assertTrue(breakdown.contains("Encryption:    encrypt"));
    assertTrue(breakdown.contains("Vault Volume:  " + vtag));
    assertTrue(breakdown.contains("# Commands"));
    assertTrue(breakdown.contains("Execute:"));
    assertTrue(breakdown.contains("Serve:"));
    assertTrue(breakdown.contains("rclone check"));

    // Bare mode emits only rclone command line
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info", "task", ttag, "-c"));
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
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Create backup task (with default retention and check)
    int code = executeWithPassword("repo-pass", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir, "--exclude", "*.tmp");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    Manager m = managerFor(volDir);
    Task backupTask = m.tasks().values().stream().filter(t -> t instanceof Backup).findFirst().orElseThrow();
    String backupTag = backupTask.tag();

    // 2. Info task on backup
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info", "task", backupTag));
    String bBreakdown = out.toString();

    assertTrue(bBreakdown.contains("# Task " + backupTag + ": backup"));
    assertTrue(bBreakdown.contains("Status:        ✓ intact"));
    assertTrue(bBreakdown.contains("Backend:       restic"));
    assertTrue(bBreakdown.contains("Operation:     backup"));
    assertTrue(bBreakdown.contains("Repository:"));
    assertTrue(bBreakdown.contains("Vault Volume:  " + vtag));
    assertTrue(bBreakdown.contains("# Service & Retention"));
    assertTrue(bBreakdown.contains("Cadence:       7d,5 (profile: @default)"));
    assertTrue(bBreakdown.contains("Status:        due"));
    assertTrue(bBreakdown.contains("Last Backup:   never"));
    assertTrue(bBreakdown.contains("Last Service:  never"));
    assertTrue(bBreakdown.contains("Retention:     --keep-within-hourly 24h"));
    assertTrue(bBreakdown.contains("Check:         @default (structural)"));
    assertTrue(bBreakdown.contains("Prune:         @default"));
    assertTrue(bBreakdown.contains("# Filters"));
    assertTrue(bBreakdown.contains("Exclude:       *.tmp"));
    assertTrue(bBreakdown.contains("# Commands"));
    assertTrue(bBreakdown.contains("Execute:"));
    assertTrue(bBreakdown.contains("restic backup"));
    assertTrue(bBreakdown.contains("Serve:"));
    assertTrue(bBreakdown.contains("restic check"));
    assertTrue(bBreakdown.contains("restic prune"));

    // Bare mode on backup
    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info", "task", backupTag, "-c"));
    String bBare = out.toString().trim();
    assertTrue(bBare.startsWith("restic backup"));
    assertFalse(bBare.contains("# Task"));

    // 3. Create restore task
    code = executeWithPassword("repo-pass", 
      "create", "task", "restore", "local:" + repoDir, ":" + vtag + ":restore");
    assertEquals(0, code, () -> "stderr: " + err.toString());

    m = managerFor(volDir);
    Task restoreTask = m.tasks().values().stream().filter(t -> t instanceof Restore).findFirst().orElseThrow();
    String restoreTag = restoreTask.tag();

    cmd = Main.createCommandLine();
    out = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    assertEquals(0, cmd.execute("info", "task", restoreTag));
    String rBreakdown = out.toString();

    assertTrue(rBreakdown.contains("# Task " + restoreTag + ": restore"));
    assertTrue(rBreakdown.contains("Backend:       restic"));
    assertTrue(rBreakdown.contains("Operation:     restore"));
    assertTrue(rBreakdown.contains("Repository:"));
    assertTrue(rBreakdown.contains("Destination:"));
    assertTrue(rBreakdown.contains("Vault Volume:  " + vtag));
    assertTrue(rBreakdown.contains("Snapshot Scope:"));
    assertTrue(rBreakdown.contains(backupTag));
    assertTrue(rBreakdown.contains("# Commands"));
    assertTrue(rBreakdown.contains("Execute:"));
    assertTrue(rBreakdown.contains("restic restore"));
    assertTrue(rBreakdown.contains("Serve:"));
    assertTrue(rBreakdown.contains("restic check"));
  }

  @Test
  void createRestoreTaskRejectsNonResticDirectory() throws IOException {
    Path volDir = tempDir.resolve("cli_restore_vol");
    Path notRepo = tempDir.resolve("cli_not_a_repo");
    Files.createDirectories(volDir);
    Files.createDirectories(notRepo);
    Files.writeString(notRepo.resolve("dummy.txt"), "hello");

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    int code = executeWithPassword("any-pass", 
      "create", "task", "restore", "local:" + notRepo, ":" + vtag + ":restored");
    assertEquals(1, code);
    assertTrue(err.toString().toLowerCase().contains("restic repository"));
  }

  @Test
  void modifyTaskBackupPoliciesAndOptions() throws IOException {
    Path volDir = tempDir.resolve("mod_backup_vol");
    Path repoDir = tempDir.resolve("mod_backup_repo");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    int code = executeWithPassword("secret1", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code);

    Backup task = (Backup) managerFor(volDir).selectTasks().values().iterator().next();

    // 1. Modify task using 'modify task' with --forget @default --check @default --prune @default --serve @default -X @default
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("modify", "--path", volDir.toString(), "task", task.tag(), "--forget", "@default", "--check", "@default", "--prune", "@default", "--serve", "@default", "-x", "@default");
    assertEquals(0, code, () -> "stderr: " + err);
    assertTrue(out.toString().contains("modified successfully"));

    Manager m = managerFor(volDir);
    Backup updated1 = (Backup) m.selectTasks().get(task.tag());
    assertEquals(Backup.FORGET.get("default"), updated1.forget());
    assertNull(updated1.check());
    assertNull(updated1.prune());
    assertEquals(Cadence.PROFILES.get("default"), updated1.cadence());
    assertEquals(Backup.PROCESS.get("default"), updated1.processOptions());

    // 2. The include filters are rejected for the backup tasks upfront (restic backup
    // filters by exclusion only) and the rejected command mutates nothing
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(),
      "--forget", "--keep-daily,7", "--check", "--read-data", "--prune", "--max-unused,5%", "--serve", "14d,10", "-x", "--no-cache",
      "--include", "src/**", "--exclude", "*.tmp");
    assertEquals(1, code);
    assertTrue(err.toString().toLowerCase().contains("include filters are not applicable to backup tasks"), () -> "stderr: " + err);

    m = managerFor(volDir);
    Backup unchanged = (Backup) m.selectTasks().get(task.tag());
    assertEquals(Backup.FORGET.get("default"), unchanged.forget());
    assertNull(unchanged.check());
    assertNull(unchanged.prune());
    assertEquals(Cadence.PROFILES.get("default"), unchanged.cadence());
    assertEquals(Backup.PROCESS.get("default"), unchanged.processOptions());
    // The backup carries no include state (the capability is structural): the
    // descriptor holds no such section
    assertFalse(unchanged.serialize().has("include"));

    // The exclude filters and custom prune flags apply cleanly
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(),
      "-x", "--no-cache", "--prune", "--max-unused,5%", "--exclude", "*.tmp");
    assertEquals(0, code, () -> "stderr: " + err);

    m = managerFor(volDir);
    Backup updated2 = (Backup) m.selectTasks().get(task.tag());
    assertEquals(List.of("--no-cache"), updated2.processOptions());
    assertEquals(List.of("--max-unused", "5%"), updated2.prune());
    assertEquals(List.of("*.tmp"), updated2.exclude());

    // 3. Clear/disable options using '-'
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(),
      "--forget", "-", "--check", "-", "--prune", "-", "--serve", "-", "-x", "-", "--include", "-", "--exclude", "-");
    assertEquals(0, code, () -> "stderr: " + err);

    m = managerFor(volDir);
    Backup updated3 = (Backup) m.selectTasks().get(task.tag());
    assertNull(updated3.forget());
    assertNull(updated3.check());
    assertNull(updated3.prune());
    assertNull(updated3.cadence());
    assertTrue(updated3.processOptions().isEmpty());
    assertFalse(updated3.serialize().has("include"));
    assertTrue(updated3.exclude().isEmpty());
  }

  @Test
  void modifyTaskBackupPasswordRotation() throws IOException {
    Assumptions.assumeTrue(available("restic"), "restic not available for the integration check");

    Path volDir = tempDir.resolve("mod_pw_vol");
    Path repoDir = tempDir.resolve("mod_pw_repo");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // Create a real restic backup task
    int code = executeWithPassword("initial-secret", 
      "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir);
    assertEquals(0, code, () -> "stderr: " + err);

    Backup task = (Backup) managerFor(volDir).selectTasks().values().iterator().next();
    assertEquals("initial-secret", task.password());

    // Rotate password via 'm t <tag> -p'
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = executeWithPassword("rotated-new-secret", 
      "m", "t", task.tag(), "-p");
    assertEquals(0, code, () -> "stderr: " + err);

    // Verify stored password was updated
    Manager m = managerFor(volDir);
    Backup updated = (Backup) m.selectTasks().get(task.tag());
    assertEquals("rotated-new-secret", updated.password());

    // Verify the restic repo now accepts the new password and rejects the old password
    CommandExecutor ce = CommandExecutor.system();
    List<String> probeCmd = List.of("restic", "-r", repoDir.toString(), "cat", "config");
    try {
      int exitCodeNew = ce.execute(probeCmd, null, Map.of("RESTIC_PASSWORD", "rotated-new-secret"), null);
      assertEquals(0, exitCodeNew);

      int exitCodeOld = ce.execute(probeCmd, null, Map.of("RESTIC_PASSWORD", "initial-secret"), null);
      assertNotEquals(0, exitCodeOld);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void modifyTaskRestoreOptions() throws IOException {
    Path volDir = tempDir.resolve("mod_restore_vol");
    Files.createDirectories(volDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    int code = executeWithPassword("secret1", 
      "create", "task", "restore", ":" + vtag + ":repo", ":" + vtag + ":restored");
    assertEquals(0, code);

    Restore task = (Restore) managerFor(volDir).selectTasks().values().iterator().next();

    // 1. Modifying execute options succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "-x", "--verify");
    assertEquals(0, code, () -> "stderr: " + err);

    Manager m = managerFor(volDir);
    Restore updated = (Restore) m.selectTasks().get(task.tag());
    assertEquals(List.of("--verify"), updated.processOptions());

    // 2. Modifying backup-only options on restore fails
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "--forget", "@default");
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("forget, check, prune, and serve options are only applicable to backup tasks"));
  }

  @Test
  void modifyTaskRcloneOptionsAndRejections() throws IOException {
    Path volDir = tempDir.resolve("mod_rclone_vol");
    Path dataDir = tempDir.resolve("mod_rclone_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    int code = cmd.execute(
      "create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir);
    assertEquals(0, code);

    Replicate task = (Replicate) managerFor(volDir).selectTasks().values().iterator().next();

    // 1. Modifying execute and filters succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "-x", "@default", "--include", "docs/**");
    assertEquals(0, code, () -> "stderr: " + err);

    Manager m = managerFor(volDir);
    Replicate updated = (Replicate) m.selectTasks().get(task.tag());
    assertEquals(Rclone.PROCESS.get("default"), updated.processOptions());
    assertEquals(List.of("docs/**"), updated.include());

    // 2. Modifying password on rclone fails
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "-p");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Password update is only applicable to restic tasks"));

    // 3. Modifying backup options on rclone fails
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "--serve", "@default");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Forget, check, prune, and serve options are only applicable to backup tasks"));

    // 4. Modifying operation type on rclone succeeds
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "-t", "copy");
    assertEquals(0, code, () -> "stderr: " + err);

    m = managerFor(volDir);
    Task morphed = m.selectTasks().get(task.tag());
    assertInstanceOf(Copy.class, morphed);
    assertEquals("copy", morphed.operation());
  }

  @Test
  void modifyTaskManualFlagsAndMorphOrdering() throws IOException {
    Path volDir = tempDir.resolve("mod_manual_vol");
    Path dataDir = tempDir.resolve("mod_manual_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Creating with -m (the manual execution shorthand) marks the task
    // manual-only in the descriptor
    int code = cmd.execute(
      "create", "task", "equalize", ":" + vtag + ":data", "local:" + dataDir, "-m");
    assertEquals(0, code, () -> "stderr: " + err + " out: " + out);
    Manager probed = managerFor(volDir);
    Equalize task = (Equalize) probed.liveTasks().values().iterator().next();
    assertTrue(task.serialize().has("manual"), () -> task.serialize().toPrettyString());
    // The manual-only task is invisible for the mass-run selection (by-tag lookup
    // still reaches it)
    assertTrue(probed.selectTasks().isEmpty(), () -> "mass selection: " + probed.selectTasks().keySet());
    assertFalse(probed.liveTasks().isEmpty());

    // 2. The created baseline (the listings seeded into the workdir): the following
    // morph-failure scenario relies on it being destructible-but-intact
    Path workdir = dataDir.resolve(".bitferry." + task.tag());
    Files.createDirectories(workdir);
    Files.writeString(workdir.resolve("baseline.path1.lst"), "listing");
    Manager m = managerFor(volDir);
    assertTrue(((Equalize) m.selectTasks(task.tag()).get(task.tag())).resynced());

    // 3. The failing value mutation must not strand the destructive morph: the type
    // change is rejected upfront (the '@'-profile does not resolve against the target
    // operation's route), so the bisync baseline survives untouched
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "-t", "copy", "-x", "@nonexistent");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Unknown options profile @nonexistent"), () -> "stderr: " + err);
    assertTrue(Files.exists(workdir.resolve("baseline.path1.lst")), "the morph must not run before the value validation");
    assertInstanceOf(Equalize.class, managerFor(volDir).selectTasks(task.tag()).get(task.tag()));

    // 4. The execution modes: set to automatic, then back to manual (both spellings)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "--execution", "automatic");
    assertEquals(0, code, () -> "stderr: " + err);
    assertFalse(managerFor(volDir).selectTasks(task.tag()).get(task.tag()).serialize().has("manual"));

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "-m");
    assertEquals(0, code, () -> "stderr: " + err);
    assertTrue(managerFor(volDir).selectTasks(task.tag()).get(task.tag()).serialize().has("manual"));

    // 5. The bogus execution mode is rejected; the shorthand and the explicit form
    // are mutually exclusive
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "--execution", "bogus");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Unsupported execution mode 'bogus'"), () -> "stderr: " + err);

    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "-m", "--execution", "automatic");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Execution mode specified twice"), () -> "stderr: " + err);
  }

  @Test
  void restoreManagerProbesTheBitferryPathSystemProperty() throws IOException {
    Path volDir = tempDir.resolve("sysprop_vol");
    Files.createDirectories(volDir);
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);

    System.setProperty("bitferry.path", volDir.toString());
    try {
      Manager m = CliUtils.restoreManager(null, new CommonOptions());
      assertTrue(m.volumes().values().stream()
        .anyMatch(v -> v.root().equals(Bitferry.canonicalize(volDir))), () -> "volumes: " + m.volumes().keySet());
    } finally {
      System.clearProperty("bitferry.path");
    }
  }

  @Test
  void restoreManagerProbesSearchRootsPreferences() throws IOException {
    List<String> original = org.bitferry.Paths.load();
    Path volDir = tempDir.resolve("prefs_vol");
    Files.createDirectories(volDir);
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);

    org.bitferry.Paths.save(List.of(volDir.toString()));
    try {
      Manager m = CliUtils.restoreManager(null, new CommonOptions());
      assertTrue(m.volumes().values().stream()
        .anyMatch(v -> v.root().equals(Bitferry.canonicalize(volDir))), () -> "volumes: " + m.volumes().keySet());
    } finally {
      org.bitferry.Paths.save(original);
    }
  }

  @Test
  void modifyTaskValidationFailures() throws IOException {
    Path volDir = tempDir.resolve("mod_val_vol");
    Path dataDir = tempDir.resolve("mod_val_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    int code = cmd.execute(
      "create", "task", "replicate", ":" + vtag + ":", "local:" + dataDir);
    assertEquals(0, code);

    Task task = managerFor(volDir).selectTasks().values().iterator().next();

    // 1. No modifications specified
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag());
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("no modifications specified for task " + task.tag()));

    // 2. Conflicting flags
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "--execution", "bogus");
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("unsupported execution mode 'bogus'"));

    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "--include", "a", "--include", "-");
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("cannot combine '-' (clear) with the include patterns"));

    // The ext variant of the pair carries the clear marker too, and the marker and
    // the real patterns are mutually exclusive within a pair
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", task.tag(), "--exclude-ext", "-", "--exclude-ext", "log");
    assertNotEquals(0, code);
    assertTrue(err.toString().toLowerCase().contains("cannot combine '-' (clear) with the exclude patterns"));
  }

  @Test
  void extensionPatternsTolerateTheLeadingDot() {
    // '--include-ext .txt' and '--include-ext txt' mean the same (the broken '*..txt' pattern matched nothing)
    assertEquals(List.of("*.txt", "*.md"), CliUtils.parsePatterns(null, List.of(".txt, .md")));
    assertEquals(List.of("*.txt"), CliUtils.parsePatterns(null, List.of("txt")));
    assertEquals(List.of("*.log"), CliUtils.parsePatterns(null, List.of("..log")));
    // The plain specs ride unchanged
    assertEquals(List.of("a", "b"), CliUtils.parsePatterns(List.of("a,b"), null));
    // The global '-' clear marker yields the empty list (in either half)
    assertEquals(List.of(), CliUtils.parsePatterns(List.of("-"), null));
    assertEquals(List.of(), CliUtils.parsePatterns(null, List.of("-")));
    // The marker mixed with the real patterns (the accumulated occurrences too) is rejected
    assertThrows(BitferryError.class, () -> CliUtils.parsePatterns(List.of("a", "-"), null));
    assertThrows(BitferryError.class, () -> CliUtils.parsePatterns(null, List.of("-", "log")));
  }

  @Test
  void rcloneCreationWithExplicitSaltStoresBothPasswordAndSalt() throws IOException {
    Path volDir = tempDir.resolve("salt_vol");
    Path encDir = tempDir.resolve("salt_enc");
    Files.createDirectories(volDir);
    Files.createDirectories(encDir);

    CommandLine cmd = Main.createCommandLine();
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    var origPwd = CliUtils.passwordSource;
    var origSalt = CliUtils.saltSource;
    CliUtils.passwordSource = () -> "data-secret";
    CliUtils.saltSource = () -> "custom-salt";
    try {
      cmd = Main.createCommandLine();
      StringWriter err = new StringWriter();
      cmd.setErr(new PrintWriter(err));
      // -S shorthand for specifying salt
      int code = cmd.execute("create", "task", "replicate", ":" + vtag + ":", "local:" + encDir, "-e", "-s");
      assertEquals(0, code, () -> "stderr: " + err);
    } finally {
      CliUtils.passwordSource = origPwd;
      CliUtils.saltSource = origSalt;
    }

    Manager reloaded = managerFor(volDir);
    Rclone task = (Rclone) reloaded.tasks().values().stream()
      .filter(t -> t.refersVolume(vtag))
      .findFirst().orElseThrow();
    assertEquals("data-secret", task.encryption().password());
    assertEquals("custom-salt", task.encryption().password2());

    JsonNode storage = Storage.readJson(volDir.resolve(Bitferry.STORAGE));
    JsonNode entry = storage.get("vault").get(task.tag());
    assertNotNull(entry);
    assertEquals("data-secret", Passwords.bitferryReveal(entry.get("password").asString()));
    assertEquals("custom-salt", Passwords.bitferryReveal(entry.get("salt").asString()));
  }

  @Test
  void vaultFlagAndKeyringFlagsSelectStorageProvider() throws IOException {
    Path volDir = tempDir.resolve("vault_flag_vol");
    Path repoDir1 = tempDir.resolve("vault_flag_repo1");
    Path repoDir2 = tempDir.resolve("vault_flag_repo2");
    Files.createDirectories(volDir);
    Files.createDirectories(repoDir1);
    Files.createDirectories(repoDir2);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. Invalid vault provider is rejected
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code1 = executeWithPassword("pass", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir1, "--vault", "invalid");
    assertNotEquals(0, code1);
    assertTrue(err.toString().contains("Unsupported vault provider 'invalid'"));

    // 2. Conflicting -V and -K flags are rejected
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int codeConflict = executeWithPassword("pass", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir1, "-z", "-k");
    assertNotEquals(0, codeConflict);
    assertTrue(err.toString().contains("Conflicting vault storage options"));

    // 3. Explicit -V shorthand stores in volume vault
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code2 = executeWithPassword("pass", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir1, "-z");
    assertEquals(0, code2, () -> "stderr: " + err);

    // 4. Explicit -K shorthand stores in keyring
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code3 = executeWithPassword("pass", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir2, "-k");
    assertEquals(0, code3, () -> "stderr: " + err);
  }

  @Test
  void modifyTaskVaultMigrationGuardsAndApplies() throws IOException {
    Path volDir = tempDir.resolve("mod_vault_vol");
    Path encDir = tempDir.resolve("mod_vault_enc");
    Files.createDirectories(volDir);
    Files.createDirectories(encDir);

    CommandLine cmd = Main.createCommandLine();
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    cmd = Main.createCommandLine();
    assertEquals(0, cmd.execute("create", "task", "copy", ":" + vtag + ":", "local:" + encDir));
    Manager reloaded = managerFor(volDir);
    Task unencrypted = reloaded.tasks().values().iterator().next();

    // 1. Vault migration on unencrypted task is rejected
    cmd = Main.createCommandLine();
    StringWriter err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int code = cmd.execute("m", "--path", volDir.toString(), "t", unencrypted.tag(), "-z");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Vault storage migration is only applicable to encrypted or credential-bearing tasks"));

    // 2. Conflicting flags are rejected
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    code = cmd.execute("m", "--path", volDir.toString(), "t", unencrypted.tag(), "-k", "-z");
    assertNotEquals(0, code);
    assertTrue(err.toString().contains("Conflicting vault storage options"));
  }

  @Test
  void cliNormalizedOptionsCoverage() throws IOException {
    Path volDir = tempDir.resolve("cli_norm_vol");
    Path plainDir = tempDir.resolve("cli_norm_plain");
    Files.createDirectories(volDir);
    Files.createDirectories(plainDir);

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // 1. -eu creates extended encryption
    int code = executeWithPassword("secret", 
      "create", "task", "copy", ":" + vtag + ":", "local:" + plainDir.resolve("dst1"), "-eu");
    assertEquals(0, code, () -> "stderr: " + err);
    Manager m = managerFor(volDir);
    Task t1 = m.tasks().values().stream().filter(t -> t.destination().toString().contains("dst1")).findFirst().orElseThrow();
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(t1.serialize().get("rclone").get("encrypt"), false));

    // 2. -d with -u creates extended decryption
    code = executeWithPassword("secret", 
      "create", "task", "copy", "local:" + plainDir.resolve("dst1"), ":" + vtag + ":sub", "-d", "-u");
    assertEquals(0, code, () -> "stderr: " + err);
    m = managerFor(volDir);
    Task t2 = m.tasks().values().stream().filter(t -> t.source().toString().contains("dst1")).findFirst().orElseThrow();
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(t2.serialize().get("rclone").get("decrypt"), false));

    // 3. -x / --exclude-ext and -i / --include-ext filters
    code = cmd.execute(
      "create", "task", "update", ":" + vtag + ":", "local:" + plainDir.resolve("dst2"),
      "--include-ext", "txt,md", "--exclude-ext", "tmp,log");
    assertEquals(0, code, () -> "stderr: " + err);
    m = managerFor(volDir);
    Task t3 = m.tasks().values().stream().filter(t -> t.destination().toString().contains("dst2")).findFirst().orElseThrow();
    assertEquals(List.of("*.txt", "*.md"), ((IncludeFilters) t3).include());
    assertEquals(List.of("*.tmp", "*.log"), t3.exclude());

    // 4. Modify task using -t for morphing and the long ext-filter options
    code = cmd.execute(
      "m", "t", t3.tag(), "-t", "replicate", "--include-ext", "java", "--exclude-ext", "class");
    assertEquals(0, code, () -> "stderr: " + err);
    m = managerFor(volDir);
    Task morphed = m.selectTasks().get(t3.tag());
    assertInstanceOf(Replicate.class, morphed);
    assertEquals("replicate", morphed.operation());
    assertEquals(List.of("*.java"), ((IncludeFilters) morphed).include());
    assertEquals(List.of("*.class"), morphed.exclude());

    // 5. Removed flags (-T, -F, -C for serve, --extended/-X the old execute short,
    // -V/-K the old vault shorts, -S the old salt short, -i the old include-ext
    // short) are rejected
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    int rejected = cmd.execute("m", "--path", volDir.toString(), "t", t3.tag(), "-T", "copy");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'-T'"));

    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("create", "task", "backup", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "-F", "@default");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'-F'"));

    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("create", "task", "copy", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "-e", "--extended");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'--extended'"));

    // The old execute short is gone (-x is its replacement)
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("create", "task", "copy", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "-X", "--no-cache");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'-X'"));

    // The old include-ext short is gone (long-only now)
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("create", "task", "copy", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "-i", "txt");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'-i'"));

    // The old vault shorts are gone (-z/-k are their replacements)
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("create", "task", "backup", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "-V");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'-V'"));

    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("create", "task", "backup", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "-K");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'-K'"));

    // The old salt short is gone (-s is its replacement)
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("--path", volDir.toString(), "create", "task", "replicate", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "-e", "-S");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'-S'"));

    // The old manual flag pair is gone (--execution replaced it)
    cmd = Main.createCommandLine();
    err = new StringWriter();
    cmd.setErr(new PrintWriter(err));
    rejected = cmd.execute("--path", volDir.toString(), "create", "task", "copy", ":" + vtag + ":", "local:" + plainDir.resolve("dst3"), "--no-manual");
    assertNotEquals(0, rejected);
    assertTrue(err.toString().contains("'--no-manual'"));
  }

  @Test
  void repairCommandRunsTheBackendRepairOperations() throws Exception {
    Assumptions.assumeTrue(available("restic"), "restic not available for integration check");
    Path volDir = tempDir.resolve("cli_repair_vol");
    Path repoDir = tempDir.resolve("cli_repair_repo");
    Path copyDst = tempDir.resolve("cli_repair_dst");
    Files.createDirectories(volDir);

    ProcessBuilder pb = new ProcessBuilder("restic", "init", "-r", repoDir.toString());
    pb.environment().put("RESTIC_PASSWORD", "repairSecret");
    Process p = pb.start();
    assertEquals(0, p.waitFor());

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    // The tasks: a restic backup (the repair target) and a plain copy (no repair operations)
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, executeWithPassword("repairSecret", "create", "task", "backup", ":" + vtag + ":", "local:" + repoDir));
    assertEquals(0, cmd.execute("create", "task", "copy", ":" + vtag + ":", "local:" + copyDst));
    Manager m = managerFor(volDir);
    String backupTag = m.tasks().values().stream().filter(t -> t instanceof Backup).findFirst().orElseThrow().tag();
    String copyTag = m.tasks().values().stream().filter(t -> t instanceof Copy).findFirst().orElseThrow().tag();

    // 1. The repair on the backup task: the dry-run preview + the real stale-only unlock
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("repair", backupTag), () -> "stderr: " + err);
    assertTrue(out.toString().contains("remove stale locks"), () -> "out: " + out);
    assertTrue(out.toString().contains("success"), () -> "out: " + out);

    // 2. The repair on the rclone task: the backend provides no operations
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertNotEquals(0, cmd.execute("repair", copyTag));
    assertTrue(err.toString().contains("no repair operations"));

    // 3. The destructive remove-all is gated behind --force
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertNotEquals(0, cmd.execute("repair", "--all", backupTag));
    assertTrue(err.toString().contains("requires --force"));

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("repair", "--all", "--force", backupTag), () -> "stderr: " + err);
    assertTrue(out.toString().contains("remove all locks"), () -> "out: " + out);

    // 4. Failed preview aborts the operation and returns nonzero
    Path cfg = repoDir.resolve("config");
    cfg.toFile().setWritable(true);
    Files.writeString(cfg, "corrupted-content");
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertNotEquals(0, cmd.execute("repair", backupTag));
    assertTrue(out.toString().contains("failure"), () -> "out: " + out);
  }

  @Test
  void runBatchReportsPartialSuccessAndReturnsCode3() throws IOException {
    Path volDir = tempDir.resolve("partial_vol");
    Path dstDir = tempDir.resolve("partial_dst");
    Files.createDirectories(volDir);
    Files.createDirectories(dstDir);
    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);
    assertEquals(0, cmd.execute("create", "task", "copy", ":" + vtag + ":", "local:" + dstDir));

    CommandLine commandLine = Main.createCommandLine();
    StringWriter outWriter = new StringWriter();
    commandLine.setOut(new PrintWriter(outWriter));
    CommonOptions opts = new CommonOptions();

    int exitCode = CliUtils.runBatch(commandLine.getCommandSpec(), opts, null,
      t -> Task.Outcome.PARTIAL,
      "execute",
      "@|green All %d task(s) executed successfully.|@",
      "@|yellow Some of the %d task(s) completed with warnings (partial success).|@",
      "@|red Some of the %d task(s) failed to execute.|@",
      "commit failed");

    assertEquals(3, exitCode);
    assertTrue(outWriter.toString().contains("partial"));
    assertTrue(outWriter.toString().contains("completed with warnings"));
  }

  @Test
  void createAndModifyRestoreAcceptsForeignSnapshotTag() throws Exception {
    Assumptions.assumeTrue(available("restic"), "restic not available for integration check");
    Path volDir = tempDir.resolve("cli_foreign_restore_vol");
    Path repoDir = tempDir.resolve("cli_foreign_restore_repo");
    Path dataDir = tempDir.resolve("cli_foreign_restore_data");
    Files.createDirectories(volDir);
    Files.createDirectories(dataDir);
    Files.writeString(dataDir.resolve("sample.txt"), "hello cli foreign restore");

    ProcessBuilder initPb = new ProcessBuilder("restic", "init", "-r", repoDir.toString());
    initPb.environment().put("RESTIC_PASSWORD", "foreign-cli-pass");
    assertEquals(0, initPb.start().waitFor());

    ProcessBuilder backupPb = new ProcessBuilder("restic", "-r", repoDir.toString(), "backup",
      "--tag", "bitferry,foreignCliTag", dataDir.toString());
    backupPb.environment().put("RESTIC_PASSWORD", "foreign-cli-pass");
    assertEquals(0, backupPb.start().waitFor());

    assertEquals(0, cmd.execute("create", "volume", volDir.toString()));
    probeRoot(volDir);
    String vtag = volumeTag(managerFor(volDir), volDir);

    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = executeWithPassword("foreign-cli-pass", "create", "task", "restore",
      "local:" + repoDir, ":" + vtag + ":restored", "-b", "foreignCliTag");
    assertEquals(0, code, () -> "stderr: " + err);

    Manager m = managerFor(volDir);
    Restore restore = (Restore) m.tasks().values().stream()
      .filter(t -> t instanceof Restore)
      .findFirst().orElseThrow();
    assertEquals("foreignCliTag", restore.snapshotTag());

    // Modify using snapshot shortId discovered from the repository
    String shortId = restore.snapshots().getFirst().shortId();
    cmd = Main.createCommandLine();
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    assertEquals(0, cmd.execute("modify", "task", restore.tag(), "-b", shortId), () -> "stderr: " + err);

    Restore reloaded = (Restore) managerFor(volDir).task(restore.tag());
    assertEquals(shortId, reloaded.snapshotTag());
  }
}
