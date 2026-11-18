- multiple backups sharing a repo: retention-policy starvation resolved (per-task maintenance entries); remains: warn on --force re-init of a co-repository task (destroys the siblings' snapshots), verify restic lock contention (concurrent backup vs prune)

- maintain a site-local list of volume lookup directories

- warn about unreachable volumes after creation

- password vault/keyring migration on radion button state change

- rollback architectural issue (GLM full)

- secure the temp password file management on restic password change

- get rid of legacy mirror, replicate refs; 1-way sync -- replicate (r) in cli