package bitferry;

import bitferry.endpoint.*;
import bitferry.task.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagerTest {

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

  private Task replicateTask() {
    // Local source directory into the second volume's backup path
    return new Replicate(manager,
      Endpoints.parse(manager, "local:" + dir.resolve("first").resolve("data")),
      Endpoints.parse(manager, ":" + second.tag() + ":backup"),
      null, List.of(), List.of());
  }

  @Test
  void findTaskMatchesTheEndpointPairDirectionSensitively() {
    Task task = replicateTask();
    assertNotNull(manager.findTask(task.source(), task.destination()));
    // The reversed pair is a different task
    assertNull(manager.findTask(task.destination(), task.source()));
    assertNull(manager.findTask(Endpoints.parse(manager, "local:/elsewhere"), task.destination()));
    // The deleted tasks do not match
    task.delete();
    assertNull(manager.findTask(task.source(), task.destination()));
  }

  @Test
  void deletingTaskSucceedsWhenThePeerVolumeIsAbsent() {
    // A task bound to both volumes; its descriptor is stored redundantly in both storages
    Task task = new Replicate(manager,
      Endpoints.parse(manager, ":" + first.tag() + ":"),
      Endpoints.parse(manager, ":" + second.tag() + ":backup"),
      null, List.of(), List.of());
    manager.commit();

    // A fresh manager restoring only the first volume; the peer (second) volume is absent
    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("first")));
    Task copy = restored.task(task.tag());
    assertNotNull(copy);

    // The deletion must succeed in spite of the absent peer volume; the tombstone
    // is retained (invisible) until the peer volume returns and the task is collected
    assertDoesNotThrow(copy::delete);
    assertDoesNotThrow(restored::commit);
    assertTrue(copy.isDeleted());

    // The present volume's storage keeps the invisible tombstone
    Manager after = new Manager();
    after.restore(List.of(dir.resolve("first")));
    Task leftover = after.task(task.tag());
    assertNotNull(leftover);
    assertTrue(leftover.isDeleted());
    assertTrue(after.selectTasks().isEmpty());
  }

  @Test
  void volumesAndTasksRoundTripThroughTheManager() {
    Task task = replicateTask();
    manager.commit();

    Manager restored = new Manager();
    restored.restore(List.of(dir.resolve("first"), dir.resolve("second")));
    assertTrue(restored.volumes().containsKey(first.tag()));
    assertTrue(restored.volumes().containsKey(second.tag()));
    // The tasks are stored redundantly in both volumes but restored into a single instance
    Map<String, Task> intact = restored.selectTasks();
    assertEquals(1, intact.size());
    Task copy = intact.get(task.tag());
    assertEquals(task.describe(), copy.describe());
    assertEquals(second.tag(), assertInstanceOf(VolumeEndpoint.class, copy.destination()).vtag());
    assertTrue(copy.isIntact());

    // The partial tag selection
    assertEquals(1, restored.selectTasks(task.tag().substring(0, 4)).size());
    // 'zzzz' can never occur within a hexadecimal tag
    assertThrows(BitferryError.class, () -> restored.selectTasks("zzzz"));
    assertThrows(BitferryError.class, () -> restored.selectTasks("0000"));
  }

  @Test
  void commitIsIdempotentForTheUpToDateVolumes() throws Exception {
    replicateTask();
    manager.commit();
    Path storage = dir.resolve("first").resolve(Bitferry.STORAGE);
    long stamp = java.nio.file.Files.getLastModifiedTime(storage).toMillis();
    manager.commit();
    // The unmodified storage is not rewritten
    assertEquals(stamp, java.nio.file.Files.getLastModifiedTime(storage).toMillis());
  }

  @Test
  void endpointsResolveAgainstTheNestedVolumesByTheLongestRoot() {
    new Volume(manager, dir.resolve("first").resolve("nested"));
    var endpoint = assertInstanceOf(VolumeEndpoint.class,
      manager.endpoint(dir.resolve("first").resolve("nested").resolve("deep")));
    assertEquals(dir.resolve("first").resolve("nested"), manager.volumes().get(endpoint.vtag()).root());
  }

  @Test
  void tagPickingRequiresUnambiguousMatches() {
    Map<String, String> entities = Map.of("abc1", "x", "abc2", "y", "def1", "z");
    assertThrows(BitferryError.class, () -> Manager.pick(entities, List.of("abc"), 1)); // Too many matches
    assertThrows(BitferryError.class, () -> Manager.pick(entities, List.of("qqq"), 1)); // No matches
    assertEquals(List.of("x"), Manager.pick(entities, List.of("abc1"), 1));
    // Without the per-tag requirement the matches are not required to be unique
    assertEquals(2, Manager.pick(entities, List.of("abc"), null).size());
  }

  @Test
  void processingFailsGracefullyWithTheStubExecutor() {
    manager.backendExecutor(bitferry.task.Executor.stub());
    replicateTask();
    // The foundational-port stub executor fails the processing without exceptions
    assertFalse(manager.process());
    // The intact task selection still works
    assertEquals(1, manager.selectTasks().size());
  }

  @Test
  void verbosityDerivesTheVerboseFlag() {
    assertFalse(manager.verbose());
    manager.verbose(true);
    assertEquals(Verbosity.VERBOSE, manager.verbosity);
    assertTrue(manager.verbose());
    manager.verbosity = Verbosity.DEBUG;
    assertTrue(manager.verbose());
  }
}
