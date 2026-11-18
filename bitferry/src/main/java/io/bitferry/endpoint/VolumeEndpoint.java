package io.bitferry.endpoint;

import io.bitferry.*;
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
    if (manager == null || vtag == null) throw new BitferryError("Invalid endpoint descriptor");
    path = path == null ? "." : path;
    Path p = Path.of(path);
    for (Path element : p) {
      if (element.toString().equals("..")) throw new BitferryError("Invalid Bitferry volume -relative path " + path);
    }
    if (p.isAbsolute()) throw new BitferryError("Invalid Bitferry volume -relative path " + path);
    // The rooted relative paths (".../dir", "\\dir", "C:dir") pass the isAbsolute()
    // check on Windows yet resolve() re-anchors them at the volume root's drive (or
    // the given drive), escaping the volume - reject any path carrying a root component
    // (a crafted storage descriptor or a ':tag:/dir' specifier typo must not escape)
    if (p.getRoot() != null) throw new BitferryError("Invalid Bitferry volume -relative path " + path);
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
    Path resolved = volume().root().resolve(path).normalize();
    // The defense-in-depth containment check: the creator validation guards the
    // new descriptors, this one backstops whatever reaches the processing (the
    // crafted or hand-edited storage descriptors restored from the media)
    if (!resolved.startsWith(volume().root())) {
      throw new BitferryError("Invalid Bitferry volume -relative path " + path + ": it escapes the volume root " + volume().root());
    }
    return resolved.toString();
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
    if (volume == null) throw new BitferryError("No volume " + vtag + " is registered");
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
    if (volume == null) throw new BitferryError("No volume " + vtag + " is registered");
    return volume;
  }
}
