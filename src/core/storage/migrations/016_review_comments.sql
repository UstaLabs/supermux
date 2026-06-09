CREATE TABLE IF NOT EXISTS review_comments (
  id              TEXT PRIMARY KEY,
  session_id      TEXT NOT NULL,
  repo            TEXT NOT NULL DEFAULT '',
  path            TEXT NOT NULL,
  side            TEXT NOT NULL DEFAULT 'RIGHT',
  base_sha        TEXT,
  head_blob_sha   TEXT,
  anchor_line     INTEGER NOT NULL,
  range_start     INTEGER,
  range_end       INTEGER,
  anchor_context  TEXT NOT NULL DEFAULT '',
  diff_hunk_header TEXT,
  parent_id       TEXT,
  body            TEXT NOT NULL,
  author          TEXT NOT NULL DEFAULT 'user',
  created_at      TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'open',
  resolved_by     TEXT,
  resolved_sha    TEXT
);
CREATE INDEX IF NOT EXISTS idx_review_comments_session ON review_comments(session_id);
