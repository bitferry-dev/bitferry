package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.time.*;
import java.util.*;

/// Two way synchronization.
public class Equalize extends Rclone {

  public static final String OPERATION = "equalize";

  /// Constructor for a new task instance.
  public Equalize(Manager manager, Endpoint source, Endpoint destination, String process, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, process, include, exclude);
  }

  /// Jackson creator restoring an existing task from the storage descriptor;
  /// the manager is injected as it is not a part of the descriptor.
  @JsonCreator
  Equalize(
    @JacksonInject("manager") Manager manager,
    @JsonProperty("source") Endpoint source,
    @JsonProperty("destination") Endpoint destination,
    @JsonProperty("task") String tag,
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("include") List<String> include,
    @JsonProperty("exclude") List<String> exclude,
    @JsonProperty("rclone") Backend backend
  ) {
    super(manager, source, destination, tag, modified, include, exclude, backend);
  }

  /// The rclone subcommand and its modifiers for this operation.
  @Override
  public List<String> operationArgs() {
    return List.of("bisync", "--resync");
  }

  @Override
  public String operation() {
    return OPERATION;
  }
}
