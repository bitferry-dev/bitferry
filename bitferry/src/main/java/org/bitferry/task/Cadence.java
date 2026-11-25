package org.bitferry.task;

import com.fasterxml.jackson.annotation.*;
import org.bitferry.*;
import java.time.*;
import java.util.*;

/// Service cadence dual-threshold specifier: enable toggle, elapsed time, and backup count.
/// Service is triggered whenever [enabled] is true and either threshold is crossed ("whichever wins").
/// At least one of [duration] or [backups] must be present (non-null).
public record Cadence(
  @JsonProperty("enabled") boolean enabled,
  @JsonProperty("duration") Duration duration,
  @JsonProperty("backups") Integer backups
) {

  /// Predefined profiles; the default profile is enabled with 7 days or 5 backups.
  public static final Map<String, Cadence> PROFILES = Map.of(
    "default", new Cadence(true, Duration.ofDays(7), 5)
  );

  public Cadence {
    if (duration == null && backups == null) {
      throw new BitferryError("Cadence must specify at least a duration or a backup count");
    }
    if (duration != null && (duration.isZero() || duration.isNegative())) {
      throw new BitferryError("Duration must be positive but got: " + duration);
    }
    if (backups != null && backups <= 0) {
      throw new BitferryError("Invalid cadence backup count " + backups + ": must be positive");
    }
  }

  /// Convenience constructor defaulting [enabled] to true.
  public Cadence(Duration duration, Integer backups) {
    this(true, duration, backups);
  }

  @JsonCreator
  static Cadence fromJson(
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("duration") Duration duration,
    @JsonProperty("backups") Integer backups
  ) {
    return new Cadence(enabled == null || enabled, duration, backups);
  }

  /// Return a copy with [enabled] updated while keeping the configured thresholds.
  public Cadence withEnabled(boolean enabled) {
    return new Cadence(enabled, duration, backups);
  }

  /// Parse a cadence specification string or profile.
  public static Cadence parse(String option) {
    if (option == null) {
      return PROFILES.get("default");
    }
    if ("-".equals(option)) {
      return null;
    }
    if (option.startsWith("@")) {
      String name = option.substring(1);
      Cadence profile = PROFILES.get(name);
      if (profile == null) {
        throw new BitferryError("Unknown cadence profile " + option);
      }
      return profile;
    }

    String[] parts = option.split(",", -1);
    Duration dur = null;
    Integer cnt = null;

    if (parts.length == 1) {
      String part = parts[0].trim();
      if (part.matches("^[0-9]+$")) {
        cnt = Integer.parseInt(part);
      } else {
        dur = parseDuration(part);
      }
    } else if (parts.length == 2) {
      String dStr = parts[0].trim();
      String cStr = parts[1].trim();
      dur = dStr.isEmpty() ? null : parseDuration(dStr);
      cnt = cStr.isEmpty() ? null : parseCount(cStr);
    } else {
      throw new BitferryError("Invalid cadence specification " + option + ": expected '<duration>,<count>', '<duration>' or '<count>'");
    }

    return new Cadence(dur, cnt);
  }

  private static int parseCount(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      throw new BitferryError("Invalid cadence backup count " + s + ": expected integer", e);
    }
  }

  /// Parse duration strings such as "7d", "24h", "30m", "2w".
  public static Duration parseDuration(String s) {
    if (s == null || s.isEmpty()) {
      throw new BitferryError("Empty duration string");
    }
    char unit = s.charAt(s.length() - 1);
    String numStr = s.substring(0, s.length() - 1);
    long amount;
    try {
      amount = Long.parseLong(numStr);
    } catch (NumberFormatException e) {
      throw new BitferryError("Invalid duration number in " + s);
    }
    if (amount <= 0) {
      throw new BitferryError("Duration must be positive but got: " + s);
    }
    return switch (unit) {
      case 's' -> Duration.ofSeconds(amount);
      case 'm' -> Duration.ofMinutes(amount);
      case 'h' -> Duration.ofHours(amount);
      case 'd' -> Duration.ofDays(amount);
      case 'w' -> Duration.ofDays(amount * 7);
      default -> throw new BitferryError("Unknown duration unit " + unit + " in " + s + ": expected s, m, h, d, or w");
    };
  }

  /// Format the cadence as a canonical CLI/UI string, e.g. "7d,5".
  public String format() {
    if (duration != null && backups != null) {
      return formatDuration(duration) + "," + backups;
    }
    if (duration != null) {
      return formatDuration(duration);
    }
    return String.valueOf(backups);
  }

  /// Format a duration into a concise string (e.g. 7d, 24h).
  public static String formatDuration(Duration d) {
    long days = d.toDays();
    if (days > 0 && d.toHoursPart() == 0 && d.toMinutesPart() == 0 && d.toSecondsPart() == 0) {
      return days + "d";
    }
    long hours = d.toHours();
    if (hours > 0 && d.toMinutesPart() == 0 && d.toSecondsPart() == 0) {
      return hours + "h";
    }
    long minutes = d.toMinutes();
    if (minutes > 0 && d.toSecondsPart() == 0) {
      return minutes + "m";
    }
    return d.toSeconds() + "s";
  }

  /// Check whether serve is due given the last serve timestamp and backup count.
  public boolean isDue(Instant lastServe, int backupsSinceServe) {
    if (!enabled) return false;
    if (lastServe == null) return true;
    if (backups != null && backupsSinceServe >= backups) return true;
    if (duration != null && Duration.between(lastServe, Instant.now()).compareTo(duration) >= 0) return true;
    return false;
  }
}
