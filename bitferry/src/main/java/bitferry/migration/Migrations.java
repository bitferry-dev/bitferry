package bitferry.migration;

import bitferry.*;
import java.util.*;
import tools.jackson.databind.*;

/// Migration pipeline and registry managing schema transitions to the current storage format.
public final class Migrations {

  /// Current active storage format version.
  public static final String CURRENT_VERSION = Bitferry.STORAGE_VERSION;

  private static final Map<String, StorageMigration> MIGRATIONS = new LinkedHashMap<>();

  static {
    register(Migration0To01.INSTANCE);
  }

  private Migrations() {}

  /// Register a migration step in the pipeline.
  public static void register(StorageMigration migration) {
    MIGRATIONS.put(migration.fromVersion(), migration);
  }

  /// Migrate the storage descriptor JSON tree to the current storage version.
  public static JsonNode migrate(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw new BitferryError("invalid volume storage descriptor: expected a JSON object");
    }
    JsonNode versionNode = root.get("bitferry");
    if (versionNode == null || versionNode.isNull()) {
      throw new BitferryError("missing volume storage version property 'bitferry'");
    }
    String version = versionNode.asString();

    JsonNode current = root;
    while (!CURRENT_VERSION.equals(version)) {
      StorageMigration migration = MIGRATIONS.get(version);
      if (migration == null) {
        throw new BitferryError("unsupported volume storage version " + version);
      }
      current = migration.migrate(current);
      version = migration.toVersion();
    }
    return current;
  }
}
