-- #152: where a session's work comes from and where its pushes go. source_kind: new | existing | pr | local | reuse
-- (null = made before #152). A pull/merge request is kept by its forge (github | gitlab), number and URL; push_remote
-- is the remote its pushes go to (a fork's own). created_by is who asked for it, as JSON ({"kind":"device","name":…}):
-- the team rule, the actor goes with what it made.
ALTER TABLE sessions ADD COLUMN source_kind TEXT;
ALTER TABLE sessions ADD COLUMN pr_forge TEXT;
ALTER TABLE sessions ADD COLUMN pr_number INTEGER;
ALTER TABLE sessions ADD COLUMN pr_url TEXT;
ALTER TABLE sessions ADD COLUMN push_remote TEXT;
ALTER TABLE sessions ADD COLUMN created_by TEXT;
-- #152 B4: a person's own choices, per project folder ('' = everywhere): the launcher's Local / Worktree, the new
-- branch prefix. Keyed by the user, never by the device ('owner' until users exist).
CREATE TABLE user_prefs (
  user_id    TEXT NOT NULL,
  scope      TEXT NOT NULL,
  key        TEXT NOT NULL,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (user_id, scope, key)
);
