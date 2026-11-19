package io.bitferry.vault;

import io.bitferry.BitferryError;

/// Credentials held by a vault for a task.
public record VaultCredentials(String password, String salt) {

  public VaultCredentials {
    if (password == null || password.isEmpty()) {
      throw new BitferryError("Password must be specified");
    }
  }
}
