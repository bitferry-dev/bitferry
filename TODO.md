- multiple backups sharing a repo: retention-policy starvation resolved (per-task maintenance entries); remains: warn on --force re-init of a co-repository task (destroys the siblings' snapshots), verify restic lock contention (concurrent backup vs prune)

- warn about unreachable volumes after creation

- restic restore ergonomics - select the snapshot tag (or latest)

- select task id for restore on restore selection

- what about disabling the restic repo sharing altogether?