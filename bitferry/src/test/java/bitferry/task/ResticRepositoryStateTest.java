package bitferry.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ResticRepositoryStateTest {

  @TempDir
  Path tempDir;

  @Test
  void loadMissingFileReturnsCorruptOrMissingTrigger() {
    ResticRepositoryState state = ResticRepositoryState.load(tempDir);
    assertTrue(state.isCorruptOrMissing());
    assertNull(state.backup());
    assertNull(state.maintenance());
    assertEquals(0, state.backups());

    // isDue returns true for any cadence when corrupt or missing
    Cadence cadence = new Cadence(Duration.ofDays(7), 5);
    assertTrue(state.isDue(cadence));
  }

  @Test
  void loadCorruptFileReturnsCorruptOrMissingTrigger() throws IOException {
    Path stateFile = tempDir.resolve(ResticRepositoryState.FILENAME);
    Files.writeString(stateFile, "{ this is not valid json }");

    ResticRepositoryState state = ResticRepositoryState.load(tempDir);
    assertTrue(state.isCorruptOrMissing());
    assertTrue(state.isDue(new Cadence(Duration.ofDays(7), 5)));

    // Empty file also triggers
    Files.writeString(stateFile, "");
    ResticRepositoryState emptyState = ResticRepositoryState.load(tempDir);
    assertTrue(emptyState.isCorruptOrMissing());
    assertTrue(emptyState.isDue(new Cadence(Duration.ofDays(7), 5)));
  }

  @Test
  void saveAndLoadRoundtrip() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.recordServe();
    state.recordBackup();
    state.recordBackup();

    state.save(tempDir);

    ResticRepositoryState loaded = ResticRepositoryState.load(tempDir);
    assertFalse(loaded.isCorruptOrMissing());
    assertNotNull(loaded.backup());
    assertNotNull(loaded.maintenance());
    assertEquals(2, loaded.backups());
  }

  @Test
  void recordServeResetsCount() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.recordBackup();
    state.recordBackup();
    assertEquals(2, state.backups());

    state.recordServe();
    assertEquals(0, state.backups());
    assertNotNull(state.maintenance());
    assertFalse(state.isCorruptOrMissing());
  }

  @Test
  void disabledCadenceNeverDueWhenStateValid() {
    ResticRepositoryState state = new ResticRepositoryState();
    state.recordServe();
    state.recordBackup();
    state.save(tempDir);

    ResticRepositoryState loaded = ResticRepositoryState.load(tempDir);
    assertFalse(loaded.isDue(null));
  }
}
