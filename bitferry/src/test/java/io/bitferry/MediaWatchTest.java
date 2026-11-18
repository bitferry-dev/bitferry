package io.bitferry;

import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/// The media change watcher mechanics over the injected fake snapshots: the
/// polling-only detection, the transient-state stability, the deferred (gated)
/// reports and the silence over the unchanged state. The tiny tick intervals keep
/// the wall-clock assertions generous but fast.
class MediaWatchTest {

  private static final long FAST_TICK = 40;
  private static final long SLOW_TICK = 120;
  private static final long COALESCE = 60;
  private static final long DEADLINE_MS = 5000;

  private static SortedSet<String> sig(String... mounts) {
    return new TreeSet<>(java.util.Arrays.asList(mounts));
  }

  // Poll the assertion until it holds (the watcher threads deliver asynchronously)
  private static boolean await(Supplier<Boolean> condition) {
    long deadline = System.currentTimeMillis() + DEADLINE_MS;
    while (System.currentTimeMillis() < deadline) {
      if (condition.get()) return true;
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.get();
  }

  @Test
  void changeIsReportedOnce() {
    AtomicReference<SortedSet<String>> mounts = new AtomicReference<>(sig("a", "b"));
    AtomicInteger reports = new AtomicInteger();
    MediaWatch watch = MediaWatch.start(reports::incrementAndGet, () -> true, mounts::get,
      FAST_TICK, SLOW_TICK, COALESCE, false);
    try {
      // No change - no reports
      assertTrue(await(() -> !watch.equals(null) && reports.get() == 0));
      mounts.set(sig("a", "b", "c")); // the media arrival
      assertTrue(await(() -> reports.get() >= 1), "the change must be reported");
      int after = reports.get();
      // The settled state produces no further reports
      assertTrue(await(() -> System.currentTimeMillis() > 0) && await(() -> reports.get() == after),
        () -> "expected exactly one report, got " + reports.get());
      assertEquals(1, after - 1 + 1); // sanity: the state settled at one
      mounts.set(sig("a")); // the media removal
      assertTrue(await(() -> reports.get() >= after + 1), "the removal must be reported");
    } finally {
      watch.close();
    }
  }

  @Test
  void transientStatesAreStabilized() {
    AtomicReference<SortedSet<String>> mounts = new AtomicReference<>(sig("a"));
    AtomicInteger reports = new AtomicInteger();
    MediaWatch watch = MediaWatch.start(reports::incrementAndGet, () -> true, mounts::get,
      FAST_TICK, SLOW_TICK, COALESCE, false);
    try {
      assertTrue(await(() -> System.currentTimeMillis() > 0));
      int before = reports.get();
      // The mount flurry: the flapping between the transient states within the
      // stability window must not trip any report
      for (int i = 0; i < 6; i++) {
        mounts.set(sig("a", "b"));
        Thread.yield();
        mounts.set(sig("a", "b", "c"));
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      mounts.set(sig("a", "b", "c", "d")); // settle
      assertTrue(await(() -> reports.get() > before), "the settled change must be reported");
      // The flurry itself must not have produced the report storm (the transient
      // states are stabilized away; only the settled state counts)
      int fired = reports.get() - before;
      assertTrue(fired <= 2, () -> "expected at most the settled report(s), got " + fired);
    } finally {
      watch.close();
    }
  }

  @Test
  void closedGateDefersTheReport() {
    AtomicReference<SortedSet<String>> mounts = new AtomicReference<>(sig("a"));
    AtomicBoolean gate = new AtomicBoolean(true);
    AtomicInteger reports = new AtomicInteger();
    MediaWatch watch = MediaWatch.start(reports::incrementAndGet, gate::get, mounts::get,
      FAST_TICK, SLOW_TICK, COALESCE, false);
    try {
      assertTrue(await(() -> System.currentTimeMillis() > 0));
      int before = reports.get();
      gate.set(false); // the run in progress
      mounts.set(sig("a", "media"));
      // The gated watcher must not report while the gate is closed
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      assertEquals(before, reports.get(), "the gated report must be deferred");
      gate.set(true); // the run finished
      assertTrue(await(() -> reports.get() > before), "the deferred change must fire when the gate opens");
    } finally {
      watch.close();
    }
  }
}
