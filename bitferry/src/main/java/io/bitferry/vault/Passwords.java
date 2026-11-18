package io.bitferry.vault;

import io.bitferry.BitferryError;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/// Rclone-compatible password obscuring and the multi-password token codec.
///
/// The obscured form is obfuscation rather than encryption: it keeps the plain
/// passwords out of the storage files while remaining interoperable with rclone's
/// obscure/reveal (AES-256-CTR with the shared static key, random IV prepended,
/// unpadded URL-safe base64 encoding).
public final class Passwords {

  // The shared static key of the rclone obscured format
  private static final byte[] KEY = {
    (byte) 0x9c, (byte) 0x93, 0x5b, 0x48, 0x73, 0x0a, 0x55, 0x4d,
    0x6b, (byte) 0xfd, 0x7c, 0x63, (byte) 0xc8, (byte) 0x86, (byte) 0xa9, 0x2b,
    (byte) 0xd3, (byte) 0x90, 0x19, (byte) 0x8e, (byte) 0xb8, 0x12, (byte) 0x8a, (byte) 0xfb,
    (byte) 0xf4, (byte) 0xde, 0x16, 0x2b, (byte) 0x8b, (byte) 0x95, (byte) 0xf6, 0x38
  };

  // AES block sized random IV is prepended to the ciphertext
  private static final int IV_LENGTH = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  private Passwords() {}

  /// Obscure the value: encrypt with AES-CTR under a fresh random IV and return
  /// the base64url (unpadded) encoding of the IV followed by the ciphertext.
  public static String obscure(String data) {
    try {
      byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);
      byte[] iv = new byte[IV_LENGTH];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
      byte[] ciphertext = cipher.doFinal(plaintext);
      byte[] payload = new byte[IV_LENGTH + ciphertext.length];
      System.arraycopy(iv, 0, payload, 0, IV_LENGTH);
      System.arraycopy(ciphertext, 0, payload, IV_LENGTH, ciphertext.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Password obscuring failure: " + e.getMessage(), e);
    }
  }

  /// Reveal a value obscured by [obscure] (or by rclone itself).
  public static String reveal(String obscured) {
    byte[] payload;
    try {
      payload = Base64.getUrlDecoder().decode(obscured);
    } catch (IllegalArgumentException e) {
      throw new BitferryError("Invalid obscured password - is it obscured?", e);
    }
    if (payload.length < IV_LENGTH) throw new BitferryError("Obscured password too short - is it obscured?");
    try {
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(payload, 0, IV_LENGTH));
      byte[] plaintext = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Password revealing failure: " + e.getMessage(), e);
    }
  }

  // The slot separator of the multi-password token: ',' lies outside the base64url
  // alphabet of the obscured forms, so it unambiguously splits the token into the
  // independently obscured slots
  private static final String SLOT_SEPARATOR = ",";

  /// Encode the password pair (the second one optional) into the single token: each
  /// password is obscured independently and the obscured forms are comma-joined.
  /// A '\n' inside a password is then representable - the former join-with-newline
  /// codec silently truncated the first password at its embedded newline upon the
  /// decoding (the corrupted password surfaced as a bare authentication failure).
  /// The single-password form carries the trailing separator marking the empty
  /// second slot.
  public static String encodePasswords(String password, String password2) {
    return password2 == null
      ? obscure(password) + SLOT_SEPARATOR
      : obscure(password) + SLOT_SEPARATOR + obscure(password2);
  }

  /// Decode the token back into the password pair (the second element null when
  /// single). The legacy single-blob form (the passwords joined with the line feed
  /// prior to the obscuring - the pre-slot tokens of the older versions) keeps
  /// decoding; a legacy single password carrying a newline was already mangled by
  /// that codec's encoding and is unrecoverable.
  public static String[] decodeToken(String token) {
    int at = token.indexOf(SLOT_SEPARATOR);
    if (at >= 0) {
      String first = reveal(token.substring(0, at));
      String rest = token.substring(at + 1);
      return new String[]{first, rest.isEmpty() ? null : reveal(rest)};
    }
    // The legacy form: the newline-joined pair (or the plain single password)
    String revealed = reveal(token);
    int nl = revealed.indexOf('\n');
    if (nl < 0) return new String[]{revealed, null};
    return new String[]{revealed.substring(0, nl), revealed.substring(nl + 1)};
  }
}
