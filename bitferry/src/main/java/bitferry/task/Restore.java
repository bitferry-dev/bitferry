package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.function.Consumer;
import java.util.*;

/// Latest snapshot restore (Restic).
public class Restore extends Restic {

  public static final String OPERATION = "restore";

  /// Processing options profiles; the null key denotes the default profile.
  public static final Map<String, List<String>> PROCESS =
    route(Map.of("default", List.of("--sparse")), List.of("--sparse"));

  /// Constructor for a new task instance.
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, Collection<String> include, Collection<String> exclude) {
    this(manager, source, destination, password, process, include, exclude, false);
  }

  /// Constructor for a new task instance with the explicit keyring storage request:
  /// the repository password travels to the OS keyring when the keyring is usable.
  public Restore(Manager manager, Endpoint source, Endpoint destination, String password, String process, Collection<String> include, Collection<String> exclude, boolean keyring) {
    super(manager, source, destination, password, process, PROCESS, include, exclude, keyring);
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

  /// The repository travels with the source endpoint.
  @Override
  public Endpoint repoEndpoint() {
    return source;
  }

  /// The conformance dispatch: the override makes the call land on the executor's
  /// restore slot (the static type of `this` decides the overload).
  @Override
  protected void conform() {
    manager.backendExecutor().conform(this);
  }

  /// The restore tasks share the repository without owning the key.
  @Override
  public boolean mayRotateRepositoryKey() {
    return false;
  }

  /// Return the volume hosting the task's password vault: the volume of the destination
  /// directory (the unencrypted end of the restore task).
  /// Raises [BitferryError] if the destination endpoint resides outside Bitferry volumes.
  @Override
  public Volume vaultVolume() {
    return destination.requireVolume(manager, "restore destination endpoint must reside within a Bitferry volume");
  }

  @Override
  public boolean runWith(Executor executor, Consumer<String> output) {
    return executor.run(this, output);
  }

  @Override
  public boolean serveWith(Executor executor, Consumer<String> output) {
    return executor.serve(this, output);
  }

  @Override
  public List<List<String>> commandsWith(Executor executor) {
    return executor.commands(this);
  }

  @Override
  public List<List<String>> serveCommandsWith(Executor executor) {
    return executor.serveCommands(this);
  }
}
