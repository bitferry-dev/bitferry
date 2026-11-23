package org.bitferry;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.core.*;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

  @TempDir
  Path dir;

  @Test
  void writeTextIsAtomic() throws IOException {
    Path target = dir.resolve("data.txt");
    Storage.writeText(target, "hello");
    assertEquals("hello", Files.readString(target));
    // The intermediate image is gone upon the successful commit
    assertFalse(Files.exists(dir.resolve("data.txt~")));
    // The replacement goes through the intermediate file as well
    Storage.writeText(target, "bye");
    assertEquals("bye", Files.readString(target));
    assertFalse(Files.exists(dir.resolve("data.txt~")));
  }

  @Test
  void writeTextCreatesTheMissingParents() throws IOException {
    Path target = dir.resolve("deep").resolve("nested").resolve("data.txt");
    Storage.writeText(target, "hello");
    assertEquals("hello", Files.readString(target));
  }

  @Test
  void failedWriteLeavesNoIntermediateImage() throws IOException {
    // A regular file in place of the expected directory makes any child creation fail
    Files.writeString(dir.resolve("sub"), "not a directory");
    Path target = dir.resolve("sub").resolve("data.txt");
    assertThrows(IOException.class, () -> Storage.writeText(target, "hello"));
    assertFalse(Files.exists(dir.resolve("sub").resolve("data.txt~")));
  }

  @Test
  void jsonRoundTrip() throws IOException {
    var node = JsonNodeFactory.instance.objectNode();
    node.put("volume", "e42f2d8c");
    node.putObject("tasks").putObject("89e1c119").put("modified", "2026-09-13T11:46:45.123456+03:00");
    Path file = dir.resolve(Bitferry.STORAGE);
    Storage.writeText(file, Storage.toJson(node));
    var restored = Storage.readJson(file);
    assertEquals("e42f2d8c", restored.get("volume").asString());
    assertEquals("2026-09-13T11:46:45.123456+03:00", restored.path("tasks").path("89e1c119").path("modified").asString());
  }

  @Test
  void readingGarbageFails() throws IOException {
    Path file = dir.resolve(Bitferry.STORAGE);
    Storage.writeText(file, "not a json at all {");
    assertThrows(JacksonException.class, () -> Storage.readJson(file));
    assertTrue(Files.exists(file));
  }
}
