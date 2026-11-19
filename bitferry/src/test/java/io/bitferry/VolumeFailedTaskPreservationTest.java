package io.bitferry;

import io.bitferry.endpoint.Endpoints;
import io.bitferry.task.Copy;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

/// The unparsable task descriptors must survive: the storage dump re-emits them
/// verbatim (along with their vault tokens) instead of silently erasing both upon
/// the following commit - a failed descriptor may become parsable after an upgrade,
/// and the vault token may be the only durable copy of the password.
class VolumeFailedTaskPreservationTest {

  @TempDir
  Path dir;

  // The real volume carrying one live task: the storage round-trips through a
  // commit before the broken entry is injected into the file (so the fixture
  // descriptors are the genuine serialized forms, not hand-crafted guesses)
  private Path seedVolume() throws java.io.IOException {
    Manager manager = new Manager();
    Volume volume = Volume.create(manager, dir.resolve("volume"));
    Copy copy = new Copy(manager,
      Endpoints.parse(manager, ":" + volume.tag() + ":data"),
      Endpoints.parse(manager, ":" + volume.tag() + ":replica"),
      null, List.of(), List.of());
    manager.commit(copy);
    return dir.resolve("volume").resolve(Bitferry.STORAGE);
  }

  @Test
  void failedDescriptorAndItsTokenSurviveTheCommit() throws java.io.IOException {
    Path storage = seedVolume();
    // Inject the unparsable descriptor (the unknown operation fails the discriminator
    // dispatch) and the matching vault token: the 'task' tag inside is what the vault
    // entry keys on
    ObjectNode data = (ObjectNode) Storage.readJson(storage);
    ArrayNode tasks = (ArrayNode) data.get("tasks");
    assertEquals(1, tasks.size());
    ObjectNode broken = tasks.addObject();
    broken.put("task", "deadbee1");
    broken.put("operation", "nosuchoperation");
    broken.put("modified", "2026-01-01T00:00:00+00:00");
    data.putObject("vault").put("deadbee1", "obscured-token");
    Files.writeString(storage, Storage.toJson(data));

    // The restore: the broken descriptor fails while the volume and the live task
    // survive, and the failure travels into the manager's loadErrors
    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    assertFalse(restored.volumes().isEmpty());
    assertEquals(1, restored.tasks().size());
    assertFalse(restored.loadErrors().isEmpty());
    assertTrue(restored.loadErrors().stream().anyMatch(x -> x.contains("deadbee1")));

    // The commit re-emits the preserved descriptor under its own section (the tasks
    // section keeps the polymorphic discriminator for the live ones) and the token:
    // nothing is erased
    restored.force = true;
    restored.commit();
    JsonNode rewritten = Storage.readJson(storage);
    assertEquals(1, rewritten.get("tasks").size());
    JsonNode preserved = rewritten.get("preserved");
    assertNotNull(preserved);
    assertEquals(1, preserved.size());
    assertEquals("deadbee1", preserved.get(0).get("task").asString());
    assertEquals("nosuchoperation", preserved.get(0).get("operation").asString());
    assertEquals("obscured-token", rewritten.get("vault").get("deadbee1").asString());
  }

  @Test
  void orphanedTokensAreStillPurged() throws java.io.IOException {
    // The preservation must not turn the vault into an append-only log: the tokens
    // of the tasks no descriptor refers to keep being garbage-collected
    Path storage = seedVolume();
    ObjectNode data = (ObjectNode) Storage.readJson(storage);
    data.putObject("vault").put("deadbeef", "ghost-token");
    Files.writeString(storage, Storage.toJson(data));

    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("volume")));
    assertEquals(1, restored.tasks().size());
    restored.force = true;
    restored.commit();
    JsonNode rewritten = Storage.readJson(storage);
    assertFalse(rewritten.has("vault"));
  }
}
