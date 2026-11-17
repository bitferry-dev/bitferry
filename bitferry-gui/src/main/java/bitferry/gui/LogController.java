package bitferry.gui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.text.*;

import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.function.Consumer;

/// The tabbed log window controller: the general application log tab (defined by
/// the FXML resource) plus the per-run tabs appended dynamically by the processing
/// runner.
///
/// <p>The output lines are buffered per tab and flushed on the application thread
/// in budgeted batches (the backend utilities emit the progress lines densely);
/// the oldest lines are dropped once a tab exceeds the line cap keeping the memory
/// bounded. Both the pending buffer and the displayed text are capped: the
/// flood-shaped backend failures (the retry storms emitting tens of thousands of
/// lines within seconds) must not stall the application thread with the oversized
/// parse-and-layout batches — the unbounded draining was freezing the whole
/// window for the duration of the dense failures.
public final class LogController {

  /// The per-tab line buffer cap; the overflow drops the oldest lines. The cap
  /// applies at the append time (the pending buffer) and at the display time
  /// (the text flow), so the flood output never accumulates beyond it.
  private static final int LINE_CAP = 5000;

  /// The per-flush line budget: a flush processes at most this many buffered lines
  /// per application-thread pulse and reschedules itself for the remainder. The
  /// budget keeps the pulses short so the input, layout and rendering events
  /// interleave with the log drain.
  private static final int FLUSH_BUDGET = 500;

  /// A single log stream: the buffered lines plus the tab they belong to.
  private static final class Stream {
    /// The normalized scroll position tolerance for the tail test: the vertical scrollbar
    /// ranges in [0.0, 1.0], with 1.0 resting exactly at the tail.
    private static final double TAIL_TOLERANCE = 0.01;

    private final TabPane tabs;
    /// The deferred-attachment title: the scene-graph objects (the scroll pane, the tab)
    /// are constructed on the application thread, never on the emitting one.
    private final String title;

    // FX-thread confined; null until attached (the per-run streams) when the
    // deferred-attachment constructor was used
    private Tab tab;
    private ScrollPane scroll;
    private TextFlow flow;

    private final List<String> buffer = new ArrayList<>();
    private boolean flushPending;

    /// The tail-tracking switch: on while the view rests at the tail (the default),
    /// off while the user is reading the history mid-run. Purely position-based -
    /// our own tail pin sets the scroll value to the tail (re-arming the tracking),
    /// any user scroll away from the tail disarms it, scrolling back to the bottom
    /// re-arms it.
    private boolean follow = true;

    /// Guard against the stream's own programmatic appends tripping the tail-tracking listener.
    private int updating;

    /// Number of Text nodes per line for fast line capping.
    private final ArrayDeque<Integer> lineNodeCounts = new ArrayDeque<>();
    /// The approximate number of lines in the area (bounded by LINE_CAP).
    private int lineCount;

    // The FXML-backed general stream: the tab and the controls already exist
    private Stream(TabPane tabs, Tab tab, ScrollPane scroll, TextFlow flow) {
      this.tabs = tabs;
      this.title = null;
      this.tab = tab;
      this.scroll = scroll;
      this.flow = flow;
      initView(scroll, flow);
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
      if (scroll != null) return;
      TextFlow createdFlow = new TextFlow();
      createdFlow.setStyle("-fx-background-color: -fx-control-inner-background; -fx-padding: 4px;");

      ScrollPane createdScroll = new ScrollPane(createdFlow);
      createdScroll.setFitToWidth(true);
      createdScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
      createdScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
      createdScroll.setStyle("-fx-background: -fx-control-inner-background; -fx-background-color: -fx-control-inner-background;");

      initView(createdScroll, createdFlow);

      this.scroll = createdScroll;
      this.flow = createdFlow;
      this.tab = new Tab(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " — " + title, createdScroll);
      tabs.getTabs().add(this.tab);
      tabs.getSelectionModel().select(this.tab);
    }

    private void initView(ScrollPane sp, TextFlow tf) {
      sp.setFocusTraversable(true);

      ContextMenu menu = new ContextMenu();
      MenuItem copyAll = new MenuItem("Copy All");
      copyAll.setOnAction(e -> copyAllToClipboard());
      MenuItem clear = new MenuItem("Clear");
      clear.setOnAction(e -> clear());
      menu.getItems().addAll(copyAll, clear);

      sp.setContextMenu(menu);
      tf.setOnContextMenuRequested(e -> menu.show(tf, e.getScreenX(), e.getScreenY()));

      sp.setOnMouseClicked(e -> sp.requestFocus());
      tf.setOnMouseClicked(e -> sp.requestFocus());

      sp.setOnKeyPressed(e -> {
        if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
          copyAllToClipboard();
          e.consume();
        }
      });
      tf.setOnKeyPressed(e -> {
        if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
          copyAllToClipboard();
          e.consume();
        }
      });

      sp.vvalueProperty().addListener((obs, was, now) -> {
        if (updating == 0) {
          follow = atTail();
        }
      });
      tf.heightProperty().addListener((obs, oldVal, newVal) -> {
        if (follow) {
          updating++;
          try {
            sp.layout();
            sp.setVvalue(sp.getVmax());
          } finally {
            updating--;
          }
        }
      });
    }

    private void copyAllToClipboard() {
      if (flow == null) return;
      StringBuilder sb = new StringBuilder();
      for (Node node : flow.getChildren()) {
        if (node instanceof Text text) {
          sb.append(text.getText());
        }
      }
      ClipboardContent content = new ClipboardContent();
      content.putString(sb.toString());
      Clipboard.getSystemClipboard().setContent(content);
    }

    private void clear() {
      if (flow == null) return;
      flow.getChildren().clear();
      lineNodeCounts.clear();
      lineCount = 0;
      follow = true;
    }

  // Append on the emitting thread; the flush marshals the buffer to the UI. The
  // pending buffer is capped right here: the flood-shaped producers must not
  // accumulate an oversized batch (nor grow the memory) ahead of the draining.
  private synchronized void append(String line) {
    buffer.add(line);
    if (buffer.size() > LINE_CAP) {
      buffer.subList(0, buffer.size() - LINE_CAP).clear();
    }
    if (!flushPending) {
      flushPending = true;
      Platform.runLater(this::flush);
    }
  }

  // Flush the pending buffer into the text flow on the application thread in
  // budgeted batches (also caps the displayed lines): the backlog beyond the
  // budget reschedules the flush for the following pulse leaving the application
  // thread free to process the input, layout and rendering events in between
  private void flush() {
    List<String> lines;
    boolean drained;
    synchronized (this) {
      if (buffer.isEmpty()) {
        flushPending = false;
        return;
      }
      int count = Math.min(buffer.size(), FLUSH_BUDGET);
      lines = new ArrayList<>(buffer.subList(0, count));
      buffer.subList(0, count).clear();
      if (buffer.isEmpty()) {
        flushPending = false;
        drained = true;
      } else {
        // The backlog remains: the re-scheduled run doubles as the continuation drain
        Platform.runLater(this::flush);
        drained = false;
      }
    }
    if (flow == null) attach();
    if (lines.isEmpty()) return;

    List<Text> newNodes = new ArrayList<>();
    List<Integer> counts = new ArrayList<>(lines.size());
    for (String line : lines) {
      List<Text> parsed = AnsiParser.parseLine(line);
      counts.add(parsed.size());
      newNodes.addAll(parsed);
    }

    updating++;
    try {
      flow.getChildren().addAll(newNodes);
      lineNodeCounts.addAll(counts);
      lineCount += lines.size();

      if (lineCount > LINE_CAP) {
        int drop = lineCount - LINE_CAP;
        int nodesToRemove = 0;
        for (int i = 0; i < drop; i++) {
          Integer c = lineNodeCounts.pollFirst();
          if (c != null) nodesToRemove += c;
        }
        if (nodesToRemove > 0) {
          flow.getChildren().subList(0, Math.min(nodesToRemove, flow.getChildren().size())).clear();
        }
        lineCount -= drop;
      }

      // The tail pinning runs on the drained batches only: the CSS/layout pass
      // over the capped text flow is expensive, and the mid-drain pins would run
      // it per pulse; the height listener keeps the grow-phase following meanwhile
      if (follow && drained) {
        flow.applyCss();
        flow.layout();
        scroll.layout();
        scroll.setVvalue(scroll.getVmax());
        Platform.runLater(() -> {
          try {
            if (follow) {
              flow.applyCss();
              flow.layout();
              scroll.layout();
              scroll.setVvalue(scroll.getVmax());
            }
          } finally {
            updating--;
          }
        });
      } else {
        updating--;
      }
    } catch (Throwable t) {
      updating--;
      throw t;
    }
  }

    /// Whether the view rests at the tail of the text; the not-yet-laid-out (and the
    /// non-scrollable) area counts as the tail.
    private boolean atTail() {
      if (scroll == null) return true;
      return scroll.getVvalue() >= scroll.getVmax() - TAIL_TOLERANCE;
    }
  }

  @FXML
  private TabPane tabs;

  @FXML
  private Tab generalTab;

  @FXML
  private ScrollPane generalScroll;

  @FXML
  private TextFlow generalFlow;

  private Stream general;

  public LogController() {}

  // The general stream binding happens after the FXML member injection
  @FXML
  private void initialize() {
    general = new Stream(tabs, generalTab, generalScroll, generalFlow);
  }

  /// Append a message to the general application log tab (thread-safe).
  public void log(String message) {
    if (general != null) {
      general.append("\u001B[90m[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "]\u001B[0m " + message);
    }
  }

  /// Open a new run tab returning the per-line sink (thread-safe). The tab is named
  /// by the wall clock; the caller keeps appending the run's progress. The scene-graph
  /// objects are constructed on the application thread (the caller may be the
  /// operations thread); the lines emitted before the attachment lands buffer in
  /// the stream and flush once the tab exists.
  public Consumer<String> openRun(String title) {
    if (tabs == null) return s -> {};
    Stream stream = new Stream(tabs, title);
    Platform.runLater(stream::attach);
    return stream::append;
  }

  /// The root pane (used by the main controller to set the layout growth).
  public TabPane root() {
    return tabs;
  }
}
