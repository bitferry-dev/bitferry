package bitferry.migration;

import bitferry.*;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/// Migrates legacy volume storage format "0" (from the Python reference implementation)
/// to modernized format "0.1".
///
/// In format "0":
/// <ul>
/// <li>The 'tasks' container is a JSON Object mapping task tags to task descriptors</li>
/// <li>Task operation is a nested object key containing source and destination</li>
/// <li>Endpoints are single-key wrapper objects ('local' or 'bitferry')</li>
/// </ul>
///
/// In format "0.1":
/// <ul>
/// <li>The 'tasks' container is a JSON Array of self-contained task objects</li>
/// <li>Task has in-band 'task' tag and 'operation' property</li>
/// <li>Endpoints have in-band 'endpoint' type discriminator property</li>
/// </ul>
public final class Migration0To01 implements StorageMigration {

  /// Explicit constructor for the exported module API; the shared instance is {@link #INSTANCE}.
  public Migration0To01() {}

  public static final Migration0To01 INSTANCE = new Migration0To01();

  private static final List<String> KNOWN_OPERATIONS =
    List.of("copy", "update", "synchronize", "equalize", "backup", "restore");

  @Override
  public String fromVersion() {
    return "0";
  }

  @Override
  public String toVersion() {
    return "0.1";
  }

  @Override
  public JsonNode migrate(JsonNode root) {
    if (!root.isObject()) {
      throw new BitferryError("invalid volume storage descriptor: expected a JSON object");
    }
    ObjectNode migrated = ((ObjectNode) root).deepCopy();
    migrated.put("bitferry", toVersion());

    JsonNode tasksNode = migrated.get("tasks");
    if (tasksNode != null && tasksNode.isObject()) {
      ArrayNode taskList = JsonNodeFactory.instance.arrayNode();
      for (Map.Entry<String, JsonNode> entry : tasksNode.properties()) {
        String tag = entry.getKey();
        JsonNode taskData = entry.getValue();
        taskList.add(migrateTask(tag, taskData));
      }
      migrated.set("tasks", taskList);
    } else if (tasksNode != null && tasksNode.isArray()) {
      ArrayNode taskList = JsonNodeFactory.instance.arrayNode();
      for (JsonNode taskData : tasksNode) {
        String tag = taskData.has("task") ? taskData.get("task").asString() : Tags.newTag();
        taskList.add(migrateTask(tag, taskData));
      }
      migrated.set("tasks", taskList);
    }
    return migrated;
  }

  private ObjectNode migrateTask(String tag, JsonNode taskData) {
    ObjectNode task = JsonNodeFactory.instance.objectNode();
    task.put("task", tag);

    if (taskData.has("modified")) {
      task.set("modified", taskData.get("modified"));
    }
    if (taskData.has("include")) {
      task.set("include", taskData.get("include"));
    }
    if (taskData.has("exclude")) {
      task.set("exclude", taskData.get("exclude"));
    }
    if (taskData.has("rclone")) {
      JsonNode rcloneNode = taskData.get("rclone");
      if (rcloneNode.isObject()) {
        ObjectNode rclone = ((ObjectNode) rcloneNode).deepCopy();
        if (rclone.has("process")) {
          rclone.set("execute", rclone.remove("process"));
        }
        task.set("rclone", rclone);
      } else {
        task.set("rclone", rcloneNode);
      }
    }
    if (taskData.has("restic")) {
      JsonNode resticNode = taskData.get("restic");
      if (resticNode.isObject()) {
        ObjectNode restic = ((ObjectNode) resticNode).deepCopy();
        if (restic.has("process")) {
          restic.set("execute", restic.remove("process"));
        }
        task.set("restic", restic);
      } else {
        task.set("restic", resticNode);
      }
    }

    String foundOp = null;
    for (String op : KNOWN_OPERATIONS) {
      if (taskData.has(op)) {
        foundOp = op;
        break;
      }
    }

    if (foundOp != null) {
      task.put("operation", foundOp);
      JsonNode opData = taskData.get(foundOp);
      if (opData != null && opData.isObject()) {
        if (opData.has("source")) {
          task.set("source", migrateEndpoint(opData.get("source")));
        }
        if (opData.has("destination")) {
          task.set("destination", migrateEndpoint(opData.get("destination")));
        }
      }
    } else if (taskData.has("operation")) {
      String op = taskData.get("operation").asString();
      task.put("operation", op);
      if (taskData.has("source")) {
        task.set("source", migrateEndpoint(taskData.get("source")));
      } else if (taskData.has("directory") && !"restore".equals(op)) {
        task.set("source", migrateEndpoint(taskData.get("directory")));
      } else if (taskData.has("repository") && "restore".equals(op)) {
        task.set("source", migrateEndpoint(taskData.get("repository")));
      }
      if (taskData.has("destination")) {
        task.set("destination", migrateEndpoint(taskData.get("destination")));
      } else if (taskData.has("repository") && !"restore".equals(op)) {
        task.set("destination", migrateEndpoint(taskData.get("repository")));
      } else if (taskData.has("directory") && "restore".equals(op)) {
        task.set("destination", migrateEndpoint(taskData.get("directory")));
      }
    } else {
      throw new BitferryError("invalid task descriptor for task #" + tag + ": missing operation");
    }

    return task;
  }

  private JsonNode migrateEndpoint(JsonNode endpointNode) {
    if (endpointNode == null || !endpointNode.isObject()) {
      throw new BitferryError("invalid endpoint descriptor: expected a JSON object");
    }
    if (endpointNode.has("endpoint")) {
      ObjectNode ep = ((ObjectNode) endpointNode).deepCopy();
      if ("bitferry".equals(ep.get("endpoint").asString()) && !ep.has("path")) {
        ep.put("path", ".");
      }
      return ep;
    }
    ObjectNode migrated = JsonNodeFactory.instance.objectNode();
    if (endpointNode.has("local")) {
      migrated.put("endpoint", "local");
      JsonNode local = endpointNode.get("local");
      if (local.has("root")) {
        migrated.set("root", local.get("root"));
      }
      return migrated;
    }
    if (endpointNode.has("bitferry")) {
      migrated.put("endpoint", "bitferry");
      JsonNode bf = endpointNode.get("bitferry");
      if (bf.has("volume")) {
        migrated.set("volume", bf.get("volume"));
      }
      if (bf.has("path")) {
        migrated.set("path", bf.get("path"));
      }
      return migrated;
    }
    throw new BitferryError("invalid legacy endpoint descriptor: " + endpointNode);
  }
}
