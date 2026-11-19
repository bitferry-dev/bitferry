package io.bitferry.vault;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import io.bitferry.task.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class VaultTest {

  // The no-op command executor keeping the repository initialization of the backup
  // tasks hermetic: the vault tests exercise the token storage, not the restic backend
  private static final CommandExecutor NOOP = (command, workingDirectory, environment, output) -> 0;

  @TempDir
  Path dir;

  Manager manager;
  Volume volume;

  @BeforeEach
  void setUp() {
    manager = new Manager();
    manager.backendExecutor(Executor.system(NOOP));
    volume = Volume.create(manager, dir.resolve("volume"));
  }

  private Endpoint volumeSource() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":");
  }

  private Endpoint volumeRepo() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":repo");
  }

  private Endpoint volumeRestored() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":restored");
  }

  @Test
  void backupPasswordIsStoredInTheSourceVolumeStorage() throws IOException {
    Backup backup = new Backup(manager, volumeSource(), volumeRepo(), "Our secret password", null, null, null, null, List.of());
    manager.commit(backup);

    // The obscured token travels in the storage file's vault section
    JsonNode storage = Storage.readJson(dir.resolve("volume").resolve(Bitferry.STORAGE));
    String token = storage.get("vault").get(backup.tag()).asString();
    assertNotEquals("Our secret password", token); // Never the plain form
    assertEquals("Our secret password", Passwords.decodeToken(token)[0]);
  }

  @Test
  void restorePasswordIsStoredInTheDestinationVolumeStorage() throws IOException {
    Restore restore = new Restore(manager, volumeRepo(), volumeRestored(), "restore-pass", null, List.of(), List.of());
    manager.commit(restore);

    JsonNode storage = Storage.readJson(dir.resolve("volume").resolve(Bitferry.STORAGE));
    String token = storage.get("vault").get(restore.tag()).asString();
    assertEquals("restore-pass", Passwords.decodeToken(token)[0]);
  }

  @Test
  void passwordSurvivesTheManagerRoundTrip() throws IOException {
    Backup backup = new Backup(manager, volumeSource(), volumeRepo(), "Our secret password", null, null, null, null, List.of());
    manager.commit(backup);

    // A fresh manager restores the task and its password from the vault section lazily
    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    Backup copy = (Backup) restored.selectTasks().get(backup.tag());
    assertNotNull(copy);
    assertEquals("Our secret password", copy.password());
    assertEquals(Map.of("RESTIC_PASSWORD", "Our secret password"), copy.processEnvironment());
  }

  @Test
  void localBackupSourceIsRejected() {
    Endpoint local = Endpoints.parse(manager, "local:" + dir.resolve("plain"));
    BitferryError error = assertThrows(BitferryError.class,
      () -> new Backup(manager, local, volumeRepo(), "secret", null, null, null, null, List.of()));
    assertTrue(error.getMessage().contains("backup directory endpoint must reside within a Bitferry volume"));
  }

  @Test
  void localRestoreDestinationIsRejected() {
    Endpoint local = Endpoints.parse(manager, "local:" + dir.resolve("plain"));
    BitferryError error = assertThrows(BitferryError.class,
      () -> new Restore(manager, volumeRepo(), local, "secret", null, List.of(), List.of()));
    assertTrue(error.getMessage().contains("restore destination endpoint must reside within a Bitferry volume"));
  }

  @Test
  void passwordsMustBeSpecified() {
    assertThrows(BitferryError.class, () -> new Backup(manager, volumeSource(), volumeRepo(), null, null, null, null, null, List.of()));
    assertThrows(BitferryError.class, () -> new Backup(manager, volumeSource(), volumeRepo(), "", null, null, null, null, List.of()));
  }

  @Test
  void vaultEntriesOfTheDeletedTasksArePurged() throws IOException {
    Backup backup = new Backup(manager, volumeSource(), volumeRepo(), "Our secret password", null, null, null, null, List.of());
    manager.commit(backup);
    assertTrue(Storage.readJson(dir.resolve("volume").resolve(Bitferry.STORAGE)).has("vault"));

    backup.delete();
    manager.commit();
    JsonNode storage = Storage.readJson(dir.resolve("volume").resolve(Bitferry.STORAGE));
    assertFalse(storage.has("vault")); // The dead task's token is purged
  }
}
