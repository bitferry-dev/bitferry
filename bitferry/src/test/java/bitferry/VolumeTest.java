package bitferry;

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
    // The storage format version is tagged 0.1
    assertEquals("0.1", data.get("bitferry").asString());
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
    manager.force = true;
    manager.commit();
    assertTrue(Files.isRegularFile(root.resolve(Bitferry.STORAGE)));
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
  void deletionRemovesTheStorage() throws IOException {
    Manager manager = new Manager();
    Volume volume = new Volume(manager, dir.resolve("volume"));
    manager.commit();
    assertTrue(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));

    volume.delete(false);
    manager.commit();
    assertFalse(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE)));
    assertFalse(Files.exists(dir.resolve("volume").resolve(Bitferry.STORAGE_IMAGE)));
    assertFalse(volume.isLive());
    assertFalse(manager.liveVolumes().containsKey(volume.tag()));
  }

  @Test
  void deletionWithWipeEmptiesTheDirectory() throws IOException {
    Manager manager = new Manager();
    Path root = dir.resolve("volume");
    Files.createDirectories(root);
    Volume volume = new Volume(manager, root);
    Files.writeString(root.resolve("payload.txt"), "data");
    Files.createDirectories(root.resolve("nested").resolve("deep"));
    Files.writeString(root.resolve("nested").resolve("deep").resolve("payload.txt"), "data");
    manager.commit();

    volume.delete(true);
    manager.commit();
    assertFalse(Files.exists(root.resolve("payload.txt")));
    assertFalse(Files.exists(root.resolve("nested")));
    assertTrue(Files.exists(root)); // The root itself is retained
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
    assertTrue(error.getMessage().contains("unsupported volume storage version 8"));
  }

  @Test
  void endpointResolutionCoversTheVolumeScopeOnly() {
    Manager manager = new Manager();
    Volume volume = new Volume(manager, dir.resolve("volume"));
    var endpoint = assertInstanceOf(bitferry.endpoint.VolumeEndpoint.class,
      volume.endpoint(dir.resolve("volume").resolve("sub")));
    assertEquals(volume.tag(), endpoint.vtag());
    assertThrows(BitferryError.class, () -> volume.endpoint(dir.resolve("elsewhere")));
    assertThrows(BitferryError.class, () -> manager.endpoint(dir.resolve("elsewhere")));
  }
}
