package io.bitferry.task;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorSupportTest {

  @Test
  void displayCommandLineQuotesTheWhitespaceTokens() {
    // The filter patterns and the spaced paths stay unambiguous and pasteable
    assertEquals("rclone --filter \"+ *.png\"", ExecutorSupport.displayCommandLine(List.of("rclone", "--filter", "+ *.png")));
    assertEquals("sync \"/mnt/my disk\"", ExecutorSupport.displayCommandLine(List.of("sync", "/mnt/my disk")));
    // The empty tokens stay visible instead of collapsing into the separator
    assertEquals("\"\" x", ExecutorSupport.displayCommandLine(List.of("", "x")));
    // The embedded double quotes are escaped
    assertEquals("\"a\\\"b\"", ExecutorSupport.displayCommandLine(List.of("a\"b")));
    // The plain tokens stay bare
    assertEquals("rclone copy /src /dst", ExecutorSupport.displayCommandLine(List.of("rclone", "copy", "/src", "/dst")));
  }
}
