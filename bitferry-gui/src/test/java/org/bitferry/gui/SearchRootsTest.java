package org.bitferry.gui;

import org.bitferry.Bitferry;
import org.bitferry.Manager;
import org.bitferry.Paths;
import org.bitferry.Volume;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for SearchRoots preferences persistence and AppContext volume discovery merge.
class SearchRootsTest {

  @TempDir
  Path tempDir;

  private List<String> originalRoots;

  @BeforeEach
  void setUp() {
    originalRoots = Paths.load();
  }

  @AfterEach
  void tearDown() {
    Paths.save(originalRoots);
  }

  @Test
  void preferencesNodeIsNotGuiSpecific() {
    assertEquals("/org/bitferry", Paths.nodePath());
    assertEquals("path", Paths.key());
  }

  @Test
  void saveDirectlyWritesPathKeyUnderBitferryNode() {
    java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(Bitferry.class);
    Paths.save(List.of("/test/dir1", "/test/dir2"));
    assertEquals(String.join(java.io.File.pathSeparator, "/test/dir1", "/test/dir2"), prefs.get("path", null));

    Paths.save(List.of());
    assertNull(prefs.get("path", null));
  }

  @Test
  void legacyPreferencesNodeFallbackAndMigration() {
    java.util.prefs.Preferences legacyPrefs = java.util.prefs.Preferences.userRoot().node("bitferry");
    String legacyPath = "/legacy/search/path";
    legacyPrefs.put("path", legacyPath);
    try {
      Paths.save(List.of()); // clears /org/bitferry
      List<String> loaded = Paths.load(); // should pick up and migrate from /bitferry
      assertEquals(List.of(legacyPath), loaded);
      assertEquals(legacyPath, java.util.prefs.Preferences.userNodeForPackage(Bitferry.class).get("path", null));
    } finally {
      legacyPrefs.remove("path");
    }
  }

  @Test
  void saveAndLoadRoundTrip() {
    // Null and empty save removes the key, load returns empty list
    Paths.save(null);
    assertTrue(Paths.load().isEmpty());

    Paths.save(List.of());
    assertTrue(Paths.load().isEmpty());

    // Blanks, nulls, and whitespace are trimmed and discarded
    Paths.save(Arrays.asList("  /path/alpha  ", "", "   ", null, "/path/beta"));
    assertEquals(List.of("/path/alpha", "/path/beta"), Paths.load());

    // Single entry save and load
    Paths.save(List.of("/single/root"));
    assertEquals(List.of("/single/root"), Paths.load());

    // Clean up
    Paths.save(List.of());
    assertTrue(Paths.load().isEmpty());
  }

  @Test
  void appContextProbingMergesSearchRoots() throws Exception {
    Path sub = tempDir.resolve("search_vol");
    Files.createDirectories(sub);

    // Create a Bitferry volume in an isolated temp directory outside standard search paths
    Manager initMgr = new Manager();
    Volume vol = Volume.create(initMgr, sub);
    initMgr.commit();

    // With search roots empty, AppContext should not discover the volume
    Paths.save(List.of());
    AppContext context = new AppContext();

    CountDownLatch initialLatch = new CountDownLatch(1);
    context.onModelChanged(initialLatch::countDown);
    context.reload();
    assertTrue(initialLatch.await(5, TimeUnit.SECONDS), "initial reload timed out");
    context.operations().submit(() -> null).get(5, TimeUnit.SECONDS);

    assertFalse(context.manager().liveVolumes().containsKey(vol.tag()),
        "volume should not be discovered before search paths are configured");

    // Configure the volume location in SearchRoots
    Paths.save(List.of(sub.toString()));
    assertEquals(List.of(sub.toString()), Paths.load());

    CountDownLatch configuredLatch = new CountDownLatch(1);
    context.onModelChanged(configuredLatch::countDown);
    context.reload();
    assertTrue(configuredLatch.await(5, TimeUnit.SECONDS), "reload with search paths timed out");
    context.operations().submit(() -> null).get(5, TimeUnit.SECONDS);

    // Verify the volume is discovered and live
    assertTrue(context.manager().liveVolumes().containsKey(vol.tag()),
        "volume must be discovered after adding directory to SearchRoots");
    assertEquals(Bitferry.canonicalize(sub), context.manager().liveVolumes().get(vol.tag()).root());
  }

  @Test
  void missingAndInvalidSearchRootsAreSkippedGracefully() throws Exception {
    Path nonExistent = tempDir.resolve("does_not_exist");
    Paths.save(List.of(nonExistent.toString()));

    AppContext context = new AppContext();
    CountDownLatch latch = new CountDownLatch(1);
    context.onModelChanged(latch::countDown);
    assertDoesNotThrow(context::reload);
    assertTrue(latch.await(5, TimeUnit.SECONDS), "reload with non-existent root timed out");
    context.operations().submit(() -> null).get(5, TimeUnit.SECONDS);

    assertNotNull(context.manager());
  }
}
