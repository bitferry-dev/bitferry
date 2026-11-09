package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.VolumeEndpoint;
import bitferry.task.Backup;
import bitferry.task.Rclone;
import bitferry.task.Restore;
import bitferry.task.Task;
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
    assertTrue(out.toString().contains("process"));
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

    Manager afterDelete = new Manager().restore(volDir);
    assertTrue(afterDelete.tasks().values().stream().noneMatch(t -> t.tag().equals(task.tag())));
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
}
