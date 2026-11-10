package bitferry.migration;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

  @TempDir
  Path dir;

  private static final String LEGACY_V0_JSON = """
    {
      "bitferry": "0",
      "volume": "d2f10024",
      "modified": "2026-09-13T11:46:45.123456+03:00",
      "tasks": {
        "89e1c119": {
          "modified": "2026-09-13T11:46:45.123456+03:00",
          "include": ["*.txt"],
          "exclude": ["*.bak"],
          "synchronize": {
            "source": {
              "local": {
                "root": "/data/work"
              }
            },
            "destination": {
              "bitferry": {
                "volume": "d2f10024",
                "path": "backup"
              }
            }
          },
          "rclone": {
            "process": ["--metadata"]
          }
        },
        "77b2a001": {
          "modified": "2026-09-13T12:00:00+03:00",
          "backup": {
            "source": {
              "local": {
                "root": "/data/work"
              }
            },
            "destination": {
              "bitferry": {
                "volume": "d2f10024",
                "path": "repo"
              }
            }
          },
          "restic": {
            "process": ["--no-cache"]
          }
        }
      },
      "vault": {
        "89e1c119": "test-vault-token"
      }
    }
    """;

  @Test
  void migrateV0ToV01ConvertsTasksAndEndpoints() {
    JsonNode legacyNode = Storage.mapper().readTree(LEGACY_V0_JSON);
    JsonNode migrated = Migrations.migrate(legacyNode);

    assertEquals("0.1", migrated.get("bitferry").asString());
    assertEquals("d2f10024", migrated.get("volume").asString());
    assertEquals("test-vault-token", migrated.get("vault").get("89e1c119").asString());

    JsonNode tasks = migrated.get("tasks");
    assertTrue(tasks.isArray());
    assertEquals(2, tasks.size());

    // Task 89e1c119
    JsonNode task1 = findTask(tasks, "89e1c119");
    assertNotNull(task1);
    assertEquals("synchronize", task1.get("operation").asString());
    assertEquals("rclone", task1.get("backend").asString());
    assertEquals("local", task1.get("source").get("endpoint").asString());
    assertEquals("/data/work", task1.get("source").get("root").asString());
    assertEquals("bitferry", task1.get("destination").get("endpoint").asString());
    assertEquals("d2f10024", task1.get("destination").get("volume").asString());
    assertEquals("backup", task1.get("destination").get("path").asString());
    assertEquals(1, task1.get("include").size());
    assertEquals("*.txt", task1.get("include").get(0).asString());
    assertEquals(1, task1.get("exclude").size());
    assertEquals("*.bak", task1.get("exclude").get(0).asString());
    assertEquals("--metadata", task1.get("rclone").get("execute").get(0).asString());

    // Task 77b2a001
    JsonNode task2 = findTask(tasks, "77b2a001");
    assertNotNull(task2);
    assertEquals("backup", task2.get("operation").asString());
    assertEquals("restic", task2.get("backend").asString());
    assertEquals("--no-cache", task2.get("restic").get("execute").get(0).asString());
  }

  @Test
  void currentVersionPassesThroughUnchanged() {
    JsonNode current = Storage.mapper().createObjectNode()
      .put("bitferry", "0.1")
      .put("volume", "12345678");

    JsonNode result = Migrations.migrate(current);
    assertEquals("0.1", result.get("bitferry").asString());
    assertEquals("12345678", result.get("volume").asString());
  }

  @Test
  void unsupportedVersionThrows() {
    JsonNode unknown = Storage.mapper().createObjectNode()
      .put("bitferry", "99")
      .put("volume", "12345678");

    BitferryError err = assertThrows(BitferryError.class, () -> Migrations.migrate(unknown));
    assertTrue(err.getMessage().contains("unsupported volume storage version 99"));
  }

  @Test
  void missingVersionThrows() {
    JsonNode missing = Storage.mapper().createObjectNode().put("volume", "12345678");
    assertThrows(BitferryError.class, () -> Migrations.migrate(missing));
  }

  @Test
  void legacyFileRestoresThroughVolumeAndSavesAsV01() throws IOException {
    Path root = dir.resolve("legacy-vol");
    Files.createDirectories(root);
    Files.writeString(root.resolve(Bitferry.STORAGE), LEGACY_V0_JSON);

    Manager manager = new Manager();
    Volume volume = Volume.restore(manager, root);

    assertEquals("d2f10024", volume.tag());
    assertEquals(2, volume.tasks().size());

    Task syncTask = volume.tasks().get("89e1c119");
    assertNotNull(syncTask);
    assertInstanceOf(Synchronize.class, syncTask);
    Local src = assertInstanceOf(Local.class, syncTask.source());
    assertEquals(Bitferry.canonicalize(Path.of("/data/work")), src.root());
    VolumeEndpoint dst = assertInstanceOf(VolumeEndpoint.class, syncTask.destination());
    assertEquals("d2f10024", dst.vtag());
    assertEquals("backup", dst.path());

    Task backupTask = volume.tasks().get("77b2a001");
    assertNotNull(backupTask);
    assertInstanceOf(Backup.class, backupTask);

    // Committing the volume re-serializes in 0.1 format
    volume.touch();
    manager.commit();

    JsonNode committed = Storage.readJson(root.resolve(Bitferry.STORAGE));
    assertEquals("0.1", committed.get("bitferry").asString());
    assertTrue(committed.get("tasks").isArray());
  }

  @Test
  void legacyEncryptedTaskKeepsTheEncryptionMode() throws IOException {
    // The legacy (Python-era) descriptor of the encrypted synchronize task
    String legacy = """
      {
        "bitferry": "0",
        "volume": "d2f10024",
        "modified": "2026-09-13T11:46:45.123456+03:00",
        "tasks": {
          "89e1c119": {
            "modified": "2026-09-13T11:46:45.123456+03:00",
            "synchronize": {
              "source": {
                "bitferry": {
                  "volume": "d2f10024",
                  "path": "."
                }
              },
              "destination": {
                "bitferry": {
                  "volume": "d2f10024",
                  "path": "backup"
                }
              }
            },
            "rclone": {
              "process": ["--metadata"],
              "encrypt": ["--crypt-filename-encoding", "base32", "--crypt-filename-encryption", "standard"]
            }
          }
        },
        "vault": {
          "89e1c119": "%s"
        }
      }
      """.formatted(bitferry.vault.Passwords.encodePasswords("legacy-secret", null));

    Path root = dir.resolve("legacy-enc");
    Files.createDirectories(root);
    Files.writeString(root.resolve(Bitferry.STORAGE), legacy);

    Manager manager = new Manager();
    Volume volume = Volume.restore(manager, root);
    assertEquals(1, volume.tasks().size());

    // The encryption mode survives the migration along with the vault password
    Synchronize task = (Synchronize) volume.tasks().get("89e1c119");
    assertInstanceOf(Rclone.Encrypt.class, task.encryption());
    assertEquals("legacy-secret", task.encryption().password());
    assertEquals(List.of("--crypt-filename-encoding", "base32", "--crypt-filename-encryption", "standard"),
      Task.strings(task.serialize().get("rclone").get("encrypt"), false));
  }

  @Test
  void storageKeyProcessIsGloballyRenamedToExecute() {
    String jsonWithProcess = """
      {
        "bitferry": "0",
        "volume": "d2f10024",
        "tasks": {
          "89e1c119": {
            "synchronize": {
              "source": {"local": {"root": "/data/work"}},
              "destination": {"bitferry": {"volume": "d2f10024", "path": "backup"}}
            },
            "rclone": {
              "process": ["--metadata", "--links"]
            },
            "custom_backend": {
              "process": ["--custom-opt"]
            }
          }
        }
      }
      """;
    JsonNode node = Storage.mapper().readTree(jsonWithProcess);
    JsonNode migrated = Migrations.migrate(node);
    assertEquals("0.1", migrated.get("bitferry").asString());
    JsonNode tasks = migrated.get("tasks");
    assertTrue(tasks.isArray());
    JsonNode task = tasks.get(0);
    assertEquals("rclone", task.get("backend").asString());
    assertEquals(List.of("--metadata", "--links"), Task.strings(task.get("rclone").get("execute"), false));
    assertFalse(task.get("rclone").has("process"));
    assertEquals(List.of("--custom-opt"), Task.strings(task.get("custom_backend").get("execute"), false));
    assertFalse(task.get("custom_backend").has("process"));
  }

  private static JsonNode findTask(JsonNode tasks, String tag) {
    for (JsonNode t : tasks) {
      if (tag.equals(t.get("task").asString())) return t;
    }
    return null;
  }
}
