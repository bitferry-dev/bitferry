package io.bitferry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// The persisted search-roots helpers: the entry parsing discipline (the shared
/// env/property/prefs composition), the persistence refusing the separator-
/// carrying entries (the joined format cannot represent them unambiguously), and
/// the scanning-policy coverage of the volume locations.
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

  @Test
  void coversReflectsTheScanningPolicy() throws Exception {
    // The real persisted entries travel aside for the run (the listed-location leg)
    Preferences node = Preferences.userNodeForPackage(Bitferry.class);
    String saved = node.get(Paths.key(), null);

    Path unlisted = Files.createTempDirectory("bitferry-covers-probe-");
    try {
      // The unlisted location outside home and the mount roots is not covered
      assertFalse(Paths.covers(unlisted), () -> "unexpectedly covered: " + unlisted);
      // The listed location rides the per-site scanned locations
      Paths.save(List.of(unlisted.toString()));
      assertTrue(Paths.covers(unlisted));
      // The home directory and the mount roots are scanned by the policy
      assertTrue(Paths.covers(Path.of(System.getProperty("user.home"))));
      if (unlisted.getRoot() != null) {
        assertTrue(Paths.covers(unlisted.getRoot()), () -> "the mount root must cover: " + unlisted.getRoot());
      }
    } finally {
      if (saved != null) {
        node.put(Paths.key(), saved);
      } else {
        node.remove(Paths.key());
      }
      node.flush();
    }
  }
}
