package bitferry.task;

import bitferry.Storage;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/// Runtime service state of a repository tracked in `<repo>/bitferry.json`.
public final class RepoState {

  public static final String FILENAME = "bitferry.json";
  private static final System.Logger LOG = System.getLogger(RepoState.class.getName());

  @JsonProperty("last_backup")
  private Instant lastBackup;

  @JsonProperty("last_serve")
  @JsonAlias("last_maintenance")
  private Instant lastServe;

  @JsonProperty("backups_since_serve")
  @JsonAlias("backups_since_maintenance")
  private int backupsSinceServe;

  @JsonIgnore
  private boolean corruptOrMissing;

  public RepoState() {
    this(null, null, 0, false);
  }

  @JsonCreator
  public RepoState(
    @JsonProperty("last_backup") Instant lastBackup,
    @JsonProperty("last_serve") @JsonAlias("last_maintenance") Instant lastServe,
    @JsonProperty("backups_since_serve") @JsonAlias("backups_since_maintenance") int backupsSinceServe
  ) {
    this(lastBackup, lastServe, backupsSinceServe, false);
  }

  public RepoState(Instant lastBackup, Instant lastServe, int backupsSinceServe, boolean corruptOrMissing) {
    this.lastBackup = lastBackup;
    this.lastServe = lastServe;
    this.backupsSinceServe = backupsSinceServe;
    this.corruptOrMissing = corruptOrMissing;
  }

  public Instant lastBackup() {
    return lastBackup;
  }

  public Instant lastServe() {
    return lastServe;
  }

  public int backupsSinceServe() {
    return backupsSinceServe;
  }

  public boolean isCorruptOrMissing() {
    return corruptOrMissing;
  }

  /// Load repository state from `<repoDir>/bitferry.json`.
  /// If missing or corrupt, logs a warning and returns an uninitialized state that triggers service.
  public static RepoState load(Path repoDir) {
    if (repoDir == null) {
      return new RepoState(null, null, 0, true);
    }
    Path file = repoDir.resolve(FILENAME);
    if (!Files.isRegularFile(file)) {
      return new RepoState(null, null, 0, true);
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        LOG.log(Level.WARNING, () -> "empty repository state file in " + file + ", will trigger service");
        return new RepoState(null, null, 0, true);
      }
      RepoState state = Storage.mapper().readValue(bytes, RepoState.class);
      state.corruptOrMissing = false;
      return state;
    } catch (Exception e) {
      LOG.log(Level.WARNING, () -> "corrupted repository state file in " + file + " (" + e.getMessage() + "), will trigger service");
      return new RepoState(null, null, 0, true);
    }
  }

  /// Persist the repository state to `<repoDir>/bitferry.json`.
  public void save(Path repoDir) {
    if (repoDir == null) return;
    Path file = repoDir.resolve(FILENAME);
    try {
      String json = Storage.toJson(this);
      Storage.writeText(file, json);
      this.corruptOrMissing = false;
    } catch (IOException e) {
      LOG.log(Level.WARNING, () -> "failed to save repository state file " + file + ": " + e.getMessage());
    }
  }

  /// Record a completed backup run.
  public void recordBackup() {
    this.lastBackup = Instant.now();
    this.backupsSinceServe++;
  }

  /// Record a completed serve run.
  public void recordServe() {
    this.lastServe = Instant.now();
    this.backupsSinceServe = 0;
    this.corruptOrMissing = false;
  }

  /// Check whether serve is due according to the cadence policy.
  public boolean isDue(Cadence cadence) {
    if (corruptOrMissing) {
      return true;
    }
    if (cadence == null) {
      return false;
    }
    return cadence.isDue(lastServe, backupsSinceServe);
  }
}
