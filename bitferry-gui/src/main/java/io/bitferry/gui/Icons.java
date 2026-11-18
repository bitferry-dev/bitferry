package io.bitferry.gui;

import javafx.scene.control.ButtonBase;
import javafx.scene.shape.SVGPath;

/// Centralized vector icon factory for GUI buttons.
/// Uses built-in JavaFX [SVGPath] nodes with vector coordinates normalized to a 16x16 grid,
/// binding their fill dynamically to the host button's textFill property so icons automatically
/// match button themes, tint colors, and disabled state styling without external asset dependencies.
public final class Icons {

  private Icons() {}

  /// Vector icon symbols normalized for a 16x16 pixel bounding box.
  public enum Symbol {
    /// Play triangle pointing right (execution / run)
    PLAY("M 4 3 L 13 8 L 4 13 Z"),

    /// Solid rounded square (abort / cancel)
    STOP("M 4 3 H 12 C 12.6 3 13 3.4 13 4 V 12 C 13 12.6 12.6 13 12 13 H 4 C 3.4 13 3 12.6 3 12 V 4 C 3 3.4 3.4 3 4 3 Z"),

    /// Dual circular refresh arrows (reload / sync)
    REFRESH("M 13.65 6.88 A 6 6 0 0 0 3.32 4.93 L 2 3.6 V 8 H 6.4 L 4.67 6.27 A 4.2 4.2 0 0 1 11.85 7.6 L 13.65 6.88 Z M 2.35 9.12 A 6 6 0 0 0 12.68 11.07 L 14 12.4 V 8 H 9.6 L 11.33 9.73 A 4.2 4.2 0 0 1 4.15 8.4 L 2.35 9.12 Z"),

    /// Exit door with arrow (quit / log out)
    EXIT("M 7 2 H 2.5 C 1.7 2 1 2.7 1 3.5 V 12.5 C 1 13.3 1.7 14 2.5 14 H 7 V 12.5 H 2.5 V 3.5 H 7 V 2 Z M 10 4.5 L 8.9 5.6 L 11.3 8 H 5 V 9.5 H 11.3 L 8.9 11.9 L 10 13 L 14.5 8.5 Z"),

    /// Bold plus sign (create / add)
    PLUS("M 7 2.5 H 9 V 7 H 13.5 V 9 H 9 V 13.5 H 7 V 9 H 2.5 V 7 H 7 Z"),

    /// Angled pencil (modify / edit)
    EDIT("M 11.2 2.3 L 13.7 4.8 L 12.4 6.1 L 9.9 3.6 Z M 9.2 4.3 L 11.7 6.8 L 4.5 14 H 2 V 11.5 Z"),

    /// Trash can (delete / remove)
    DELETE("M 6 1.5 H 10 V 2.5 H 13.5 V 4 H 2.5 V 2.5 H 6 Z M 4 5 H 12 L 11.2 14.5 H 4.8 Z"),

    /// Checked checkbox (mass select all)
    ALL_ON("M 2 2 H 14 V 14 H 2 Z M 3.5 3.5 V 12.5 H 12.5 V 3.5 Z M 6.5 11 L 3.5 8 L 4.6 6.9 L 6.5 8.8 L 11.4 3.9 L 12.5 5 Z"),

    /// Empty checkbox (mass clear all)
    ALL_OFF("M 2 2 H 14 V 14 H 2 Z M 3.5 3.5 V 12.5 H 12.5 V 3.5 Z"),

    /// Opposing horizontal swap arrows (mass invert)
    INVERT("M 2 4.5 H 10.5 V 2.5 L 14.5 5.5 L 10.5 8.5 V 6.5 H 2 Z M 14 11.5 H 5.5 V 13.5 L 1.5 10.5 L 5.5 7.5 V 9.5 H 14 Z"),

    /// Clock face with hands (cadence / serve)
    SERVE("M 8 1.5 A 6.5 6.5 0 1 0 14.5 8 A 6.5 6.5 0 0 0 8 1.5 Z M 8 3 A 5 5 0 1 1 3 8 A 5 5 0 0 1 8 3 Z M 7.25 4.5 H 8.75 V 7.25 H 11 V 8.75 H 7.25 Z"),

    /// Storage disk drive (volumes / drive endpoints)
    DRIVE("M 2 3 C 2 2.2 2.7 1.5 3.5 1.5 H 12.5 C 13.3 1.5 14 2.2 14 3 V 13 C 14 13.8 13.3 14.5 12.5 14.5 H 3.5 C 2.7 14.5 2 13.8 2 13 Z M 3.5 3 V 9.5 H 12.5 V 3 Z M 3.5 11 V 13 H 12.5 V 11 Z M 10.5 11.5 H 11.5 V 12.5 H 10.5 Z"),

    /// Up chevron (reorder move up)
    CHEVRON_UP("M 8 4.5 L 14 10.5 L 12.6 11.9 L 8 7.3 L 3.4 11.9 L 2 10.5 Z"),

    /// Down chevron (reorder move down)
    CHEVRON_DOWN("M 8 11.5 L 2 5.5 L 3.4 4.1 L 8 8.7 L 12.6 4.1 L 14 5.5 Z"),

    /// Directory folder (filesystem browse)
    BROWSE("M 1.5 3 C 1.5 2.2 2.2 1.5 3 1.5 H 6.5 L 8 3.5 H 13 C 13.8 3.5 14.5 4.2 14.5 5 V 12.5 C 14.5 13.3 13.8 14 13 14 H 3 C 2.2 14 1.5 13.3 1.5 12.5 Z M 3 5 V 12.5 H 13 V 5 Z"),

    /// Single checkmark (apply / commit)
    APPLY("M 6 12.5 L 1.5 8 L 2.9 6.6 L 6 9.7 L 13.1 2.6 L 14.5 4 Z"),

    /// Arrow pointing down to tray (load preset)
    LOAD("M 2.5 9 V 13.5 H 13.5 V 9 H 12 V 12 H 4 V 9 Z M 7.25 2.5 H 8.75 V 7.5 H 11 L 8 10.5 L 5 7.5 H 7.25 Z");

    private final String path;

    Symbol(String path) {
      this.path = path;
    }

    public String path() {
      return path;
    }
  }

  /// Create an [SVGPath] node initialized with the vector coordinates of the specified symbol.
  public static SVGPath icon(Symbol symbol) {
    SVGPath svg = new SVGPath();
    svg.setContent(symbol.path());
    return svg;
  }

  /// Equip a button with the symbol's vector graphic, binding its fill to the button's textFill property.
  public static SVGPath equip(ButtonBase button, Symbol symbol) {
    if (button == null) return null;
    SVGPath svg = icon(symbol);
    svg.fillProperty().bind(button.textFillProperty());
    button.setGraphic(svg);
    button.setGraphicTextGap(6.0);
    return svg;
  }
}
