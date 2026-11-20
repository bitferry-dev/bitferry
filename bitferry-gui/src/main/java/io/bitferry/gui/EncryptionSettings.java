package io.bitferry.gui;

/// The encryption and vault state captured by the security dialog and held by the
/// task form: the crypt mode and options specifier (the rclone family), the
/// context-dependent password (the crypt password for the rclone tasks, the
/// repository password for the restic ones) with its confirmation entry, the
/// optional crypt salt (the filename encryption secondary password; the restic
/// backend keeps none) with its confirmation entry, and the password-storage
/// request. The plain secrets live in the memory only (the same exposure as the
/// task form's password fields before the refactor); the confirmations ride along
/// so the reopened dialog validates against the same entries; the profile text
/// stays the raw specifier (@-profile or comma options), so the apply-side
/// resolution path is unchanged.
///
/// @param mode            the crypt mode ("encrypt" | "decrypt") or null for none
/// @param profileText     the raw encryption options specifier (empty when unset)
/// @param password        the entered password (empty when none entered)
/// @param passwordConfirm the password confirmation entry (empty when none entered)
/// @param salt            the entered crypt salt (empty when none entered)
/// @param saltConfirm     the salt confirmation entry (empty when none entered)
/// @param keyring         the password-storage request: the OS keyring when true,
///                        the volume vault otherwise
record EncryptionSettings(String mode, String profileText, String password, String passwordConfirm,
                          String salt, String saltConfirm, boolean keyring) {

  /// The unset state: no encryption, no options, no secrets, the volume vault.
  static final EncryptionSettings NONE = new EncryptionSettings(null, "", "", "", "", "", false);

  EncryptionSettings {
    if (profileText == null) profileText = "";
    if (password == null) password = "";
    if (passwordConfirm == null) passwordConfirm = "";
    if (salt == null) salt = "";
    if (saltConfirm == null) saltConfirm = "";
  }

  boolean encrypted() {
    return mode != null && !mode.isBlank() && !"(none)".equals(mode);
  }
}
