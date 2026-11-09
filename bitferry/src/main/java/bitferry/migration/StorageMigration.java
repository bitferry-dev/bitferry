package bitferry.migration;

import tools.jackson.databind.*;

/// Contract for format version migration converting older storage JSON descriptors
/// to newer schema versions.
public interface StorageMigration {

  /// The source schema version this migration accepts.
  String fromVersion();

  /// The target schema version this migration produces.
  String toVersion();

  /// Migrate the storage descriptor JSON tree.
  JsonNode migrate(JsonNode root);
}
