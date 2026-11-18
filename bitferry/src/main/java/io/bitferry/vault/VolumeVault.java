package io.bitferry.vault;

import io.bitferry.Volume;
import io.bitferry.task.Task;

/// Per-volume storage password vault: the obscured task tokens are kept in the vault
/// section of the volume hosting the unencrypted end of the task.
public final class VolumeVault implements Vault {

  public static final VolumeVault INSTANCE = new VolumeVault();

  private VolumeVault() {}

  @Override
  public void storeToken(Task task, String token) {
    task.vaultVolume().writeVault(task.tag(), token);
  }

  @Override
  public String retrieveToken(Task task) {
    Volume volume = task.vaultVolume();
    return volume.readVault(task.tag());
  }

  @Override
  public void deleteToken(Task task) {
    task.vaultVolume().removeVault(task.tag());
  }
}
