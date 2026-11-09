package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.vault.Passwords;
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
    volume = new Volume(manager, dir.resolve("volume"));
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
    Synchronize task = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("default"));
    JsonNode data = task.serialize();
    assertEquals(Rclone.PROFILE.get("default"), Task.strings(data.get("rclone").get("encrypt"), false));
    assertFalse(data.get("rclone").has("decrypt"));
    // The password never travels with the descriptor
    assertFalse(data.has("password"));
    assertEquals("encrypt", task.encryption().operation());
  }

  @Test
  void extendedAndRawProfilesResolve() {
    Synchronize task = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("extended"));
    assertEquals(Rclone.PROFILE.get("extended"), Task.strings(task.serialize().get("rclone").get("encrypt"), false));

    Synchronize raw = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    raw.encrypt("secret", null, Rclone.Encryption.options("-custom,value"));
    assertEquals(List.of("-custom", "value"), Task.strings(raw.serialize().get("rclone").get("encrypt"), false));

    assertThrows(BitferryError.class, () -> Rclone.Encryption.options("nonexistent"));
  }

  @Test
  void decryptModeMirrorsTheEncryptOne() {
    Copy task = new Copy(manager, volumeEncrypted(), volumeSource(), null, List.of(), List.of());
    task.decrypt("secret", null, Rclone.Encryption.options("default"));
    JsonNode data = task.serialize();
    assertEquals(Rclone.PROFILE.get("default"), Task.strings(data.get("rclone").get("decrypt"), false));
    assertFalse(data.get("rclone").has("encrypt"));
    assertEquals("decrypt", task.encryption().operation());
  }

  @Test
  void encryptedTaskRoundTripsThroughTheStorage() throws IOException {
    Synchronize task = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("Our secret password", null, Rclone.Encryption.options("default"));
    manager.commit();

    // The obscured password token is stored in the vault of the source volume
    JsonNode storage = Storage.readJson(dir.resolve("volume").resolve(Bitferry.STORAGE));
    String token = storage.get("vault").get(task.tag()).asString();
    assertEquals("Our secret password", Passwords.decodeToken(token)[0]);

    // A fresh manager restores the task with its encryption mode and password
    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    Synchronize copy = (Synchronize) restored.selectTasks().get(task.tag());
    assertInstanceOf(Rclone.Encrypt.class, copy.encryption());
    assertEquals("Our secret password", copy.encryption().password());
    // The rclone environment carries the obscured (not plain) password token
    String obscured = copy.encryption().processEnvironment().get("RCLONE_CRYPT_PASSWORD");
    assertNotEquals("Our secret password", obscured);
    assertEquals("Our secret password", Passwords.reveal(obscured));
    // The descriptor keeps the encryption section upon the re-serialization
    assertTrue(copy.serialize().get("rclone").has("encrypt"));
  }

  @Test
  void dualPasswordTokensSupportTheSecondPassword() throws IOException {
    Synchronize task = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("main-secret", "file-secret", Rclone.Encryption.options("default"));
    manager.commit();

    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    Synchronize copy = (Synchronize) restored.selectTasks().get(task.tag());
    assertEquals("main-secret", copy.encryption().password());
    assertEquals("file-secret", copy.encryption().password2());
    Map<String, String> environment = copy.encryption().processEnvironment();
    assertEquals("main-secret", Passwords.reveal(environment.get("RCLONE_CRYPT_PASSWORD")));
    assertEquals("file-secret", Passwords.reveal(environment.get("RCLONE_CRYPT_PASSWORD2")));
  }

  @Test
  void bothEncryptionDescriptorsAreRejected() {
    Synchronize task = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("default"));
    JsonNode data = task.serialize();
    ((tools.jackson.databind.node.ObjectNode) data.get("rclone")).set("decrypt", data.get("rclone").get("encrypt").deepCopy());
    assertThrows(BitferryError.class, () -> Task.restore(manager, data));
  }

  @Test
  void localUnencryptedEndIsRejected() {
    Synchronize task = new Synchronize(manager, localSource(), volumeEncrypted(), null, List.of(), List.of());
    BitferryError error = assertThrows(BitferryError.class, () -> task.encrypt("secret", null, Rclone.Encryption.options("default")));
    assertTrue(error.getMessage().contains("encryption requires the unencrypted endpoint to reside within a Bitferry volume"));

    Copy reverse = new Copy(manager, volumeEncrypted(), localSource(), null, List.of(), List.of());
    error = assertThrows(BitferryError.class, () -> reverse.decrypt("secret", null, Rclone.Encryption.options("default")));
    assertTrue(error.getMessage().contains("encryption requires the unencrypted endpoint to reside within a Bitferry volume"));
  }

  @Test
  void noCryptIsTheDefaultMode() {
    Synchronize task = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    assertInstanceOf(Rclone.NoCrypt.class, task.encryption());
    JsonNode data = task.serialize();
    assertFalse(data.get("rclone").has("encrypt"));
    assertFalse(data.get("rclone").has("decrypt"));
    assertTrue(task.encryption().processEnvironment().isEmpty());
    assertThrows(BitferryError.class, () -> task.vaultVolume());
  }

  @Test
  void passwordsMustBeSpecified() {
    Synchronize task = new Synchronize(manager, volumeSource(), volumeEncrypted(), null, List.of(), List.of());
    assertThrows(BitferryError.class, () -> task.encrypt(null, null, Rclone.Encryption.options("default")));
    assertThrows(BitferryError.class, () -> task.encrypt("", null, Rclone.Encryption.options("default")));
  }

  private static List<String> toStringList(JsonNode node) {
    return Task.strings(node, false);
  }
}
