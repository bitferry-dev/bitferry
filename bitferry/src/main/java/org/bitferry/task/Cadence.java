package org.bitferry.task;

import org.bitferry.BitferryError;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/// Service cadence dual-threshold specifier: elapsed time and backup count.
/// Service is triggered whenever either threshold is crossed ("whichever wins").
public record Cadence(Duration duration, int count) {

  /// Predefined profiles; the default profile is 7 days or 5 backups.
  public static final Map<String, Cadence> PROFILES = Map.of(
    "default", new Cadence(Duration.ofDays(7), 5)
  );

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

    String[] parts = option.split(",");
    Duration dur = null;
    Integer cnt = null;

    if (parts.length == 1) {
      String part = parts[0].trim();
      if (part.matches("^[0-9]+$")) {
        cnt = Integer.parseInt(part);
        dur = Duration.ofDays(36500); // 100 years (effectively infinite)
      } else {
        dur = parseDuration(part);
        cnt = Integer.MAX_VALUE;
      }
    } else if (parts.length == 2) {
      String dStr = parts[0].trim();
      String cStr = parts[1].trim();
      dur = dStr.isEmpty() ? Duration.ofDays(36500) : parseDuration(dStr);
      try {
        cnt = cStr.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(cStr);
      } catch (NumberFormatException e) {
        throw new BitferryError("Invalid cadence backup count " + cStr + ": expected integer", e);
      }
    } else {
      throw new BitferryError("Invalid cadence specification " + option + ": expected '<duration>,<count>', '<duration>' or '<count>'");
    }

    if (cnt <= 0) {
      throw new BitferryError("Invalid cadence backup count " + cnt + ": must be positive");
    }
    return new Cadence(dur, cnt);
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

  /// Format the cadence as a canonical string, e.g. "7d,5".
  public String format() {
    String durStr = formatDuration(duration);
    if (count == Integer.MAX_VALUE) {
      return durStr;
    }
    if (duration.toDays() >= 36500) {
      return String.valueOf(count);
    }
    return durStr + "," + count;
  }

  /// Format a duration into a concise string (e.g. 7d, 24h). Every parseable duration
  /// round-trips through format→parse: returning an empty string (as the earlier
  /// effectively-infinite case did) would persist as an unparsable cadence and
  /// silently drop the task upon the restoration.
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
    if (lastServe == null) return true;
    if (backupsSinceServe >= count) return true;
    Duration elapsed = Duration.between(lastServe, Instant.now());
    return elapsed.compareTo(duration) >= 0;
  }
}
