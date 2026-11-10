package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.*;

/// Latest snapshot restore (Restic).
public class Restore extends Restic {

  public static final String OPERATION = "restore";

  /// Processing options profiles; the null key denotes the default profile.
  public static final Map<String, List<String>> PROCESS =
    route(Map.of("default", List.of("--no-cache", "--sparse")), List.of("--no-cache", "--sparse"));

  /// Constructor for a new task instance.
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, password, process, PROCESS, include, exclude);
  }

  /// Jackson creator restoring an existing task from the storage descriptor;
  /// the manager is injected as it is not a part of the descriptor.
  @JsonCreator
  Restore(
    @JacksonInject("manager") Manager manager,
    @JsonProperty("source") Endpoint source,
    @JsonProperty("destination") Endpoint destination,
    @JsonProperty("task") String tag,
    @JsonProperty("backend") String backend,
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("include") List<String> include,
    @JsonProperty("exclude") List<String> exclude,
    @JsonProperty("restic") Backend backendOpts
  ) {
    super(manager, source, destination, tag, modified, include, exclude, backendOpts, PROCESS);
    if (backend != null && !BACKEND.equals(backend)) {
      throw new BitferryError("invalid task backend: expected '" + BACKEND + "' but got '" + backend + "'");
    }
  }

  @Override
  public String operation() {
    return OPERATION;
  }

  /// Return the volume hosting the task's password vault: the volume of the destination
  /// directory (the unencrypted end of the restore task).
  /// Raises [BitferryError] if the destination endpoint resides outside Bitferry volumes.
  @Override
  public Volume vaultVolume() {
    if (!(destination instanceof VolumeEndpoint destinationEndpoint)) throw new BitferryError("restore destination endpoint must reside within a Bitferry volume");
    Volume volume = manager.volume(destinationEndpoint.vtag());
    if (volume == null) throw new BitferryError("no volume #" + destinationEndpoint.vtag() + " is registered");
    return volume;
  }

  @Override
  public List<List<String>> commands() {
    return manager.backendExecutor().commands(this);
  }
}
