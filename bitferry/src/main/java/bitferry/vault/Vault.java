package bitferry.vault;

import bitferry.task.Task;

/// Password storage provider keeping the obscured task tokens out of the storage descriptors.
public interface Vault {

  /// Store the obscured token of the task.
  void storeToken(Task task, String token);

  /// Retrieve the obscured token of the task or null when none is stored.
  String retrieveToken(Task task);

  /// The per-volume storage provider (the default one): the tokens are kept in the vault
  /// section of the volume hosting the unencrypted end of the task, so the password
  /// travels along with the plaintext data.
  static Vault volume() {
    return VolumeVault.INSTANCE;
  }
}
