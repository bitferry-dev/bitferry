package bitferry.endpoint;

import bitferry.*;
import com.fasterxml.jackson.annotation.*;
import java.io.File;
import java.nio.file.*;
import java.util.Optional;

/// Path endpoint relative to a Bitferry volume matched by the volume's tag. The
/// volume-relative path is kept in the universal '/'-separated form (regardless of
/// the platform's own separator) making the storage files portable across the
/// platforms and restored interchangeably on Windows, UNIX and macOS hosts.
public final class VolumeEndpoint implements Endpoint {

  @JsonIgnore
  private final transient Manager manager;

  @JsonProperty("volume")
  private final String vtag;

  @JsonProperty
  private final String path;

  /// Constructor for a new endpoint validating the volume-relative path; also serves
  /// as the Jackson creator restoring an existing endpoint from the storage descriptor
  /// (the manager is injected as it is not a part of the descriptor).
  @JsonCreator
  public VolumeEndpoint(
    @JacksonInject("manager") Manager manager,
    @JsonProperty("volume") String vtag,
    @JsonProperty("path") String path
  ) {
    if (manager == null || vtag == null) throw new BitferryError("invalid endpoint descriptor");
    path = path == null ? "." : path;
    Path p = Path.of(path);
    for (Path element : p) {
      if (element.toString().equals("..")) throw new BitferryError("invalid volume-relative path '" + path + "'");
    }
    if (p.isAbsolute()) throw new BitferryError("invalid volume-relative path '" + path + "'");
    this.manager = manager;
    this.vtag = vtag;
    // The platform separator (the backslash of the Windows paths) is normalized
    // into the universal '/'-separated storage form
    String rendered = p.toString().replace(File.separatorChar, '/');
    this.path = rendered.isEmpty() ? "." : rendered;
  }

  public String vtag() {
    return vtag;
  }

  public String path() {
    return path;
  }

  @Override
  public String processOpts() {
    return volume().root().resolve(path).normalize().toString();
  }

  @Override
  public int generation() {
    // The absent (not registered) volumes contribute nothing to the out-of-date
    // generation dance: the operations must succeed in spite of the absence
    // (e.g. the deletion of a task whose peer volume is not restored)
    Volume volume = manager.volumes().get(vtag);
    return volume == null ? 0 : volume.generation();
  }

  @Override
  public boolean refersVolume(String tag) {
    return vtag.equals(tag);
  }

  @Override
  public Optional<String> volumeTag() {
    return Optional.of(vtag);
  }

  @Override
  public Volume requireVolume(Manager manager, String message) {
    Volume volume = manager.volumes().get(vtag);
    if (volume == null) throw new BitferryError("no volume #" + vtag + " is registered");
    return volume;
  }

  @JsonIgnore
  @Override
  public boolean isIntact() {
    return manager.volumes().containsKey(vtag); // An intact endpoint has referring volume registered
  }

  @Override
  public String describe() {
    // The volume root's '.' path is omitted in the listings
    return ":" + vtag + ":" + (path.equals(".") ? "" : path);
  }

  private Volume volume() {
    Volume volume = manager.volumes().get(vtag);
    if (volume == null) throw new BitferryError("no volume #" + vtag + " is registered");
    return volume;
  }
}
