package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.*;

/// Unconditional one way copy.
public class Copy extends Rclone {

  public static final String OPERATION = "copy";

  /// Constructor for a new task instance.
  public Copy(Manager manager, Endpoint source, Endpoint destination, String process, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, process, include, exclude);
  }

  /// Jackson creator restoring an existing task from the storage descriptor;
  /// the manager is injected as it is not a part of the descriptor.
  @JsonCreator
  Copy(
    @JacksonInject("manager") Manager manager,
    @JsonProperty("source") Endpoint source,
    @JsonProperty("destination") Endpoint destination,
    @JsonProperty("task") String tag,
    @JsonProperty("backend") String backend,
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("include") List<String> include,
    @JsonProperty("exclude") List<String> exclude,
    @JsonProperty("rclone") Backend backendOpts
  ) {
    super(manager, source, destination, tag, modified, include, exclude, backendOpts);
    if (backend != null && !BACKEND.equals(backend)) {
      throw new BitferryError("invalid task backend: expected '" + BACKEND + "' but got '" + backend + "'");
    }
  }

  /// The rclone subcommand and its modifiers for this operation.
  @Override
  public List<String> operationArgs() {
    return List.of("copy");
  }

  @Override
  public String operation() {
    return OPERATION;
  }
}
