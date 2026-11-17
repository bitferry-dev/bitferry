package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
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
    assertEquals("replicate", new Replicate(manager, source(), destination(), null, List.of(), List.of()).operation());
    assertEquals("equalize", new Equalize(manager, source(), destination(), null, List.of(), List.of()).operation());
    assertEquals("backup", new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of(), List.of()).operation());
    assertEquals("restore", new Restore(manager, destination(), volumeRestored(), "secret", null, List.of(), List.of()).operation());
    // The tasks own their backend operation arguments
    assertEquals(List.of("copy"), new Copy(manager, source(), destination(), null, List.of(), List.of()).operationArgs());
    assertEquals(List.of("copy", "--update"), new Update(manager, source(), destination(), null, List.of(), List.of()).operationArgs());
    assertEquals(List.of("sync"), new Replicate(manager, source(), destination(), null, List.of(), List.of()).operationArgs());
    Equalize eqTask = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    assertEquals(List.of("bisync", "--resync", "--workdir", eqTask.workdir().toString()), eqTask.operationArgs());
  }

  @Test
  void tasksAreRegisteredAndIntactUponCreation() {
    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    assertEquals(task, manager.task(task.tag()));
    assertTrue(task.isIntact());
    assertTrue(task.isLive());
    assertEquals("replicate " + source().describe() + " → " + destination().describe(), task.describe());
    assertTrue(task.refersVolume(volume.tag()));
    assertFalse(task.refersVolume("00000000"));
  }

  @Test
  void taskSerializationCarriesTheDescriptors() {
    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    JsonNode data = task.serialize();
    // The operation is the discriminator property; the tag travels in-band
    assertEquals("replicate", data.get("operation").asString());
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
    Replicate task = new Replicate(manager, source(), destination(), "-X,verbose", List.of("include/**"), List.of("exclude/**"));
    Task restored = Task.restore(manager, task.serialize());
    assertInstanceOf(Replicate.class, restored);
    assertEquals(task.tag(), restored.tag());
    assertEquals(task.describe(), restored.describe());
    assertEquals(List.of("include/**"), toStringList(restored.serialize().get("include")));
  }

  @Test
  void resticProfilesFlowIntoTheDescriptors() {
    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, "@default", "@full", List.of(), List.of());
    JsonNode data = backup.serialize();
    assertEquals("restic", data.get("backend").asString());
    assertNull(data.get("restic").get("execute"));
    assertEquals(Backup.FORGET.get("default"), toStringList(data.get("restic").get("forget")));
    assertEquals(Backup.CHECK.get("full"), toStringList(data.get("restic").get("check")));
    assertEquals("7d,5", data.get("restic").get("serve").asString());
    // The password never travels with the descriptor
    assertFalse(data.has("password"));

    Backup restoredBackup = (Backup) Task.restore(manager, data);
    assertEquals("7d,5", restoredBackup.cadence().format());

    Restore restore = new Restore(manager, destination(), volumeRestored(), "secret", "@default", List.of(), List.of());
    assertEquals("restic", restore.serialize().get("backend").asString());
    assertEquals(List.of("--sparse"), toStringList(restore.serialize().get("restic").get("execute")));
  }

  @Test
  void descriptorsCarryExactlyTheExpectedFieldSet() {
    // The stray bean properties leak into the persisted storages (e.g. an is-getter
    // without @JsonIgnore): the descriptor shape is pinned here as the regression guard
    // (the field order is not asserted - the discriminator/section placement may vary)
    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, "@default", "@full", List.of(), List.of());
    assertIterableEquals(
      List.of("backend", "destination", "modified", "operation", "restic", "source", "task"),
      sortedNames(backup.serialize()));

    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    assertIterableEquals(
      List.of("backend", "destination", "modified", "operation", "rclone", "source", "task"),
      sortedNames(task.serialize()));

    // The runtime flags (the conformance state) never travel with the descriptor
    assertFalse(sortedNames(backup.serialize()).contains("conformed"));
  }

  private static List<String> keyNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.properties().forEach(e -> names.add(e.getKey()));
    return names;
  }

  private static List<String> sortedNames(JsonNode node) {
    List<String> names = keyNames(node);
    java.util.Collections.sort(names);
    return names;
  }

  @Test
  void directOptionsWithoutAtArePassedVerbatim() {
    // Both options starting with '-' and options without '-' are treated as raw options
    Copy task = new Copy(manager, source(), destination(), "--transfers,4", List.of(), List.of());
    assertEquals(List.of("--transfers", "4"), toStringList(task.serialize().get("rclone").get("execute")));

    Copy raw = new Copy(manager, source(), destination(), "custom,options", List.of(), List.of());
    assertEquals(List.of("custom", "options"), toStringList(raw.serialize().get("rclone").get("execute")));

    // '-' disables execution options: the cleared state persists as the explicit
    // empty list (an absent key would resolve into the default profile at the restore)
    Copy disabled = new Copy(manager, source(), destination(), "-", List.of(), List.of());
    assertEquals(List.of(), toStringList(disabled.serialize().get("rclone").get("execute")));
  }

  @Test
  void clearedExecutionOptionsSurviveTheRestoration() {
    // The cleared ('-') execution options must not resolve back into the backend's
    // default profile upon the restoration (the rclone --metadata, the restore --sparse)
    Copy copy = new Copy(manager, source(), destination(), null, List.of(), List.of());
    copy.setProcessOptions("-");
    Task restored = Task.restore(manager, copy.serialize());
    assertEquals(List.of(), restored.processOptions());

    Restore restore = new Restore(manager, destination(), volumeRestored(), "secret", "@default", List.of(), List.of());
    restore.setProcessOptions("-");
    Task restoredRestore = Task.restore(manager, restore.serialize());
    assertEquals(List.of(), restoredRestore.processOptions());

    // The round trip through the actual volume storage keeps the cleared state too
    manager.commit();
    Manager reloaded = new Manager().restore(dir.resolve("volume"));
    Task stored = reloaded.tasks().get(copy.tag());
    assertEquals(List.of(), stored.processOptions());
  }

  @Test
  void emptyCommandOptionsListOnRestoreDoesNotTriggerDefaultList() {
    tools.jackson.databind.node.ObjectNode data = Storage.mapper().createObjectNode();
    data.put("operation", "copy");
    data.put("task", "test-tag-empty-exec");
    data.put("modified", java.time.OffsetDateTime.now().toString());
    data.set("source", Endpoints.parse(manager, "local:/src").serialize());
    data.set("destination", Endpoints.parse(manager, "local:/dst").serialize());

    // 1. Explicit empty execute list []
    tools.jackson.databind.node.ObjectNode rclone = Storage.mapper().createObjectNode();
    rclone.putArray("execute");
    data.set("rclone", rclone);
    Task t1 = Task.restore(manager, data);
    assertEquals(List.of(), t1.processOptions());

    // 2. rclone section without execute key
    data.put("task", "test-tag-no-exec-key");
    data.set("rclone", Storage.mapper().createObjectNode());
    Task t2 = Task.restore(manager, data);
    assertEquals(List.of(), t2.processOptions());

    // 3. No rclone section at all
    data.put("task", "test-tag-no-rclone");
    data.remove("rclone");
    Task t3 = Task.restore(manager, data);
    assertEquals(List.of(), t3.processOptions());
  }

  @Test
  void unknownOptionsProfilesAreRejected() {
    assertThrows(BitferryError.class, () -> new Copy(manager, source(), destination(), "@nonexistent", List.of(), List.of()));
    assertThrows(BitferryError.class, () -> new Backup(manager, volumeSource(), destination(), "secret", null, null, "@nonexistent", null, List.of(), List.of()));
  }

  @Test
  void processingIsDelegatedToTheStubExecutor() {
    manager.backendExecutor(Executor.stub());
    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    assertFalse(task.process(null));
    assertFalse(manager.process());
  }

  @Test
  void deletionFlagsTheTaskAndItsVolumes() {
    Replicate task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
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
    assertEquals(0, volume.generation());

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
    assertEquals(0, volume.generation());

    Manager reloaded = new Manager().restore(dir.resolve("volume"));
    assertTrue(reloaded.tasks().isEmpty());
  }

  @Test
  void optionsParsingTrimsWhitespaceAndIgnoresEmptyTokens() {
    Copy copy = new Copy(manager, source(), destination(), "--fast-list, --transfers, 4", List.of(), List.of());
    assertEquals(List.of("--fast-list", "--transfers", "4"), copy.processOptions());

    copy.setProcessOptions("--checkers, 8, , --metadata");
    assertEquals(List.of("--checkers", "8", "--metadata"), copy.processOptions());

    copy.setProcessOptions("-");
    assertEquals(List.of(), copy.processOptions());
  }

  @Test
  void equalizeResyncsOnlyUntilTheBaselineIsEstablished() {
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    String workdir = task.workdir().toString();
    // The fresh task carries the initial baseline rebuild (--resync)
    assertFalse(task.resynced());
    assertEquals(List.of("bisync", "--resync", "--workdir", workdir), task.operationArgs());
    assertNull(task.serialize().get("resynced"));

    // The failed run leaves the baseline pending
    manager.backendExecutor(Executor.stub());
    assertFalse(task.process(null));
    assertFalse(task.resynced());
    assertEquals(List.of("bisync", "--resync", "--workdir", workdir), task.operationArgs());

    // Commit the newly created task so manager is clean
    manager.commit();
    assertFalse(manager.isModified());

    // The successful run establishes the baseline (rclone creates listings in workdir):
    // following runs execute plain bisync without mutating task descriptor or touching volume
    manager.backendExecutor(new Executor() {
      public boolean run(Task t, java.util.function.Consumer<String> output) {
        try {
          Files.createDirectories(task.workdir());
          Files.createFile(task.workdir().resolve("test.path1.lst"));
          return true;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
    assertTrue(task.process(null));
    assertTrue(task.resynced());
    assertEquals(List.of("bisync", "--workdir", workdir), task.operationArgs());
    assertNull(task.serialize().get("resynced"));
    assertFalse(manager.isModified()); // Execution does not mutate descriptor or trigger commits!

    // The state is held in workdir; the descriptor round-trip remains clean
    Task.restore(manager, task.serialize());
    assertSame(task, manager.task(task.tag()));
    assertTrue(((Equalize) manager.task(task.tag())).resynced());

    // The filter change invalidates the saved-listings baseline by removing listings
    task.setInclude(List.of("*.txt"));
    assertFalse(task.resynced());
    assertEquals(List.of("bisync", "--resync", "--workdir", workdir), task.operationArgs());
    assertNull(task.serialize().get("resynced"));
  }

  @Test
  void equalizeAdaptsListingNamesOnPathShift() throws IOException {
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    Path workdir = task.workdir();
    Files.createDirectories(workdir);

    // Old session from a previous mount
    String oldSession = "old_mount_src..dest";
    Files.createFile(workdir.resolve(oldSession + ".path1.lst"));
    Files.createFile(workdir.resolve(oldSession + ".path2.lst"));
    assertTrue(task.resynced());

    // Prepare workdir with new paths
    String newP1 = "/new/mount/src";
    String newP2 = "/new/mount/dest";
    String expectedSession = Equalize.rcloneSessionName(newP1, newP2);
    task.prepareWorkdir(newP1, newP2);

    // Old listing files should be renamed to match expectedSession
    assertTrue(Files.exists(workdir.resolve(expectedSession + ".path1.lst")));
    assertTrue(Files.exists(workdir.resolve(expectedSession + ".path2.lst")));
    assertFalse(Files.exists(workdir.resolve(oldSession + ".path1.lst")));
    assertTrue(task.resynced());
  }

  @Test
  void equalizeProcessProfilesResolveCorrectly() {
    Equalize def = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    assertEquals(List.of("--metadata", "--recover", "--resilient", "--force"), def.processOptions());

    Equalize explicitDefault = new Equalize(manager, source(), destination(), "@default", List.of(), List.of());
    assertEquals(List.of("--metadata", "--recover", "--resilient", "--force"), explicitDefault.processOptions());

    Equalize safe = new Equalize(manager, source(), destination(), "@safe", List.of(), List.of());
    assertEquals(List.of("--metadata", "--recover", "--resilient"), safe.processOptions());

    def.setProcessOptions("@safe");
    assertEquals(List.of("--metadata", "--recover", "--resilient"), def.processOptions());

    def.setProcessOptions("@default");
    assertEquals(List.of("--metadata", "--recover", "--resilient", "--force"), def.processOptions());
  }

  @Test
  void massRunParticipationDefaultsAndPersists() {
    var task = new Replicate(manager, source(), destination(), null, List.of(), List.of());
    // The default: every task participates in the mass runs (the descriptor omits the flag)
    assertTrue(task.isRunnable());
    assertFalse(task.isManual());
    assertNull(task.serialize().get("manual"));

    // The runtime opt-out: the checkbox toggles the execution status without touching
    // the persisted descriptor (the mass run skips the task, the explicit tag runs it)
    task.setRunnable(false);
    assertFalse(task.isRunnable());
    assertFalse(task.isManual());
    assertNull(task.serialize().get("manual"));
    assertFalse(manager.selectTasks(List.of()).containsKey(task.tag()));
    assertTrue(manager.selectTasks(task.tag()).containsKey(task.tag()));

    // The persisted manual flag (set via the dialog): the descriptor carries it and
    // the mass run skips the task
    task.setManual(true);
    assertTrue(task.isManual());
    assertFalse(task.isRunnable());
    assertEquals(Boolean.TRUE, task.serialize().get("manual").asBoolean());
    assertFalse(manager.selectTasks(List.of()).containsKey(task.tag()));

    // The descriptor round-trip: the restored execution status mirrors the persisted flag
    task.setManual(false);
    var restored = (Replicate) Task.restore(manager, task.serialize());
    assertFalse(restored.isManual());
    assertTrue(restored.isRunnable());

    task.setManual(true);
    restored = (Replicate) Task.restore(manager, task.serialize());
    assertTrue(restored.isManual());
    assertFalse(restored.isRunnable());
    assertSame(task, manager.task(task.tag()));
  }


  @Test
  void equalTimestampConflictsPreferTheTombstone() {
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());

    // The equal-mtime plain copy keeps the registered one: the tie is resolved
    // deterministically instead of by the registration order alone
    Task.restore(manager, task.serialize());
    assertSame(task, manager.task(task.tag()));

    // The equal-mtime tombstoned copy wins: the deletions never lose a tie
    ObjectNode tombstone = task.serialize();
    tombstone.put("deleted", true);
    Task marker = Task.restore(manager, tombstone);
    assertSame(marker, manager.task(task.tag()));
    assertTrue(marker.isDeleted());
  }

  @Test
  void backupRejectsTheIncludeFilters() {
    Backup backup = new Backup(manager, volumeSource(), destination(), "secret", null, null, null, null, List.of(), List.of());
    // restic backup filters by exclusion only: the include filters would be silently ignored
    assertThrows(BitferryError.class, () -> backup.setInclude(List.of("*.txt")));
    assertTrue(backup.include().isEmpty());
    // The legacy cleanup path stays available
    assertDoesNotThrow(backup::clearInclude);
  }

  @Test
  void rcloneDeleteCleansUpWorkdirEvenIfMorphed() throws IOException {
    Equalize task = new Equalize(manager, source(), destination(), null, List.of(), List.of());
    Path workdir = task.workdir();
    Files.createDirectories(workdir);
    Files.writeString(workdir.resolve("test.lst"), "dummy content");
    assertTrue(Files.exists(workdir.resolve("test.lst")));

    // 1. Morph to Replicate cleans up old equalize workdir
    Rclone morphed = task.morph("replicate");
    assertFalse(Files.exists(workdir));

    // 2. Re-create workdir and test deleting a non-equalize Rclone task removes it
    Files.createDirectories(workdir);
    Files.writeString(workdir.resolve("test.lst"), "dummy content");
    assertTrue(Files.exists(workdir));

    morphed.delete();
    assertFalse(Files.exists(workdir));
  }

  private static List<String> toStringList(JsonNode node) {
    return Task.strings(node, false);
  }
}
