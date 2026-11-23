package org.bitferry;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/// The compact console logging setup: the default JUL handler prints every record
/// as a verbose two-line block ('Nov 13, 2026 9:35:50 AM org.bitferry.gui.Main start'
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
      // The newlines are neutralized: the log arguments embedding untrusted text
      // (the exception messages derived from the parsed storage files and the
      // backend output) must not forge extra console lines (the throwables keep
      // their real stack traces below - those are the logger's own)
      StringBuilder line = new StringBuilder(128)
        .append(time).append(' ')
        .append(level).append(' ')
        .append(singleLine(dot < 0 ? logger : logger.substring(dot + 1)))
        .append(": ")
        .append(singleLine(formatMessage(record)));
      if (record.getThrown() != null) {
        java.io.StringWriter trace = new java.io.StringWriter();
        record.getThrown().printStackTrace(new java.io.PrintWriter(trace));
        line.append(System.lineSeparator()).append(trace);
      }
      return line.append(System.lineSeparator()).toString();
    }

    // Flatten the CR/LF into the visible escapes
    private static String singleLine(String text) {
      if (text == null) return null;
      return text.replace("\r", "\\r").replace("\n", "\\n");
    }
  }
}
