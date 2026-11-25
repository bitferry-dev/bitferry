package org.bitferry.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ResticRepositoryStateTest {

  @TempDir
  Path tempDir;

  private static final Cadence CADENCE = new Cadence(Duration.ofDays(7), 5);

  @Test
  void loadMissingFileReturnsCorruptOrMissingTrigger() {
    ResticRepositoryState state = ResticRepositoryState.load(tempDir);
    assertTrue(state.isCorruptOrMissing());

    // The corrupt or missing state is due for tasks with active cadence, but never for disabled cadence
    assertTrue(state.isDue("task-a", CADENCE));
    assertFalse(state.isDue("task-a", null));
    assertFalse(state.isDue("task-a", CADENCE.withEnabled(false)));
  }

  @Test
  void disabledCadenceNeverDueWhenStateValid() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of("task-a"));
    state.recordServe(List.of("task-a"));
    assertFalse(state.isDue("task-a", null));
    assertFalse(state.isDue("task-a", CADENCE.withEnabled(false)));
  }

  @Test
  void loadCorruptFileReturnsCorruptOrMissingTrigger() throws IOException {
    Path stateFile = tempDir.resolve(ResticRepositoryState.FILENAME);
    Files.writeString(stateFile, "{ this is not valid json }");

    ResticRepositoryState state = ResticRepositoryState.load(tempDir);
    assertTrue(state.isCorruptOrMissing());
    assertTrue(state.isDue("task-a", CADENCE));
    assertFalse(state.isDue("task-a", null));

    // Empty file also triggers
    Files.writeString(stateFile, "");
    ResticRepositoryState emptyState = ResticRepositoryState.load(tempDir);
    assertTrue(emptyState.isCorruptOrMissing());
    assertTrue(emptyState.isDue("task-a", CADENCE));
  }

  @Test
  void saveAndLoadRoundtrip() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of("task-a"));
    state.recordServe(List.of("task-a"));
    state.recordBackup("task-a");
    state.recordBackup("task-a");

    state.save(tempDir);

    ResticRepositoryState loaded = ResticRepositoryState.load(tempDir);
    assertFalse(loaded.isCorruptOrMissing());
    ResticRepositoryState.Entry entry = loaded.entry("task-a");
    assertNotNull(entry.backup());
    assertNotNull(entry.maintenance());
    assertEquals(2, entry.backups());
  }

  @Test
  void recordBackupTouchesOwnEntryOnly() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of("task-a", "task-b"));
    state.recordServe(List.of("task-a", "task-b"));

    state.recordBackup("task-a");
    state.recordBackup("task-a");
    assertEquals(2, state.entry("task-a").backups());
    assertEquals(0, state.entry("task-b").backups());

    Instant bMaintenance = state.entry("task-b").maintenance();
    state.recordBackup("task-a");
    assertEquals(bMaintenance, state.entry("task-b").maintenance());
    assertEquals(3, state.entry("task-a").backups());
  }

  @Test
  void recordServeResetsTheListedEntriesOnly() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of("task-a", "task-b"));
    state.recordBackup("task-a");
    state.recordBackup("task-b");
    state.recordBackup("task-b");

    state.recordServe(List.of("task-b"));

    assertEquals(1, state.entry("task-a").backups());
    assertEquals(0, state.entry("task-b").backups());
    assertNotNull(state.entry("task-b").maintenance());
  }

  @Test
  void cadenceDuePerTaskEntry() throws IOException, InterruptedException {
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of("task-a", "task-b"));
    state.recordServe(List.of("task-a", "task-b"));
    state.recordBackup("task-a");
    state.recordBackup("task-a");
    state.recordBackup("task-a");
    state.recordBackup("task-a");
    state.recordBackup("task-a"); // task-a: 5 of 5 backups since service
    state.recordBackup("task-b"); // task-b: 1 of 5
    state.save(tempDir);

    ResticRepositoryState loaded = ResticRepositoryState.load(tempDir);
    assertFalse(loaded.isCorruptOrMissing());
    assertTrue(loaded.isDue("task-a", CADENCE), "task-a must be due at its own 5-backup threshold");
    assertFalse(loaded.isDue("task-b", CADENCE), "task-b must not be due on task-a's counter");
    // A task without its own entry is due: its policy has never been applied to the
    // repository yet (the initial-service-pending semantics driving the anti-starvation
    // service after a new task attaches)
    assertTrue(loaded.isDue("task-c", CADENCE));
  }

  @Test
  void legacyFlatFileIsAdoptedAsSharedBaseline() throws IOException {
    // The pre-envelope shape: one flat entry shared by every task (the recent
    // maintenance keeps both below the 7-day duration threshold)
    String legacyJson = """
      {
        "backup": "2026-01-01T00:00:00Z",
        "maintenance": "%s",
        "backups_since_maintenance": 3
      }""".formatted(Instant.now());
    Files.writeString(tempDir.resolve(ResticRepositoryState.FILENAME), legacyJson);

    ResticRepositoryState state = ResticRepositoryState.load(tempDir);
    assertFalse(state.isCorruptOrMissing());
    assertEquals(3, state.entry("task-a").backups());
    assertEquals(3, state.entry("task-b").backups(), "the legacy entry is the shared baseline");
    assertFalse(state.isDue("task-a", CADENCE), "3 backups is below the threshold and the maintenance is recent");
  }

  @Test
  void legacyPlainNameIsAdoptedAndReplaced() throws IOException {
    // The pre-rename plain name
    String legacyJson = """
      {
        "backup": "2026-01-01T00:00:00Z",
        "maintenance": "2026-11-01T00:00:00Z",
        "backups_since_maintenance": 3
      }""";
    Files.writeString(tempDir.resolve(ResticRepositoryState.LEGACY_FILENAME), legacyJson);

    ResticRepositoryState state = ResticRepositoryState.load(tempDir);
    assertFalse(state.isCorruptOrMissing());
    assertEquals(3, state.entry("task-a").backups());

    // The normalization seeds the per-task entries from the legacy baseline
    assertTrue(state.normalize(List.of("task-a", "task-b")));
    state.recordBackup("task-a");
    state.save(tempDir);

    // The envelope file replaced the legacy plain name
    assertTrue(Files.isRegularFile(tempDir.resolve(ResticRepositoryState.FILENAME)));
    assertFalse(Files.isRegularFile(tempDir.resolve(ResticRepositoryState.LEGACY_FILENAME)));

    ResticRepositoryState loaded = ResticRepositoryState.load(tempDir);
    assertEquals(4, loaded.entry("task-a").backups(), "task-a seeded from the legacy counter, then incremented");
    assertEquals(3, loaded.entry("task-b").backups(), "task-b seeded from the legacy counter");
    assertEquals(Instant.parse("2026-11-01T00:00:00Z"), loaded.entry("task-a").maintenance());
  }

  @Test
  void normalizeIsIdempotent() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of("task-a"));
    assertFalse(state.normalize(List.of("task-a")), "the second normalization must be a no-op");
    assertEquals(0, state.entry("task-a").backups());
  }

  @Test
  void unknownTagsPreservedAcrossSaveAndLoad() throws IOException {
    ResticRepositoryState state = new ResticRepositoryState();
    state.normalize(List.of("task-a"));
    state.recordServe(List.of("task-a"));
    // An entry of a task unknown to this session lands in the map directly
    state.recordServe(List.of("foreign-task"));
    state.save(tempDir);

    ResticRepositoryState loaded = ResticRepositoryState.load(tempDir);
    assertTrue(loaded.tags().contains("foreign-task"), "the foreign entry must survive the roundtrip");
    assertNotNull(loaded.entry("foreign-task").maintenance());
  }
}
