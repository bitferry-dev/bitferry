package io.bitferry;

/// The text glyph policy: whether the emitted text may carry the unicode glyphs
/// (the direction arrows, the status marks) or must resort to the ASCII
/// approximations. The class holds the unicode axis of the rendering matrix; the
/// coloring is the orthogonal axis (the ANSI escapes are decided by the consumer
/// type: the interactive terminals and the GUI log parser render them, the log
/// files and the pipes must not receive them).
///
/// <p>A process runs exactly one frontend, which decides the axis once upon the
/// startup: the GUI always allows (JavaFX renders the glyphs, no terminal is
/// involved), the CLI probes the console encoding — the Windows consoles carry
/// the unicode through the wide-char console API regardless of the code page,
/// while the unix consoles follow the locale-derived encoding (the redirected
/// streams default to UTF-8). The encoder would otherwise silently degrade the
/// unmappable glyphs to the semantically lossy '?' characters; the ASCII
/// approximations (--> and <-> for the arrows, ok/x for the marks) preserve the
/// meaning instead.
public final class Symbols {

  private static volatile boolean unicode = true;

  private Symbols() {}

  /// Whether the unicode glyphs are allowed in the emitted text.
  public static boolean unicode() {
    return unicode;
  }

  /// Decide the unicode axis (called once by the frontend upon the startup).
  public static void unicode(boolean value) {
    unicode = value;
  }
}
