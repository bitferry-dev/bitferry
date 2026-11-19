package io.bitferry.task;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

/// The restore snapshot scoping: the selection must never be the bare repo-wide
/// 'latest' - on a shared (co-repository) repository that would restore whichever
/// sibling task's snapshot happens to be the newest.
class RestoreScopeTest {

  @TempDir
  Path dir;

  private Restore restore(String snapshotTag) {
    Manager manager = new Manager();
    Volume volume = Volume.create(manager, dir.resolve("volume"));
    Endpoint repo = Endpoints.parse(manager, "local:" + dir.resolve("repo"));
    Endpoint target = Endpoints.parse(manager, ":" + volume.tag() + ":restored");
    return new Restore(manager, repo, target, "password", null, List.of(), List.of(), false, snapshotTag);
  }

  // The explicit scope pins the selection to the newest snapshot of the named task
  // (the restic tag filters OR-match their alternatives, so the unique per-task tag
  // alone scopes precisely)
  @Test
  void explicitScopeSelectsTheNamedTaskSnapshots() {
    List<String> cmd = new ResticExecutor(null).restoreCommand(restore("cafebabe"));
    int at = cmd.indexOf("--tag");
    assertNotEquals(-1, at);
    assertEquals("cafebabe", cmd.get(at + 1));
  }

  // The missing scope falls back to the marker tag: the foreign (untagged) snapshots
  // stay excluded instead of restoring the repo-wide newest
  @Test
  void missingScopeFallsBackToTheMarkerTag() {
    List<String> cmd = new ResticExecutor(null).restoreCommand(restore(null));
    int at = cmd.indexOf("--tag");
    assertNotEquals(-1, at);
    assertEquals(io.bitferry.Tags.MARKER, cmd.get(at + 1));
  }

  // The scope resolution: the unique co-repository match derives automatically, the
  // explicit scope must name a live backup task, and the ambiguity refuses to guess
  @Test
  void scopeResolutionDerivesValidatesAndRejectsAmbiguity() {
    Manager manager = new Manager();
    Volume volume = Volume.create(manager, dir.resolve("volume"));
    Endpoint repo = Endpoints.parse(manager, "local:" + dir.resolve("repo"));
    Endpoint source = Endpoints.parse(manager, ":" + volume.tag() + ":data");
    Backup backup = new Backup(manager, source, repo, "password", null, null, null, null, List.of());
    manager.registerTask(backup);

    assertEquals(backup.tag(), Restore.resolveSnapshotTag(manager, repo, null));
    assertEquals(backup.tag(), Restore.resolveSnapshotTag(manager, repo, backup.tag()));
    assertThrows(BitferryError.class, () -> Restore.resolveSnapshotTag(manager, repo, "nosuch"));

    Backup twin = new Backup(manager, source, repo, "password", null, null, null, null, List.of());
    manager.registerTask(twin);
    assertThrows(BitferryError.class, () -> Restore.resolveSnapshotTag(manager, repo, null));
    assertEquals(twin.tag(), Restore.resolveSnapshotTag(manager, repo, twin.tag()));
  }
}
