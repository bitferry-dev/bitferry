package io.bitferry.vault;

import io.bitferry.BitferryError;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {

  @Test
  void obscureRevealRoundTrips() {
    for (String secret : List.of("Our secret password", "p@$$w0rd !#", "пароль с юникодом", "x", "a".repeat(100))) {
      assertEquals(secret, Passwords.reveal(Passwords.obscure(secret)));
    }
  }

  @Test
  void obscuringIsRandomizedAndJittered() {
    // The fresh random salt and dynamic padding make each obscured form distinct
    String first = Passwords.obscure("same-secret");
    String second = Passwords.obscure("same-secret");
    assertNotEquals(first, second);
    assertEquals("same-secret", Passwords.reveal(first));
    assertEquals("same-secret", Passwords.reveal(second));

    Set<Integer> lengths = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      lengths.add(Passwords.obscure("same-secret").length());
    }
    assertTrue(lengths.size() > 1, "Dynamic null padding must produce varying token lengths");
  }

  @Test
  void nullPasswordIsRejected() {
    assertThrows(BitferryError.class, () -> Passwords.obscure(null));
  }

  @Test
  void garbageTokensAreRejected() {
    assertThrows(BitferryError.class, () -> Passwords.reveal("not base64 at all!!"));
    assertThrows(BitferryError.class, () -> Passwords.reveal(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[4])));
  }

  @Test
  void rcloneObscureRevealRoundTrips() {
    for (String secret : List.of("Our secret password", "rclone-secret", "p@$$w0rd")) {
      assertEquals(secret, Passwords.rcloneReveal(Passwords.rcloneObscure(secret)));
    }
  }

  @Test
  void rcloneTokensAreInteroperableWithRclone() throws Exception {
    Assumptions.assumeTrue(rcloneAvailable(), "rclone not available for the interoperability check");

    String secret = "Our secret password";

    // rclone reveals the token obscured by the library...
    String obscured = Passwords.rcloneObscure(secret);
    assertEquals(secret, revealWithRclone(obscured));

    // ...and the library reveals the token obscured by rclone
    String rcloneToken = obscureWithRclone(secret);
    assertEquals(secret, Passwords.rcloneReveal(rcloneToken));
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

  private static String obscureWithRclone(String data) throws Exception {
    Process process = new ProcessBuilder("rclone", "obscure", data).start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IllegalStateException("rclone obscure failed");
      }
      return reader.readLine().trim();
    }
  }

  private static String revealWithRclone(String data) throws Exception {
    Process process = new ProcessBuilder("rclone", "reveal", data).start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IllegalStateException("rclone reveal failed");
      }
      return reader.readLine().trim();
    }
  }
}
