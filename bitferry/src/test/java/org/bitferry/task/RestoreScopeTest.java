package org.bitferry.task;

import org.bitferry.*;
import org.bitferry.endpoint.*;
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
    assertEquals(org.bitferry.Tags.MARKER, cmd.get(at + 1));
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

  @Test
  void explicitScopeRejectsUnrelatedBackupRepository() {
    Manager manager = new Manager();
    Volume volume = Volume.create(manager, dir.resolve("volume"));
    Endpoint repoA = Endpoints.parse(manager, "local:" + dir.resolve("repoA"));
    Endpoint repoB = Endpoints.parse(manager, "local:" + dir.resolve("repoB"));
    Endpoint source = Endpoints.parse(manager, ":" + volume.tag() + ":data");
    Backup backupA = new Backup(manager, source, repoA, "password", null, null, null, null, List.of());
    manager.registerTask(backupA);

    BitferryError err = assertThrows(BitferryError.class, () -> Restore.resolveSnapshotTag(manager, repoB, backupA.tag()));
    assertTrue(err.getMessage().contains("does not match restore repository"));
  }

  @Test
  void scopeCandidatesMatchesEquivalentRepositoryPaths() throws Exception {
    Manager manager = new Manager();
    Volume volume = Volume.create(manager, dir.resolve("volume"));
    Path realRepo = dir.resolve("realRepo");
    Files.createDirectories(realRepo);
    Path symlinkRepo = dir.resolve("symlinkRepo");
    Files.createSymbolicLink(symlinkRepo, realRepo);

    Endpoint repoReal = Endpoints.parse(manager, "local:" + realRepo);
    Endpoint repoSymlink = Endpoints.parse(manager, "local:" + symlinkRepo);
    Endpoint source = Endpoints.parse(manager, ":" + volume.tag() + ":data");

    Backup backup = new Backup(manager, source, repoReal, "password", null, null, null, null, List.of());
    manager.registerTask(backup);

    assertEquals(List.of(backup.tag()), Restore.scopeCandidates(manager, repoSymlink));
    assertEquals(backup.tag(), Restore.resolveSnapshotTag(manager, repoSymlink, backup.tag()));
  }

  private static boolean available(String executable) {
    String env = System.getenv(executable.toUpperCase());
    String command = env != null && !env.isBlank() ? env : executable;
    try {
      Process process = new ProcessBuilder(command, "version").start();
      return process.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void foreignRepositoryTagIsDiscoveredAndAcceptedWhenPresentInSnapshots() throws Exception {
    Assumptions.assumeTrue(available("restic"), "restic not available");
    Path repoDir = dir.resolve("foreign-repo");
    Path dataDir = dir.resolve("foreign-data");
    Files.createDirectories(dataDir);
    Files.writeString(dataDir.resolve("sample.txt"), "hello foreign restore");

    ProcessBuilder initPb = new ProcessBuilder("restic", "init", "-r", repoDir.toString());
    initPb.environment().put("RESTIC_PASSWORD", "foreign-pass");
    assertEquals(0, initPb.start().waitFor());

    ProcessBuilder backupPb = new ProcessBuilder("restic", "-r", repoDir.toString(), "backup",
      "--tag", "bitferry,foreign123", dataDir.toString());
    backupPb.environment().put("RESTIC_PASSWORD", "foreign-pass");
    assertEquals(0, backupPb.start().waitFor());

    Manager manager = new Manager();
    Endpoint repo = Endpoints.parse(manager, "local:" + repoDir);

    // 1. Query repository snapshots
    List<Snapshot> snapshots = Restore.repositorySnapshots(manager, repo, "foreign-pass");
    assertEquals(1, snapshots.size());
    Snapshot s = snapshots.getFirst();
    assertTrue(s.tags().contains("bitferry"));
    assertTrue(s.tags().contains("foreign123"));

    // 2. resolveSnapshotTag accepts the foreign tag present in snapshots even when not registered in manager.tasks()
    assertEquals("foreign123", Restore.resolveSnapshotTag(manager, repo, "foreign123", "foreign-pass"));

    // 3. resolveSnapshotTag still rejects unknown tags not in manager and not in snapshots
    assertThrows(BitferryError.class, () -> Restore.resolveSnapshotTag(manager, repo, "nosuch-foreign", "foreign-pass"));
  }

  @Test
  void snapshotIdDefaultsToLatestAndControlsTagFlagInCommand() {
    Restore r = restore("cafebabe");
    assertEquals(Restore.DEFAULT_SNAPSHOT, r.snapshotId());
    assertFalse(r.serialize().has("snapshotId"));
    List<String> defCmd = new ResticExecutor(null).restoreCommand(r);
    assertEquals("latest", defCmd.get(defCmd.indexOf("restore") + 1));
    assertTrue(defCmd.contains("--tag"));

    r.setSnapshotId("  8345a490  ");
    assertEquals("8345a490", r.snapshotId());
    assertEquals("8345a490", r.serialize().get("snapshotId").asString());
    List<String> pinnedCmd = new ResticExecutor(null).restoreCommand(r);
    assertEquals("8345a490", pinnedCmd.get(pinnedCmd.indexOf("restore") + 1));
    assertFalse(pinnedCmd.contains("--tag"), "--tag is only applicable when snapshot ID is 'latest'");

    r.setSnapshotId("latest:/docs");
    assertEquals("latest:/docs", r.snapshotId());
    assertTrue(Restore.isLatestSnapshot(r.snapshotId()));
    List<String> subCmd = new ResticExecutor(null).restoreCommand(r);
    assertEquals("latest:/docs", subCmd.get(subCmd.indexOf("restore") + 1));
    assertEquals("cafebabe", subCmd.get(subCmd.indexOf("--tag") + 1));

    // Resetting to latest / empty / "-" omits snapshotId from JSON again
    r.setSnapshotId("latest");
    assertEquals("latest", r.snapshotId());
    assertFalse(r.serialize().has("snapshotId"));

    // Restore serveCheckCommand mirrors Backup check flag support
    assertFalse(new ResticExecutor(null).serveCheckCommand(r).contains("--read-data"));
    r.setCheck("@full");
    List<String> checkCmd = new ResticExecutor(null).serveCheckCommand(r);
    assertEquals("check", checkCmd.get(1));
    assertTrue(checkCmd.contains("--read-data"));
    assertEquals(List.of(ExecutorSupport.displayCommand(checkCmd)), r.serveCommandsWith(new SystemExecutor()));
  }
}
