package bitferry.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RepoStateTest {

  @TempDir
  Path tempDir;

  @Test
  void loadMissingFileReturnsCorruptOrMissingTrigger() {
    RepoState state = RepoState.load(tempDir);
    assertTrue(state.isCorruptOrMissing());
    assertNull(state.lastBackup());
    assertNull(state.lastMaintenance());
    assertEquals(0, state.backupsSinceMaintenance());

    // isDue returns true for any cadence when corrupt or missing
    Cadence cadence = new Cadence(Duration.ofDays(7), 5);
    assertTrue(state.isDue(cadence));
  }

  @Test
  void loadCorruptFileReturnsCorruptOrMissingTrigger() throws IOException {
    Path stateFile = tempDir.resolve(RepoState.FILENAME);
    Files.writeString(stateFile, "{ this is not valid json }");

    RepoState state = RepoState.load(tempDir);
    assertTrue(state.isCorruptOrMissing());
    assertTrue(state.isDue(new Cadence(Duration.ofDays(7), 5)));

    // Empty file also triggers
    Files.writeString(stateFile, "");
    RepoState emptyState = RepoState.load(tempDir);
    assertTrue(emptyState.isCorruptOrMissing());
    assertTrue(emptyState.isDue(new Cadence(Duration.ofDays(7), 5)));
  }

  @Test
  void saveAndLoadRoundtrip() {
    RepoState state = new RepoState();
    state.recordMaintenance();
    state.recordBackup();
    state.recordBackup();

    state.save(tempDir);

    RepoState loaded = RepoState.load(tempDir);
    assertFalse(loaded.isCorruptOrMissing());
    assertNotNull(loaded.lastBackup());
    assertNotNull(loaded.lastMaintenance());
    assertEquals(2, loaded.backupsSinceMaintenance());
  }

  @Test
  void recordMaintenanceResetsCount() {
    RepoState state = new RepoState();
    state.recordBackup();
    state.recordBackup();
    assertEquals(2, state.backupsSinceMaintenance());

    state.recordMaintenance();
    assertEquals(0, state.backupsSinceMaintenance());
    assertNotNull(state.lastMaintenance());
    assertFalse(state.isCorruptOrMissing());
  }

  @Test
  void disabledCadenceNeverDueWhenStateValid() {
    RepoState state = new RepoState();
    state.recordMaintenance();
    state.recordBackup();
    state.save(tempDir);

    RepoState loaded = RepoState.load(tempDir);
    assertFalse(loaded.isDue(null));
  }
}
