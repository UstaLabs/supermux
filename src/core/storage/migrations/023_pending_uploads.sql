-- In-flight chunked/resumable uploads. A row is created by POST /upload/init and
-- deleted when the upload finalizes (its bytes move into `attachments`) or when
-- gcPendingOnce reaps an abandoned partial past its TTL. `path` is the
-- <upload_id>.part file; `received` mirrors that file's byte length.
CREATE TABLE pending_uploads (
  upload_id  TEXT PRIMARY KEY,
  session    TEXT NOT NULL,
  kind       TEXT NOT NULL,
  mime       TEXT,
  name       TEXT,
  total_size INTEGER NOT NULL,
  received   INTEGER NOT NULL DEFAULT 0,
  path       TEXT NOT NULL,
  origin     TEXT NOT NULL,
  device     TEXT,
  created_at TEXT NOT NULL
);
