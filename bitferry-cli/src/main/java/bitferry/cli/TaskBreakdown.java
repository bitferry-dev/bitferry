package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import picocli.CommandLine.Help.Ansi;

/// Formatter that produces a complete, structured breakdown of a task.
public final class TaskBreakdown {

  private static final DateTimeFormatter ISO_FMT =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  private TaskBreakdown() {}

  /// Format a task into a multi-line human-readable breakdown.
  public static String format(Task task) {
    StringBuilder sb = new StringBuilder();

    // 1. Header & Identity
    sb.append(Ansi.AUTO.string(String.format("@|bold # Task %s: %s|@%n", task.tag(), task.displayOperation())));

    appendProperty(sb, "Status:", formatStatus(task));
    appendProperty(sb, "Backend:", task.backend());
    appendProperty(sb, "Operation:", task.operation());
    appendProperty(sb, "Mass run:", task.isRunnable() ? "yes" : "no (manual-only)");

    // The endpoint entries ride the task hierarchy (the per-type labels and the
    // per-type data)
    for (Task.EndpointEntry entry : task.endpointEntries()) {
      appendProperty(sb, entry.label(), formatEndpoint(entry.endpoint()));
    }

    // The encryption summary (the polymorphic accessor returns empty for the tasks
    // without encryption)
    task.encryptionSummary().ifPresent(s -> appendProperty(sb, "Encryption:", s));

    // The execution options (when present)
    if (!task.processOptions().isEmpty()) {
      appendProperty(sb, "Execute Opts:", String.join(" ", task.processOptions()));
    }

    String vault = formatVaultVolume(task);
    if (vault != null) {
      appendProperty(sb, "Vault Volume:", vault);
    }

    if (task.mtime() != null) {
      String modified = String.format("%s (generation %d)",
        task.mtime().atZoneSameInstant(ZoneOffset.UTC).format(ISO_FMT),
        task.generation());
      appendProperty(sb, "Modified:", modified);
    }

    // 2. Specific sections: Service & Retention for Backup
    if (task instanceof Backup backup) {
      appendServiceSection(sb, backup);
    }

    // 3. Filters
    sb.append(System.lineSeparator());
    sb.append(Ansi.AUTO.string("@|bold # Filters|@")).append(System.lineSeparator());
    appendProperty(sb, "Include:", task.include().isEmpty() ? "(none)" : String.join(", ", task.include()));
    appendProperty(sb, "Exclude:", task.exclude().isEmpty() ? "(none)" : String.join(", ", task.exclude()));

    // 4. Commands
    sb.append(System.lineSeparator());
    sb.append(Ansi.AUTO.string("@|bold # Commands|@")).append(System.lineSeparator());

    appendCommands(sb, "Execute:", task::commands);
    appendCommands(sb, "Serve:", task::serveCommands);

    return sb.toString();
  }

  private static void appendProperty(StringBuilder sb, String label, String value) {
    sb.append(String.format("  %-15s%s%n", label, value));
  }

  private static String formatStatus(Task task) {
    // The ASCII approximations on the non-unicode consoles: the encoder would
    // otherwise collapse the marks into the semantically lossy '?'
    return Symbols.unicode()
      ? (task.isIntact()
        ? Ansi.AUTO.string("@|green ✓ intact|@")
        : Ansi.AUTO.string("@|red ✗ stale|@"))
      : (task.isIntact()
        ? Ansi.AUTO.string("@|green ok intact|@")
        : Ansi.AUTO.string("@|red x stale|@"));
  }

  private static String formatEndpoint(Endpoint endpoint) {
    if (endpoint instanceof LocalEndpoint l) {
      return Ansi.AUTO.string("local:" + AnsiFormat.endpoint(l, AnsiFormat.Markup.PICOCLI));
    } else if (endpoint instanceof VolumeEndpoint ve) {
      // The detailed breakdown appends the probing outcome the shared form leaves out
      String suffix = ve.isIntact() ? " (" + ve.processOpts() + ")" : " (volume missing)";
      return Ansi.AUTO.string(AnsiFormat.endpoint(ve, AnsiFormat.Markup.PICOCLI) + suffix);
    }
    return endpoint.describe();
  }

  private static String formatVaultVolume(Task task) {
    try {
      Volume v = task.vaultVolume();
      if (v != null) {
        return Ansi.AUTO.string(String.format("@|cyan %s|@ (%s)", v.tag(), v.root()));
      }
    } catch (Exception e) {
      String tag = null;
      if (task instanceof Backup b && b.source() instanceof VolumeEndpoint ve) {
        tag = ve.vtag();
      } else if (task instanceof Restore r && r.destination() instanceof VolumeEndpoint ve) {
        tag = ve.vtag();
      } else if (task instanceof Rclone rc) {
        if (rc.encryption() instanceof Rclone.Encrypt && rc.source() instanceof VolumeEndpoint ve) {
          tag = ve.vtag();
        } else if (rc.encryption() instanceof Rclone.Decrypt && rc.destination() instanceof VolumeEndpoint ve) {
          tag = ve.vtag();
        }
      }
      if (tag != null) {
        return Ansi.AUTO.string(String.format(":@|bold,red %s|@ (volume missing)", tag));
      }
    }
    return null;
  }

  private static void appendRcloneDetails(StringBuilder sb, Rclone rclone) {
    Rclone.Encryption enc = rclone.encryption();
    if (enc instanceof Rclone.Encrypt e) {
      String opts = e.options().isEmpty() ? "" : " (options: " + String.join(" ", e.options()) + ")";
      appendProperty(sb, "Encryption:", "encrypt" + opts);
    } else if (enc instanceof Rclone.Decrypt d) {
      String opts = d.options().isEmpty() ? "" : " (options: " + String.join(" ", d.options()) + ")";
      appendProperty(sb, "Encryption:", "decrypt" + opts);
    } else {
      appendProperty(sb, "Encryption:", "none");
    }

    appendProperty(sb, "Source:", formatEndpoint(rclone.source()));
    appendProperty(sb, "Destination:", formatEndpoint(rclone.destination()));

    if (!rclone.processOptions().isEmpty()) {
      appendProperty(sb, "Execute Opts:", String.join(" ", rclone.processOptions()));
    }
  }

  private static void appendBackupEndpoints(StringBuilder sb, Backup backup) {
    appendProperty(sb, "Source:", formatEndpoint(backup.source()));
    appendProperty(sb, "Repository:", formatEndpoint(backup.destination()));
    if (!backup.processOptions().isEmpty()) {
      appendProperty(sb, "Execute Opts:", String.join(" ", backup.processOptions()));
    }
  }

  private static void appendRestoreEndpoints(StringBuilder sb, Restore restore) {
    appendProperty(sb, "Repository:", formatEndpoint(restore.source()));
    appendProperty(sb, "Target:", formatEndpoint(restore.destination()));
    if (!restore.processOptions().isEmpty()) {
      appendProperty(sb, "Execute Opts:", String.join(" ", restore.processOptions()));
    }
  }

  private static void appendDefaultEndpoints(StringBuilder sb, Task task) {
    appendProperty(sb, "Source:", formatEndpoint(task.source()));
    appendProperty(sb, "Destination:", formatEndpoint(task.destination()));
  }

  private static void appendServiceSection(StringBuilder sb, Backup backup) {
    sb.append(System.lineSeparator());
    sb.append(Ansi.AUTO.string("@|bold # Service & Retention|@")).append(System.lineSeparator());

    Cadence cadence = backup.cadence();
    if (cadence == null) {
      appendProperty(sb, "Cadence:", "disabled (-)");
    } else {
      String profile = Cadence.PROFILES.get("default").equals(cadence) ? " (profile: @default)" : "";
      appendProperty(sb, "Cadence:", cadence.format() + profile);
    }

    Path repoDir = null;
    ResticRepositoryState state = null;
    boolean stateFileExists = false;
    String loadFailure = null;
    if (backup.destination().isIntact()) {
      try {
        repoDir = Path.of(backup.destination().processOpts());
        state = ResticRepositoryState.load(repoDir);
        stateFileExists = Files.isRegularFile(repoDir.resolve(ResticRepositoryState.FILENAME))
          || Files.isRegularFile(repoDir.resolve(ResticRepositoryState.LEGACY_FILENAME));
      } catch (Exception e) {
        // The load failure is reported verbatim: collapsing it into the
        // volume-missing status would misdirect the diagnosis
        loadFailure = e.getMessage();
      }
    }

    // The repository-wide due evaluation: the service covers the whole repository,
    // so it triggers when any co-repository task's cadence is due - not only this
    // task's own one (mirrors the executor's pre-backup gate). The evaluation is
    // gated on this task's own cadence mirroring the executor's trigger: the run of
    // a cadence-disabled task never services the repository, so reporting 'due'
    // (even a sibling-attributed one) would contradict the run behavior. The live
    // tasks only: the deletion tombstones are invisible for all the operations.
    Backup dueTask = null;
    if (cadence != null && state != null && !state.isCorruptOrMissing() && repoDir != null) {
      String repo = repoDir.toString();
      for (Task t : backup.manager().liveTasks().values()) {
        if (t.isIntact() && t instanceof Backup other && other.cadence() != null
          && Restic.sameRepository(repo, other.destination().processOpts())
          && state.isDue(other.tag(), other.cadence())) {
          dueTask = other;
          break;
        }
      }
    }

    if (state == null) {
      appendProperty(sb, "Status:", loadFailure != null
        ? Ansi.AUTO.string("@|yellow unavailable (state load failure: " + loadFailure + ")|@")
        : "unavailable (destination volume missing)");
    } else if (!stateFileExists) {
      appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due (initial service pending)|@"));
    } else if (state.isCorruptOrMissing()) {
      appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due (corrupted state file)|@"));
    } else if (dueTask != null) {
      ResticRepositoryState.Entry due = state.entry(dueTask.tag());
      Cadence dueCadence = dueTask.cadence();
      String attribution = dueTask == backup ? "" : " (co-repository task " + dueTask.tag() + ")";
      if (dueCadence.count() < Integer.MAX_VALUE && due.backups() >= dueCadence.count()) {
        appendProperty(sb, "Status:", Ansi.AUTO.string(String.format(Locale.ROOT, "@|yellow due (threshold reached: %d backups since service)%s|@", due.backups(), attribution)));
      } else if (dueCadence.duration() != null && due.maintenance() != null && Duration.between(due.maintenance(), Instant.now()).compareTo(dueCadence.duration()) >= 0) {
        appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due (cadence duration elapsed)" + attribution + "|@"));
      } else {
        appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due" + attribution + "|@"));
      }
    } else {
      appendProperty(sb, "Status:", Ansi.AUTO.string("@|green ok|@"));
    }

    ResticRepositoryState.Entry entry = state == null ? null : state.entry(backup.tag());
    if (entry != null && entry.backup() != null) {
      appendProperty(sb, "Last Backup:", formatInstant(entry.backup()) + " (" + formatElapsed(entry.backup(), Instant.now()) + ")");
    } else {
      appendProperty(sb, "Last Backup:", "never");
    }

    if (entry != null && entry.maintenance() != null) {
      String countInfo = cadence != null && cadence.count() < Integer.MAX_VALUE
        ? String.format(Locale.ROOT, " (%d of %d backups)", entry.backups(), cadence.count())
        : String.format(Locale.ROOT, " (%d backups)", entry.backups());
      appendProperty(sb, "Last Service:", formatInstant(entry.maintenance()) + " (" + formatElapsed(entry.maintenance(), Instant.now()) + ")" + countInfo);
    } else {
      appendProperty(sb, "Last Service:", "never");
    }

    if (backup.forget() == null || backup.forget().isEmpty()) {
      appendProperty(sb, "Retention:", "none");
    } else {
      appendProperty(sb, "Retention:", String.join(" ", backup.forget()));
    }

    if (backup.check() == null) {
      appendProperty(sb, "Check:", "none");
    } else if (backup.check().isEmpty()) {
      appendProperty(sb, "Check:", "@default (structural)"); 
    } else if (backup.check().contains("--read-data")) {
      appendProperty(sb, "Check:", "@full (--read-data)");
    } else {
      appendProperty(sb, "Check:", String.join(" ", backup.check()));
    }
  }

  private static void appendCommands(StringBuilder sb, String header, java.util.function.Supplier<List<List<String>>> supplier) {
    try {
      List<List<String>> cmds = supplier.get();
      if (cmds != null && !cmds.isEmpty()) {
        sb.append("  ").append(header).append(System.lineSeparator());
        for (List<String> cmd : cmds) {
          // The display form quotes the tokens carrying whitespace (mirroring
          // ExecutorSupport.echoCommandLine and the info --commands output)
          sb.append("    ").append(ExecutorSupport.displayCommandLine(cmd)).append(System.lineSeparator());
        }
      }
    } catch (Exception e) {
      sb.append("  ").append(header).append(System.lineSeparator());
      sb.append("    (unavailable: ").append(e.getMessage()).append(")").append(System.lineSeparator());
    }
  }

  private static String formatInstant(Instant instant) {
    if (instant == null) return "never";
    return ISO_FMT.format(instant);
  }

  private static String formatElapsed(Instant instant, Instant now) {
    if (instant == null) return "never";
    Duration d = Duration.between(instant, now);
    if (d.isNegative() || d.getSeconds() < 60) return "just now";
    long minutes = d.toMinutes();
    if (minutes < 60) return minutes + "m ago";
    long hours = d.toHours();
    if (hours < 24) return hours + "h ago";
    long days = d.toDays();
    return days + "d ago";
  }
}
