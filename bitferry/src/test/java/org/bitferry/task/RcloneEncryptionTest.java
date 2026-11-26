package org.bitferry.task;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.vault.Passwords;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class RcloneEncryptionTest {

  @TempDir
  Path dir;

  Manager manager;
  Volume volume;

  @BeforeEach
  void setUp() {
    manager = new Manager();
    volume = Volume.create(manager, dir.resolve("volume"));
  }

  private Endpoint volumeSource() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":");
  }

  private Endpoint volumeEncrypted() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":enc");
  }

  private Endpoint localSource() {
    return Endpoints.parse(manager, "local:" + dir.resolve("plain"));
  }

  @Test
  void encryptModeCarriesTheProfileDescriptor() {
    Replicate task = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));
    JsonNode data = task.serialize();
    assertEquals(Rclone.PROFILE.get("default"), Task.strings(data.get("rclone").get("encrypt"), false));
    assertFalse(data.get("rclone").has("decrypt"));
    // The password never travels with the descriptor
    assertFalse(data.has("password"));
    assertEquals("encrypt", task.encryption().operation());
    assertEquals("encrypt+replicate", task.displayOperation());
    assertEquals("encrypt+replicate " + volumeSource().describe() + " → " + volumeEncrypted().describe(), task.describe());
  }

  @Test
  void extendedAndRawProfilesResolve() {
    Replicate task = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@extended"));
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(task.serialize().get("rclone").get("encrypt"), false));

    Replicate raw = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    raw.encrypt("secret", null, Rclone.Encryption.options("-custom,value"));
    assertEquals(List.of("-custom", "value"), Task.strings(raw.serialize().get("rclone").get("encrypt"), false));

    // Directly specified options without leading dash are passed verbatim
    Replicate rawNoDash = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    rawNoDash.encrypt("secret", null, Rclone.Encryption.options("custom-opt,val"));
    assertEquals(List.of("custom-opt", "val"), Task.strings(rawNoDash.serialize().get("rclone").get("encrypt"), false));

    assertThrows(BitferryError.class, () -> Rclone.Encryption.options("@nonexistent"));
  }

  @Test
  void decryptModeMirrorsTheEncryptOne() {
    Copy task = new Copy(manager, volumeEncrypted(), volumeSource(), null, List.of(), List.of());
    task.decrypt("secret", null, Rclone.Encryption.options("@default"));
    JsonNode data = task.serialize();
    assertEquals(Rclone.PROFILE.get("default"), Task.strings(data.get("rclone").get("decrypt"), false));
    assertFalse(data.get("rclone").has("encrypt"));
    assertEquals("decrypt", task.encryption().operation());
    assertEquals("decrypt+copy", task.displayOperation());
    assertEquals("decrypt+copy " + volumeEncrypted().describe() + " → " + volumeSource().describe(), task.describe());
  }

  @Test
  void encryptedTaskRoundTripsThroughTheStorage() throws IOException {
    Replicate task = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("Our secret password", null, Rclone.Encryption.options("@default"));
    manager.commit(task);

    // The obscured password token is stored in the vault of the source volume as JSON
    JsonNode storage = Storage.readJson(dir.resolve("volume").resolve(Bitferry.STORAGE));
    JsonNode entry = storage.get("vault").get(task.tag());
    assertNotNull(entry);
    String token = entry.get("password").asString();
    assertEquals("Our secret password", Passwords.bitferryReveal(token));

    // A fresh manager restores the task with its encryption mode and password
    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    Replicate copy = (Replicate) restored.selectTasks().get(task.tag());
    assertInstanceOf(Rclone.Encrypt.class, copy.encryption());
    assertEquals("Our secret password", copy.encryption().password());
    // The rclone environment carries the obscured (not plain) password token
    String obscured = copy.encryption().processEnvironment().get("RCLONE_CRYPT_PASSWORD");
    assertNotEquals("Our secret password", obscured);
    assertEquals("Our secret password", Passwords.rcloneReveal(obscured));
    // The descriptor keeps the encryption section upon the re-serialization
    assertTrue(copy.serialize().get("rclone").has("encrypt"));
  }

  @Test
  void dualPasswordTokensSupportTheSecondPassword() throws IOException {
    Replicate task = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("main-secret", "file-secret", Rclone.Encryption.options("@default"));
    manager.commit(task);

    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    Replicate copy = (Replicate) restored.selectTasks().get(task.tag());
    assertEquals("main-secret", copy.encryption().password());
    assertEquals("file-secret", copy.encryption().password2());
    Map<String, String> environment = copy.encryption().processEnvironment();
    assertEquals("main-secret", Passwords.rcloneReveal(environment.get("RCLONE_CRYPT_PASSWORD")));
    assertEquals("file-secret", Passwords.rcloneReveal(environment.get("RCLONE_CRYPT_PASSWORD2")));
  }

  @Test
  void bothEncryptionDescriptorsAreRejected() {
    Replicate task = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"));
    JsonNode data = task.serialize();
    ((tools.jackson.databind.node.ObjectNode) data.get("rclone")).set("decrypt", data.get("rclone").get("encrypt").deepCopy());
    assertThrows(BitferryError.class, () -> Task.restore(manager, data));
  }

  @Test
  void localUnencryptedEndIsRejected() {
    Replicate task = new Replicate(manager, localSource(), volumeEncrypted(), null, List.of(), List.of());
    BitferryError error = assertThrows(BitferryError.class, () -> task.encrypt("secret", null, Rclone.Encryption.options("@default")));
    assertTrue(error.getMessage().toLowerCase().contains("unencrypted endpoint to reside"));

    Copy reverse = new Copy(manager, volumeEncrypted(), localSource(), null, List.of(), List.of());
    error = assertThrows(BitferryError.class, () -> reverse.decrypt("secret", null, Rclone.Encryption.options("@default")));
    assertTrue(error.getMessage().toLowerCase().contains("unencrypted endpoint to reside"));
  }

  @Test
  void noCryptIsTheDefaultMode() {
    Replicate task = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    assertInstanceOf(Rclone.NoCrypt.class, task.encryption());
    JsonNode data = task.serialize();
    assertFalse(data.get("rclone").has("encrypt"));
    assertFalse(data.get("rclone").has("decrypt"));
    assertTrue(task.encryption().processEnvironment().isEmpty());
    assertEquals("replicate", task.displayOperation());
    assertEquals("replicate " + volumeSource().describe() + " → " + volumeEncrypted().describe(), task.describe());
    assertThrows(BitferryError.class, () -> task.vaultVolume());
  }

  @Test
  void passwordsMustBeSpecified() {
    Replicate task = new Replicate(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    assertThrows(BitferryError.class, () -> task.encrypt(null, null, Rclone.Encryption.options("@default")));
    assertThrows(BitferryError.class, () -> task.encrypt("", null, Rclone.Encryption.options("@default")));
  }

  @Test
  void realRcloneConformanceRejectsIncompatibleOptionsAndAcceptsCorrectPassword() throws Exception {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for the integration check");

    Path plainDir = dir.resolve("fixture-plain");
    Files.createDirectories(plainDir);
    Files.writeString(plainDir.resolve("test.txt"), "hello conformance");

    Path encDir = dir.resolve("fixture-enc");
    String nullConfig = ExecutorSupport.isWindows() ? "NUL" : "/dev/null";
    // Build encrypted fixture with password A and standard base32 encryption
    ProcessBuilder pb = new ProcessBuilder(
      "rclone", "sync", plainDir.toString(), ":crypt:",
      "--crypt-remote", encDir.toString(),
      "--crypt-filename-encryption", "standard",
      "--crypt-filename-encoding", "base32",
      "--config", nullConfig
    );
    pb.environment().put("RCLONE_CRYPT_PASSWORD", Passwords.rcloneObscure("secretA"));
    Process p = pb.start();
    assertEquals(0, p.waitFor());

    Endpoint src = Endpoints.parse(manager, ":" + volume.tag() + ":");
    Endpoint enc = Endpoints.parse(manager, "local:" + encDir);
    String encPath = Bitferry.canonicalize(encDir).toString();

    // 1. Wrong password is refused and task is deregistered
    Replicate wrongPass = new Replicate(manager, src, enc, null, List.of(), List.of());
    wrongPass.encrypt("secretB", null, Rclone.Encryption.options("@default"));
    BitferryError err = assertThrows(BitferryError.class, () -> manager.commit(wrongPass));
    assertTrue(err.getMessage().contains("the existing encrypted content in '" + encPath + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"));
    assertFalse(manager.tasks().containsKey(wrongPass.tag()));

    Manager restored = new Manager().restore(dir.resolve("volume"));
    assertFalse(restored.tasks().containsKey(wrongPass.tag()));

    // 2. Wrong filename encryption mode (extended base32768 over base32) is refused
    Replicate wrongMode = new Replicate(manager, src, enc, null, List.of(), List.of());
    wrongMode.encrypt("secretA", null, Rclone.Encryption.options("@extended"));
    err = assertThrows(BitferryError.class, () -> manager.commit(wrongMode));
    assertTrue(err.getMessage().contains("the existing encrypted content in '" + encPath + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"));
    assertFalse(manager.tasks().containsKey(wrongMode.tag()));

    // 3. Decrypt task with wrong password is also refused
    Copy wrongDecrypt = new Copy(manager, enc, src, null, List.of(), List.of());
    wrongDecrypt.decrypt("secretB", null, Rclone.Encryption.options("@default"));
    err = assertThrows(BitferryError.class, () -> manager.commit(wrongDecrypt));
    assertTrue(err.getMessage().contains("the existing encrypted content in '" + encPath + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"));
    assertFalse(manager.tasks().containsKey(wrongDecrypt.tag()));

    // 4. Correct password encrypt task succeeds and persists
    Replicate correctPass = new Replicate(manager, src, enc, null, List.of(), List.of());
    correctPass.encrypt("secretA", null, Rclone.Encryption.options("@default"));
    manager.commit(correctPass);
    assertTrue(manager.tasks().containsKey(correctPass.tag()));

    restored = new Manager().restore(dir.resolve("volume"));
    assertTrue(restored.tasks().containsKey(correctPass.tag()));

    // 5. Correct password decrypt task succeeds and persists
    Copy correctDecrypt = new Copy(manager, enc, src, null, List.of(), List.of());
    correctDecrypt.decrypt("secretA", null, Rclone.Encryption.options("@default"));
    manager.commit(correctDecrypt);
    assertTrue(manager.tasks().containsKey(correctDecrypt.tag()));

    restored = new Manager().restore(dir.resolve("volume"));
    assertTrue(restored.tasks().containsKey(correctDecrypt.tag()));
  }

  @Test
  void realRcloneConformanceWithFilenameEncryptionOffRejectsWrongPasswordAndPlaintext() throws Exception {
    Assumptions.assumeTrue(available("rclone"), "rclone not available for the integration check");

    Path plainDir = dir.resolve("fixture-plain-off");
    Files.createDirectories(plainDir);
    Files.writeString(plainDir.resolve("sample.txt"), "hello filename encryption off content");

    Path encDir = dir.resolve("fixture-enc-off");
    String nullConfig = ExecutorSupport.isWindows() ? "NUL" : "/dev/null";
    // Build encrypted fixture with password A and filename encryption off
    ProcessBuilder pb = new ProcessBuilder(
      "rclone", "sync", plainDir.toString(), ":crypt:",
      "--crypt-remote", encDir.toString(),
      "--crypt-filename-encryption", "off",
      "--config", nullConfig
    );
    pb.environment().put("RCLONE_CRYPT_PASSWORD", Passwords.rcloneObscure("secretA"));
    Process p = pb.start();
    assertEquals(0, p.waitFor());

    Endpoint src = Endpoints.parse(manager, ":" + volume.tag() + ":");
    Endpoint enc = Endpoints.parse(manager, "local:" + encDir);
    String encPath = Bitferry.canonicalize(encDir).toString();
    String plainPath = Bitferry.canonicalize(plainDir).toString();
    List<String> encOffOpts = List.of("--crypt-filename-encryption", "off");

    // 1. Wrong password is refused even with filename encryption off
    Replicate wrongPass = new Replicate(manager, src, enc, null, List.of(), List.of());
    wrongPass.encrypt("secretB", null, encOffOpts);
    BitferryError err = assertThrows(BitferryError.class, () -> manager.commit(wrongPass));
    assertTrue(err.getMessage().contains("the existing encrypted content in '" + encPath + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"));
    assertFalse(manager.tasks().containsKey(wrongPass.tag()));

    // 2. Unencrypted plaintext destination is refused
    Endpoint plainEndpoint = Endpoints.parse(manager, "local:" + plainDir);
    Replicate plainTarget = new Replicate(manager, src, plainEndpoint, null, List.of(), List.of());
    plainTarget.encrypt("secretA", null, encOffOpts);
    err = assertThrows(BitferryError.class, () -> manager.commit(plainTarget));
    assertTrue(err.getMessage().contains("the existing encrypted content in '" + plainPath + "' is not compatible with the provided encryption options (wrong password or filename encryption mode)"));
    assertFalse(manager.tasks().containsKey(plainTarget.tag()));

    // 3. Correct password succeeds and persists
    Replicate correctPass = new Replicate(manager, src, enc, null, List.of(), List.of());
    correctPass.encrypt("secretA", null, encOffOpts);
    manager.commit(correctPass);
    assertTrue(manager.tasks().containsKey(correctPass.tag()));

    Manager restored = new Manager().restore(dir.resolve("volume"));
    assertTrue(restored.tasks().containsKey(correctPass.tag()));
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

  private static List<String> toStringList(JsonNode node) {
    return Task.strings(node, false);
  }
}
