package org.bitferry.task;

import org.bitferry.BitferryError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CadenceTest {

  @Test
  void parsePredefinedProfiles() {
    Cadence def = Cadence.parse("@default");
    assertNotNull(def);
    assertEquals(Duration.ofDays(7), def.duration());
    assertEquals(5, def.count());

    // Null option defaults to @default
    Cadence nullParsed = Cadence.parse(null);
    assertEquals(def, nullParsed);

    // Disable flag '-' returns null
    assertNull(Cadence.parse("-"));

    // Unknown profile throws BitferryError
    assertThrows(BitferryError.class, () -> Cadence.parse("@nonexistent"));
  }

  @Test
  void parseCustomSpecifications() {
    // Both duration and count
    Cadence c1 = Cadence.parse("7d,5");
    assertNotNull(c1);
    assertEquals(Duration.ofDays(7), c1.duration());
    assertEquals(5, c1.count());

    // Duration only
    Cadence c2 = Cadence.parse("14d");
    assertNotNull(c2);
    assertEquals(Duration.ofDays(14), c2.duration());
    assertEquals(Integer.MAX_VALUE, c2.count());

    // Count only
    Cadence c3 = Cadence.parse("10");
    assertNotNull(c3);
    assertEquals(Duration.ofDays(36500), c3.duration());
    assertEquals(10, c3.count());

    // Weeks
    Cadence c4 = Cadence.parse("2w,20");
    assertNotNull(c4);
    assertEquals(Duration.ofDays(14), c4.duration());
    assertEquals(20, c4.count());

    // Hours and minutes
    Cadence c5 = Cadence.parse("12h,1");
    assertEquals(Duration.ofHours(12), c5.duration());
    assertEquals(1, c5.count());

    Cadence c6 = Cadence.parse("30m");
    assertEquals(Duration.ofMinutes(30), c6.duration());
    assertEquals(Integer.MAX_VALUE, c6.count());

    // Invalid format
    assertThrows(BitferryError.class, () -> Cadence.parse("invalid"));
    assertThrows(BitferryError.class, () -> Cadence.parse("7x,5"));
    assertThrows(BitferryError.class, () -> Cadence.parse("7d,invalid"));
  }

  @Test
  void formatCanonicalString() {
    assertEquals("7d,5", new Cadence(Duration.ofDays(7), 5).format());
    assertEquals("14d", new Cadence(Duration.ofDays(14), Integer.MAX_VALUE).format());
    assertEquals("10", new Cadence(Duration.ofDays(36500), 10).format());
    assertEquals("12h,3", new Cadence(Duration.ofHours(12), 3).format());
  }

  @Test
  void formatRoundTripsThroughParse() {
    // Every parseable specification must round-trip through format→parse: an
    // unparsable format would silently drop the task upon the storage restoration
    for (String spec : List.of("7d,5", "14d", "10", "2w,20", "12h,1", "30m", "36500d", "40000d", "36500d,3", "90s")) {
      Cadence cadence = Cadence.parse(spec);
      String formatted = cadence.format();
      Cadence reparsed = Cadence.parse(formatted);
      assertEquals(cadence, reparsed, () -> "spec '" + spec + "' formatted to '" + formatted + "' and failed to round-trip");
      // The formatted form is never empty (an empty string is unparsable)
      assertFalse(formatted.isBlank(), () -> "spec '" + spec + "' formatted to a blank string");
    }
  }

  @Test
  void infiniteDurationFormatsToAParsableForm() {
    // The effectively-infinite durations format to the exact parseable days form
    assertEquals("36500d", new Cadence(Duration.ofDays(36500), Integer.MAX_VALUE).format());
    assertEquals("40000d", new Cadence(Duration.ofDays(40000), Integer.MAX_VALUE).format());
    assertEquals(Duration.ofDays(40000), Cadence.parse(Cadence.parse("40000d").format()).duration());
  }

  @Test
  void evaluateThresholds() {
    Cadence cadence = new Cadence(Duration.ofDays(7), 5);

    // Missing lastServe -> always due
    assertTrue(cadence.isDue(null, 0));

    // Count threshold crossed
    assertTrue(cadence.isDue(Instant.now(), 5));
    assertTrue(cadence.isDue(Instant.now(), 6));

    // Duration threshold crossed
    Instant eightDaysAgo = Instant.now().minus(Duration.ofDays(8));
    assertTrue(cadence.isDue(eightDaysAgo, 0));

    // Neither threshold crossed
    Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));
    assertFalse(cadence.isDue(oneDayAgo, 4));
  }
}
