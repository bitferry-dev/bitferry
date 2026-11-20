package io.bitferry.task;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

/// Tests for Restic repository formatting and initialization under forced/normal modes.
class ResticFormatTest {

  @TempDir
  Path dir;

  @Test
  void formatWithForceAgainstNonExistentRepositoryDir() {
    Manager manager = new Manager();
    Volume volume = Volume.create(manager, dir.resolve("volume"));
    Path repoDir = dir.resolve("not_yet_existing_repo");
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);
    Endpoint source = Endpoints.parse(manager, ":" + volume.tag() + ":data");
    Backup backup = new Backup(manager, source, repo, "password", Boolean.TRUE, null, null, null, List.of());

    List<List<String>> executedCommands = new ArrayList<>();
    CommandExecutor executor = (cmd, workDir, env, output) -> {
      executedCommands.add(cmd);
      return 0;
    };

    ResticExecutor resticExecutor = new ResticExecutor(executor);
    assertDoesNotThrow(() -> resticExecutor.format(backup));
    assertFalse(executedCommands.isEmpty());
    assertTrue(executedCommands.get(0).contains("init"));
    assertTrue(executedCommands.get(0).contains(repoDir.toString()));
  }

  @Test
  void formatWithForceAgainstFileThrows() throws Exception {
    Manager manager = new Manager();
    Volume volume = Volume.create(manager, dir.resolve("volume"));
    Path filePath = dir.resolve("file_not_dir");
    Files.writeString(filePath, "content");
    Endpoint repo = Endpoints.parse(manager, "local:" + filePath);
    Endpoint source = Endpoints.parse(manager, ":" + volume.tag() + ":data");
    Backup backup = new Backup(manager, source, repo, "password", Boolean.TRUE, null, null, null, List.of());

    ResticExecutor resticExecutor = new ResticExecutor((cmd, workDir, env, output) -> 0);
    BitferryError err = assertThrows(BitferryError.class, () -> resticExecutor.format(backup));
    assertTrue(err.getMessage().contains("is not a directory"));
  }
}
