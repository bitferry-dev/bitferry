package org.bitferry.vault;

import org.bitferry.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/// Password obscuring and token codec.
///
/// Features length-masking stream obfuscation (ARCFOUR-drop256 with random salt and
/// leading null padding) for concealing plaintexts in volume storage, alongside the
/// rclone-compatible AES-256-CTR obscure/reveal for rclone process environments.
public final class Passwords {

  // The 16-byte shared static key of the Bitferry stream obfuscation
  private static final byte[] BITFERRY_KEY = {
    (byte) 0x2b, (byte) 0x7e, (byte) 0x15, (byte) 0x16, (byte) 0x28, (byte) 0xae, (byte) 0xd2, (byte) 0xa6,
    (byte) 0xab, (byte) 0xf7, (byte) 0x15, (byte) 0x88, (byte) 0x09, (byte) 0xcf, (byte) 0x4f, (byte) 0x3c
  };

  private static final int SALT_LENGTH = 8;
  private static final int MIN_PAD = 16;
  private static final int MAX_PAD = 32;
  private static final int DROP_BYTES = 256;

  // The shared static key of the rclone obscured format
  private static final byte[] RCLONE_KEY = {
    (byte) 0x9c, (byte) 0x93, (byte) 0x5b, (byte) 0x48, (byte) 0x73, (byte) 0x0a, (byte) 0x55, (byte) 0x4d,
    (byte) 0x6b, (byte) 0xfd, (byte) 0x7c, (byte) 0x63, (byte) 0xc8, (byte) 0x86, (byte) 0xa9, (byte) 0x2b,
    (byte) 0xd3, (byte) 0x90, (byte) 0x19, (byte) 0x8e, (byte) 0xb8, (byte) 0x12, (byte) 0x8a, (byte) 0xfb,
    (byte) 0xf4, (byte) 0xde, (byte) 0x16, (byte) 0x2b, (byte) 0x8b, (byte) 0x95, (byte) 0xf6, (byte) 0x38
  };

  private static final int IV_LENGTH = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  private Passwords() {}

  /// Obscure a secret value using ARCFOUR-drop256 with a fresh random salt and randomized
  /// leading null padding, returning the unpadded base64url encoding of the salt followed
  /// by the ciphertext.
  public static String bitferryObscure(String data) {
    if (data == null) throw new BitferryError("Cannot obscure null password");
    try {
      byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);
      int padLen = MIN_PAD + RANDOM.nextInt(MAX_PAD - MIN_PAD + 1);
      byte[] payload = new byte[padLen + plaintext.length];
      System.arraycopy(plaintext, 0, payload, padLen, plaintext.length);

      byte[] salt = new byte[SALT_LENGTH];
      RANDOM.nextBytes(salt);

      Cipher cipher = Cipher.getInstance("ARCFOUR");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveBitferryKey(salt), "ARCFOUR"));
      cipher.update(new byte[DROP_BYTES]);
      byte[] ciphertext = cipher.doFinal(payload);

      byte[] result = new byte[SALT_LENGTH + ciphertext.length];
      System.arraycopy(salt, 0, result, 0, SALT_LENGTH);
      System.arraycopy(ciphertext, 0, result, SALT_LENGTH, ciphertext.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Password obscuring failure: " + e.getMessage(), e);
    }
  }

  /// Reveal a value obscured by [obscure].
  public static String bitferryReveal(String obscured) {
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(obscured);
    } catch (IllegalArgumentException e) {
      throw new BitferryError("Invalid obscured password - is it obscured?", e);
    }
    if (raw.length <= SALT_LENGTH + MIN_PAD) throw new BitferryError("Obscured password too short - is it obscured?");
    try {
      byte[] salt = new byte[SALT_LENGTH];
      System.arraycopy(raw, 0, salt, 0, SALT_LENGTH);
      byte[] ciphertext = new byte[raw.length - SALT_LENGTH];
      System.arraycopy(raw, SALT_LENGTH, ciphertext, 0, ciphertext.length);

      Cipher cipher = Cipher.getInstance("ARCFOUR");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveBitferryKey(salt), "ARCFOUR"));
      cipher.update(new byte[DROP_BYTES]);
      byte[] decrypted = cipher.doFinal(ciphertext);

      int start = 0;
      while (start < decrypted.length && decrypted[start] == 0) start++;
      if (start >= decrypted.length) throw new BitferryError("Invalid obscured password - empty payload");
      return new String(decrypted, start, decrypted.length - start, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Password revealing failure: " + e.getMessage(), e);
    }
  }

  private static byte[] deriveBitferryKey(byte[] salt) {
    byte[] key = new byte[BITFERRY_KEY.length + salt.length];
    System.arraycopy(BITFERRY_KEY, 0, key, 0, BITFERRY_KEY.length);
    System.arraycopy(salt, 0, key, BITFERRY_KEY.length, salt.length);
    return key;
  }

  /// Obscure the value using rclone's AES-256-CTR scheme for passing to rclone's environment.
  public static String rcloneObscure(String data) {
    try {
      byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);
      byte[] iv = new byte[IV_LENGTH];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(RCLONE_KEY, "AES"), new IvParameterSpec(iv));
      byte[] ciphertext = cipher.doFinal(plaintext);
      byte[] payload = new byte[IV_LENGTH + ciphertext.length];
      System.arraycopy(iv, 0, payload, 0, IV_LENGTH);
      System.arraycopy(ciphertext, 0, payload, IV_LENGTH, ciphertext.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Password obscuring failure: " + e.getMessage(), e);
    }
  }

  /// Reveal a value obscured by [rcloneObscure] (or by rclone itself).
  public static String rcloneReveal(String obscured) {
    byte[] payload;
    try {
      payload = Base64.getUrlDecoder().decode(obscured);
    } catch (IllegalArgumentException e) {
      throw new BitferryError("Invalid obscured password - is it obscured?", e);
    }
    if (payload.length < IV_LENGTH) throw new BitferryError("Obscured password too short - is it obscured?");
    try {
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(RCLONE_KEY, "AES"), new IvParameterSpec(payload, 0, IV_LENGTH));
      byte[] plaintext = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Password revealing failure: " + e.getMessage(), e);
    }
  }
}
