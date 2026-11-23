package org.bitferry;

import java.io.*;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.prefs.*;
import java.util.regex.*;
import java.lang.System.Logger.*;

/// The persisted volume search roots: extra directories probed during manager
/// restore, persisted per user via the Java Preferences API under the domain-level
/// `/org/bitferry` node.
public final class Paths {

  private static final System.Logger LOG = System.getLogger(Paths.class.getName());
  private static final Preferences prefs = Preferences.userNodeForPackage(Bitferry.class);
  private static final Preferences legacyPrefs = Preferences.userRoot().node("bitferry");
  private static final String KEY = "path";

  private Paths() {}

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

  /// Split the joined (pathSeparator-separated) entries into the trimmed, blank-free
  /// path list: the environment/property composed roots ride the same discipline as
  /// the persisted ones (the empty segments and the unparseable entries are skipped -
  /// a stray separator must not kill the whole restoration).
  public static List<Path> parseEntries(String joined) {
    List<Path> result = new ArrayList<>();
    if (joined == null || joined.isBlank()) return result;
    for (String entry : joined.split(Pattern.quote(File.pathSeparator))) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) continue;
      try {
        result.add(Path.of(trimmed));
      } catch (InvalidPathException e) {
        LOG.log(Level.DEBUG, () -> "Skipping the unparseable path entry '" + trimmed + "': " + e.getMessage());
      }
    }
    return result;
  }

  /// Whether the specified location is covered by the volume scanning policy: the
  /// persisted per-site scanned locations, the BITFERRY_PATH entries, the
  /// bitferry.path property, the user's home directory, or the mounted file system
  /// roots. The probing is exact-root (no recursion), so the uncovered locations
  /// host the volumes the next start will not discover - the frontends offer
  /// adding them to the scanned locations upon the creation.
  public static boolean covers(Path location) {
    Path canonical = Bitferry.canonicalize(location);
    List<Path> roots = new ArrayList<>();
    String env = System.getenv("BITFERRY_PATH");
    if (env != null && !env.isBlank()) {
      roots.addAll(parseEntries(env));
    }
    roots.addAll(parseEntries(System.getProperty("bitferry.path")));
    for (String entry : load()) {
      try {
        roots.add(Path.of(entry));
      } catch (InvalidPathException ignored) {
        // The unparseable entries cannot cover anything
      }
    }
    roots.add(Path.of(System.getProperty("user.home")));
    roots.addAll(Manager.mountPoints());
    for (Path root : roots) {
      try {
        if (Bitferry.canonicalize(root).equals(canonical)) return true;
      } catch (Exception ignored) {
        // The unresolvable entries cannot cover anything
      }
    }
    return false;
  }

  /// Persist the volume search roots (the entries are trimmed, blanks dropped, and
  /// joined with [File#pathSeparator]; an empty list removes the key).
  public static void save(List<String> roots) {
    List<String> valid = new ArrayList<>();
    if (roots != null) {
      for (String root : roots) {
        if (root != null) {
          String trimmed = root.trim();
          if (trimmed.isEmpty()) continue;
          // The separator characters cannot be persisted unambiguously (the persisted
          // form joins on the platform separator): refuse instead of silently splitting
          // the entry into two on the following load
          // WINDOWS: the legal Windows directory names may carry ';'-the refusal is
          // deliberate, confirm the frontends surface it acceptably on such entries
          if (trimmed.contains(File.pathSeparator)) {
            throw new BitferryError("The volume search path '" + trimmed + "' contains the path separator '" + File.pathSeparator + "' and cannot be persisted");
          }
          valid.add(trimmed);
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
      LOG.log(Level.WARNING, () -> "The volume search paths persistence failed: " + e);
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
