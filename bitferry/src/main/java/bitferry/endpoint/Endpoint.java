package bitferry.endpoint;

import bitferry.*;
import com.fasterxml.jackson.annotation.*;
import tools.jackson.databind.*;

/// Task data endpoint: a local directory or a path relative to a Bitferry volume.
/// The concrete endpoint type is discriminated by the 'endpoint' descriptor property.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "endpoint")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Local.class, name = "local"),
  @JsonSubTypes.Type(value = VolumeEndpoint.class, name = "bitferry")
})
public sealed interface Endpoint permits Local, VolumeEndpoint {

  /// Serialize the endpoint into the storage descriptor node.
  default JsonNode serialize() {
    return Storage.mapper().valueToTree(this);
  }

  /// Return the path to be passed to the backend processing.
  String processOpts();

  /// Return the endpoint's generation used for the out-of-date flagging.
  int generation();

  /// Return true if the endpoint refers the volume matched by the specified tag.
  boolean refersVolume(String vtag);

  /// Return true if the endpoint is intact, e.g. ready for processing.
  boolean isIntact();

  /// Return the endpoint's short descriptor used in the task listings.
  String describe();
}
