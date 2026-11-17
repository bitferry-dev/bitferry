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

/// Runtime service state of a repository tracked in `<repo>/.bitferry.json`.
public final class ResticRepositoryState {

  public static final String FILENAME = ".bitferry.json";
  private static final System.Logger LOG = System.getLogger(ResticRepositoryState.class.getName());

  @JsonProperty("backup")
  private Instant backup;

  @JsonProperty("maintenance")
  private Instant maintenance;

  @JsonProperty("backups_since_maintenance")
  private int backups;

  @JsonIgnore
  private boolean corruptOrMissing;

  public ResticRepositoryState() {
    this(null, null, 0, false);
  }

  @JsonCreator
  public ResticRepositoryState(
    @JsonProperty("backup") Instant backup,
    @JsonProperty("maintenance") Instant maintenance,
    @JsonProperty("backups") @JsonAlias("backups_since_maintenance") int backups
  ) {
    this(backup, maintenance, backups, false);
  }

  public ResticRepositoryState(Instant backup, Instant maintenance, int backups, boolean corruptOrMissing) {
    this.backup = backup;
    this.maintenance = maintenance;
    this.backups = backups;
    this.corruptOrMissing = corruptOrMissing;
  }

  public Instant backup() {
    return backup;
  }

  public Instant maintenance() {
    return maintenance;
  }

  public int backups() {
    return backups;
  }

  public boolean isCorruptOrMissing() {
    return corruptOrMissing;
  }

  /// Load repository state from `<repoDir>/.bitferry.json`.
  /// If missing or corrupt, logs a warning and returns an uninitialized state that triggers service.
  public static ResticRepositoryState load(Path repoDir) {
    if (repoDir == null) {
      return new ResticRepositoryState(null, null, 0, true);
    }
    Path file = repoDir.resolve(FILENAME);
    if (!Files.isRegularFile(file)) {
      return new ResticRepositoryState(null, null, 0, true);
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        LOG.log(Level.WARNING, () -> "Empty repository state file in " + file + ", will trigger service");
        return new ResticRepositoryState(null, null, 0, true);
      }
      ResticRepositoryState state = Storage.mapper().readValue(bytes, ResticRepositoryState.class);
      state.corruptOrMissing = false;
      return state;
    } catch (Exception e) {
      LOG.log(Level.WARNING, () -> "Corrupted repository state file in " + file + " (" + e.getMessage() + "), will trigger service");
      return new ResticRepositoryState(null, null, 0, true);
    }
  }

  /// Persist the repository state to `<repoDir>/.bitferry.json`.
  public void save(Path repoDir) {
    if (repoDir == null) return;
    Path file = repoDir.resolve(FILENAME);
    try {
      String json = Storage.toJson(this);
      Storage.writeText(file, json);
      this.corruptOrMissing = false;
    } catch (IOException e) {
      LOG.log(Level.WARNING, () -> "Failed to save repository state file " + file + ": " + e.getMessage());
    }
  }

  /// Record a completed backup run.
  public void recordBackup() {
    this.backup = Instant.now();
    this.backups++;
  }

  /// Record a completed serve run.
  public void recordServe() {
    this.maintenance = Instant.now();
    this.backups = 0;
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
    return cadence.isDue(maintenance, backups);
  }
}
