package io.bitferry;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class VolumeTest {

  @TempDir
  Path dir;

  @Test
  void newVolumeIsRegisteredAndCommitted() throws IOException {
    Manager manager = new Manager();
    Volume volume = new Volume(manager, dir.resolve("volume"));
    assertTrue(manager.volumes().containsKey(volume.tag()));
    manager.commit();
    Path storage = dir.resolve("volume").resolve(Bitferry.STORAGE);
    assertTrue(Files.isRegularFile(storage));
    JsonNode data = Storage.readJson(storage);
    // The storage format version is tagged 0
    assertEquals("0", data.get("bitferry").asString());
    assertEquals(volume.tag(), data.get("volume").asString());
    assertTrue(data.has("modified"));
    // No tasks and no vault entries yet: the empty sections are omitted
    assertFalse(data.has("tasks"));
    assertFalse(data.has("vault"));
  }

  @Test
  void volumeSurvivesTheManagerRoundTrip() throws IOException {
    Manager manager = new Manager();
    Volume volume = new Volume(manager, dir.resolve("volume"));
    manager.commit();

    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    assertTrue(restored.volumes().containsKey(volume.tag()));
    Volume copy = restored.volumes().get(volume.tag());
    assertEquals(volume.tag(), copy.tag());
    assertEquals(volume.root(), copy.root());
    assertFalse(copy.isModified());
  }

  @Test
  void refusedToOverwriteForeignStorage() throws IOException {
    Path root = dir.resolve("occupied");
    Files.createDirectories(root);
    Files.writeString(root.resolve(Bitferry.STORAGE), "{}");
    Manager manager = new Manager();
    Volume volume = new Volume(manager, root);
    assertThrows(BitferryError.class, manager::commit);
    // The refused volume deregisters: it must not stay registered re-failing every
    // following commit (nor poisoning the manager for the other operations)
    assertFalse(manager.volumes().containsKey(volume.tag()));
    assertDoesNotThrow(manager::commit);
    // The explicit force overcomes the refusal on the re-created volume
    manager.force = true;
    Volume forced = new Volume(manager, root);
    manager.commit();
    assertTrue(Files.isRegularFile(root.resolve(Bitferry.STORAGE)));
    assertNotEquals("{}", Files.readString(root.resolve(Bitferry.STORAGE)));
  }

  @Test
  void failedInitialCommitDeregistersVolume() throws IOException {
    // The failure injection parks a directory at the intermediate image path: the
    // atomic write fails on every platform (EISDIR) - the directory read-only flag
    // is a POSIX-only mechanism (a no-op for the child creation on Windows)
    // WINDOWS: confirm this test goes green there (the open-on-a-directory failure
    // mode, and the empty '~' directory cleanup in the temp dir)
    Path blockedRoot = dir.resolve("blocked");
    Files.createDirectories(blockedRoot);
    Files.createDirectories(blockedRoot.resolve(Bitferry.STORAGE_IMAGE));
    Manager manager = new Manager();
    Volume volume = new Volume(manager, blockedRoot);
    assertThrows(BitferryError.class, manager::commit);
    // The volume whose initial creation failed must deregister from manager
    assertFalse(manager.volumes().containsKey(volume.tag()));
    assertFalse(manager.liveVolumes().containsKey(volume.tag()));
    assertFalse(volume.isLive());
    assertDoesNotThrow(manager::commit);
  }

  @Test
  void simulationModeWritesNothing() {
    Manager manager = new Manager();
    manager.simulate = true;
    Volume volume = new Volume(manager, dir.resolve("volume"));
    manager.commit();
    assertFalse(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));
    assertTrue(manager.liveVolumes().containsKey(volume.tag()));
  }

  @Test
  void simulationConsumesNoDurableBookkeeping() throws IOException, InterruptedException {
    Manager manager = new Manager();
    manager.simulate = true;
    Volume volume = new Volume(manager, dir.resolve("volume"));
    manager.commit();
    assertFalse(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));
    // The simulation leaves the write pending: a following real commit materializes it
    assertTrue(volume.isModified());
    java.time.OffsetDateTime simulated = volume.mtime();
    // The microsecond-truncated mtimes: a clock step guarantees the real commit
    // lands on a distinguishable timestamp on the fast machines
    Thread.sleep(2);
    manager.simulate = false;
    manager.commit();
    assertTrue(Files.isRegularFile(dir.resolve("volume").resolve(Bitferry.STORAGE)));
    assertFalse(volume.isModified());
    assertNotEquals(simulated, volume.mtime());
  }

  @Test
  void simulatedDeletionStaysPendingUntilTheRealCommit() throws IOException {
    Manager manager = new Manager();
    Volume volume = new Volume(manager, dir.resolve("volume"));
    manager.commit();
    assertTrue(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));

    manager.simulate = true;
    volume.delete();
    manager.commit();
    // The simulated deletion removes nothing and stays pending
    assertTrue(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));
    assertTrue(volume.isLive());

    manager.simulate = false;
    manager.commit();
    assertFalse(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));
    assertFalse(volume.isLive());
  }

  @Test
  void deletionRemovesTheStorage() throws IOException {
    Manager manager = new Manager();
    Volume volume = new Volume(manager, dir.resolve("volume"));
    manager.commit();
    assertTrue(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));

    volume.delete();
    manager.commit();
    assertFalse(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));
    assertFalse(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE_IMAGE)));
    assertFalse(volume.isLive());
    assertFalse(manager.liveVolumes().containsKey(volume.tag()));
  }


  @Test
  void corruptedStorageIsReported() throws IOException {
    Path root = dir.resolve("broken");
    Files.createDirectories(root);
    Files.writeString(root.resolve(Bitferry.STORAGE), "{ truncated");
    Manager manager = new Manager();
    assertThrows(BitferryError.class, () -> Volume.restore(manager, root));
  }

  @Test
  void unsupportedStorageVersionIsReported() throws IOException {
    Path root = dir.resolve("future");
    Files.createDirectories(root);
    Files.writeString(root.resolve(Bitferry.STORAGE), "{\"bitferry\": \"8\", \"volume\": \"e42f2d8c\", \"modified\": \"2026-01-01T00:00:00+00:00\"}");
    Manager manager = new Manager();
    BitferryError error = assertThrows(BitferryError.class, () -> Volume.restore(manager, root));
    assertTrue(error.getMessage().toLowerCase().contains("unsupported volume storage version 8"));
  }

  @Test
  void missingStorageVersionIsReported() throws IOException {
    Path root = dir.resolve("missing-ver");
    Files.createDirectories(root);
    Files.writeString(root.resolve(Bitferry.STORAGE), "{\"volume\": \"e42f2d8c\", \"modified\": \"2026-01-01T00:00:00+00:00\"}");
    Manager manager = new Manager();
    BitferryError error = assertThrows(BitferryError.class, () -> Volume.restore(manager, root));
    assertTrue(error.getMessage().toLowerCase().contains("missing volume storage version property 'bitferry'"));
  }

  @Test
  void nonObjectStorageIsReported() throws IOException {
    Path root = dir.resolve("non-object");
    Files.createDirectories(root);
    Files.writeString(root.resolve(Bitferry.STORAGE), "[\"not an object\"]");
    Manager manager = new Manager();
    BitferryError error = assertThrows(BitferryError.class, () -> Volume.restore(manager, root));
    assertTrue(error.getMessage().toLowerCase().contains("invalid volume storage descriptor: expected a json object"));
  }

  @Test
  void endpointResolutionCoversTheVolumeScopeOnly() {
    Manager manager = new Manager();
    Volume volume = new Volume(manager, dir.resolve("volume"));
    var endpoint = assertInstanceOf(io.bitferry.endpoint.VolumeEndpoint.class,
      volume.endpoint(dir.resolve("volume").resolve("sub")));
    assertEquals(volume.tag(), endpoint.vtag());
    assertThrows(BitferryError.class, () -> volume.endpoint(dir.resolve("elsewhere")));
    assertThrows(BitferryError.class, () -> manager.endpoint(dir.resolve("elsewhere")));
  }
}
