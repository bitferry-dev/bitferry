package io.bitferry.vault;

import io.bitferry.BitferryError;
import io.bitferry.task.Task;
import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.model.StoredCredential;

/// OS keyring-backed password vault (Windows Credential Manager, macOS Keychain,
/// Linux libsecret/GNOME Keyring via the credential-secure-storage adapter): the
/// task credentials are machine- and user-bound instead of traveling with the
/// volume storage, so the storage backups alone never carry the secrets. The fixed
/// (non-removable) volumes are the natural fit ([Volume.isFixed] hints the default);
/// the explicit requests are honored for the removable volumes as well.
///
/// <p>The entries are keyed by 'bitferry:<tag>:password' and 'bitferry:<tag>:salt'
/// containing the plaintext secrets. Every keyring miss or failure degrades to the
/// empty result: the storage probing must never wedge the processing (the retrieval
/// chain falls back to the per-volume vault before erroring).
public final class KeyringVault implements Vault {

  public static final KeyringVault INSTANCE = new KeyringVault();

  // The keyring entry namespace: user side of the service/user pair
  private static final String USER = "bitferry";
  private static final String KEY_PREFIX = "bitferry:";
  private static final String KEY_PASSWORD_SUFFIX = ":password";
  private static final String KEY_SALT_SUFFIX = ":salt";

  // The probed keyring store; null when the host provides none (probed once, cached)
  private volatile SecretStore<StoredCredential> store;

  private KeyringVault() {}

  public static String passwordKey(String tag) {
    return KEY_PREFIX + tag + KEY_PASSWORD_SUFFIX;
  }

  public static String saltKey(String tag) {
    return KEY_PREFIX + tag + KEY_SALT_SUFFIX;
  }

  /// Whether the host provides a usable (secure, persisted) OS keyring.
  public boolean available() {
    return store() != null;
  }

  // Probe the keyring store once: the persisted and secure store is required — the
  // in-memory fallback of the adapter is refused (it would silently lose the tokens
  // upon the exit defeating the storage purpose)
  private SecretStore<StoredCredential> store() {
    if (store == null) {
      synchronized (this) {
        if (store == null) {
          try {
            store = StorageProvider.getCredentialStorage(true, StorageProvider.SecureOption.REQUIRED);
          } catch (RuntimeException e) {
            // No usable keyring on the host; the volume vault keeps serving
            return null;
          }
        }
      }
    }
    return store;
  }

  @Override
  public void store(Task task, String password, String salt) {
    SecretStore<StoredCredential> store = store();
    if (store == null) throw new BitferryError("No usable operating system keyring detected");

    String pwKey = passwordKey(task.tag());
    try {
      store.delete(pwKey);
    } catch (RuntimeException ignored) {
      // The stale-entry cleanup is best-effort
    }
    try {
      if (!store.add(pwKey, new StoredCredential(USER, password.toCharArray()))) {
        throw new BitferryError("Keyring storage refused the password of task " + task.tag());
      }
    } catch (RuntimeException e) {
      if (e instanceof BitferryError error) throw error;
      throw new BitferryError("Keyring storage failure for task " + task.tag() + ": " + e.getMessage(), e);
    }

    String sKey = saltKey(task.tag());
    if (salt != null && !salt.isEmpty()) {
      try {
        store.delete(sKey);
      } catch (RuntimeException ignored) {}
      try {
        if (!store.add(sKey, new StoredCredential(USER, salt.toCharArray()))) {
          throw new BitferryError("Keyring storage refused the salt of task " + task.tag());
        }
      } catch (RuntimeException e) {
        if (e instanceof BitferryError error) throw error;
        throw new BitferryError("Keyring storage failure for salt of task " + task.tag() + ": " + e.getMessage(), e);
      }
    } else {
      try {
        store.delete(sKey);
      } catch (RuntimeException ignored) {}
    }
  }

  @Override
  public VaultCredentials retrieve(Task task) {
    SecretStore<StoredCredential> store = store();
    if (store == null) return null;
    try {
      StoredCredential pwCred = store.get(passwordKey(task.tag()));
      if (pwCred == null) return null;
      String password = new String(pwCred.getPassword());
      StoredCredential saltCred = store.get(saltKey(task.tag()));
      String salt = saltCred == null ? null : new String(saltCred.getPassword());
      return new VaultCredentials(password, salt);
    } catch (RuntimeException e) {
      // A broken or locked keyring must not wedge the processing
      return null;
    }
  }

  @Override
  public void delete(Task task) {
    SecretStore<StoredCredential> store = store();
    if (store == null) return;
    try {
      store.delete(passwordKey(task.tag()));
    } catch (RuntimeException ignored) {}
    try {
      store.delete(saltKey(task.tag()));
    } catch (RuntimeException ignored) {}
  }
}
