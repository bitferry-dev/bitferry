package org.bitferry.task;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/// The registry of the live backend processes. Every process spawned through the
/// executor registers itself upon the start and deregisters upon the termination;
/// the [abort] tears the currently running backend(s) down (the graceful terminate
/// first: the backends flush their progress state upon the signal; the termination-
/// ignoring backends are force-killed by a watchdog after the grace period).
///
/// <p>The registry is process-wide by design: the GUI runs the backends through the
/// single operations thread while the CLI pass-through inherits the terminal, so at
/// most one backend carries the meaningful work at a time; the stray leftovers (the
/// short-lived probing calls) are terminated alongside harmlessly.
public final class BackendProcesses {

  private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();

  static {
    // The JVM-exit reaper: the GUI exit (or the CLI Ctrl+C, or any System.exit) must
    // not orphan the running backend child - the daemon run threads die with the JVM
    // while the spawned OS processes would keep writing into the destinations. The
    // hook terminates everything live (the graceful terminate first), waits bounded,
    // and force-kills the remainder (the daemon abort watchdogs cannot be relied
    // upon during the shutdown)
    // WINDOWS: verify the reaper there - Process.destroy() carries no POSIX signal
    // semantics (TerminateProcess, immediate), so the graceful phase is a no-op:
    // confirm the children die cleanly (no orphans, no wedged file handles) and
    // decide whether a taskkill-based graceful escalation is wanted for the aborted
    // runs (mirroring the POSIX SIGTERM-then-SIGKILL ladder)
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (Process process : LIVE) {
        if (process.isAlive()) process.destroy();
      }
      long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
      for (Process process : LIVE) {
        try {
          long left = deadline - System.currentTimeMillis();
          if (left > 0 && process.waitFor(left, TimeUnit.MILLISECONDS)) continue;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        if (process.isAlive()) process.destroyForcibly();
      }
    }, "bitferry-process-reaper"));
  }

  private BackendProcesses() {}

  // Register the process upon its start (called by the executor)
  static void register(Process process) {
    LIVE.add(process);
  }

  // Deregister the process upon its termination (called by the executor)
  static void unregister(Process process) {
    LIVE.remove(process);
  }

  /// Terminate the live backend processes gracefully; return the count terminated
  /// (the already exited ones deregister themselves and are not counted). Non-blocking:
  /// the escalation to the force kill rides on a daemon watchdog per terminated
  /// process, so a termination-ignoring backend cannot wedge the abort caller.
  public static int abort() {
    int terminated = 0;
    Iterator<Process> processes = LIVE.iterator();
    while (processes.hasNext()) {
      Process process = processes.next();
      if (process.isAlive()) {
        process.destroy();
        terminated++;
        Thread watchdog = new Thread(() -> {
          try {
            if (process.waitFor(10, TimeUnit.SECONDS)) return;
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
          }
        }, "bitferry-abort-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
      }
    }
    return terminated;
  }
}
