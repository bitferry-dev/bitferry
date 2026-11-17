package bitferry.task;

import bitferry.*;
import bitferry.endpoint.*;
import com.fasterxml.jackson.annotation.*;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/// Two way synchronization.
public class Equalize extends Rclone {

  public static final String OPERATION = "equalize";

  private static final System.Logger LOG = System.getLogger(Equalize.class.getName());

  /// Processing options profiles for equalize; the null key denotes the default profile.
  public static final Map<String, List<String>> PROCESS = route(
    Map.of(
      "default", List.of("--metadata", "--recover", "--resilient", "--force"),
      "safe", List.of("--metadata", "--recover", "--resilient")
    ),
    List.of("--metadata", "--recover", "--resilient", "--force")
  );

  /// Constructor for a new task instance.
  public Equalize(Manager manager, Endpoint source, Endpoint destination, String process, Collection<String> include, Collection<String> exclude) {
    super(manager, source, destination, process, PROCESS, include, exclude);
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
    super(manager, source, destination, tag, modified, include, exclude, backendOpts, PROCESS);
    if (backend != null && !BACKEND.equals(backend)) {
      throw new BitferryError("Invalid task backend: expected " + BACKEND + " but got " + backend);
    }
  }

  /// Silently accept any legacy resynced property during JSON deserialization without persisting it.
  @JsonProperty("resynced")
  public void setLegacyResynced(Boolean legacy) {}

  /// Resolve the endpoint hosting the bisync workdir: for encrypted tasks, the unencrypted
  /// endpoint is strictly required to avoid leaking plaintext filenames; for unencrypted
  /// tasks, preference is given to a local (host) endpoint to keep I/O fast and isolated
  /// from removable media.
  public Endpoint workdirEndpoint() {
    if (usesVault()) {
      String enc = encryption.encryptedSide(this);
      return enc != null && enc.equals(source.processOpts()) ? destination : source;
    }
    if (source instanceof LocalEndpoint) {
      return source;
    }
    if (destination instanceof LocalEndpoint) {
      return destination;
    }
    return source;
  }

  /// The root-contained hidden directory for this task's bisync listings and state:
  /// <endpoint-root>/.bitferry.<task_id>
  public Path workdir() {
    return Path.of(workdirEndpoint().processOpts()).resolve(Bitferry.STORAGE + "." + tag());
  }

  /// Return true if the bisync baseline is established (the initial --resync has completed
  /// and listing files exist in the workdir).
  public boolean resynced() {
    try {
      Path dir = workdir();
      if (!Files.isDirectory(dir)) return false;
      try (var stream = Files.list(dir)) {
        return stream.anyMatch(p -> p.getFileName().toString().endsWith(".path1.lst"));
      }
    } catch (Exception e) {
      return false;
    }
  }

  /// Canonicalize a path string the way rclone bisync does: trim slashes and replace
  /// whitespace and special characters [\s\\/:?*] with underscores.
  public static String rcloneCanonicalPath(String remote) {
    String trimmed = remote.replaceAll("^[\\\\/]+|[\\\\/]+$", "");
    return trimmed.replaceAll("[\\s\\\\/:?*]", "_");
  }

  /// Form the session name: CanonicalPath(path1) + ".." + CanonicalPath(path2)
  public static String rcloneSessionName(String path1, String path2) {
    return rcloneCanonicalPath(path1) + ".." + rcloneCanonicalPath(path2);
  }

  /// Prepare the workdir before running bisync: ensures the directory exists and dynamically
  /// adapts any existing listing files if the endpoints' canonical paths have changed
  /// (e.g. removable drive mount point or drive letter change on this host).
  public void prepareWorkdir(String path1, String path2) {
    Path dir = workdir();
    try {
      Files.createDirectories(dir);
      Bitferry.hideIfSupported(dir);
      if (!resynced()) {
        try (var stream = Files.list(dir)) {
          for (Path f : stream.toList()) {
            String name = f.getFileName().toString();
            if (name.endsWith(".lst-err") || name.endsWith(".lst-new") || name.endsWith(".lck")) {
              Files.deleteIfExists(f);
            }
          }
        }
      }
      String expectedSession = rcloneSessionName(path1, path2);
      try (var stream = Files.list(dir)) {
        List<Path> files = stream.toList();
        for (Path f : files) {
          String name = f.getFileName().toString();
          if (name.contains(".path1.lst") || name.contains(".path2.lst") || name.endsWith(".lck")) {
            int dotIdx = name.indexOf(".path");
            if (dotIdx == -1 && name.endsWith(".lck")) dotIdx = name.lastIndexOf(".lck");
            if (dotIdx > 0) {
              String oldSession = name.substring(0, dotIdx);
              if (!oldSession.equals(expectedSession)) {
                String suffix = name.substring(dotIdx);
                Files.move(f, dir.resolve(expectedSession + suffix), StandardCopyOption.REPLACE_EXISTING);
              }
            }
          }
        }
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, () -> "Failed to prepare bisync workdir " + dir + ": " + e.getMessage());
    }
  }

  /// Invalidate the baseline by deleting listing files from the workdir.
  public void invalidateBaseline() {
    try {
      Path dir = workdir();
      if (Files.isDirectory(dir)) {
        try (var stream = Files.list(dir)) {
          for (Path f : stream.toList()) {
            String name = f.getFileName().toString();
            if (name.endsWith(".lst") || name.endsWith(".lst-old") || name.endsWith(".lst-new") || name.endsWith(".lst-err") || name.endsWith(".lck")) {
              Files.deleteIfExists(f);
            }
          }
        }
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, () -> "Failed to invalidate bisync baseline in " + workdir() + ": " + e.getMessage());
    }
  }

  /// The rclone subcommand and its modifiers for this operation. The initial run carries
  /// the baseline rebuild (--resync), the following ones the plain bisync: the standard
  /// run propagates the deletions through the saved listings while --resync merges both
  /// sides into a superset leaving the deletions unpropagated.
  @Override
  public List<String> operationArgs() {
    return resynced()
      ? List.of("bisync", "--workdir", workdir().toString())
      : List.of("bisync", "--resync", "--workdir", workdir().toString());
  }

  @Override
  public void prepare(List<String> endpoints) {
    String path1 = endpoints.get(endpoints.size() - 2);
    String path2 = endpoints.get(endpoints.size() - 1);
    prepareWorkdir(path1, path2);
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

  @Override
  public void delete() {
    super.delete();
  }

  /// Flags specific to rclone bisync that are not recognized by rclone check.
  private static final Set<String> BISYNC_ONLY_FLAGS = Set.of(
    "--recover", "--resilient", "--force", "--resync",
    "--check-access", "--create-empty-src-dirs", "--download-hash",
    "--ignore-listing-checksum", "--max-delete-renames-aware",
    "--no-cleanup", "--no-slow-hash", "--remove-empty-dirs", "--slow-hash-sync-only"
  );

  private static final Set<String> BISYNC_VALUED_FLAGS = Set.of(
    "--resync-mode", "--workdir", "--check-filename", "--check-sync",
    "--compare", "--conflict-loser", "--conflict-resolve", "--conflict-suffix",
    "--filters-file", "--max-lock", "--backup-dir1", "--backup-dir2"
  );

  /// Return options to pass to the integrity check command (rclone check): filters out
  /// bisync-specific modifiers (--recover, --resilient, --force) which are not supported
  /// by rclone check.
  @Override
  public List<String> serveOptions() {
    List<String> options = new ArrayList<>();
    List<String> all = processOptions();
    for (int i = 0; i < all.size(); i++) {
      String opt = all.get(i);
      if (BISYNC_ONLY_FLAGS.contains(opt)) {
        continue;
      }
      if (BISYNC_VALUED_FLAGS.contains(opt)) {
        i++; // skip flag value
        continue;
      }
      if (opt.startsWith("--")) {
        int eq = opt.indexOf('=');
        if (eq > 0) {
          String flagName = opt.substring(0, eq);
          if (BISYNC_ONLY_FLAGS.contains(flagName) || BISYNC_VALUED_FLAGS.contains(flagName)) {
            continue;
          }
        }
      }
      options.add(opt);
    }
    return options;
  }

  /// The bidirectional arrow: the equalize tasks render (and describe) differently
  /// from the one-way operations.
  @Override
  public String director() {
    return "<->";
  }

  @Override
  public String operation() {
    return OPERATION;
  }
}
