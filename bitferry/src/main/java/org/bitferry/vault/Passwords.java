package org.bitferry.vault;

import org.bitferry.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/// Password obscuring and token codec.
///
/// Features length-bucketed AES-GCM obfuscation with a random IV and zero padding for
/// concealing plaintexts in volume storage, alongside the rclone-compatible AES-256-CTR
/// obscure/reveal for rclone process environments.
public final class Passwords {

  // Public obfuscation material derived from a domain-separation label, not a secret key
  private static final SecretKey BITFERRY_KEY_SPEC = new SecretKeySpec(bitferryKey(), "AES");

  private static final int BITFERRY_IV_LENGTH = 12;
  private static final int GCM_TAG_BITS = 128;
  private static final int GCM_TAG_LENGTH = GCM_TAG_BITS / Byte.SIZE;
  private static final int LENGTH_PREFIX_LENGTH = Integer.BYTES;
  private static final int PAYLOAD_BLOCK_SIZE = 32;

  // The shared static key of the rclone obscured format
  private static final byte[] RCLONE_KEY = {
    (byte) 0x9c, (byte) 0x93, (byte) 0x5b, (byte) 0x48, (byte) 0x73, (byte) 0x0a, (byte) 0x55, (byte) 0x4d,
    (byte) 0x6b, (byte) 0xfd, (byte) 0x7c, (byte) 0x63, (byte) 0xc8, (byte) 0x86, (byte) 0xa9, (byte) 0x2b,
    (byte) 0xd3, (byte) 0x90, (byte) 0x19, (byte) 0x8e, (byte) 0xb8, (byte) 0x12, (byte) 0x8a, (byte) 0xfb,
    (byte) 0xf4, (byte) 0xde, (byte) 0x16, (byte) 0x2b, (byte) 0x8b, (byte) 0x95, (byte) 0xf6, (byte) 0x38
  };

  private static final SecretKey RCLONE_KEY_SPEC = new SecretKeySpec(RCLONE_KEY, "AES");

  private static final int RCLONE_IV_LENGTH = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  private Passwords() {}

  /// Obscure a secret value as a 12-byte IV followed by AES-GCM of a big-endian plaintext
  /// length, the plaintext and zero padding. The encrypted payload is rounded up to a
  /// 32-byte boundary and the result uses unpadded base64url encoding.
  public static String bitferryObscure(String data) {
    if (data == null) throw new BitferryError("Cannot obscure null password");
    try {
      byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);
      int contentLength = LENGTH_PREFIX_LENGTH + plaintext.length;
      int paddedLength = ((contentLength + PAYLOAD_BLOCK_SIZE - 1) / PAYLOAD_BLOCK_SIZE) * PAYLOAD_BLOCK_SIZE;
      byte[] payload = new byte[paddedLength];
      ByteBuffer.wrap(payload).putInt(plaintext.length);
      System.arraycopy(plaintext, 0, payload, LENGTH_PREFIX_LENGTH, plaintext.length);

      byte[] iv = new byte[BITFERRY_IV_LENGTH];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, BITFERRY_KEY_SPEC, new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(payload);

      byte[] result = new byte[BITFERRY_IV_LENGTH + ciphertext.length];
      System.arraycopy(iv, 0, result, 0, BITFERRY_IV_LENGTH);
      System.arraycopy(ciphertext, 0, result, BITFERRY_IV_LENGTH, ciphertext.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Bitferry password obscuring failure: " + e.getMessage(), e);
    }
  }

  /// Reveal a value obscured by [bitferryObscure].
  public static String bitferryReveal(String obscured) {
    if (obscured == null) throw new BitferryError("Cannot reveal null password");
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(obscured);
    } catch (IllegalArgumentException e) {
      throw new BitferryError("Invalid Bitferry obscured password - bad format", e);
    }
    if (!hasGcmShape(raw)) throw new BitferryError("Invalid Bitferry obscured password - invalid token length");
    try {
      return revealGcm(raw);
    } catch (AEADBadTagException e) {
      throw new BitferryError("Invalid Bitferry obscured password - bad format", e);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Bitferry password revealing failure: " + e.getMessage(), e);
    }
  }

  private static boolean hasGcmShape(byte[] raw) {
    return raw.length >= BITFERRY_IV_LENGTH + PAYLOAD_BLOCK_SIZE + GCM_TAG_LENGTH
      && (raw.length - BITFERRY_IV_LENGTH - GCM_TAG_LENGTH) % PAYLOAD_BLOCK_SIZE == 0;
  }

  private static String revealGcm(byte[] raw) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, BITFERRY_KEY_SPEC,
      new GCMParameterSpec(GCM_TAG_BITS, raw, 0, BITFERRY_IV_LENGTH));
    byte[] decrypted = cipher.doFinal(raw, BITFERRY_IV_LENGTH, raw.length - BITFERRY_IV_LENGTH);
    int plaintextLength = ByteBuffer.wrap(decrypted, 0, LENGTH_PREFIX_LENGTH).getInt();
    if (plaintextLength < 0 || plaintextLength > decrypted.length - LENGTH_PREFIX_LENGTH) {
      throw new BitferryError("Invalid Bitferry obscured password - invalid plaintext length");
    }
    return decodeUtf8(decrypted, LENGTH_PREFIX_LENGTH, plaintextLength);
  }

  private static String decodeUtf8(byte[] data, int offset, int length) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(data, offset, length))
        .toString();
    } catch (CharacterCodingException e) {
      throw new BitferryError("Invalid obscured password - invalid UTF-8 payload", e);
    }
  }

  private static byte[] bitferryKey() {
    try {
      return MessageDigest.getInstance("SHA-256").digest("bitferry-static-0".getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /// Obscure the value using rclone's AES-256-CTR scheme for passing to rclone's environment.
  public static String rcloneObscure(String data) {
    if (data == null) throw new BitferryError("Cannot obscure null password");
    try {
      byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);
      byte[] iv = new byte[RCLONE_IV_LENGTH];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, RCLONE_KEY_SPEC, new IvParameterSpec(iv));
      byte[] ciphertext = cipher.doFinal(plaintext);
      byte[] payload = new byte[RCLONE_IV_LENGTH + ciphertext.length];
      System.arraycopy(iv, 0, payload, 0, RCLONE_IV_LENGTH);
      System.arraycopy(ciphertext, 0, payload, RCLONE_IV_LENGTH, ciphertext.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Rclone password obscuring failure: " + e.getMessage(), e);
    }
  }

  /// Reveal a value obscured by [rcloneObscure] (or by rclone itself).
  public static String rcloneReveal(String obscured) {
    if (obscured == null) throw new BitferryError("Cannot reveal null password");
    byte[] payload;
    try {
      payload = Base64.getUrlDecoder().decode(obscured);
    } catch (IllegalArgumentException e) {
      throw new BitferryError("Invalid Rclone obscured password - is it obscured?", e);
    }
    if (payload.length < RCLONE_IV_LENGTH) throw new BitferryError("Obscured Rclone password too short - is it obscured?");
    try {
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, RCLONE_KEY_SPEC, new IvParameterSpec(payload, 0, RCLONE_IV_LENGTH));
      byte[] plaintext = cipher.doFinal(payload, RCLONE_IV_LENGTH, payload.length - RCLONE_IV_LENGTH);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new BitferryError("Rclone password revealing failure: " + e.getMessage(), e);
    }
  }
}
