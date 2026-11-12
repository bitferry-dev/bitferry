package bitferry.endpoint;

import bitferry.*;
import com.fasterxml.jackson.annotation.*;
import java.nio.file.*;

/// Local directory endpoint; the path is taken as is (not resolved against volumes).
/// The canonical constructor serves as the Jackson creator binding the 'root' descriptor property.
public record LocalEndpoint(Path root) implements Endpoint {

  public LocalEndpoint {
    root = Bitferry.canonicalize(root);
  }

  @Override
  public String processOpts() {
    return root.toString();
  }

  @Override
  public int generation() {
    return 0;
  }

  @Override
  public boolean refersVolume(String vtag) {
    return false;
  }

  @JsonIgnore
  @Override
  public boolean isIntact() {
    return true;
  }

  @Override
  public String describe() {
    return root.toString();
  }
}
