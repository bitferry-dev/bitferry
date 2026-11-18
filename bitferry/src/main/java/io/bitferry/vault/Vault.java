package io.bitferry.vault;

import io.bitferry.task.Task;

/// Password storage provider keeping the obscured task tokens out of the storage descriptors.
public interface Vault {

  /// Store the obscured token of the task.
  void storeToken(Task task, String token);

  /// Retrieve the obscured token of the task or null when none is stored.
  String retrieveToken(Task task);

  /// Drop the stored token of the task (no-op when none is stored): the stale entries
  /// must not survive the password updates and the store switches.
  void deleteToken(Task task);

  /// The per-volume storage provider (the default one): the tokens are kept in the vault
  /// section of the volume hosting the unencrypted end of the task, so the password
  /// travels along with the plaintext data. The only option for the removable media.
  static Vault volume() {
    return VolumeVault.INSTANCE;
  }

  /// The OS keyring provider (Windows Credential Manager, macOS Keychain, Linux
  /// libsecret/GNOME Keyring): the tokens are machine- and user-bound instead of
  /// traveling with the volume storage; applies to the fixed volumes only.
  static Vault keyring() {
    return KeyringVault.INSTANCE;
  }

  /// Select the storage provider for the task: the keyring when explicitly requested
  /// and usable on the host; the per-volume vault otherwise. The fixedness of the
  /// vault volume ([Volume.isFixed]) only hints the sensible default — the explicit
  /// request is honored for the removable volumes as well, with the portability
  /// tradeoff surfaced by the frontends (the retrieval chain resolves the token
  /// regardless of the store holding it).
  static Vault select(Task task, boolean keyringRequested) {
    return keyringRequested && KeyringVault.INSTANCE.available()
      ? KeyringVault.INSTANCE
      : VolumeVault.INSTANCE;
  }
}
