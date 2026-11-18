package io.bitferry;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class TagsTest {

  @Test
  void newTagMatchesTheTagFormat() {
    for (int i = 0; i < 100; i++) {
      assertTrue(Tags.newTag().matches("^[0-9a-f]{8}$"));
    }
  }

  @Test
  void newTagsAreUnique() {
    Set<String> tags = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      assertTrue(tags.add(Tags.newTag()));
    }
  }

  @Test
  void tagPartsMatchByTheSubstringRules() {
    String tag = "e42f2d8c";
    assertTrue(Tags.matches("e42f", tag));
    assertTrue(Tags.matches(tag, tag));
    assertFalse(Tags.matches("f2d8c0", tag));
  }

  @Test
  void versionMirrorsTheReferenceImplementation() {
    // The version is provided by the build through the version resource mirroring the
    // Python implementation's build-time-resolved `__version__`; "dev" is the fallback
    // for the unresolved (bare classes) runs, so the build-backed tests never see it
    assertFalse(Bitferry.VERSION.isBlank());
    assertNotEquals("dev", Bitferry.VERSION);
    assertEquals(".bitferry", Bitferry.STORAGE);
    assertEquals(".bitferry~", Bitferry.STORAGE_IMAGE);
  }
}
