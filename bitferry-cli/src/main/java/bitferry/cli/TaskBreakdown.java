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
    sb.append(Ansi.AUTO.string(String.format("@|bold # Task #%s: %s|@%n", task.tag(), task.displayOperation())));

    appendProperty(sb, "Status:", formatStatus(task));
    appendProperty(sb, "Backend:", task.backend());
    appendProperty(sb, "Operation:", task.operation());

    if (task instanceof Rclone rclone) {
      appendRcloneDetails(sb, rclone);
    } else if (task instanceof Backup backup) {
      appendBackupEndpoints(sb, backup);
    } else if (task instanceof Restore restore) {
      appendRestoreEndpoints(sb, restore);
    } else {
      appendDefaultEndpoints(sb, task);
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
    return task.isIntact()
      ? Ansi.AUTO.string("@|green ✓ intact|@")
      : Ansi.AUTO.string("@|red ✗ stale|@");
  }

  private static String formatEndpoint(Endpoint endpoint) {
    if (endpoint instanceof LocalEndpoint l) {
      return Ansi.AUTO.string("local:@|blue " + l.root() + "|@");
    } else if (endpoint instanceof VolumeEndpoint ve) {
      String path = ve.path().equals(".") ? "" : ve.path();
      if (ve.isIntact()) {
        return Ansi.AUTO.string(":@|cyan " + ve.vtag() + "|@:" + path) + " (" + ve.processOpts() + ")";
      } else {
        return Ansi.AUTO.string(":{@|bold,red " + ve.vtag() + "|@}:" + path) + " (volume missing)";
      }
    }
    return endpoint.describe();
  }

  private static String formatVaultVolume(Task task) {
    try {
      Volume v = task.vaultVolume();
      if (v != null) {
        return Ansi.AUTO.string(String.format("@|cyan #%s|@ (%s)", v.tag(), v.root()));
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
    RepoState state = null;
    boolean stateFileExists = false;
    if (backup.destination().isIntact()) {
      try {
        repoDir = Path.of(backup.destination().processOpts());
        state = RepoState.load(repoDir);
        stateFileExists = Files.isRegularFile(repoDir.resolve(RepoState.FILENAME));
      } catch (Exception e) {
        // ignore
      }
    }

    if (state == null) {
      appendProperty(sb, "Status:", "unavailable (destination volume missing)");
    } else if (!stateFileExists) {
      appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due (initial service pending)|@"));
    } else if (state.isCorruptOrMissing()) {
      appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due (corrupted state file)|@"));
    } else if (cadence != null && state.isDue(cadence)) {
      if (cadence.count() < Integer.MAX_VALUE && state.backupsSinceServe() >= cadence.count()) {
        appendProperty(sb, "Status:", Ansi.AUTO.string(String.format("@|yellow due (threshold reached: %d backups since service)|@", state.backupsSinceServe())));
      } else if (cadence.duration() != null && state.lastServe() != null && Duration.between(state.lastServe(), Instant.now()).compareTo(cadence.duration()) >= 0) {
        appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due (cadence duration elapsed)|@"));
      } else {
        appendProperty(sb, "Status:", Ansi.AUTO.string("@|yellow due|@"));
      }
    } else {
      appendProperty(sb, "Status:", Ansi.AUTO.string("@|green ok|@"));
    }

    if (state != null && state.lastBackup() != null) {
      appendProperty(sb, "Last Backup:", formatInstant(state.lastBackup()) + " (" + formatElapsed(state.lastBackup(), Instant.now()) + ")");
    } else {
      appendProperty(sb, "Last Backup:", "never");
    }

    if (state != null && state.lastServe() != null) {
      String countInfo = cadence != null && cadence.count() < Integer.MAX_VALUE
        ? String.format(" (%d of %d backups)", state.backupsSinceServe(), cadence.count())
        : String.format(" (%d backups)", state.backupsSinceServe());
      appendProperty(sb, "Last Service:", formatInstant(state.lastServe()) + " (" + formatElapsed(state.lastServe(), Instant.now()) + ")" + countInfo);
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
          sb.append("    ").append(String.join(" ", cmd)).append(System.lineSeparator());
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
