package org.bitferry.vault;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.model.StoredCredential;
import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

/// Credential creation must not leak persistent provider writes before the task commits.
class CredentialTransactionTest {

  private static final CommandExecutor NOOP = (command, workingDirectory, environment, output) -> 0;

  @TempDir
  Path dir;

  Manager manager;
  Volume volume;

  private Field keyringStore;
  private SecretStore<StoredCredential> originalKeyring;
  private Map<String, StoredCredential> keyringEntries;

  @BeforeEach
  void setUp() {
    manager = new Manager();
    manager.backendExecutor(Executor.system(NOOP));
    volume = Volume.create(manager, dir.resolve("volume"));
  }

  @AfterEach
  void restoreKeyring() throws ReflectiveOperationException {
    if (keyringStore != null) {
      keyringStore.set(KeyringVault.INSTANCE, originalKeyring);
    }
  }

  private Endpoint volumeSource() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":");
  }

  private Endpoint volumeRepo() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":repo");
  }

  /// Install an in-memory SecretStore behind the singleton without touching the host keyring.
  @SuppressWarnings("unchecked")
  private void useFakeKeyring() throws ReflectiveOperationException {
    keyringEntries = new LinkedHashMap<>();
    SecretStore<StoredCredential> fake = (SecretStore<StoredCredential>) Proxy.newProxyInstance(
      SecretStore.class.getClassLoader(),
      new Class<?>[]{SecretStore.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "add" -> {
          keyringEntries.put((String) args[0], (StoredCredential) args[1]);
          yield true;
        }
        case "get" -> keyringEntries.get((String) args[0]);
        case "delete" -> {
          keyringEntries.remove((String) args[0]);
          yield method.getReturnType() == boolean.class ? true : null;
        }
        case "toString" -> "fake keyring";
        default -> throw new UnsupportedOperationException(method.toString());
      }
    );
    keyringStore = KeyringVault.class.getDeclaredField("store");
    keyringStore.setAccessible(true);
    originalKeyring = (SecretStore<StoredCredential>) keyringStore.get(KeyringVault.INSTANCE);
    keyringStore.set(KeyringVault.INSTANCE, fake);
  }

  private void assertKeyringCredentials(Task task, String password, String salt) {
    StoredCredential passwordEntry = keyringEntries.get(KeyringVault.passwordKey(task.tag()));
    assertNotNull(passwordEntry, "The real commit must publish the password");
    assertEquals(password, new String(passwordEntry.getPassword()));
    StoredCredential saltEntry = keyringEntries.get(KeyringVault.saltKey(task.tag()));
    if (salt == null) {
      assertNull(saltEntry);
    } else {
      assertNotNull(saltEntry, "The real commit must publish the second credential");
      assertEquals(salt, new String(saltEntry.getPassword()));
    }
  }

  @Test
  void simulatedResticCreationStagesKeyringCredentialsUntilRealCommit() throws Exception {
    useFakeKeyring();
    manager.simulate = true;
    Backup backup = new Backup(manager, volumeSource(), volumeRepo(), "restic-secret", null, null, null, null, null, List.of(), true);

    assertTrue(keyringEntries.isEmpty(), "The constructor must only stage the password");
    manager.commit(backup);
    assertTrue(keyringEntries.isEmpty(), "The simulated commit must not write the keyring");
    assertNull(volume.readVault(backup.tag()));
    assertEquals("restic-secret", backup.password(), "The staged password must remain usable in memory");

    manager.simulate = false;
    manager.commit();
    assertKeyringCredentials(backup, "restic-secret", null);
    assertNull(volume.readVault(backup.tag()), "A keyring-held token must not leak into the volume vault");
  }

  @Test
  void simulatedRcloneCreationStagesKeyringCredentialsUntilRealCommit() throws Exception {
    useFakeKeyring();
    manager.simulate = true;
    Replicate task = new Replicate(manager, volumeSource(), volumeRepo(), null, List.of(), List.of());
    task.encrypt("crypt-secret", "filename-secret", Rclone.Encryption.options("@default"), true);

    assertTrue(keyringEntries.isEmpty(), "Encryption setup must only stage the password pair");
    manager.commit(task);
    assertTrue(keyringEntries.isEmpty(), "The simulated commit must not write either keyring entry");
    assertEquals("crypt-secret", task.encryption().password());
    assertEquals("filename-secret", task.encryption().password2());

    manager.simulate = false;
    manager.commit();
    assertKeyringCredentials(task, "crypt-secret", "filename-secret");
    assertNull(volume.readVault(task.tag()));
  }

  @Test
  void rejectedKeyringTasksWriteNoCredentials() throws Exception {
    useFakeKeyring();
    Endpoint local = Endpoints.parse(manager, "local:" + dir.resolve("plain"));

    assertThrows(BitferryError.class,
      () -> new Backup(manager, local, volumeRepo(), "secret", null, null, null, null, null, List.of(), true));
    Replicate rclone = new Replicate(manager, local, volumeRepo(), null, List.of(), List.of());
    assertThrows(BitferryError.class,
      () -> rclone.encrypt("secret", null, Rclone.Encryption.options("@default"), true));

    assertTrue(keyringEntries.isEmpty(), "Rejected endpoints must not leave keyring residue");
  }

  @Test
  void failedResticInitializationWritesNoCredentials() throws Exception {
    useFakeKeyring();
    manager.backendExecutor(new Executor() {
      @Override
      public Task.Outcome run(Task task, java.util.function.Consumer<String> output) {
        return Task.Outcome.FAILURE;
      }

      @Override
      public void format(Task task) {
        throw new BitferryError("repository initialization failure");
      }
    });
    Backup backup = new Backup(manager, volumeSource(), volumeRepo(), "secret", null, null, null, null, null, List.of(), true);

    assertThrows(BitferryError.class, () -> manager.commit(backup));

    assertTrue(keyringEntries.isEmpty(), "Failed repository initialization must not publish the password");
    assertNull(volume.readVault(backup.tag()));
    assertFalse(manager.tasks().containsKey(backup.tag()));
  }

  @Test
  void failedRcloneConformanceWritesNoCredentials() throws Exception {
    useFakeKeyring();
    manager.backendExecutor(new Executor() {
      @Override
      public Task.Outcome run(Task task, java.util.function.Consumer<String> output) {
        return Task.Outcome.FAILURE;
      }

      @Override
      public void conform(Task task) {
        throw new BitferryError("encryption conformance failure");
      }
    });
    Replicate task = new Replicate(manager, volumeSource(), volumeRepo(), null, List.of(), List.of());
    task.encrypt("secret", null, Rclone.Encryption.options("@default"), true);

    assertThrows(BitferryError.class, () -> manager.commit(task));

    assertTrue(keyringEntries.isEmpty(), "Failed conformance must not publish the password pair");
    assertNull(volume.readVault(task.tag()));
    assertFalse(manager.tasks().containsKey(task.tag()));
  }

  @Test
  void failedVolumeCommitRollsBackAndRetriesKeyringPublication() throws Exception {
    useFakeKeyring();
    Backup backup = new Backup(manager, volumeSource(), volumeRepo(), "secret", null, null, null, null, null, List.of(), true);
    Path storage = dir.resolve("volume").resolve(Bitferry.STORAGE);
    Files.delete(storage);
    Files.createDirectory(storage); // Force the storage write to fail after credential publication.

    assertThrows(BitferryError.class, () -> manager.commit(backup));
    assertTrue(keyringEntries.isEmpty(), "A failed descriptor commit must roll back the published password");

    Files.delete(storage);
    manager.commit(backup);
    assertKeyringCredentials(backup, "secret", null);
    assertTrue(manager.tasks().containsKey(backup.tag()));
  }
}
