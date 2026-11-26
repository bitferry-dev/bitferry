package org.bitferry.vault;

import org.bitferry.BitferryError;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {

  @Test
  void obscureRevealRoundTrips() {
    for (String secret : List.of("Our secret password", "p@$$w0rd !#", "пароль с юникодом", "x", "\0leading-null", "", "a".repeat(100))) {
      assertEquals(secret, Passwords.bitferryReveal(Passwords.bitferryObscure(secret)));
    }
  }

  @Test
  void obscuringIsRandomizedAndLengthBucketed() {
    String first = Passwords.bitferryObscure("same-secret");
    String second = Passwords.bitferryObscure("same-secret");
    assertNotEquals(first, second);
    assertEquals("same-secret", Passwords.bitferryReveal(first));
    assertEquals("same-secret", Passwords.bitferryReveal(second));

    Set<Integer> lengths = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      lengths.add(Passwords.bitferryObscure("same-secret").length());
    }
    assertEquals(1, lengths.size(), "Block padding must not leak variation within a length bucket");
  }

  @Test
  void plaintextLengthRoundsUpToPayloadBlocks() {
    assertRawTokenLength(0, 60);
    assertRawTokenLength(1, 60);
    assertRawTokenLength(28, 60);
    assertRawTokenLength(29, 92);
    assertRawTokenLength(60, 92);
    assertRawTokenLength(61, 124);
  }

  @Test
  void modifiedTokensAreRejected() {
    byte[] raw = Base64.getUrlDecoder().decode(Passwords.bitferryObscure("authentication-secret"));
    for (int offset : new int[] {0, 12, raw.length - 1}) {
      byte[] modified = raw.clone();
      modified[offset] ^= 1;
      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(modified);
      BitferryError error = assertThrows(BitferryError.class, () -> Passwords.bitferryReveal(token));
      assertEquals("Invalid Bitferry obscured password - bad format", error.getMessage());
    }
  }

  @Test
  void legacyTokensAreRejected() {
    assertThrows(BitferryError.class, () -> Passwords.bitferryReveal(
      "8bLIbHH3xWlZGcoUznIdX-Ji7cUowmncwTDvj_sjJplIw1Hucq4edd5Tx3U"));
    assertThrows(BitferryError.class, () -> Passwords.bitferryReveal(
      "kkOPfP0PqbQrnPQvF9rAhPXN7GunBfYLRn7mdoCBGEHB0ipUV0n-RkfbJ-yqcG4sB4ccRiCEH0z-rgFb"));
  }

  private static void assertRawTokenLength(int plaintextLength, int expected) {
    String token = Passwords.bitferryObscure("a".repeat(plaintextLength));
    assertEquals(expected, Base64.getUrlDecoder().decode(token).length);
    assertEquals("a".repeat(plaintextLength), Passwords.bitferryReveal(token));
  }

  @Test
  void nullPasswordIsRejected() {
    assertThrows(BitferryError.class, () -> Passwords.bitferryObscure(null));
    assertThrows(BitferryError.class, () -> Passwords.bitferryReveal(null));
    assertThrows(BitferryError.class, () -> Passwords.rcloneObscure(null));
    assertThrows(BitferryError.class, () -> Passwords.rcloneReveal(null));
  }

  @Test
  void garbageTokensAreRejected() {
    assertThrows(BitferryError.class, () -> Passwords.bitferryReveal("not base64 at all!!"));
    assertThrows(BitferryError.class, () -> Passwords.bitferryReveal(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[4])));
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
    return runRclone("obscure", data);
  }

  private static String revealWithRclone(String data) throws Exception {
    return runRclone("reveal", data);
  }

  // Run the rclone subcommand, retrying once on failure: the fresh process spawn
  // under the loaded test run (the parallel suite, the antivirus scanning) used to
  // exceed the tight wait and flake the interoperability check - the crypto itself
  // verifies in a fraction of the budget. The stderr travels into the failure
  // message so the genuine rclone refusals stay diagnosable.
  private static String runRclone(String subcommand, String data) throws Exception {
    IllegalStateException failure = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      Process process = new ProcessBuilder("rclone", subcommand, data).start();
      StringBuilder stderr = new StringBuilder();
      Thread errPump = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            stderr.append(line).append('\n');
          }
        } catch (IOException ignored) {
          // The stream ends with the process
        }
      });
      errPump.setDaemon(true);
      errPump.start();
      boolean exited;
      try {
        exited = process.waitFor(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw e;
      }
      if (exited && process.exitValue() == 0) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
          String line = reader.readLine();
          return line == null ? "" : line.trim();
        }
      }
      process.destroyForcibly();
      failure = new IllegalStateException("rclone " + subcommand + " failed ("
        + (exited ? "exit " + process.exitValue() : "timed out") + "): " + stderr.toString().trim());
    }
    throw failure;
  }
}
