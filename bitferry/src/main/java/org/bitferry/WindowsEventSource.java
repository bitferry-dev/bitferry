package org.bitferry;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// The Windows media-change event source: the WM_DEVICECHANGE device messages
/// delivered to a hidden top-level window pumped on a dedicated thread. Win32
/// delivers WM_DEVICECHANGE broadcasts (such as volume and device arrivals/removals)
/// to top-level windows only, not message-only (HWND_MESSAGE) windows. The window
/// is created without WS_VISIBLE and never shown. The coarse DBT_DEVNODES_CHANGED /
/// DBT_DEVICEARRIVAL / DBT_DEVICEREMOVECOMPLETE notifications suffice: the sources
/// merely say "check now", the snapshot comparison is the real detector, so false
/// positives are free. Any setup failure degrades to the polling fallback quietly.
final class WindowsEventSource implements MediaWatch.EventSource {

  private static final int WM_DEVICECHANGE = WinUser.WM_DEVICECHANGE; // 0x0219
  private static final int DBT_DEVNODES_CHANGED = 0x0007;
  private static final int DBT_DEVICEARRIVAL = 0x8000;
  private static final int DBT_DEVICEREMOVECOMPLETE = 0x8004;
  private static final int WM_QUIT = 0x0012;

  private static final java.lang.System.Logger LOG = java.lang.System.getLogger(WindowsEventSource.class.getName());

  // The window and the callback must stay strongly referenced for the pump lifetime
  // (the native side holds plain pointers; a collected callback would crash the VM)
  private volatile WinDef.HWND window;
  private volatile WinUser.WindowProc windowProc;
  private volatile int pumpThreadId;
  private final CountDownLatch started = new CountDownLatch(1);
  private volatile boolean alive;
  // The close requested before the pump registered its thread id: the pump
  // self-quits upon the registration (the close cannot post to an unknown thread)
  private volatile boolean closeRequested;

  /// Create the hidden top-level window and pump its messages until close; runs
  /// on the dedicated pump thread (Win32 windows have thread affinity). Returns
  /// false when the window machinery fails to initialize.
  boolean start(Runnable onChange) {
    try {
      Thread pump = new Thread(() -> pump(onChange), "bitferry-media-winmsg");
      pump.setDaemon(true);
      pump.start();
      if (!started.await(5, TimeUnit.SECONDS)) {
        LOG.log(java.lang.System.Logger.Level.DEBUG, "Device message window startup timed out");
        close();
        return false;
      }
      return alive;
    } catch (Exception e) {
      LOG.log(java.lang.System.Logger.Level.DEBUG, () -> "Device message window unavailable: " + e);
      close();
      return false;
    }
  }

  // The message loop: the window creation, the device notification subscription and
  // the pump live on the same thread (the Win32 window/thread affinity).
  private void pump(Runnable onChange) {
    String className = null;
    WinDef.HINSTANCE instance = null;
    try {
      instance = Kernel32.INSTANCE.GetModuleHandle(null);
      className = "bitferry-media-" + Kernel32.INSTANCE.GetCurrentThreadId();

      // The device change dispatch: the coarse notification only - the arrival,
      // the removal and the generic devnodes-changed codes all mean "check now"
      windowProc = (hwnd, msg, wParam, lParam) -> {
        if (msg == WM_DEVICECHANGE
          && (wParam.intValue() == DBT_DEVNODES_CHANGED
            || wParam.intValue() == DBT_DEVICEARRIVAL
            || wParam.intValue() == DBT_DEVICEREMOVECOMPLETE)) {
          try {
            onChange.run();
          } catch (RuntimeException e) {
            // The consumer's failures must not cross the JNA native callback
            // boundary (mirroring the udisks2 source): after the watcher's close
            // shut its scheduler down, a late WM_DEVICECHANGE used to surface a
            // RejectedExecutionException inside the native dispatch
            LOG.log(java.lang.System.Logger.Level.DEBUG, "Media change consumer failed: " + e);
          }
        }
        return User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam);
      };

      WinUser.WNDCLASSEX wndClass = new WinUser.WNDCLASSEX();
      wndClass.lpfnWndProc = windowProc;
      wndClass.hInstance = instance;
      wndClass.lpszClassName = className;
      if (User32.INSTANCE.RegisterClassEx(wndClass).intValue() == 0) {
        throw new IllegalStateException("RegisterClassEx failed: " + Kernel32.INSTANCE.GetLastError());
      }

      // Top-level unowned window (parent is null): invisible, receives broadcast messages (WM_DEVICECHANGE).
      // Note: Message-only windows (HWND_MESSAGE) explicitly do NOT receive broadcast messages in Win32.
      window = User32.INSTANCE.CreateWindowEx(0, className, "bitferry-media", 0, 0, 0, 0, 0,
        null, null, instance, null);
      if (window == null) {
        throw new IllegalStateException("CreateWindowEx failed: " + Kernel32.INSTANCE.GetLastError());
      }

      pumpThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
      alive = true;
      // The close raced the pump startup: quit right away (the thread id is only
      // now known, so the earlier close could not have posted the WM_QUIT)
      if (closeRequested) {
        User32.INSTANCE.PostThreadMessage(pumpThreadId, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
      }
      started.countDown();

      WinUser.MSG msg = new WinUser.MSG();
      while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
        User32.INSTANCE.TranslateMessage(msg);
        User32.INSTANCE.DispatchMessage(msg);
      }
    } catch (Throwable t) {
      // The pump teardown (the close) is normal here; the setup failures degrade
      // to the polling fallback
      LOG.log(java.lang.System.Logger.Level.DEBUG, () -> "Device message pump finished: " + t);
    } finally {
      alive = false;
      started.countDown();
      if (window != null) {
        try {
          User32.INSTANCE.DestroyWindow(window);
        } catch (Throwable ignored) {}
        window = null;
      }
      if (className != null && instance != null) {
        try {
          User32.INSTANCE.UnregisterClass(className, instance);
        } catch (Throwable ignored) {}
      }
    }
  }

  /// The mid-flight liveness probe: the exited pump (the WM_QUIT teardown, a
  /// message-loop failure) flips the watcher to the polling fallback cadence.
  @Override
  public boolean isAlive() {
    return alive;
  }

  @Override
  public void close() {
    closeRequested = true;
    int threadId = pumpThreadId;
    if (threadId != 0) {
      try {
        User32.INSTANCE.PostThreadMessage(threadId, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
      } catch (Throwable ignored) {}
    }
  }
}
