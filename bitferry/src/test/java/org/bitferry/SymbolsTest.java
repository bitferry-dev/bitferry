package org.bitferry;

import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/// The unicode axis of the rendering matrix: the glyphs when the policy allows,
/// the canonical ASCII markers otherwise (the encoding-level '?' degradation
/// would lose the semantics).
class SymbolsTest {

  @TempDir
  Path dir;

  @AfterEach
  void restoreDefault() {
    Symbols.unicode(true);
  }

  @Test
  void directorFollowsThePolicy() {
    assertTrue(Symbols.unicode());
    assertEquals("→", Task.displayDirector("-->"));
    assertEquals("↔", Task.displayDirector("<->"));

    Symbols.unicode(false);
    assertEquals("-->", Task.displayDirector("-->"));
    assertEquals("<->", Task.displayDirector("<->"));
    assertEquals("custom", Task.displayDirector("custom"));
    assertNull(Task.displayDirector(null));
  }

  @Test
  void describeFollowsThePolicy() {
    Manager manager = new Manager();
    Volume vol = Volume.create(manager, dir.resolve("vol"));
    Copy task = new Copy(manager,
      Endpoints.parse(manager, ":" + vol.tag() + ":src"),
      Endpoints.parse(manager, ":" + vol.tag() + ":dst"),
      null, List.of(), List.of());

    assertTrue(task.describe().contains("→"), "the unicode form by default: " + task.describe());

    Symbols.unicode(false);
    String ascii = task.describe();
    assertTrue(ascii.contains("-->"), "the canonical ASCII markers: " + ascii);
    assertFalse(ascii.contains("→"), "no unicode glyphs may remain: " + ascii);
  }
}
