package bitferry.gui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;

import java.time.*;
import java.time.format.*;
import java.util.function.Consumer;

/// The tabbed log window controller: the general application log tab (defined by
/// the FXML resource) plus the per-run tabs appended dynamically by the processing
/// runner.
///
/// <p>The output lines are buffered per tab and flushed on the application thread
/// in batches (the backend utilities emit the progress lines densely); the oldest
/// lines are dropped once a tab exceeds the line cap keeping the memory bounded.
public final class LogController {

  /// The per-tab line buffer cap; the overflow drops the oldest lines.
  private static final int LINE_CAP = 5000;

  /// A single log stream: the buffered lines plus the tab they belong to.
  private static final class Stream {
    private final Tab tab;
    private final TextArea area;
    private final StringBuilder buffer = new StringBuilder();
    private boolean flushPending;

    private Stream(Tab tab, TextArea area) {
      this.tab = tab;
      this.area = area;
    }

    // Append on the emitting thread; the flush marshals the buffer to the UI
    private synchronized void append(String line) {
      buffer.append(line).append(System.lineSeparator());
      if (!flushPending) {
        flushPending = true;
        Platform.runLater(this::flush);
      }
    }

    // Flush the pending buffer into the area on the application thread (also caps the text)
    private void flush() {
      String lines;
      synchronized (this) {
        flushPending = false;
        if (buffer.isEmpty()) return;
        lines = buffer.toString();
        buffer.setLength(0);
      }
      String text = area.getText();
      int overflow = Math.max(0, countLines(text) + countLines(lines) - LINE_CAP);
      if (overflow > 0) text = dropLines(text, overflow);
      area.setText(text + lines);
      // The log follows the stream: keep the viewport pinned to the tail
      area.setScrollTop(Double.MAX_VALUE);
    }

    private static int countLines(String text) {
      int count = 1;
      for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) count++;
      return text.isEmpty() ? 0 : count;
    }

    private static String dropLines(String text, int count) {
      int at = 0;
      for (int i = 0; i < count && at >= 0; i++) at = text.indexOf('\n', at + 1);
      return at < 0 ? "" : text.substring(at + 1);
    }
  }

  @FXML
  private TabPane tabs;

  @FXML
  private Tab generalTab;

  @FXML
  private TextArea generalArea;

  private Stream general;

  public LogController() {}

  // The general stream binding happens after the FXML member injection
  @FXML
  private void initialize() {
    general = new Stream(generalTab, generalArea);
  }

  /// Append a message to the general application log tab (thread-safe).
  public void log(String message) {
    general.append("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message);
  }

  /// Open a new run tab returning the per-line sink (thread-safe). The tab is
  /// named by the wall clock; the caller keeps appending the run's progress.
  public Consumer<String> openRun(String title) {
    TextArea area = new TextArea();
    area.setEditable(false);
    area.setWrapText(false);
    area.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
    Stream stream = new Stream(new Tab(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " — " + title, area), area);
    Platform.runLater(() -> {
      tabs.getTabs().add(stream.tab);
      tabs.getSelectionModel().select(stream.tab);
    });
    return stream::append;
  }

  /// The root pane (used by the main controller to set the layout growth).
  public TabPane root() {
    return tabs;
  }
}
