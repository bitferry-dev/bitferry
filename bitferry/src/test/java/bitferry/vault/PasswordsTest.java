package bitferry.vault;

import bitferry.BitferryError;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {

  @Test
  void obscureRevealRoundTrips() {
    for (String secret : List.of("Our secret password", "p@$$w0rd !#", "пароль с юникодом", "x")) {
      assertEquals(secret, Passwords.reveal(Passwords.obscure(secret)));
    }
  }

  @Test
  void obscuringIsRandomized() {
    // The fresh random IV makes each obscured form distinct while remaining revealable
    String first = Passwords.obscure("same-secret");
    String second = Passwords.obscure("same-secret");
    assertNotEquals(first, second);
    assertEquals("same-secret", Passwords.reveal(first));
    assertEquals("same-secret", Passwords.reveal(second));
  }

  @Test
  void passwordPairsTravelThroughTheTokens() {
    String single = Passwords.encodePasswords("alpha", null);
    assertEquals("alpha", Passwords.decodeToken(single)[0]);
    assertNull(Passwords.decodeToken(single)[1]);

    String pair = Passwords.encodePasswords("alpha", "beta");
    assertEquals("alpha", Passwords.decodeToken(pair)[0]);
    assertEquals("beta", Passwords.decodeToken(pair)[1]);
  }

  @Test
  void passwordsCarryingNewlinesSurviveTheTokens() {
    // The slot codec obscures each password independently: an embedded newline used
    // to be silently consumed as the pair separator upon the decoding (the first
    // password came back truncated, surfacing as a bare authentication failure)
    String single = Passwords.encodePasswords("alpha\nbeta", null);
    assertEquals("alpha\nbeta", Passwords.decodeToken(single)[0]);
    assertNull(Passwords.decodeToken(single)[1]);

    String pair = Passwords.encodePasswords("al\npha", "be\nta");
    assertEquals("al\npha", Passwords.decodeToken(pair)[0]);
    assertEquals("be\nta", Passwords.decodeToken(pair)[1]);
  }

  @Test
  void legacyNewlineJoinedTokensKeepDecoding() {
    // The pre-slot single-blob form (the passwords joined with the line feed prior
    // to the obscuring) keeps decoding for the tokens stored by the older versions
    String legacySingle = Passwords.obscure("alpha");
    assertEquals("alpha", Passwords.decodeToken(legacySingle)[0]);
    assertNull(Passwords.decodeToken(legacySingle)[1]);

    String legacyPair = Passwords.obscure("alpha\nbeta");
    assertEquals("alpha", Passwords.decodeToken(legacyPair)[0]);
    assertEquals("beta", Passwords.decodeToken(legacyPair)[1]);
  }

  @Test
  void garbageTokensAreRejected() {
    assertThrows(BitferryError.class, () -> Passwords.reveal("not base64 at all!!"));
    assertThrows(BitferryError.class, () -> Passwords.reveal(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[4])));
  }

  @Test
  void tokensAreInteroperableWithRclone() throws Exception {
    Assumptions.assumeTrue(rcloneAvailable(), "rclone not available for the interoperability check");

    String secret = "Our secret password";

    // rclone reveals the token obscured by the library...
    String obscured = Passwords.obscure(secret);
    assertEquals(secret, revealWithRclone(obscured));

    // ...and the library reveals the token obscured by rclone
    String rcloneToken = obscureWithRclone(secret);
    assertEquals(secret, Passwords.reveal(rcloneToken));
  }

  // The portable availability probe (no 'which' dependency, works on Windows and UNIX)
  private static boolean rcloneAvailable() {
    try {
      Process process = new ProcessBuilder("rclone", "version").start();
      return process.waitFor() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  // The token travels after the '--' end-of-options marker: the base64url alphabet
  // makes a '-'-leading (or '_' -leading) form a roughly one-in-32 outcome of the
  // randomized obscuring, and the bare token would be parsed as a flag cluster then
  private static String obscureWithRclone(String secret) throws Exception {
    Process process = new ProcessBuilder("rclone", "obscure", "--", secret).start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    assertTrue(process.waitFor() == 0);
    return output;
  }

  private static String revealWithRclone(String token) throws Exception {
    Process process = new ProcessBuilder("rclone", "reveal", "--", token).start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    assertTrue(process.waitFor() == 0, () -> "rclone reveal failed for " + token);
    return output;
  }
}
