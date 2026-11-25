package org.bitferry.task;

import org.bitferry.BitferryError;
import org.bitferry.Storage;
import tools.jackson.databind.JsonNode;
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
    assertEquals(5, def.backups());

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
    assertEquals(5, c1.backups());

    // Duration only
    Cadence c2 = Cadence.parse("14d");
    assertNotNull(c2);
    assertEquals(Duration.ofDays(14), c2.duration());
    assertNull(c2.backups());

    // Count only
    Cadence c3 = Cadence.parse("10");
    assertNotNull(c3);
    assertNull(c3.duration());
    assertEquals(10, c3.backups());

    // Weeks
    Cadence c4 = Cadence.parse("2w,20");
    assertNotNull(c4);
    assertEquals(Duration.ofDays(14), c4.duration());
    assertEquals(20, c4.backups());

    // Hours and minutes
    Cadence c5 = Cadence.parse("12h,1");
    assertEquals(Duration.ofHours(12), c5.duration());
    assertEquals(1, c5.backups());

    Cadence c6 = Cadence.parse("30m");
    assertEquals(Duration.ofMinutes(30), c6.duration());
    assertNull(c6.backups());

    // Invalid format
    assertThrows(BitferryError.class, () -> Cadence.parse("invalid"));
    assertThrows(BitferryError.class, () -> Cadence.parse("7x,5"));
    assertThrows(BitferryError.class, () -> Cadence.parse("7d,invalid"));
    assertThrows(BitferryError.class, () -> Cadence.parse(""));
    assertThrows(BitferryError.class, () -> Cadence.parse(","));
  }

  @Test
  void rejectsMissingOrNonPositiveThresholds() {
    assertThrows(BitferryError.class, () -> new Cadence(null, null));
    assertThrows(BitferryError.class, () -> new Cadence(Duration.ZERO, 5));
    assertThrows(BitferryError.class, () -> new Cadence(Duration.ofSeconds(-1), 5));
    assertThrows(BitferryError.class, () -> new Cadence(Duration.ofDays(7), 0));
    assertThrows(BitferryError.class, () -> new Cadence(Duration.ofDays(7), -3));
  }

  @Test
  void formatCanonicalString() {
    assertEquals("7d,5", new Cadence(Duration.ofDays(7), 5).format());
    assertEquals("14d", new Cadence(Duration.ofDays(14), null).format());
    assertEquals("10", new Cadence(null, 10).format());
    assertEquals("12h,3", new Cadence(Duration.ofHours(12), 3).format());
  }

  @Test
  void formatRoundTripsThroughParse() {
    for (String spec : List.of("7d,5", "14d", "10", "2w,20", "12h,1", "30m", "36500d", "40000d", "36500d,3", "90s")) {
      Cadence cadence = Cadence.parse(spec);
      String formatted = cadence.format();
      Cadence reparsed = Cadence.parse(formatted);
      assertEquals(cadence, reparsed, () -> "spec '" + spec + "' formatted to '" + formatted + "' and failed to round-trip");
      assertFalse(formatted.isBlank(), () -> "spec '" + spec + "' formatted to a blank string");
    }
  }

  @Test
  void jsonSerializationUsesStandardIsoDurationsAndOmitsNulls() {
    Cadence both = new Cadence(Duration.ofDays(7), 5);
    JsonNode bothNode = Storage.mapper().valueToTree(both);
    assertTrue(bothNode.get("enabled").asBoolean());
    assertEquals("PT168H", bothNode.get("duration").asString());
    assertEquals(5, bothNode.get("backups").asInt());
    assertFalse(bothNode.has("count"));
    assertEquals(both, Storage.mapper().treeToValue(bothNode, Cadence.class));

    Cadence disabledWithState = new Cadence(false, Duration.ofDays(14), 10);
    JsonNode disabledNode = Storage.mapper().valueToTree(disabledWithState);
    assertFalse(disabledNode.get("enabled").asBoolean());
    assertEquals("PT336H", disabledNode.get("duration").asString());
    assertEquals(10, disabledNode.get("backups").asInt());
    assertEquals(disabledWithState, Storage.mapper().treeToValue(disabledNode, Cadence.class));

    // Omitting 'enabled' in JSON defaults to true
    var omittedEnabled = Storage.mapper().createObjectNode();
    omittedEnabled.put("duration", "PT168H");
    assertTrue(Storage.mapper().treeToValue(omittedEnabled, Cadence.class).enabled());

    Cadence durOnly = new Cadence(Duration.ofDays(14), null);
    JsonNode durNode = Storage.mapper().valueToTree(durOnly);
    assertEquals("PT336H", durNode.get("duration").asString());
    assertFalse(durNode.has("backups"));
    assertEquals(durOnly, Storage.mapper().treeToValue(durNode, Cadence.class));

    Cadence cntOnly = new Cadence(null, 10);
    JsonNode cntNode = Storage.mapper().valueToTree(cntOnly);
    assertFalse(cntNode.has("duration"));
    assertEquals(10, cntNode.get("backups").asInt());
    assertEquals(cntOnly, Storage.mapper().treeToValue(cntNode, Cadence.class));
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

    // Unbounded thresholds do not trigger spuriously
    assertFalse(new Cadence(null, 5).isDue(eightDaysAgo, 4));
    assertFalse(new Cadence(Duration.ofDays(7), null).isDue(oneDayAgo, 100));

    // Disabled cadence with thresholds preserved is never due
    assertFalse(new Cadence(false, Duration.ofDays(7), 5).isDue(null, 100));
    assertFalse(new Cadence(false, Duration.ofDays(7), 5).isDue(eightDaysAgo, 10));
  }
}
