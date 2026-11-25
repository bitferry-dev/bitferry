package org.bitferry.gui;

import org.bitferry.task.*;
import java.util.*;

/// Snapshot of a backup task's service configuration edited in the Service dialog:
/// scheduling cadence plus the per-phase option lists (`check`, `forget`, `prune`).
/// Empty lists normalize to null (omitted in storage): `check` and `prune` run
/// unconditionally with no extra flags, while `forget` is skipped when null.
record ServiceSettings(Cadence cadence, List<String> check, List<String> forget, List<String> prune) {

  static final ServiceSettings DEFAULTS = new ServiceSettings(
    Cadence.PROFILES.get("default"),
    Backup.CHECK.get("default"),
    Backup.FORGET.get("default"),
    Backup.PRUNE.get("default")
  );

  ServiceSettings {
    check = check == null || check.isEmpty() ? null : List.copyOf(check);
    forget = forget == null || forget.isEmpty() ? null : List.copyOf(forget);
    prune = prune == null || prune.isEmpty() ? null : List.copyOf(prune);
  }
}
