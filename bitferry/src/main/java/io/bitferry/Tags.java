package io.bitferry;

import java.security.*;
import java.util.*;

/// Tag generation and matching helpers. Volumes and tasks are identified by unique
/// 8 hexadecimal character tags which are matched by the substring rules: a (partial)
/// tag part matches a tag as long as it is contained in the latter.
public final class Tags {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Tags() {}

  /// Generate a new random 8 hexadecimal character tag (mirroring Python's `secrets.token_hex(4)`).
  public static String newTag() {
    byte[] bytes = new byte[4];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /// Return true if the tag part matches the tag by the substring rules.
  public static boolean matches(String part, String tag) {
    return tag.contains(part);
  }
}
