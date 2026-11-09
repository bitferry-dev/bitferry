package bitferry;

import bitferry.endpoint.*;
import bitferry.task.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/// The deletion tombstone lifecycle: a task deleted in spite of the absence of a peer
/// volume is recorded with the `deleted` marker, becomes invisible for all the
/// operations, propagates through the conflicting restorations once the peer returns
/// and is removed physically by the garbage collection once all its referred volumes
/// become visible.
class TombstoneTest {

  @TempDir
  Path dir;

  Manager manager;
  Volume first;
  Volume second;

  @BeforeEach
  void setUp() {
    manager = new Manager();
    first = new Volume(manager, dir.resolve("first"));
    second = new Volume(manager, dir.resolve("second"));
  }

  private Task bindTask() {
    return new Synchronize(manager,
      Endpoints.parse(manager, ":" + first.tag() + ":"),
      Endpoints.parse(manager, ":" + second.tag() + ":backup"),
      null, List.of(), List.of());
  }

  @Test
  void normalDescriptorsCarryNoDeletedKey() throws IOException {
    Task task = bindTask();
    manager.commit();
    JsonNode descriptor = Storage.readJson(dir.resolve("first").resolve(Bitferry.STORAGE)).get("tasks").get(0);
    assertFalse(descriptor.has("deleted"));
  }

  @Test
  void deletionWithAbsentPeerRecordsTheTombstone() throws IOException {
    Task task = bindTask();
    manager.commit();

    // A fresh manager restoring only the first volume; the peer (second) is absent
    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("first")));
    restored.task(task.tag()).delete();
    restored.commit();

    // The tombstone persists in the present storage while the absent one keeps the plain copy
    JsonNode a = Storage.readJson(dir.resolve("first").resolve(Bitferry.STORAGE));
    assertTrue(a.get("tasks").get(0).get("deleted").asBoolean());
    JsonNode b = Storage.readJson(dir.resolve("second").resolve(Bitferry.STORAGE));
    assertFalse(b.get("tasks").get(0).has("deleted"));
  }

  @Test
  void tombstonedTaskIsInvisible() throws IOException {
    Task task = bindTask();
    manager.commit();
    // Delete with the peer absent: the tombstone is recorded in the present storage
    Manager partial = new Manager();
    partial.restore(List.of(dir.resolve("first")));
    partial.task(task.tag()).delete();
    partial.commit();

    // The tombstoned task is invisible for all the operations
    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("first")));
    Task tombstone = restored.task(task.tag());
    assertNotNull(tombstone);
    assertTrue(tombstone.isDeleted());
    assertTrue(restored.selectTasks().isEmpty());
    assertThrows(BitferryError.class, () -> restored.selectLiveTasks(List.of(task.tag())));
    assertNull(restored.findTask(tombstone.source(), tombstone.destination()));
    assertTrue(restored.process());
  }

  @Test
  void tombstoneWinsUponThePeerReturn() throws IOException {
    Task task = bindTask();
    manager.commit();
    Manager partial = new Manager();
    partial.restore(List.of(dir.resolve("first")));
    partial.task(task.tag()).delete();
    partial.commit();

    // Both volumes restored: the plain copy and the tombstone conflict, the tombstone
    // wins; the commit-time garbage collection then removes it from all the storages
    Manager full = new Manager();
    full.restore(List.of(dir.resolve("first"), dir.resolve("second")));
    Task copy = full.task(task.tag());
    assertNotNull(copy);
    assertTrue(copy.isDeleted());
    assertTrue(full.selectTasks().isEmpty());
    full.commit();
    assertFalse(Storage.readJson(dir.resolve("first").resolve(Bitferry.STORAGE)).has("tasks"));
    assertFalse(Storage.readJson(dir.resolve("second").resolve(Bitferry.STORAGE)).has("tasks"));
  }

  @Test
  void deletionSurvivesThePeerVanishingMidFlight() throws IOException {
    Task task = bindTask();
    manager.commit();

    // Both volumes are restored and the deletion is issued; the peer volume then
    // vanishes before the commit (its storage never receives the tombstone)
    Manager live = new Manager();
    live.restore(List.of(dir.resolve("first"), dir.resolve("second")));
    live.task(task.tag()).delete();

    // Only the surviving volume commits; the vanished one keeps the stale plain copy
    live.selectVolumes(List.of(first.tag())).getFirst().commit();

    // The peer returns: the tombstone (bumped timestamp) wins over the plain copy -
    // the deleted task does not resurrect - and is collected once the both volumes
    // are visible again
    Manager returned = new Manager();
    returned.restore(List.of(dir.resolve("first"), dir.resolve("second")));
    Task conflict = returned.task(task.tag());
    assertNotNull(conflict);
    assertTrue(conflict.isDeleted());
    assertTrue(returned.selectTasks().isEmpty());

    returned.commit();
    assertFalse(Storage.readJson(dir.resolve("first").resolve(Bitferry.STORAGE)).has("tasks"));
    assertFalse(Storage.readJson(dir.resolve("second").resolve(Bitferry.STORAGE)).has("tasks"));
  }

  @Test
  void tombstoneIsCollectedOnceAllVolumesAreVisible() throws IOException {
    Task task = bindTask();
    manager.commit();
    Manager partial = new Manager();
    partial.restore(List.of(dir.resolve("first")));
    partial.task(task.tag()).delete();
    partial.commit();

    // All volumes restored: the garbage collection removes the tombstone physically
    Manager full = new Manager();
    full.restore(List.of(dir.resolve("first"), dir.resolve("second")));
    full.commit();

    Manager clean = new Manager();
    clean.restore(List.of(dir.resolve("first"), dir.resolve("second")));
    // The collected task is gone entirely: not registered, not stored, not visible
    assertNull(clean.task(task.tag()));
    assertTrue(clean.selectTasks().isEmpty());
    assertFalse(Storage.readJson(dir.resolve("first").resolve(Bitferry.STORAGE)).has("tasks"));
    assertFalse(Storage.readJson(dir.resolve("second").resolve(Bitferry.STORAGE)).has("tasks"));
  }

  @Test
  void physicalDeletionWhenAllVolumesArePresentEmitsNoTombstone() throws IOException {
    Task task = bindTask();
    manager.commit();
    manager.task(task.tag()).delete();
    manager.commit();
    // The descriptor is dropped immediately: no tombstone is recorded
    assertFalse(Storage.readJson(dir.resolve("first").resolve(Bitferry.STORAGE)).has("tasks"));
    assertFalse(Storage.readJson(dir.resolve("second").resolve(Bitferry.STORAGE)).has("tasks"));
  }
}
