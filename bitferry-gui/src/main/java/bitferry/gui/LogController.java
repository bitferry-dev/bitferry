package bitferry.gui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
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
    /// The normalized scroll position tolerance for the tail test: the vertical scrollbar
    /// ranges in [0.0, 1.0], with 1.0 resting exactly at the tail.
    private static final double TAIL_TOLERANCE = 0.01;

    private final TabPane tabs;
    /// The deferred-attachment title: the scene-graph objects (the area, the tab)
    /// are constructed on the application thread, never on the emitting one.
    private final String title;

    // FX-thread confined; null until attached (the per-run streams) when the
    // deferred-attachment constructor was used
    private Tab tab;
    private TextArea area;

    private final StringBuilder buffer = new StringBuilder();
    private boolean flushPending;

    /// The tail-tracking switch: on while the view rests at the tail (the default),
    /// off while the user is reading the history mid-run. Purely position-based -
    /// our own tail pin sets the scroll value to the tail (re-arming the tracking),
    /// any user scroll away from the tail disarms it, scrolling back to the bottom
    /// re-arms it.
    private boolean follow = true;

    /// Guard against the stream's own programmatic appends tripping the tail-tracking listener.
    private boolean updating;

    /// The approximate number of lines in the area (bounded by LINE_CAP).
    private int lineCount;

    // The vertical scroll bar of the area's skin; resolved lazily (null until the
    // skin is laid out) and cached once found
    private ScrollBar tailBar;

    // The FXML-backed general stream: the tab and the area already exist
    private Stream(TabPane tabs, Tab tab, TextArea area) {
      this.tabs = tabs;
      this.title = null;
      this.tab = tab;
      this.area = area;
      this.lineCount = countLines(area.getText());
    }

    // The per-run stream: the scene-graph objects are created upon the attachment
    // on the application thread (the tab appears through the scheduled attach or
    // the first flush, whichever lands first)
    private Stream(TabPane tabs, String title) {
      this.tabs = tabs;
      this.title = title;
    }

    /// Construct the scene-graph objects and present the tab; application thread only.
    private void attach() {
      if (area != null) return;
      TextArea created = new TextArea();
      created.setEditable(false);
      created.setWrapText(false);
      created.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
      this.area = created;
      this.tab = new Tab(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " — " + title, created);
      tabs.getTabs().add(this.tab);
      tabs.getSelectionModel().select(this.tab);
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
        if (buffer.isEmpty() && area == null) return;
        lines = buffer.toString();
        buffer.setLength(0);
      }
      if (area == null) attach();

      int added = countLines(lines);
      updating = true;
      try {
        if (lineCount + added <= LINE_CAP) {
          // Fast-path: append text directly without recreating or resetting the TextArea.
          // In JavaFX, setText() resets caret and scroll to top (0), causing severe
          // flickering during live log tracking. appendText() preserves the viewport
          // smoothly.
          double savedScroll = area.getScrollTop();
          area.appendText(lines);
          lineCount += added;
          if (follow) {
            area.setScrollTop(Double.MAX_VALUE);
          } else {
            area.setScrollTop(savedScroll);
            Platform.runLater(() -> {
              if (!follow) area.setScrollTop(savedScroll);
            });
          }
        } else {
          // Line cap exceeded: drop oldest lines from text and re-set. setText()
          // resets the viewport, so the tail pin rides along synchronously too -
          // a deferred-only pin lets one frame render scrolled to the top (the
          // flicker the fast path already avoids).
          String text = area.getText();
          int currentLines = countLines(text);
          int overflow = Math.max(0, currentLines + added - LINE_CAP);
          if (overflow > 0) text = dropLines(text, overflow);
          double savedScroll = area.getScrollTop();
          area.setText(text + lines);
          lineCount = countLines(text) + added;
          if (follow) {
            area.setScrollTop(Double.MAX_VALUE);
            Platform.runLater(() -> {
              if (follow) area.setScrollTop(Double.MAX_VALUE);
            });
          } else {
            area.setScrollTop(savedScroll);
            Platform.runLater(() -> {
              if (!follow) area.setScrollTop(savedScroll);
            });
          }
        }
      } finally {
        updating = false;
      }

      // Ensure the vertical scroll bar listener is wired once skin is ready
      verticalBar();
    }

    /// Whether the view rests at the tail of the text; the not-yet-laid-out (and the
    /// non-scrollable) area counts as the tail.
    private boolean atTail() {
      ScrollBar bar = verticalBar();
      return bar == null || bar.getValue() >= bar.getMax() - TAIL_TOLERANCE;
    }

    // Resolve and cache the vertical scroll bar of the area's skin, wiring the
    // tracking re-evaluation into its value changes
    private ScrollBar verticalBar() {
      if (tailBar == null) {
        for (Node node : area.lookupAll(".scroll-bar")) {
          if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
            tailBar = bar;
            // Every user scroll change re-arms or disarms tracking depending on
            // whether the position rests at the tail
            bar.valueProperty().addListener((x, was, now) -> {
              if (!updating) {
                follow = atTail();
              }
            });
            break;
          }
        }
      }
      return tailBar;
    }

    private static int countLines(String text) {
      int count = 0;
      for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) count++;
      if (!text.isEmpty() && !text.endsWith("\n")) count++;
      return count;
    }

    private static String dropLines(String text, int count) {
      int at = 0;
      for (int i = 0; i < count && at >= 0; i++) at = text.indexOf('\n', at) + 1;
      return at <= 0 ? "" : text.substring(at);
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
    general = new Stream(tabs, generalTab, generalArea);
  }

  /// Append a message to the general application log tab (thread-safe).
  public void log(String message) {
    general.append("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message);
  }

  /// Open a new run tab returning the per-line sink (thread-safe). The tab is named
  /// by the wall clock; the caller keeps appending the run's progress. The scene-graph
  /// objects are constructed on the application thread (the caller may be the
  /// operations thread); the lines emitted before the attachment lands buffer in
  /// the stream and flush once the tab exists.
  public Consumer<String> openRun(String title) {
    Stream stream = new Stream(tabs, title);
    Platform.runLater(stream::attach);
    return stream::append;
  }

  /// The root pane (used by the main controller to set the layout growth).
  public TabPane root() {
    return tabs;
  }
}
