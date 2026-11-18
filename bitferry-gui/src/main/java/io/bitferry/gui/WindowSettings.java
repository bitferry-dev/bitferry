package io.bitferry.gui;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.lang.System.Logger.Level;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/// The persisted window status: the placement, the size, the maximized flag plus
/// the log split divider position and the backend verbosity switch. Backed by the
/// platform per-user preferences store; the missing or out-of-range entries fall
/// back to the defaults.
///
/// <p>The persisted geometry is the <em>normal</em> (restored) window bounds, not
/// the transient expanded ones: the [attach] tracker keeps recording the bounds
/// while the window is not maximized, so the capture stores the pre-maximize
/// geometry along with the maximized flag — unmaximizing on the next launch lands
/// back on the proper normal size instead of a screen-sized window. The restored
/// placement is validated against the currently available screens: the geometry
/// saved for a since-detached monitor falls back to the platform default
/// placement.
final class WindowSettings {

  private static final double DEFAULT_WIDTH = 1080;
  private static final double DEFAULT_HEIGHT = 720;
  private static final double DEFAULT_DIVIDER = 0.62;

  // The minimal sensible window size; the persisted entries below it are treated
  // as corrupt and clamped
  private static final double MIN_WIDTH = 320;
  private static final double MIN_HEIGHT = 240;

  private static final System.Logger LOG = System.getLogger(WindowSettings.class.getName());

  private final Preferences prefs = Preferences.userNodeForPackage(WindowSettings.class);

  // The normal (non-maximized) bounds; the placement is NaN when never persisted:
  // the platform default placement applies
  private double x = Double.NaN;
  private double y = Double.NaN;
  private double width = DEFAULT_WIDTH;
  private double height = DEFAULT_HEIGHT;
  private boolean maximized;
  private double divider = DEFAULT_DIVIDER;
  private boolean verbose;
  private boolean simulate;

  /// Read the persisted state (the missing entries fall back to the defaults).
  static WindowSettings load() {
    WindowSettings settings = new WindowSettings();
    Preferences prefs = settings.prefs;
    Preferences legacy = Preferences.userRoot().node("bitferry/gui");
    Preferences source = prefs;
    if (prefs.get("width", null) == null && legacy.get("width", null) != null) {
      source = legacy;
    }
    settings.x = source.getDouble("x", Double.NaN);
    settings.y = source.getDouble("y", Double.NaN);
    settings.width = source.getDouble("width", DEFAULT_WIDTH);
    settings.height = source.getDouble("height", DEFAULT_HEIGHT);
    settings.maximized = source.getBoolean("maximized", false);
    settings.divider = clamp(source.getDouble("divider", DEFAULT_DIVIDER), 0.1, 0.9);
    settings.verbose = source.getBoolean("verbose", false);
    settings.simulate = source.getBoolean("simulate", false);
    return settings;
  }

  /// Apply the persisted state to the stage: the normal bounds always; the
  /// placement only when it lands on the currently available screens. Call before
  /// the stage shows (the maximized flag overrides the size upon the restore).
  void apply(Stage stage) {
    width = Math.max(MIN_WIDTH, width);
    height = Math.max(MIN_HEIGHT, height);
    if (!Double.isNaN(x) && !Double.isNaN(y)
      && Double.isFinite(x) && Double.isFinite(y)
      && screenUnion().intersects(x, y, width, height)) {
      stage.setX(x);
      stage.setY(y);
    }
    stage.setWidth(width);
    stage.setHeight(height);
    stage.setMaximized(maximized);
  }

  /// Track the normal bounds of the stage: the geometry is recorded continuously
  /// while the window is not maximized/fullscreen, so the later capture persists
  /// the pre-maximize geometry regardless of the state the window exits in. Call
  /// once after [apply] (before the show) so the applied size seeds the tracker.
  void attach(Stage stage) {
    InvalidationListener tracker = observable -> track(stage);
    stage.xProperty().addListener(tracker);
    stage.yProperty().addListener(tracker);
    stage.widthProperty().addListener(tracker);
    stage.heightProperty().addListener(tracker);
    // The restored geometry settles asynchronously on some platforms (the X11
    // window managers deliver the configure events late): re-track on the next
    // pulse instead of trusting the in-flight bounds
    stage.maximizedProperty().addListener((observable, was, now) -> {
      if (!now) Platform.runLater(() -> track(stage));
    });
    stage.fullScreenProperty().addListener((observable, was, now) -> {
      if (!now) Platform.runLater(() -> track(stage));
    });
    track(stage);
  }

  // Snapshot the stage geometry into the normal bounds; skipped in the maximized /
  // fullscreen state (the expanded bounds are transient) and when not yet measurable
  private void track(Stage stage) {
    if (stage.isMaximized() || stage.isFullScreen()) return;
    double atX = stage.getX();
    double atY = stage.getY();
    double atWidth = stage.getWidth();
    double atHeight = stage.getHeight();
    if (Double.isNaN(atX) || Double.isNaN(atY) || atWidth <= 0 || atHeight <= 0) return;
    x = atX;
    y = atY;
    width = Math.max(MIN_WIDTH, atWidth);
    height = Math.max(MIN_HEIGHT, atHeight);
  }

  /// Capture the live state and persist it (call upon the window hiding, while the
  /// platform still reports the geometry). The maximized flag travels from the
  /// stage; the geometry comes from the normal bounds tracker. The divider position
  /// (the log pane share), the verbosity switch, and the simulation mode travel from the caller.
  void capture(Stage stage, double divider, boolean verbose, boolean simulate) {
    track(stage);
    maximized = stage.isMaximized();
    this.divider = clamp(divider, 0.1, 0.9);
    this.verbose = verbose;
    this.simulate = simulate;
    save();
  }

  void capture(Stage stage, double divider, boolean verbose) {
    capture(stage, divider, verbose, false);
  }

  double divider() {
    return divider;
  }

  boolean verbose() {
    return verbose;
  }

  boolean simulate() {
    return simulate;
  }

  // Write the state into the preferences store; the storage failures are swallowed
  // (the missing persistence degrades to the defaults, never to a startup failure)
  private void save() {
    prefs.putDouble("x", x);
    prefs.putDouble("y", y);
    prefs.putDouble("width", width);
    prefs.putDouble("height", height);
    prefs.putBoolean("maximized", maximized);
    prefs.putDouble("divider", divider);
    prefs.putBoolean("verbose", verbose);
    prefs.putBoolean("simulate", simulate);
    try {
      prefs.flush();
    } catch (BackingStoreException e) {
      LOG.log(Level.WARNING, () -> "The window status persistence failed: " + e);
    }
  }

  // The union of the visual bounds of all the screens (the conservative placement test;
  // Rectangle2D carries no union operation, so the bounding box is composed by hand)
  private static Rectangle2D screenUnion() {
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    for (Screen screen : Screen.getScreens()) {
      Rectangle2D bounds = screen.getVisualBounds();
      minX = Math.min(minX, bounds.getMinX());
      minY = Math.min(minY, bounds.getMinY());
      maxX = Math.max(maxX, bounds.getMaxX());
      maxY = Math.max(maxY, bounds.getMaxY());
    }
    return minX > maxX ? new Rectangle2D(0, 0, Double.MAX_VALUE, Double.MAX_VALUE)
      : new Rectangle2D(minX, minY, maxX - minX, maxY - minY);
  }

  private static double clamp(double value, double min, double max) {
    if (!Double.isFinite(value)) return min;
    return Math.max(min, Math.min(max, value));
  }
}
