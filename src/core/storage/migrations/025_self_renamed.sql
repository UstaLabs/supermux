-- Tracks whether a session's agent has already used its one allowed self-rename.
-- 0 = not yet renamed (the agent may rename once), 1 = already renamed (further
-- name changes via the rename_session tool are rejected).
ALTER TABLE sessions ADD COLUMN self_renamed INTEGER NOT NULL DEFAULT 0;
