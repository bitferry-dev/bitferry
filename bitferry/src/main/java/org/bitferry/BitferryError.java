package org.bitferry;

/// Non-fatal capturable exception raised by the core for all expected failures;
/// frontends are expected to handle it and present a clean error message.
public class BitferryError extends RuntimeException {

  @java.io.Serial
  private static final long serialVersionUID = 1L;

  public BitferryError(String message) {
    super(message);
  }

  public BitferryError(String message, Throwable cause) {
    super(message, cause);
  }

  /// Rethrow the first BitferryError found in the exception chain — the Jackson
  /// machinery wraps the creator-raised errors into its own instantiations — or
  /// wrap the exception with the specified message.
  public static BitferryError resolve(RuntimeException exception, String message) {
    for (Throwable t = exception; t != null; t = t.getCause()) {
      if (t instanceof BitferryError error) return error;
    }
    return new BitferryError(message, exception);
  }
}
