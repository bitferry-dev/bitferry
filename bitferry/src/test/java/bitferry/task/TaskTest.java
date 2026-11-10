package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

  @TempDir
  Path dir;

  Manager manager;
  Volume volume;

  @BeforeEach
  void setUp() {
    manager = new Manager();
    volume = new Volume(manager, dir.resolve("volume"));
  }

  private Endpoint source() {
    return Endpoints.parse(manager, "local:" + dir.resolve("data"));
  }

  private Endpoint destination() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":backup");
  }

  // The volume-root endpoint serving as the volume-bound source for the restic tasks
  private Endpoint volumeSource() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":");
  }

  // The volume-bound restored path endpoint serving as the volume-bound destination for the restore tasks
  private Endpoint volumeRestored() {
    return Endpoints.parse(manager, ":" + volume.tag() + ":restored");
  }

  @Test
  void rcloneOperationsCarryTheirDescriptors() {
    assertEquals("copy", new Copy(manager, source(), destination(), null, List.of(), List.of()).operation());
    assertEquals("update", new Update(manager, source(), destination(), null, List.of(), List.of()).operation());
    assertEquals("synchronize", new Synchronize(manager, source(), destination(), null, List.of(), List.of()).operation());
    assertEquals("equalize", new Equalize(manager, source(), destination(), null, List.of(), List.of()).operation());
    assertEquals("backup", new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of(), List.of()).operation());
    assertEquals("restore", new Restore(manager, destination(), volumeRestored(), "secret", null, List.of(), List.of()).operation());
    // The tasks own their backend operation arguments
    assertEquals(List.of("copy"), new Copy(manager, source(), destination(), null, List.of(), List.of()).operationArgs());
    assertEquals(List.of("copy", "--update"), new Update(manager, source(), destination(), null, List.of(), List.of()).operationArgs());
    assertEquals(List.of("sync"), new Synchronize(manager, source(), destination(), null, List.of(), List.of()).operationArgs());
    assertEquals(List.of("bisync", "--resync"), new Equalize(manager, source(), destination(), null, List.of(), List.of()).operationArgs());
  }

  @Test
  void tasksAreRegisteredAndIntactUponCreation() {
    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of(), List.of());
    assertEquals(task, manager.task(task.tag()));
    assertTrue(task.isIntact());
    assertTrue(task.isLive());
    assertEquals("synchronize " + source().describe() + " --> " + destination().describe(), task.describe());
    assertTrue(task.refersVolume(volume.tag()));
    assertFalse(task.refersVolume("00000000"));
  }

  @Test
  void taskSerializationCarriesTheDescriptors() {
    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of(), List.of());
    JsonNode data = task.serialize();
    // The operation is the discriminator property; the tag travels in-band
    assertEquals("synchronize", data.get("operation").asString());
    assertEquals("rclone", data.get("backend").asString());
    assertEquals(task.tag(), data.get("task").asString());
    // The endpoint descriptors are discriminated by the 'endpoint' property
    assertEquals("local", data.get("source").get("endpoint").asString());
    assertEquals("bitferry", data.get("destination").get("endpoint").asString());
    assertEquals(volume.tag(), data.get("destination").get("volume").asString());
    assertEquals("backup", data.get("destination").get("path").asString());
    // The backend section with the default processing options is present
    assertEquals(List.of("--metadata"), toStringList(data.get("rclone").get("execute")));
    // Empty include/exclude sections are omitted
    assertFalse(data.has("include"));
    assertFalse(data.has("exclude"));
    assertTrue(data.has("modified"));
  }

  @Test
  void taskRoundTripsThroughTheDescriptor() {
    Synchronize task = new Synchronize(manager, source(), destination(), "-X,verbose", List.of("include/**"), List.of("exclude/**"));
    Task restored = Task.restore(manager, task.serialize());
    assertInstanceOf(Synchronize.class, restored);
    assertEquals(task.tag(), restored.tag());
    assertEquals(task.describe(), restored.describe());
    assertEquals(List.of("include/**"), toStringList(restored.serialize().get("include")));
  }

  @Test
  void resticProfilesFlowIntoTheDescriptors() {
    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, "@default", "@full", List.of(), List.of());
    JsonNode data = backup.serialize();
    assertEquals("restic", data.get("backend").asString());
    assertEquals(List.of("--no-cache"), toStringList(data.get("restic").get("execute")));
    assertEquals(Backup.FORGET.get("default"), toStringList(data.get("restic").get("forget")));
    assertEquals(Backup.CHECK.get("full"), toStringList(data.get("restic").get("check")));
    // The password never travels with the descriptor
    assertFalse(data.has("password"));

    Restore restore = new Restore(manager, destination(), volumeRestored(), "secret", "@default", List.of(), List.of());
    assertEquals("restic", restore.serialize().get("backend").asString());
    assertEquals(List.of("--no-cache", "--sparse"), toStringList(restore.serialize().get("restic").get("execute")));
  }

  @Test
  void directOptionsWithoutAtArePassedVerbatim() {
    // Both options starting with '-' and options without '-' are treated as raw options
    Copy task = new Copy(manager, source(), destination(), "--transfers,4", List.of(), List.of());
    assertEquals(List.of("--transfers", "4"), toStringList(task.serialize().get("rclone").get("execute")));

    Copy raw = new Copy(manager, source(), destination(), "custom,options", List.of(), List.of());
    assertEquals(List.of("custom", "options"), toStringList(raw.serialize().get("rclone").get("execute")));

    // '-' disables execution options
    Copy disabled = new Copy(manager, source(), destination(), "-", List.of(), List.of());
    assertNull(disabled.serialize().get("rclone"));
  }

  @Test
  void unknownOptionsProfilesAreRejected() {
    assertThrows(BitferryError.class, () -> new Copy(manager, source(), destination(), "@nonexistent", List.of(), List.of()));
    assertThrows(BitferryError.class, () -> new Backup(manager, volumeSource(), destination(), "secret", null, null, "@nonexistent", null, List.of(), List.of()));
  }

  @Test
  void processingIsDelegatedToTheStubExecutor() {
    manager.backendExecutor(Executor.stub());
    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of(), List.of());
    assertFalse(task.process(null));
    assertFalse(manager.process());
  }

  @Test
  void deletionFlagsTheTaskAndItsVolumes() {
    Synchronize task = new Synchronize(manager, source(), destination(), null, List.of(), List.of());
    assertTrue(volume.isModified());
    task.delete();
    // The tombstone: the task stays live (the marker must persist) yet invisible
    assertTrue(task.isDeleted());
    assertTrue(task.isLive());
    assertTrue(volume.staleTasks().isEmpty());
    assertTrue(volume.intactTasks().isEmpty());
  }

  @Test
  void failedInitializationAbortsTheRegistration() {
    // The backend executor whose repository initialization (the format) fails - e.g.
    // the restic init over the existing repository
    manager.backendExecutor(new Executor() {
      public boolean run(Task task, java.util.function.Consumer<String> output) { return false; }
      public void format(Task task) { throw new BitferryError("repository initialization failure"); }
    });
    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of(), List.of());

    // The commit raises the initialization failure and aborts the registration
    BitferryError error = assertThrows(BitferryError.class, volume::commit);
    assertTrue(error.getMessage().contains("repository initialization failure"), error.getMessage());
    assertFalse(manager.tasks().containsKey(backup.tag()));

    // The storage dump excludes the aborted task: nothing resurrects upon the restore
    Manager reloaded = new Manager().restore(dir.resolve("volume"));
    assertTrue(reloaded.tasks().isEmpty());
  }

  @Test
  void failedConformanceAbortsTheRegistration() {
    manager.backendExecutor(new Executor() {
      public boolean run(Task task, java.util.function.Consumer<String> output) { return false; }
      public void conform(Task task) { throw new BitferryError("encryption conformance failure"); }
    });
    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of(), List.of());

    BitferryError error = assertThrows(BitferryError.class, volume::commit);
    assertTrue(error.getMessage().contains("encryption conformance failure"), error.getMessage());
    assertFalse(manager.tasks().containsKey(backup.tag()));

    Manager reloaded = new Manager().restore(dir.resolve("volume"));
    assertTrue(reloaded.tasks().isEmpty());
  }

  private static List<String> toStringList(JsonNode node) {
    return Task.strings(node, false);
  }
}
