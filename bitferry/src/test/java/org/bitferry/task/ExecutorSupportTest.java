package org.bitferry.task;

import org.bitferry.Manager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  // The stub executor exiting with the specified code (the outcome-mapping probe)
  private static CommandExecutor exitingWith(int code) {
    return (command, workingDirectory, environment, output) -> code;
  }

  @Test
  void toleratedExitCodesReportPartialSuccess() {
    Manager manager = new Manager();
    List<String> cmd = List.of("restic", "backup");
    // Exit 3 without the tolerance stays a failure
    assertEquals(Task.Outcome.FAILURE, ExecutorSupport.execute(exitingWith(3), manager, cmd, null, Map.of(), "t", line -> {}));
    // Exit 3 tolerated: a partial success with the advisory line (the restic backup writes
    // the snapshot despite the unreadable source files - the outright failure would
    // skew the maintenance cadence)
    List<String> out = new ArrayList<>();
    assertEquals(Task.Outcome.PARTIAL, ExecutorSupport.execute(exitingWith(3), manager, cmd, null, Map.of(), "t", out::add, 3));
    assertTrue(out.stream().anyMatch(line -> line.contains("partial success")), () -> "missing advisory: " + out);
    // A tolerated code differing from the actual one stays a failure
    List<String> out2 = new ArrayList<>();
    assertEquals(Task.Outcome.FAILURE, ExecutorSupport.execute(exitingWith(3), manager, cmd, null, Map.of(), "t", out2::add, 2));
    // The zero exit stays a plain success (with or without the tolerances)
    assertEquals(Task.Outcome.SUCCESS, ExecutorSupport.execute(exitingWith(0), manager, cmd, null, Map.of(), "t", line -> {}, 3));
  }

  // The stub executor emitting the specified lines and exiting with the specified code
  private static CommandExecutor reporting(List<String> lines, int code) {
    return (command, workingDirectory, environment, output) -> {
      if (output != null) lines.forEach(output);
      return code;
    };
  }

  @Test
  void lockConflictFailuresHintAtTheRepairAction() {
    Manager manager = new Manager();
    List<String> cmd = List.of("restic", "backup");
    // The restic lock-conflict report (the stderr travels the captured channel merged)
    List<String> out = new ArrayList<>();
    assertEquals(Task.Outcome.FAILURE, ExecutorSupport.execute(
      reporting(List.of("Fatal: unable to create lock in backend at ...: repository is already locked by PID 1234 on HOST by user"), 1),
      manager, cmd, null, Map.of(), "t", out::add));
    assertTrue(out.stream().anyMatch(line -> line.contains("repair action")), () -> "missing lock hint: " + out);
    // The exclusive-lock refusal wording of the other restic surfaces classifies too
    List<String> out2 = new ArrayList<>();
    assertEquals(Task.Outcome.FAILURE, ExecutorSupport.execute(
      reporting(List.of("Fatal: repository is already locked exclusively by PID 99 on host other"), 1),
      manager, cmd, null, Map.of(), "t", out2::add));
    assertTrue(out2.stream().anyMatch(line -> line.contains("repair action")));
    // The ordinary failures carry no hint (the classification must not over-fire)
    List<String> out3 = new ArrayList<>();
    assertEquals(Task.Outcome.FAILURE, ExecutorSupport.execute(
      reporting(List.of("Fatal: repository does not exist"), 1),
      manager, cmd, null, Map.of(), "t", out3::add));
    assertTrue(out3.stream().noneMatch(line -> line.contains("repair action")));
  }
}
