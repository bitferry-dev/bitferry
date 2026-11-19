package io.bitferry;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// The persisted search-roots helpers: the entry parsing discipline (the shared
/// env/property/prefs composition) and the persistence refusing the separator-
/// carrying entries (the joined format cannot represent them unambiguously).
class PathsTest {

  @Test
  void parseEntriesTrimsAndSkipsTheBlanks() {
    String sep = File.pathSeparator;
    List<Path> parsed = Paths.parseEntries("  /a  " + sep + "" + sep + " /b/b b " + sep + " ");
    assertEquals(List.of(Path.of("/a"), Path.of("/b/b b")), parsed);
    // The null/blank/separators-only inputs yield the empty list
    assertTrue(Paths.parseEntries(null).isEmpty());
    assertTrue(Paths.parseEntries("").isEmpty());
    assertTrue(Paths.parseEntries("   " + sep + "  " + sep).isEmpty());
  }

  @Test
  void saveRefusesTheSeparatorCarryingEntries() {
    // The persistence joins on the platform separator: a directory named with the
    // separator inside would silently split into two entries upon the load
    assertThrows(BitferryError.class, () -> Paths.save(List.of("/clean", "/dirty" + File.pathSeparator + "/sneaky")));
    // The refusal precedes any persistence: the clean-only save stays absent (this
    // test never writes the real preferences store)
    assertFalse(Paths.load().contains("/dirty"));
  }
}
