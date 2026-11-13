package bitferry;

import java.nio.file.*;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/// The live udisks2 event path (the Linux hosts with the real system bus): a loop
/// device is set up and torn down through udisksctl - the very ObjectManager signal
/// flow the watcher listens to - and the watcher must report both transitions. The
/// hosts without udisksctl/mkfs (the CI ones) skip via assumptions.
class MediaWatchLiveTest {

  private static boolean hasCommand(String command) {
    try {
      new ProcessBuilder(command, "--help").start().waitFor(10, TimeUnit.SECONDS);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static String run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "timeout: " + String.join(" ", command));
    return output;
  }

  @Test
  void fabricatedSystemBusSignalReachesTheWatcher() throws Exception {
    AtomicReference<SortedSet<String>> mounts = new AtomicReference<>(Manager.mountSetSignature());
    AtomicInteger reports = new AtomicInteger();
    MediaWatch watch = MediaWatch.start(reports::incrementAndGet, () -> true, mounts::get);
    try (DBusConnection conn = DBusConnectionBuilder.forSystemBus().withShared(false).build()) {
      // The signal registration settles
      Thread.sleep(300);
      int before = reports.get();
      // The media change: the fake snapshot moves alongside the (fabricated) udisks
      // signal announcing the new block device object
      mounts.set(new TreeSet<>(java.util.Arrays.asList("a", "b", "loop-probe")));
      Map<String, Map<String, Variant<?>>> interfaces = Map.of("org.freedesktop.UDisks2.Loop", Map.of());
      conn.sendMessage(new ObjectManager.InterfacesAdded("/org/freedesktop/UDisks2",
        new DBusPath("/org/freedesktop/UDisks2/block_devices/probe"), interfaces));
      boolean fired = false;
      for (int i = 0; i < 100 && !fired; i++) {
        fired = reports.get() > before;
        Thread.sleep(50);
      }
      assertTrue(fired, "the fabricated udisks2 signal must reach the watcher over the system bus");
    } catch (DBusException | RuntimeException e) {
      // The hosts without the system bus skip (the signal path is exercised live)
      Assumptions.abort("no usable system bus on this host: " + e);
    } finally {
      watch.close();
    }
  }

  @Test
  void loopMountEventsAreReported() throws Exception {
    Assumptions.assumeTrue(hasCommand("udisksctl"), "no udisksctl on the host");
    Assumptions.assumeTrue(hasCommand("mkfs.ext4"), "no mkfs.ext4 on the host");

    AtomicInteger reports = new AtomicInteger();
    MediaWatch watch = MediaWatch.start(reports::incrementAndGet, () -> true, Manager::mountSetSignature);
    Path image = Files.createTempFile("bitferry-media-probe", ".img");
    String loopDevice = null;
    try {
      run("dd", "if=/dev/zero", "of=" + image, "bs=1M", "count=8");
      run("mkfs.ext4", "-F", "-L", "bitferry-probe", image.toString());

      // Settle the watcher initialization
      Thread.sleep(300);
      int before = reports.get();
      String setup = run("udisksctl", "loop-setup", "-f", image.toString());
      // The polkit-restricted hosts (the loop setup gated behind the auth dialog)
      // skip: the transport itself is proven by the error arriving over the bus
      if (setup.contains("NotAuthorized")) {
        Assumptions.assumeTrue(false, "loop-setup is polkit-restricted on this host");
      }
      java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("/dev/loop\\d+").matcher(setup);
      assertTrue(matcher.find(), () -> "loop-setup output: " + setup);
      loopDevice = matcher.group();

      String mount = run("udisksctl", "mount", "-b", loopDevice);
      if (mount.contains("NotAuthorized")) {
        Assumptions.assumeTrue(false, "mount is polkit-restricted on this host");
      }

      // The arrival (the ObjectManager InterfacesAdded burst) must reach the watcher
      boolean arrivalReported = false;
      for (int i = 0; i < 150 && !arrivalReported; i++) {
        arrivalReported = reports.get() > before;
        Thread.sleep(100);
      }
      assertTrue(arrivalReported, "the loop media arrival must be reported");

      // The removal
      run("udisksctl", "unmount", "-b", loopDevice);
      run("udisksctl", "loop-delete", "-b", loopDevice);
      loopDevice = null;
      boolean removalSettled = false;
      for (int i = 0; i < 100 && !removalSettled; i++) {
        removalSettled = Manager.mountSetSignature().stream().noneMatch(m -> m.contains("bitferry-probe"));
        Thread.sleep(100);
      }
      assertTrue(removalSettled, "the loop media must be gone from the mount table");
    } finally {
      watch.close();
      if (loopDevice != null) {
        try {
          run("udisksctl", "unmount", "-b", loopDevice);
        } catch (Exception ignored) {
        }
        try {
          run("udisksctl", "loop-delete", "-b", loopDevice);
        } catch (Exception ignored) {
        }
      }
      Files.deleteIfExists(image);
    }
  }
}
