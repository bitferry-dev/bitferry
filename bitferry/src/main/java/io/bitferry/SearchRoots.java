package io.bitferry;

import java.io.*;
import java.util.*;
import java.util.prefs.*;
import java.util.regex.*;
import java.lang.System.Logger.*;

/// The persisted volume search roots: extra directories probed during manager
/// restore, persisted per user via the Java Preferences API under the domain-level
/// `/io/bitferry` node.
public final class SearchRoots {

  private static final System.Logger LOG = System.getLogger(SearchRoots.class.getName());
  private static final Preferences prefs = Preferences.userNodeForPackage(Bitferry.class);
  private static final Preferences legacyPrefs = Preferences.userRoot().node("bitferry");
  private static final String KEY = "path";

  private SearchRoots() {}

  /// Read the persisted volume search roots (the missing or blank entry yields an empty list).
  public static List<String> load() {
    String value = prefs.get(KEY, "");
    if (value == null || value.isBlank()) {
      String legacy = legacyPrefs.get(KEY, "");
      if (legacy != null && !legacy.isBlank()) {
        value = legacy;
        prefs.put(KEY, legacy);
        try {
          prefs.flush();
        } catch (BackingStoreException ignored) {}
      }
    }
    if (value == null || value.isBlank()) {
      return new ArrayList<>();
    }
    List<String> result = new ArrayList<>();
    for (String entry : value.split(Pattern.quote(File.pathSeparator))) {
      String trimmed = entry.trim();
      if (!trimmed.isEmpty()) {
        result.add(trimmed);
      }
    }
    return result;
  }

  /// Persist the volume search roots (the entries are trimmed, blanks dropped, and
  /// joined with [File#pathSeparator]; an empty list removes the key).
  public static void save(List<String> roots) {
    List<String> valid = new ArrayList<>();
    if (roots != null) {
      for (String root : roots) {
        if (root != null) {
          String trimmed = root.trim();
          if (!trimmed.isEmpty()) {
            valid.add(trimmed);
          }
        }
      }
    }
    if (valid.isEmpty()) {
      prefs.remove(KEY);
    } else {
      prefs.put(KEY, String.join(File.pathSeparator, valid));
    }
    try {
      prefs.flush();
    } catch (BackingStoreException e) {
      LOG.log(Level.WARNING, () -> "The volume search roots persistence failed: " + e);
    }
  }

  /// Return the underlying Preferences node path (for tests and verification).
  public static String nodePath() {
    return prefs.absolutePath();
  }

  /// Return the underlying Preferences key (for tests and verification).
  public static String key() {
    return KEY;
  }
}
