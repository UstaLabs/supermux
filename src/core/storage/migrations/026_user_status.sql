-- src/core/storage/migrations/026_user_status.sql
--
-- Task-list model. `user_status` is the user-facing task state, independent of
-- the lifecycle `status` column:
--   draft       — a saved launcher plan; NO agent process runs. draft_payload
--                 holds the composer { text, attachments }.
--   in_progress — a normal running/suspended session (today's behaviour).
--   settled     — user marked it done; ALWAYS implies status='archived'.
-- sort_order orders items within a (workdir, user_status) section; lower first.

ALTER TABLE sessions ADD COLUMN user_status TEXT NOT NULL DEFAULT 'in_progress'
  CHECK(user_status IN ('draft','in_progress','settled'));
ALTER TABLE sessions ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sessions ADD COLUMN draft_payload TEXT;  -- JSON { text?, attachments? }; NULL unless draft

-- Existing archived sessions are treated as settled (brainstorm decision).
UPDATE sessions SET user_status = 'settled' WHERE status = 'archived';
