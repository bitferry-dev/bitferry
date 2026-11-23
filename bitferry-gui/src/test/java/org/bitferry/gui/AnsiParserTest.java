package org.bitferry.gui;

import javafx.application.Platform;
import javafx.scene.text.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AnsiParserTest {

  private static final CountDownLatch FX_READY = new CountDownLatch(1);

  @BeforeAll
  static void startFx() throws Exception {
    try {
      Platform.startup(FX_READY::countDown);
      if (!FX_READY.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the FX toolkit did not start");
    } catch (IllegalStateException ignored) {
      // Already started in this JVM
    }
  }

  @Test
  void oscPayloadsAreSkipped() {
    // The OSC titles/hyperlinks must never render: the BEL terminator
    List<Text> nodes = AnsiParser.parseLine("\u001B]0;my title\u0007plain");
    assertEquals(1, nodes.size());
    assertEquals("plain\n", nodes.get(0).getText());
    // The ST (ESC \) terminator
    nodes = AnsiParser.parseLine("before\u001B]8;;http://example.com\u001B\\after");
    assertEquals(1, nodes.size());
    assertEquals("beforeafter\n", nodes.get(0).getText());
  }

  @Test
  void truncatedSequencesDoNotLeakAsText() {
    // The line-split (truncated) CSI drops the partial remainder instead of
    // rendering the raw escape bytes
    List<Text> nodes = AnsiParser.parseLine("text \u001B[31");
    assertEquals(1, nodes.size());
    assertEquals("text \n", nodes.get(0).getText());
    // The unterminated OSC payload drops the remainder along
    nodes = AnsiParser.parseLine("\u001B]8;;http://example.com/no-terminator");
    assertEquals(1, nodes.size());
    assertEquals("\n", nodes.get(0).getText());
  }

  @Test
  void stripAnsiCoversTheOscAndTheTruncatedSequences() {
    assertEquals("plain", AnsiParser.stripAnsi("\u001B]0;my title\u0007plain"));
    assertEquals("plain", AnsiParser.stripAnsi("\u001B]8;;http://example.com\u001B\\plain"));
    assertEquals("text ", AnsiParser.stripAnsi("text \u001B[31"));
    assertEquals("", AnsiParser.stripAnsi("\u001B[31m"));
  }

  @Test
  void plainTextHasDefaultStyleAndTrailingNewline() {
    List<Text> nodes = AnsiParser.parseLine("Hello, world!");
    assertEquals(1, nodes.size());
    assertEquals("Hello, world!\n", nodes.get(0).getText());
    assertEquals(AnsiParser.DEFAULT_FG, nodes.get(0).getFill());
  }

  @Test
  void emptyLineYieldsSingleNewline() {
    List<Text> nodes = AnsiParser.parseLine("");
    assertEquals(1, nodes.size());
    assertEquals("\n", nodes.get(0).getText());
  }

  @Test
  void standardColorCodesAreParsed() {
    // 31: Red, 32: Green, 34: Blue, 36: Cyan
    String line = "\u001B[31mError\u001B[0m: \u001B[32mSuccess\u001B[0m in \u001B[36mModule\u001B[0m";
    List<Text> nodes = AnsiParser.parseLine(line);
    assertEquals(5, nodes.size());

    assertEquals("Error", nodes.get(0).getText());
    assertEquals(AnsiParser.RED, nodes.get(0).getFill());

    assertEquals(": ", nodes.get(1).getText());
    assertEquals(AnsiParser.DEFAULT_FG, nodes.get(1).getFill());

    assertEquals("Success", nodes.get(2).getText());
    assertEquals(AnsiParser.GREEN, nodes.get(2).getFill());

    assertEquals(" in ", nodes.get(3).getText());
    assertEquals(AnsiParser.DEFAULT_FG, nodes.get(3).getFill());

    assertEquals("Module\n", nodes.get(4).getText());
    assertEquals(AnsiParser.CYAN, nodes.get(4).getFill());
  }

  @Test
  void boldAndStylesAreRecognized() {
    String line = "\u001B[1mBold\u001B[0m and \u001B[4mUnderline\u001B[0m";
    List<Text> nodes = AnsiParser.parseLine(line);
    assertEquals(3, nodes.size());

    assertEquals("Bold", nodes.get(0).getText());
    assertTrue(nodes.get(0).getFont().getStyle().contains("Bold"));

    assertEquals(" and ", nodes.get(1).getText());
    assertFalse(nodes.get(1).getFont().getStyle().contains("Bold"));

    assertEquals("Underline\n", nodes.get(2).getText());
    assertTrue(nodes.get(2).isUnderline());
  }

  @Test
  void rcloneDeltaReportLineIsParsedAccurately() {
    // The exact line reported by user from rclone bisync
    String line = "2026/11/16 17:43:44 NOTICE: - \u001B[34mPath2\u001B[0m    \u001B[35m\u001B[33mFile changed: \u001B[35msize (smaller)\u001B[0m, \u001B[35mtime (newer)\u001B[0m\u001B[0m\u001B[0m - \u001B[36mlibgen0.conflict1\u001B[0m";
    List<Text> nodes = AnsiParser.parseLine(line);

    // Verify all text concatenated equals the original text with ANSI stripped plus newline
    StringBuilder full = new StringBuilder();
    for (Text t : nodes) full.append(t.getText());
    assertEquals(AnsiParser.stripAnsi(line) + "\n", full.toString());

    // Path2 must be blue
    Text path2Node = nodes.stream().filter(t -> t.getText().equals("Path2")).findFirst().orElseThrow();
    assertEquals(AnsiParser.BLUE, path2Node.getFill());

    // File changed: must be yellow (35 then overridden by 33)
    Text fileChangedNode = nodes.stream().filter(t -> t.getText().equals("File changed: ")).findFirst().orElseThrow();
    assertEquals(AnsiParser.YELLOW, fileChangedNode.getFill());

    // size (smaller) must be magenta
    Text sizeNode = nodes.stream().filter(t -> t.getText().equals("size (smaller)")).findFirst().orElseThrow();
    assertEquals(AnsiParser.MAGENTA, sizeNode.getFill());

    // libgen0.conflict1 must be cyan
    Text conflictNode = nodes.stream().filter(t -> t.getText().startsWith("libgen0.conflict1")).findFirst().orElseThrow();
    assertEquals(AnsiParser.CYAN, conflictNode.getFill());
  }

  @Test
  void stripAnsiRemovesAllEscapeCodes() {
    String line = "\u001B[1m[1/5]\u001B[0m \u001B[36mbackup:t1\u001B[0m  \u001B[32m✓ success\u001B[0m";
    assertEquals("[1/5] backup:t1  ✓ success", AnsiParser.stripAnsi(line));
  }
}
