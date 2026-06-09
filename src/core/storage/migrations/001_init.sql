-- src/core/storage/migrations/001_init.sql

CREATE TABLE messages (
  id          TEXT PRIMARY KEY,
  session     TEXT NOT NULL,
  ts          TEXT NOT NULL,
  direction   TEXT NOT NULL,
  channel     TEXT NOT NULL,
  chat_id     TEXT NOT NULL,
  message_id  TEXT,
  op          TEXT,
  text        TEXT,
  edited_at   TEXT,
  attachments TEXT,
  reactions   TEXT
);
CREATE INDEX messages_session_ts ON messages(session, ts);

CREATE TABLE attachments (
  file_id    TEXT PRIMARY KEY,
  kind       TEXT NOT NULL,
  mime       TEXT,
  size       INTEGER,
  name       TEXT,
  path       TEXT NOT NULL,
  origin     TEXT NOT NULL,
  session    TEXT,
  device     TEXT,
  created_at TEXT NOT NULL,
  ref_count  INTEGER NOT NULL DEFAULT 0
);
