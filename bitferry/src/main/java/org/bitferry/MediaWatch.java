package org.bitferry;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/// The media change watcher: detects the mount-set changes (the media arrival and
/// removal) and invokes the consumer once per change, coalesced.
///
/// <p>The event-driven sources mark the changes instantly (the Linux udisks2 D-Bus
/// signals, the Windows WM_DEVICECHANGE device messages; both optional - the hosts
/// without them degrade silently). The polling fallback runs alongside always: as a
/// slow (30s) safety net while an event source is alive - catching the missed events
/// and the mounts made by the tools bypassing the event sources - and as the primary
/// detector (fast 2s ticks with the two-tick stability check, riding out the mount
/// event flurries) where no source is available or after a source failure. The
/// snapshot comparison is the real detector everywhere: the sources merely say
/// "check now", so their false positives are free.
///
/// <p>The consumer is invoked through the optional gate: the closed gate (e.g. a run
/// in progress) defers the report until it opens - the change is not forgotten (the
/// snapshot comparison keeps it outstanding; the deferred report re-fires on the
/// next poll or event).
public final class MediaWatch {

  /// The fallback-primary poll interval (no event source alive).
  private static final long FAST_TICK_MS = 2000;
  /// The safety-net poll interval while an event source is alive.
  private static final long SLOW_TICK_MS = 30000;
  /// The stability requirement of the polling-only mode: the changed signature must
  /// repeat on the consecutive tick before the report fires (the mount events arrive
  /// in flurries; the transient states must not trip the consumer).
  private static final int STABILITY_TICKS = 2;
  /// The coalescing window of the event-driven reports.
  private static final long COALESCE_MS = 500;

  private final Runnable onChange;
  private final BooleanSupplier gate;
  private final Supplier<SortedSet<String>> snapshot;

  // The cadence parameters (the defaults are the constants above; the tests drive
  // the tiny intervals through the explicit seam)
  private final long fastTickMs;
  private final long slowTickMs;
  private final long coalesceMs;

  // The watcher state is confined to the scheduler thread; the flags written by the
  // event callback threads are volatile
  private final ScheduledExecutorService scheduler =
    Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "bitferry-media");
      thread.setDaemon(true);
      return thread;
    });

  /// Whether an event source is alive (the poll cadence and the stability rules hang
  /// off it; a source failure at the setup time or mid-flight flips it off).
  private volatile boolean sourceAlive;
  /// The event flag set by the event source callbacks (their own threads).
  private volatile boolean dirty;
  /// The closed flag: the close racing the asynchronous source setup must not leak
  /// the freshly created source.
  private volatile boolean closed;

  private SortedSet<String> lastReported;
  private SortedSet<String> candidate;
  private int candidateSeen;

  private volatile EventSource eventSource;

  /// The media event source contract: the liveness probe lets the watcher detect a
  /// mid-flight source death and accelerate the polling to the fallback cadence.
  interface EventSource extends AutoCloseable {
    boolean isAlive();
  }

  private MediaWatch(Runnable onChange, BooleanSupplier gate, Supplier<SortedSet<String>> snapshot,
                     long fastTickMs, long slowTickMs, long coalesceMs) {
    this.onChange = onChange;
    this.gate = gate;
    this.snapshot = snapshot;
    this.fastTickMs = fastTickMs;
    this.slowTickMs = slowTickMs;
    this.coalesceMs = coalesceMs;
  }

  /// Start the watcher: the event source per the host platform (best effort) and
  /// the polling fallback. The last observed snapshot seeds from the current mount
  /// set, so the pre-existing mounts do not trip the initial report.
  public static MediaWatch start(Runnable onChange, BooleanSupplier gate, Supplier<SortedSet<String>> snapshot) {
    return start(onChange, gate, snapshot, FAST_TICK_MS, SLOW_TICK_MS, COALESCE_MS, true);
  }

  /// The explicit-parameters seam: the tests drive the tiny intervals and skip the
  /// platform event source entirely.
  static MediaWatch start(Runnable onChange, BooleanSupplier gate, Supplier<SortedSet<String>> snapshot,
                          long fastTickMs, long slowTickMs, long coalesceMs, boolean withEventSource) {
    MediaWatch watch = new MediaWatch(onChange, gate, snapshot, fastTickMs, slowTickMs, coalesceMs);
    watch.lastReported = snapshot.get();
    if (withEventSource) watch.startEventSource();
    // The sourceAlive read is deliberately racy: the event source connects on its
    // background thread, so it is practically never alive yet here - the first tick
    // lands on the fast interval, which is desirable anyway (the early-change
    // detection before the source takes over); tick() re-reads the flag afterwards.
    watch.scheduler.schedule(watch::tick, watch.sourceAlive ? slowTickMs : fastTickMs, TimeUnit.MILLISECONDS);
    return watch;
  }

  /// Stop the watcher and the event source (best effort; the daemon threads die
  /// with the JVM anyway). The closed flag lets the asynchronous source setup
  /// (still in flight) discard the freshly created source instead of leaking it.
  public void close() {
    closed = true;
    scheduler.shutdownNow();
    EventSource source = eventSource;
    if (source != null) {
      try {
        source.close();
      } catch (Exception ignored) {
        // The shutdown best effort; the daemon threads die with the JVM regardless
      }
    }
  }

  // Start the platform event source (best effort): the Linux udisks2 signals or the
  // Windows device messages; the unsupported platforms skip straight to the polling.
  // The source setup travels through a dedicated thread: the D-Bus connection does
  // socket I/O, which must not jank the caller (the GUI application thread).
  private void startEventSource() {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    java.util.function.BooleanSupplier setup = os.contains("linux") ? this::startUdisksSource
      : os.contains("windows") ? this::startWindowsSource
      : null;
    if (setup == null) return;
    Thread setupThread = new Thread(() -> {
      try {
        if (setup.getAsBoolean()) {
          sourceAlive = true;
        }
      } catch (Throwable t) {
        // The source setup failure degrades to the polling quietly
        System.getLogger(MediaWatch.class.getName())
          .log(java.lang.System.Logger.Level.DEBUG, "media event source unavailable: " + t);
      }
    }, "bitferry-media-events");
    setupThread.setDaemon(true);
    setupThread.start();
  }

  // The Linux source: the udisks2 ObjectManager signals over the system bus.
  private boolean startUdisksSource() {
    UdisksEventSource source = new UdisksEventSource();
    if (source.start(this::markDirty)) {
      eventSource = source;
      if (closed) discardSource(source); // The close raced the setup: dispose the fresh source
      return true;
    }
    source.close();
    return false;
  }

  // The Windows source: the device change messages.
  private boolean startWindowsSource() {
    WindowsEventSource source = new WindowsEventSource();
    if (source.start(this::markDirty)) {
      eventSource = source;
      if (closed) discardSource(source); // The close raced the setup: dispose the fresh source
      return true;
    }
    source.close();
    return false;
  }

  private void discardSource(EventSource source) {
    try {
      source.close();
    } catch (Exception ignored) {
      // The shutdown best effort
    }
  }

  // Marked by the event source callbacks (their own threads); the burst of the
  // signals schedules one coalesced check.
  private void markDirty() {
    dirty = true;
    if (!closed) {
      try {
        scheduler.schedule(this::eventCheck, coalesceMs, TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }
  }

  // The coalesced event check (the scheduler thread).
  private void eventCheck() {
    try {
      if (!dirty) return;
      dirty = false;
      checkAndReport();
    } catch (RuntimeException e) {
      // The snapshot failures must not vanish into the discarded scheduled future
      System.getLogger(MediaWatch.class.getName())
        .log(java.lang.System.Logger.Level.WARNING, "media event check failed: " + e);
    }
  }

  // The poll tick (the scheduler thread); the cadence re-reads the source state so
  // a mid-flight source failure (a dropped bus, an exited window pump) accelerates
  // the polling on the next tick.
  private void tick() {
    try {
      if (sourceAlive) {
        // The mid-flight source death flips the source state off: the fallback
        // cadence (and the stability requirement) engages from this tick on
        EventSource source = eventSource;
        if (source == null || !source.isAlive()) {
          sourceAlive = false;
          System.getLogger(MediaWatch.class.getName())
            .log(java.lang.System.Logger.Level.DEBUG, "media event source died; falling back to the polling");
        } else {
          // The safety net: no stability requirement (the events already rode out
          // the flurries; the missed ones are caught raw)
          checkAndReport();
        }
      }
      if (!sourceAlive) {
        SortedSet<String> sig = snapshot.get();
        if (sig.equals(candidate)) candidateSeen++;
        else {
          candidate = sig;
          candidateSeen = 1;
        }
        if (candidateSeen >= STABILITY_TICKS) {
          candidateSeen = 0;
          checkAndReport(sig);
        }
      }
    } catch (RuntimeException e) {
      // The snapshot failures (e.g. a mount enumeration error on an exotic host)
      // must not kill the watcher silently: log and keep ticking
      System.getLogger(MediaWatch.class.getName())
        .log(java.lang.System.Logger.Level.WARNING, "media watch tick failed: " + e);
    } finally {
      if (!closed) {
        try {
          scheduler.schedule(this::tick, sourceAlive ? slowTickMs : fastTickMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
      }
    }
  }

  // The report: the changed snapshot through the open gate (the closed gate keeps
  // the change outstanding - the comparison re-fires on the next poll or event).
  private void checkAndReport() {
    checkAndReport(snapshot.get());
  }

  private void checkAndReport(SortedSet<String> sig) {
    if (sig.equals(lastReported)) return;
    if (!gate.getAsBoolean()) return;
    try {
      onChange.run();
    } catch (RuntimeException e) {
      // The consumer failure must not kill the watcher thread - and must not consume
      // the change either: it stays outstanding and re-fires on the next tick
      System.getLogger(MediaWatch.class.getName())
        .log(java.lang.System.Logger.Level.WARNING, "media change consumer failed: " + e);
      return;
    }
    lastReported = sig;
  }
}
