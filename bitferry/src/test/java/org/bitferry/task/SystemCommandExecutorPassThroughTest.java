package org.bitferry.task;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/// The CLI pass-through plumbing of the system command executor: the Windows-path
/// stderr pump forwards the child's stderr decoded as UTF-8 through System.err
/// (the unicode filenames survive the legacy code page consoles), while the exit
/// code and the stdout pass-through behave exactly like the plain child process.
public class SystemCommandExecutorPassThroughTest {

  public static void main(String[] args) throws IOException {
    if (args.length > 0 && "exit3".equals(args[0])) {
      System.exit(3);
    }
    System.err.write("наименование файла".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    System.err.flush();
  }

  private static List<String> javaCommand(String... args) {
    String java = Path.of(System.getProperty("java.home"), "bin", ExecutorSupport.isWindows() ? "java.exe" : "java").toString();
    List<String> cmd = new ArrayList<>();
    cmd.add(java);
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(SystemCommandExecutorPassThroughTest.class.getName());
    cmd.addAll(Arrays.asList(args));
    return cmd;
  }

  @Test
  void stderrPumpForwardsUtf8DecodedThroughSystemErr() throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      // The child writes a unicode filename to its stderr and exits successfully
      List<String> command = javaCommand();
      int code = SystemCommandExecutor.INSTANCE.execute(command, null, null, null, true);
      assertEquals(0, code);
      String errText = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(errText.contains("наименование файла"), "Expected text not in captured: [" + errText + "]");
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void exitCodePropagatesInBothStderrModes() throws Exception {
    List<String> failing = javaCommand("exit3");
    assertEquals(3, SystemCommandExecutor.INSTANCE.execute(failing, null, null, null, true));
    assertEquals(3, SystemCommandExecutor.INSTANCE.execute(failing, null, null, null, false));
  }
}

