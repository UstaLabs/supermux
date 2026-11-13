-- #122 phase 4: a Browser tab's comments, one row each, in the session they were sent to; an agent answers each.
CREATE TABLE IF NOT EXISTS browser_comments (
  id          TEXT PRIMARY KEY,
  session_id  TEXT NOT NULL,
  batch_id    TEXT NOT NULL,
  number      INTEGER NOT NULL,
  url         TEXT,
  viewed_as   TEXT,
  selector    TEXT NOT NULL,
  text        TEXT NOT NULL DEFAULT '',
  comment     TEXT NOT NULL,
  picture     TEXT,
  status      TEXT NOT NULL DEFAULT 'sent',
  note        TEXT,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_browser_comments_session ON browser_comments(session_id);
