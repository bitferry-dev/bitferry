package org.bitferry.gui;

import org.bitferry.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for volume load failure reporting in Manager and AppContext (GUI log).
class VolumeLoadFailureTest {

  @Test
  void managerRestoreRecordsLoadErrors() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-corrupt-vol-");
    Path storage = tempDir.resolve(Bitferry.STORAGE);
    Files.writeString(storage, "{ \"bitferry\": \"999\", \"volume\": \"badvol\" }");

    List<String> reported = new ArrayList<>();
    Manager m = new Manager().restore(List.of(tempDir), reported::add);

    assertFalse(m.loadErrors().isEmpty());
    assertEquals(1, m.loadErrors().size());
    String err = m.loadErrors().getFirst();
    String stripped = AnsiFormat.strip(err);
    // The needle is lowercased with the haystack: the Windows paths carry the mixed
    // case that the lowercasing of the haystack alone can never match
    // WINDOWS: confirm both assertions go green there (the mixed-case temp paths
    // were the original failures)
    assertTrue(stripped.toLowerCase().contains(("failed to restore bitferry volume from " + Bitferry.canonicalize(tempDir)).toLowerCase()),
        () -> "unexpected error message: " + err);
    assertEquals(m.loadErrors(), reported);
    assertEquals(m.loadErrors(), m.restoreErrors());
  }

  @Test
  void appContextReloadReportsVolumeLoadFailuresToLogger() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-corrupt-gui-");
    Path storage = tempDir.resolve(Bitferry.STORAGE);
    Files.writeString(storage, "{ corrupt json");

    AppContext context = new AppContext();
    context.extraRoots.add(tempDir);

    List<String> captured = Collections.synchronizedList(new ArrayList<>());
    context.logger = captured::add;

    CountDownLatch changed = new CountDownLatch(1);
    context.onModelChanged(changed::countDown);

    context.reload();
    assertTrue(changed.await(5, TimeUnit.SECONDS), "reload timed out");
    context.operations().submit(() -> null).get(5, TimeUnit.SECONDS);

    // Verify manager holds the error
    assertFalse(context.manager().loadErrors().isEmpty());
    String canonicalPath = Bitferry.canonicalize(tempDir).toString();
    assertTrue(context.manager().loadErrors().stream().map(AnsiFormat::strip).anyMatch(e -> e.contains(canonicalPath)));

    // Verify the failure was reported to the GUI logger (the needle lowercased with
    // the haystack: the Windows paths carry the mixed case)
    assertTrue(captured.stream().map(AnsiFormat::strip).anyMatch(msg -> msg.toLowerCase().contains(("failed to restore bitferry volume from " + canonicalPath).toLowerCase())),
        () -> "GUI log did not receive volume load failure. Captured logs: " + captured);

    // Verify subsequent manager restored summary was also logged
    assertTrue(captured.stream().map(AnsiFormat::strip).anyMatch(msg -> msg.startsWith("Manager update:")),
        () -> "GUI log did not receive manager restored message. Captured logs: " + captured);
  }
}
