package io.bitferry.vault;

import io.bitferry.BitferryError;
import io.bitferry.task.Task;
import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.model.StoredCredential;

/// OS keyring-backed password vault (Windows Credential Manager, macOS Keychain,
/// Linux libsecret/GNOME Keyring via the credential-secure-storage adapter): the
/// obscured task tokens are machine- and user-bound instead of traveling with the
/// volume storage, so the storage backups alone never carry the secrets. The fixed
/// (non-removable) volumes are the natural fit ([Volume.isFixed] hints the default);
/// the explicit requests are honored for the removable volumes as well.
///
/// <p>The entries are keyed by the task tags mirroring the per-volume vault. Every
/// keyring miss or failure degrades to the empty result: the storage probing must
/// never wedge the processing (the retrieval chain falls back to the per-volume
/// vault before erroring).
public final class KeyringVault implements Vault {

  public static final KeyringVault INSTANCE = new KeyringVault();

  // The keyring entry namespace: user side of the service/user pair
  private static final String USER = "bitferry";
  private static final String KEY_PREFIX = "bitferry:";

  // The probed keyring store; null when the host provides none (probed once, cached)
  private volatile SecretStore<StoredCredential> store;

  private KeyringVault() {}

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
  public void storeToken(Task task, String token) {
    SecretStore<StoredCredential> store = store();
    if (store == null) throw new BitferryError("No usable operating system keyring detected");
    String key = KEY_PREFIX + task.tag();
    try {
      store.delete(key);
    } catch (RuntimeException e) {
      // The stale-entry cleanup is best-effort (a locked or broken keyring must
      // surface as the expected BitferryError, not a raw RuntimeException escaping
      // the class's failure policy); the store.add below overwrites the entry anyway
    }
    try {
      if (!store.add(key, new StoredCredential(USER, token.toCharArray()))) {
        throw new BitferryError("Keyring storage refused the token of task " + task.tag());
      }
    } catch (RuntimeException e) {
      // A locked or broken keyring must surface as the expected BitferryError
      // (mirroring the degrade-to-empty policy of the retrieval side, which here
      // means the failure is reported instead of escaping raw)
      if (e instanceof BitferryError error) throw error;
      throw new BitferryError("Keyring storage failure for task " + task.tag() + ": " + e.getMessage(), e);
    }
  }

  @Override
  public String retrieveToken(Task task) {
    SecretStore<StoredCredential> store = store();
    if (store == null) return null;
    try {
      StoredCredential credential = store.get(KEY_PREFIX + task.tag());
      return credential == null ? null : new String(credential.getPassword());
    } catch (RuntimeException e) {
      // A broken or locked keyring must not wedge the processing
      return null;
    }
  }

  @Override
  public void deleteToken(Task task) {
    SecretStore<StoredCredential> store = store();
    if (store == null) return;
    try {
      store.delete(KEY_PREFIX + task.tag());
    } catch (RuntimeException e) {
      // The stale entries are tolerated (retrieval falls back across the stores)
    }
  }
}
