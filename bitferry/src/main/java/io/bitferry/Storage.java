package io.bitferry;

import com.fasterxml.jackson.annotation.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import tools.jackson.core.*;
import tools.jackson.core.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.cfg.*;
import tools.jackson.databind.json.*;
import tools.jackson.databind.module.*;

/// Volume storage I/O helpers: the shared Jackson mapper, the atomic file writer
/// mirroring the reference implementation's `atomic_writer` and the JSON utilities.
public final class Storage {

  // The shared mapper: ISO date strings, plain (non-URI) paths, the null (and hence the
  // empty, by convention) section omission mirroring the reference implementation's
  // `filter_nones`, and the lenient handling of the unknown descriptor sections
  private static final ObjectMapper MAPPER = JsonMapper.builder()
    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
    .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .addModule(pathsModule())
    .build();

  // The java.nio.file.Path values travel as the plain platform strings
  private static SimpleModule pathsModule() {
    SimpleModule module = new SimpleModule("bitferry-paths");
    module.addSerializer(Path.class, new ValueSerializer<>() {
      @Override
      public void serialize(Path value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(value.toString());
      }
    });
    module.addDeserializer(Path.class, new ValueDeserializer<>() {
      @Override
      public Path deserialize(JsonParser parser, DeserializationContext context) {
        return Path.of(parser.getString());
      }
    });
    return module;
  }

  // The pretty format matches Python's `json.dumps(indent=2)` ("key": value, 2-space indent)
  private static final ObjectWriter WRITER = MAPPER.writer().with(
    new DefaultPrettyPrinter().withSeparators(new Separators().withObjectNameValueSpacing(Separators.Spacing.AFTER)));

  private Storage() {}

  /// The shared Jackson mapper used for the storage serialization.
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  /// Write the contents into the specified target file atomically: the new contents
  /// go into the target's '~'-suffixed intermediate file first which then replaces
  /// the target in a single rename step. The intermediate file is removed upon any failure.
  ///
  /// <p>The intermediate file is restricted to its owner (best-effort) before the
  /// contents are written: the storage files carry the vault section with the password
  /// tokens (the rclone-obscured secrets recoverable by anyone who can read the file),
  /// so the umask-inherited broad permissions must not leak them. The move preserves
  /// the permissions of the intermediate file.
  public static void writeText(Path target, String contents) throws IOException {
    target = target.toAbsolutePath().normalize();
    if (target.getParent() != null) Files.createDirectories(target.getParent());
    Path image = target.resolveSibling(target.getFileName().toString() + "~");
    try {
      try (FileChannel channel = FileChannel.open(image, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        restrictToOwner(image);
        channel.write(ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
      }
      try {
        Files.move(image, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(image, target, StandardCopyOption.REPLACE_EXISTING);
      }
      // The parent directory entry durability (best-effort): the rename above is only
      // as durable as the directory's metadata - the platforms without a directory
      // fsync (Windows, the file systems refusing directory channels) skip it silently
      // WINDOWS: verify the directory-channel open fast-fails there (and is swallowed)
      // on NTFS/FAT/exFAT, and that the rename durability without a directory fsync
      // stays acceptable (the '~' image remains behind as the recovery residue)
      if (target.getParent() != null) {
        try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
          directory.force(true);
        } catch (UnsupportedOperationException | IOException ignored) {
          // The best-effort durability: no-op where the platform cannot do it
        }
      }
      if (target.getFileName().toString().startsWith(".")) {
        Bitferry.hideIfSupported(target);
      }
    } catch (AccessDeniedException e) {
      // The restriction surfaced by name: the locations reserving the file creation
      // (the drive roots, the managed shares) or refusing the writes (the read-only
      // mounts) would otherwise dead-end the diagnosis with a bare 'access denied'
      IOException enriched = new IOException("The location " + target + " does not permit file creation for this account", e);
      try {
        Files.deleteIfExists(image);
      } catch (IOException suppressed) {
        enriched.addSuppressed(suppressed);
      }
      throw enriched;
    } catch (IOException e) {
      try {
        Files.deleteIfExists(image);
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  /// Read and parse the JSON file.
  public static JsonNode readJson(Path file) {
    return MAPPER.readTree(file.toFile());
  }

  /// Serialize the node into the pretty-printed (2-space indented) storage format string.
  public static String toJson(JsonNode node) {
    return toJson((Object) node);
  }

  /// Serialize the value into the pretty-printed (2-space indented) storage format string.
  public static String toJson(Object value) {
    try {
      return WRITER.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new BitferryError("serialization failure: " + e.getMessage(), e);
    }
  }

  /// Best-effort owner-only restriction of a storage file: the POSIX mode bits where
  /// the file system supports them, the owner-only ACL otherwise (Windows). Mirrors
  /// the strict variant of the task package's temporary password files, but degrades
  /// silently: the vault travels with the removable media, and the permission-less
  /// file systems of that media (the FAT family) must not fail the storage write -
  /// the hardening simply applies where the platform can represent it.
  private static void restrictToOwner(Path file) {
    try {
      Files.setPosixFilePermissions(file, java.util.Set.of(
        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
      return;
    } catch (UnsupportedOperationException | IOException ignored) {
      // Not a POSIX file system (e.g. Windows), or the chmod was refused: fall
      // through to the ACL handling
    }
    try {
      java.nio.file.attribute.AclFileAttributeView view =
        Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
      if (view == null) return; // No ACL support (e.g. FAT): keep the defaults
      java.nio.file.attribute.UserPrincipal owner = view.getOwner();
      // The single allowed entry: the owner gets the full control; every other
      // account (and the inherited entries) gets nothing
      java.nio.file.attribute.AclEntry ownerEntry = java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
        .build();
      view.setAcl(java.util.List.of(ownerEntry));
    } catch (IOException | SecurityException ignored) {
      // The best-effort hardening must never fail the storage write
    }
  }
}
