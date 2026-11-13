package bitferry;

import java.nio.file.*;
import java.nio.file.attribute.FileAttributeView;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/// The mounted file system probing helpers: the mount point extraction out of the
/// file store's textual form and the mount points enumeration.
class ManagerMountsTest {

  /// A minimal file store fake carrying only the text (the probe consumes nothing else).
  private static FileStore store(String text, String type) {
    return new FileStore() {
      public String name() { return text; }
      public String type() { return type; }
      public long getTotalSpace() { throw new UnsupportedOperationException(); }
      public long getUsableSpace() { throw new UnsupportedOperationException(); }
      public long getUnallocatedSpace() { throw new UnsupportedOperationException(); }
      public boolean supportsFileAttributeView(String attributeView) { return false; }
      public boolean supportsFileAttributeView(Class<? extends FileAttributeView> attributeView) { return false; }
      public <V extends java.nio.file.attribute.FileStoreAttributeView> V getFileStoreAttributeView(Class<V> attributeView) { return null; }
      public boolean isReadOnly() { return false; }
      public Object getAttribute(String attribute) { throw new UnsupportedOperationException(); }
      public Map<String, Object> readAttributes(String attribute) { throw new UnsupportedOperationException(); }
      public String toString() { return text; }
    };
  }

  @Test
  void mountPointIsExtractedOutOfTheStoreText() {
    assertEquals(Path.of("/data"), Manager.mountPoint(store("/data (/dev/sdc1)", "btrfs")));
    assertEquals(Path.of("/run/media/fougas/ROAMING"), Manager.mountPoint(store("/run/media/fougas/ROAMING (/dev/sdd1)", "ntfs3")));
    // The macOS firmlink data volume store
    assertEquals(Path.of("/System/Volumes/Data"), Manager.mountPoint(store("/System/Volumes/Data (apfs)", "apfs")));
    // The Windows stores with and without label
    assertEquals(Path.of("C:\\"), Manager.mountPoint(store("SYSTEM (C:)", "NTFS")));
    assertEquals(Path.of("D:\\"), Manager.mountPoint(store("(D:)", "NTFS")));
    // The text without the mount point suffix is rejected
    assertNull(Manager.mountPoint(store("weird", "btrfs")));
  }

  @Test
  void allMountedFileSystemsAreEnumeratedWithoutFiltering() {
    // Every mount point is probeable: no filtering, the single stat per mount is
    // next to free while any skip list would be operating system specific
    List<Path> mounts = Manager.mountPoints();
    assertFalse(mounts.isEmpty());
    // At least one root file system is always mounted
    boolean hasRoot = mounts.stream().anyMatch(p -> p.equals(Path.of("/")) || (p.getRoot() != null && p.getRoot().equals(p)));
    assertTrue(hasRoot, () -> "Expected at least one filesystem root in mounts: " + mounts);
    // All the mount points are absolute
    for (Path mount : mounts) assertTrue(mount.isAbsolute());
  }

  @Test
  void mountSignatureIsStableAndComplete() {
    SortedSet<String> first = Manager.mountSetSignature();
    SortedSet<String> second = Manager.mountSetSignature();
    // The consecutive reads of the same mount state produce equal signatures
    assertEquals(first, second);
    assertFalse(first.isEmpty());
    // The root file system is always part of the signature
    assertTrue(first.stream().anyMatch(p -> p.equals("/") || p.matches("[A-Z]:\\\\?")),
      () -> "Expected a filesystem root in the signature: " + first);
    // The sorted order is deterministic (the diff is the equality of two snapshots)
    List<String> asList = new ArrayList<>(first);
    assertEquals(asList.stream().sorted().toList(), asList);
  }
}
