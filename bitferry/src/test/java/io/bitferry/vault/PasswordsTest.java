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
      assertEquals(secret, Passwords.bitferryReveal(Passwords.bitferryObscure(secret)));
    }
  }

  @Test
  void obscuringIsRandomizedAndJittered() {
    // The fresh random salt and dynamic padding make each obscured form distinct
    String first = Passwords.bitferryObscure("same-secret");
    String second = Passwords.bitferryObscure("same-secret");
    assertNotEquals(first, second);
    assertEquals("same-secret", Passwords.bitferryReveal(first));
    assertEquals("same-secret", Passwords.bitferryReveal(second));

    Set<Integer> lengths = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      lengths.add(Passwords.bitferryObscure("same-secret").length());
    }
    assertTrue(lengths.size() > 1, "Dynamic null padding must produce varying token lengths");
  }

  @Test
  void nullPasswordIsRejected() {
    assertThrows(BitferryError.class, () -> Passwords.bitferryObscure(null));
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
        exited = process.waitFor(10, TimeUnit.SECONDS);
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
