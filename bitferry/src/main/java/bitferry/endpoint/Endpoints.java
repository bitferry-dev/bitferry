package bitferry.endpoint;

import bitferry.*;
import com.fasterxml.jackson.annotation.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import tools.jackson.databind.*;

/// Endpoint specifier parsing and storage descriptor restoration.
///
/// The specifier may be one of:
/// <ul>
/// <li>{@code directory} -- bare path ({@code /data}, {@code c:\data}); resolved against the known volumes and
/// attached to the most concrete one encompassing the path, rejected when none matches (same as {@code ::directory})</li>
/// <li>{@code local:directory} -- explicit local form; the only way to reference the paths outside the volumes</li>
/// <li>{@code ::directory} -- path resolved against the intact volume encompassing the directory; rejected when none matches</li>
/// <li>{@code :tag:directory} -- path relative to the intact volume matched by (partial) tag</li>
/// </ul>
public final class Endpoints {

  // Explicit local: prefixed directory path (allows any path characters including colons)
  private static final Pattern EXPLICIT_LOCAL = Pattern.compile("^\\s*local:(?<root>.*)$");

  // Bare directory path to be resolved against volumes
  private static final Pattern BARE_LOCAL = Pattern.compile("^\\s*(?<root>([a-zA-Z]:)?[^:]*)$");

  // Explicitly specified path to be resolved against the intact volumes
  private static final Pattern RESOLVE = Pattern.compile("^\\s*:(?:\\s*?):(?<path>.*)$");

  // Path relative to the intact volume matched by the specified (partial) tag
  private static final Pattern ATTACH = Pattern.compile("^\\s*:(?<tag>[^\\s:]+?):(?<path>.*)$");

  private Endpoints() {}

  /// Parse the specified endpoint specifier with respect to the volumes registered in the manager.
  public static Endpoint parse(Manager manager, String specifier) {
    Matcher x = EXPLICIT_LOCAL.matcher(specifier);
    if (x.matches()) {
      return new LocalEndpoint(Path.of(x.group("root")));
    }
    x = BARE_LOCAL.matcher(specifier);
    if (x.matches()) {
      return manager.endpoint(Path.of(x.group("root")));
    }
    x = RESOLVE.matcher(specifier);
    if (x.matches()) return manager.endpoint(Path.of(x.group("path")));
    x = ATTACH.matcher(specifier);
    if (x.matches()) {
      String part = x.group("tag");
      List<Volume> es = new ArrayList<>();
      for (Volume v : manager.volumes().values()) if (v.isLive() && Tags.matches(part, v.tag())) es.add(v);
      if (es.isEmpty()) throw new BitferryError("no intact volume matching (partial) tag '" + part + "'");
      if (es.size() > 1) throw new BitferryError("multiple volumes matching (partial) tag '" + part + "': " + String.join(", ", es.stream().map(v -> "#" + v.tag()).toList()));
      return new VolumeEndpoint(manager, es.getFirst().tag(), x.group("path"));
    }
    throw new BitferryError("invalid endpoint specifier '" + specifier + "'");
  }

  /// Restore an endpoint from the storage descriptor node dispatching
  /// over the 'endpoint' discriminator property.
  public static Endpoint restore(Manager manager, JsonNode data) {
    try {
      return Storage.mapper().readerFor(Endpoint.class)
        .with(new InjectableValues.Std(Map.of("manager", manager)))
        .readValue(data);
    } catch (BitferryError e) {
      throw e;
    } catch (RuntimeException e) {
      throw BitferryError.resolve(e, "invalid endpoint descriptor: " + e.getMessage());
    }
  }
}
