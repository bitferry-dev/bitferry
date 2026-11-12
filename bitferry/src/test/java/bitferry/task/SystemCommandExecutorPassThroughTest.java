package bitferry.task;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/// The CLI pass-through plumbing of the system command executor: the Windows-path
/// stderr pump forwards the child's stderr decoded as UTF-8 through System.err
/// (the unicode filenames survive the legacy code page consoles), while the exit
/// code and the stdout pass-through behave exactly like the plain child process.
class SystemCommandExecutorPassThroughTest {

  @Test
  void stderrPumpForwardsUtf8DecodedThroughSystemErr() throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      // The child writes a unicode filename to its stderr and exits successfully
      List<String> command = List.of("sh", "-c", "echo 'наименование файла' >&2");
      int code = SystemCommandExecutor.INSTANCE.execute(command, null, null, null, true);
      assertEquals(0, code);
      assertTrue(captured.toString(java.nio.charset.StandardCharsets.UTF_8).contains("наименование файла"));
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void exitCodePropagatesInBothStderrModes() throws Exception {
    List<String> failing = List.of("sh", "-c", "exit 3");
    assertEquals(3, SystemCommandExecutor.INSTANCE.execute(failing, null, null, null, true));
    assertEquals(3, SystemCommandExecutor.INSTANCE.execute(failing, null, null, null, false));
  }
}
