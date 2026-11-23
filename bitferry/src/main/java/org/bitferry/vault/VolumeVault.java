package org.bitferry.vault;

import org.bitferry.Volume;
import org.bitferry.task.Task;

/// Per-volume storage password vault: the obscured task credentials are kept in the vault
/// section of the volume hosting the unencrypted end of the task.
public final class VolumeVault implements Vault {

  public static final VolumeVault INSTANCE = new VolumeVault();

  private VolumeVault() {}

  @Override
  public void store(Task task, String password, String salt) {
    String encPassword = Passwords.bitferryObscure(password);
    String encSalt = salt != null && !salt.isEmpty() ? Passwords.bitferryObscure(salt) : null;
    task.vaultVolume().writeVault(task.tag(), new Volume.VaultEntry(encPassword, encSalt));
  }

  @Override
  public VaultCredentials retrieve(Task task) {
    Volume volume = task.vaultVolume();
    Volume.VaultEntry entry = volume.readVault(task.tag());
    if (entry == null || entry.password() == null) return null;
    String plainPassword = Passwords.bitferryReveal(entry.password());
    String plainSalt = entry.salt() != null ? Passwords.bitferryReveal(entry.salt()) : null;
    return new VaultCredentials(plainPassword, plainSalt);
  }

  @Override
  public void delete(Task task) {
    task.vaultVolume().removeVault(task.tag());
  }
}
