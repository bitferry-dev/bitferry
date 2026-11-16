package bitferry;

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
  public static void writeText(Path target, String contents) throws IOException {
    target = target.toAbsolutePath().normalize();
    if (target.getParent() != null) Files.createDirectories(target.getParent());
    Path image = target.resolveSibling(target.getFileName().toString() + "~");
    try {
      try (FileChannel channel = FileChannel.open(image, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        channel.write(ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
      }
      try {
        Files.move(image, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(image, target, StandardCopyOption.REPLACE_EXISTING);
      }
      if (target.getFileName().toString().startsWith(".")) {
        Bitferry.hideIfSupported(target);
      }
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
}
