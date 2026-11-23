package org.bitferry;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/// Core library-wide constants and shared helpers.
public final class Bitferry {

  /// Library version mirroring the Python implementation's `__version__`. The value
  /// is provided by the build through the version resource (mirroring the Gradle
  /// project version); "dev" when unresolved (e.g. running off the classes alone).
  public static final String VERSION = loadVersion();

  /// Current storage schema format version.
  public static final String STORAGE_VERSION = "0";

  /// Name of the volume storage file keeping the volume state as JSON.
  public static final String STORAGE = ".bitferry";

  /// The storage file's intermediate image: the atomic write goes to this file
  /// first which then replaces the storage file in a single rename step. The
  /// intermediate file exists transiently during the commit (and is left behind
  /// by an interrupted commit) so it is excluded from all the task operations.
  public static final String STORAGE_IMAGE = STORAGE + "~";

  /// Universal exclusion prefix covering volume storage files (.bitferry and .bitferry~)
  /// as well as per-task runtime metadata directories (.bitferry.<task_id>).
  public static final String STORAGE_PREFIX = STORAGE + "*";

  /// The transient writability probe file: the volume creation attempts to create
  /// and remove it in the root, refusing the roots that accept directories while
  /// refusing files - such locations (the restricted drive roots, the managed
  /// shares) would fail the storage write mid-commit with an opaque error.
  public static final String STORAGE_PROBE = STORAGE + ".probe";

  /// Attempt to set the Windows hidden attribute (dos:hidden) on the given path if supported,
  /// keeping dotfiles and metadata directories hidden in Windows Explorer.
  public static void hideIfSupported(Path path) {
    try {
      var dosView = Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class);
      if (dosView != null) {
        dosView.setHidden(true);
      }
    } catch (Exception ignored) {
      // Best-effort attribute setting; ignore on non-compliant filesystems
    }
  }

  private Bitferry() {}

  // Read the build-provided version from the classpath resource
  private static String loadVersion() {
    try (var input = Bitferry.class.getResourceAsStream("/org/bitferry/version.properties")) {
      if (input != null) {
        var properties = new Properties();
        properties.load(input);
        String version = properties.getProperty("version");
        if (version != null && !version.isBlank()) return version;
      }
    } catch (IOException e) {
      // Fall through to the development default
    }
    return "dev";
  }

  /// Return the most normalized form of the specified path, resolving the symlinks
  /// of the existing prefix if possible (mirroring Python's `Path.resolve(strict=False)`).
  public static Path canonicalize(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }
}
