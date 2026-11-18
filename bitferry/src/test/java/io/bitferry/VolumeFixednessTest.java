package io.bitferry;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

/// The fixed-media classification (the keyring storage gating): the conservative
/// contract — anything unresolvable or non-block-backed reports not fixed.
class VolumeFixednessTest {

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void unresolvableDirectoryIsNotFixed() {
    assertFalse(Volume.isFixedStore(Path.of("/nonexistent", "bitferry-probe")));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void nonBlockBackedDirectoryIsNotFixed() throws IOException {
    // The temporary directories resolve to tmpfs (or another non-block source) on
    // the conventional hosts; the disk-backed ones carry no fixed expectation and
    // are skipped
    Path probe = Files.createTempDirectory("bitferry-fixed");
    try {
      String source = Files.getFileStore(probe).name();
      Assumptions.assumeTrue(!source.startsWith("/dev/"), "temp directory is disk-backed: " + source);
      assertFalse(Volume.isFixedStore(probe));
    } finally {
      Files.deleteIfExists(probe);
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void procfsDirectoryIsNotFixed() {
    // The pseudo file systems carry no block device source: the conservative side
    assertFalse(Volume.isFixedStore(Path.of("/proc/self")));
  }
}
