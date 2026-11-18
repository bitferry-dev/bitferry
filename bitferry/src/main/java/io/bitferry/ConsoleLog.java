package io.bitferry;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/// The compact console logging setup: the default JUL handler prints every record
/// as a verbose two-line block ('Nov 13, 2026 9:35:50 AM io.bitferry.gui.Main start'
/// followed by 'INFO: ...'), which drowns the frontends' console output. The
/// [compact] call re-formats the installed console handlers into the single-line
/// form 'HH:mm:ss LEVEL logger message' (the throwables keep their stack traces).
///
/// <p>The call is idempotent and cheap; the frontends invoke it right upon their
/// launch, before any record reaches the console.
public final class ConsoleLog {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private ConsoleLog() {}

  /// Re-format the console handlers of the root logger into the compact form; the
  /// non-console (file etc.) handlers are left alone.
  public static void compact() {
    java.util.logging.Logger root = java.util.logging.LogManager.getLogManager().getLogger("");
    for (java.util.logging.Handler handler : root.getHandlers()) {
      if (handler instanceof java.util.logging.ConsoleHandler) handler.setFormatter(new Compact());
    }
  }

  // The single-line record renderer
  private static final class Compact extends Formatter {

    @Override
    public String format(LogRecord record) {
      String time = TIME.format(LocalTime.ofInstant(record.getInstant(), ZoneId.systemDefault()));
      String level = record.getLevel().getName();
      // The short logger name: the bare class name without the package prefix
      String logger = record.getLoggerName();
      int dot = logger == null ? -1 : logger.lastIndexOf('.');
      StringBuilder line = new StringBuilder(128)
        .append(time).append(' ')
        .append(level).append(' ')
        .append(dot < 0 ? logger : logger.substring(dot + 1))
        .append(": ")
        .append(formatMessage(record));
      if (record.getThrown() != null) {
        java.io.StringWriter trace = new java.io.StringWriter();
        record.getThrown().printStackTrace(new java.io.PrintWriter(trace));
        line.append(System.lineSeparator()).append(trace);
      }
      return line.append(System.lineSeparator()).toString();
    }
  }
}
