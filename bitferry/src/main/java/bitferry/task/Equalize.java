package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.lang.System.Logger.Level;
import java.time.*;
import java.util.*;

/// Two way synchronization.
public class Equalize extends Rclone {

  public static final String OPERATION = "equalize";

  private static final System.Logger LOG = System.getLogger(Equalize.class.getName());

  /// The bisync baseline marker: true once the initial baseline rebuild (--resync) has
  /// completed successfully, so the following runs execute the standard bisync which
  /// propagates the deletions through the saved listings. Null (and omitted from the
  /// descriptor) while the baseline is pending: the marker travels with the task
  /// descriptor like the tombstone marker does.
  @JsonProperty("resynced")
  protected Boolean resynced;

  /// Constructor for a new task instance.
  public Equalize(Manager manager, Endpoint source, Endpoint destination, String process, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, process, include, exclude);
  }

  /// Jackson creator restoring an existing task from the storage descriptor;
  /// the manager is injected as it is not a part of the descriptor.
  @JsonCreator
  Equalize(
    @JacksonInject("manager") Manager manager,
    @JsonProperty("source") Endpoint source,
    @JsonProperty("destination") Endpoint destination,
    @JsonProperty("task") String tag,
    @JsonProperty("backend") String backend,
    @JsonProperty("modified") OffsetDateTime modified,
    @JsonProperty("include") List<String> include,
    @JsonProperty("exclude") List<String> exclude,
    @JsonProperty("rclone") Backend backendOpts
  ) {
    super(manager, source, destination, tag, modified, include, exclude, backendOpts);
    if (backend != null && !BACKEND.equals(backend)) {
      throw new BitferryError("invalid task backend: expected '" + BACKEND + "' but got '" + backend + "'");
    }
  }

  /// Return true if the bisync baseline is established (the initial --resync has completed).
  public boolean resynced() {
    return Boolean.TRUE.equals(resynced);
  }

  /// The rclone subcommand and its modifiers for this operation. The initial run carries
  /// the baseline rebuild (--resync), the following ones the plain bisync: the standard
  /// run propagates the deletions through the saved listings while --resync merges both
  /// sides into a superset leaving the deletions unpropagated (the files deleted on one
  /// side would be copied back from the other side on every run).
  @Override
  public List<String> operationArgs() {
    return resynced() ? List.of("bisync") : List.of("bisync", "--resync");
  }

  /// The saved bisync listings capture the filters: a filter change mismatches the saved
  /// state (rclone bisync refuses to run until re-resynced), so the baseline is dropped
  /// and the following run rebuilds it with the new filters.
  @Override
  public void setInclude(Collection<String> include) {
    super.setInclude(include);
    invalidateBaseline();
  }

  @Override
  public void setExclude(Collection<String> exclude) {
    super.setExclude(exclude);
    invalidateBaseline();
  }

  @Override
  public void clearInclude() {
    super.clearInclude();
    invalidateBaseline();
  }

  @Override
  public void clearExclude() {
    super.clearExclude();
    invalidateBaseline();
  }

  /// The post-run bookkeeping: the first successful real (non-simulated) bisync run
  /// establishes the baseline. The flip travels to the storages with the bumped
  /// timestamp upon the following commit (the run paths commit upon completion).
  @Override
  protected void processed() {
    if (!resynced()) {
      resynced = true;
      touch();
      LOG.log(Level.INFO, () -> "bisync baseline established for task #" + tag + "; the following runs propagate the deletions");
    }
  }

  // Drop the baseline marker: null is omitted from the descriptor mirroring the unset state
  private void invalidateBaseline() {
    resynced = null;
  }

  @Override
  public String operation() {
    return OPERATION;
  }
}
