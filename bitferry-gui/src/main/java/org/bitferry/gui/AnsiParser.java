package org.bitferry.gui;

import javafx.scene.paint.Color;
import javafx.scene.text.*;

import java.util.*;

/// Parses terminal text containing ANSI escape sequences (the SGR color and style
/// codes, the OSC payload skipping) into styled JavaFX Text nodes.
public final class AnsiParser {

  public static final Color DEFAULT_FG = Color.web("#222222");
  public static final Color DIM_FG = Color.web("#6e7781");

  // Standard ANSI foreground colors (30-37) - tuned for readability on light backgrounds
  public static final Color BLACK = Color.web("#24292e");
  public static final Color RED = Color.web("#cf222e");
  public static final Color GREEN = Color.web("#1a7f37");
  public static final Color YELLOW = Color.web("#9a6700");
  public static final Color BLUE = Color.web("#0969da");
  public static final Color MAGENTA = Color.web("#8250df");
  public static final Color CYAN = Color.web("#1b7c83");
  public static final Color WHITE = Color.web("#57606a");

  // Bright ANSI foreground colors (90-97)
  public static final Color BRIGHT_BLACK = Color.web("#57606a");
  public static final Color BRIGHT_RED = Color.web("#a40e26");
  public static final Color BRIGHT_GREEN = Color.web("#116329");
  public static final Color BRIGHT_YELLOW = Color.web("#b07000");
  public static final Color BRIGHT_BLUE = Color.web("#1565c0");
  public static final Color BRIGHT_MAGENTA = Color.web("#9933cc");
  public static final Color BRIGHT_CYAN = Color.web("#00838f");
  public static final Color BRIGHT_WHITE = Color.web("#24292e");

  private AnsiParser() {}

  /// Parse a line of text, extracting ANSI SGR sequences into styled Text nodes
  /// with a trailing newline.
  public static List<Text> parseLine(String line) {
    List<Text> result = new ArrayList<>();
    if (line == null || line.isEmpty()) {
      Text empty = new Text("\n");
      empty.setFont(getFont(false, false));
      empty.setFill(DEFAULT_FG);
      result.add(empty);
      return result;
    }

    boolean bold = false;
    boolean dim = false;
    boolean italic = false;
    boolean underline = false;
    Color fgColor = DEFAULT_FG;

    StringBuilder currentText = new StringBuilder();

    int i = 0;
    int len = line.length();

    while (i < len) {
      char c = line.charAt(i);
      if (c == '\u001B' && i + 1 < len) {
        char kind = line.charAt(i + 1);
        if (kind == '[') { // CSI (the SGR and the cursor sequences)
          int j = i + 2;
          while (j < len && (line.charAt(j) < 0x40 || line.charAt(j) > 0x7E)) {
            j++;
          }
          if (j >= len) {
            // The truncated sequence (the output chunked mid-escape): the remainder
            // is dropped - rendering the raw escape bytes as text would corrupt the log
            break;
          }
          char finalChar = line.charAt(j);
          if (finalChar == 'm') {
            if (currentText.length() > 0) {
              result.add(createText(currentText.toString(), bold, dim, italic, underline, fgColor));
              currentText.setLength(0);
            }
            String params = line.substring(i + 2, j);
            if (params.isEmpty()) {
              bold = false;
              dim = false;
              italic = false;
              underline = false;
              fgColor = DEFAULT_FG;
            } else {
              String[] tokens = params.split(";");
              for (int t = 0; t < tokens.length; t++) {
                if (tokens[t].isEmpty()) continue;
                int code;
                try {
                  code = Integer.parseInt(tokens[t]);
                } catch (NumberFormatException ignored) {
                  continue;
                }
                switch (code) {
                  case 0 -> {
                    bold = false;
                    dim = false;
                    italic = false;
                    underline = false;
                    fgColor = DEFAULT_FG;
                  }
                  case 1 -> bold = true;
                  case 2 -> dim = true;
                  case 3 -> italic = true;
                  case 4 -> underline = true;
                  case 21, 22 -> {
                    bold = false;
                    dim = false;
                  }
                  case 23 -> italic = false;
                  case 24 -> underline = false;
                  case 30 -> fgColor = BLACK;
                  case 31 -> fgColor = RED;
                  case 32 -> fgColor = GREEN;
                  case 33 -> fgColor = YELLOW;
                  case 34 -> fgColor = BLUE;
                  case 35 -> fgColor = MAGENTA;
                  case 36 -> fgColor = CYAN;
                  case 37 -> fgColor = WHITE;
                  case 39 -> fgColor = DEFAULT_FG;
                  case 90 -> fgColor = BRIGHT_BLACK;
                  case 91 -> fgColor = BRIGHT_RED;
                  case 92 -> fgColor = BRIGHT_GREEN;
                  case 93 -> fgColor = BRIGHT_YELLOW;
                  case 94 -> fgColor = BRIGHT_BLUE;
                  case 95 -> fgColor = BRIGHT_MAGENTA;
                  case 96 -> fgColor = BRIGHT_CYAN;
                  case 97 -> fgColor = BRIGHT_WHITE;
                  case 38 -> {
                    if (t + 4 < tokens.length && "2".equals(tokens[t + 1])) {
                      try {
                        int r = Integer.parseInt(tokens[t + 2]);
                        int g = Integer.parseInt(tokens[t + 3]);
                        int b = Integer.parseInt(tokens[t + 4]);
                        fgColor = Color.rgb(Math.min(255, Math.max(0, r)),
                                            Math.min(255, Math.max(0, g)),
                                            Math.min(255, Math.max(0, b)));
                      } catch (NumberFormatException ignored) {}
                      t += 4;
                    } else if (t + 2 < tokens.length && "5".equals(tokens[t + 1])) {
                      try {
                        int n = Integer.parseInt(tokens[t + 2]);
                        fgColor = color256(n);
                      } catch (NumberFormatException ignored) {}
                      t += 2;
                    }
                  }
                  default -> {}
                }
              }
            }
          }
          i = j + 1;
          continue;
        }
        if (kind == ']') { // OSC (the window titles, the hyperlinks): the payload is
          // skipped up to the BEL or the ST (ESC \) terminator - it must never render
          int j = i + 2;
          while (j < len) {
            if (line.charAt(j) == '\u0007') { j++; break; }
            if (line.charAt(j) == '\u001B' && j + 1 < len && line.charAt(j + 1) == '\\') { j += 2; break; }
            j++;
          }
          // The unterminated payload drops the remainder of the line (mirroring the
          // truncated CSI above)
          i = Math.min(j, len);
          continue;
        }
        // The other two-byte ESC families (the charset shifts etc.): consume the pair
        i += 2;
        continue;
      }
      currentText.append(c);
      i++;
    }

    if (currentText.length() > 0) {
      result.add(createText(currentText.toString(), bold, dim, italic, underline, fgColor));
    }

    if (result.isEmpty()) {
      Text nl = new Text("\n");
      nl.setFont(getFont(false, false));
      nl.setFill(DEFAULT_FG);
      result.add(nl);
    } else {
      Text last = result.get(result.size() - 1);
      last.setText(last.getText() + "\n");
    }

    return result;
  }

  /// Strip all ANSI escape sequences from the string.
  public static String stripAnsi(String text) {
    if (text == null || text.isEmpty()) return text;
    // Mirrors the parseLine state machine: the SGR/CSI sequences; the OSC payloads
    // (the titles, the hyperlinks) up to their BEL/ST terminator (an unterminated
    // payload rides to the end); the truncated (line-split) sequences
    return text
      .replaceAll("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?", "")
      .replaceAll("\u001B\\[[0-9;]*[a-zA-Z]?", "");
  }

  private static Text createText(String content, boolean bold, boolean dim, boolean italic, boolean underline, Color fgColor) {
    if (content.endsWith("\r")) {
      content = content.substring(0, content.length() - 1);
    }
    Text text = new Text(content);
    text.setFont(getFont(bold, italic));
    Color fill = fgColor;
    if (dim) {
      fill = (fill == DEFAULT_FG) ? DIM_FG : Color.color(fill.getRed(), fill.getGreen(), fill.getBlue(), 0.65);
    }
    text.setFill(fill);
    if (underline) {
      text.setUnderline(true);
    }
    return text;
  }

  private static Font getFont(boolean bold, boolean italic) {
    FontWeight weight = bold ? FontWeight.BOLD : FontWeight.NORMAL;
    FontPosture posture = italic ? FontPosture.ITALIC : FontPosture.REGULAR;
    return Font.font("Monospaced", weight, posture, 12);
  }

  private static Color color256(int n) {
    if (n < 0 || n > 255) return DEFAULT_FG;
    if (n < 8) {
      return switch (n) {
        case 0 -> BLACK;
        case 1 -> RED;
        case 2 -> GREEN;
        case 3 -> YELLOW;
        case 4 -> BLUE;
        case 5 -> MAGENTA;
        case 6 -> CYAN;
        default -> WHITE;
      };
    }
    if (n < 16) {
      return switch (n) {
        case 8 -> BRIGHT_BLACK;
        case 9 -> BRIGHT_RED;
        case 10 -> BRIGHT_GREEN;
        case 11 -> BRIGHT_YELLOW;
        case 12 -> BRIGHT_BLUE;
        case 13 -> BRIGHT_MAGENTA;
        case 14 -> BRIGHT_CYAN;
        default -> BRIGHT_WHITE;
      };
    }
    if (n < 232) {
      int idx = n - 16;
      int r = idx / 36;
      int g = (idx % 36) / 6;
      int b = idx % 6;
      int red = r > 0 ? r * 40 + 55 : 0;
      int green = g > 0 ? g * 40 + 55 : 0;
      int blue = b > 0 ? b * 40 + 55 : 0;
      return Color.rgb(red, green, blue);
    }
    int gray = (n - 232) * 10 + 8;
    return Color.rgb(gray, gray, gray);
  }
}
