-- src/core/storage/migrations/017_read_status_drafts.sql
--
-- Server-side, global (cross-device) read status and synced composer drafts.
-- Both are a single value per session and die with the session row, so they
-- live as columns on `sessions` rather than a per-device table.

ALTER TABLE sessions ADD COLUMN last_read_at TEXT;  -- ISO ts of the newest message considered read
ALTER TABLE sessions ADD COLUMN draft TEXT;         -- unsent composer text; NULL when empty
