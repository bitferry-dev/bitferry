package io.bitferry;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

/// The volume behavior upon the mid-flight disappearance (the drive is yanked): the
/// commits refuse to materialize the storage on the parent file system, which the
/// stale mount point directories would otherwise silently host.
class VolumeVanishTest {

  @TempDir
  Path dir;

  @Test
  void disappearedVolumeRefusesStorageWrite() throws IOException {
    Manager manager = new Manager();
    Path root = dir.resolve("volume");
    Files.createDirectories(root);
    Volume volume = new Volume(manager, root);
    volume.commit(); // The initial storage write (pristine -> live)
    assertTrue(Files.isRegularFile(root.resolve(".bitferry")));

    // The volume disappears: the root directory is gone (the mount point may or may
    // not linger on the parent file system)
    try (var entries = Files.walk(root)) {
      entries.sorted(Comparator.reverseOrder()).forEach(p -> {
        try { Files.delete(p); } catch (IOException ignored) {}
      });
    }
    assertFalse(Files.exists(root));

    // The commit refuses to materialize the storage on the parent file system
    volume.touch();
    BitferryError error = assertThrows(BitferryError.class, volume::commit);
    assertTrue(error.getMessage().contains("has disappeared"), error.getMessage());
    assertFalse(Files.exists(root)); // Nothing materialized on the parent
  }

  @Test
  void disappearedVolumeRefusesDeletion() throws IOException {
    Manager manager = new Manager();
    Path root = dir.resolve("volume");
    Files.createDirectories(root);
    Volume volume = new Volume(manager, root);
    volume.commit();

    // The volume disappears before the deletion commit
    try (var entries = Files.walk(root)) {
      entries.sorted(Comparator.reverseOrder()).forEach(p -> {
        try { Files.delete(p); } catch (IOException ignored) {}
      });
    }

    volume.delete();
    BitferryError error = assertThrows(BitferryError.class, volume::commit);
    assertTrue(error.getMessage().contains("has disappeared"), error.getMessage());
    assertFalse(Files.exists(root));
  }
}
