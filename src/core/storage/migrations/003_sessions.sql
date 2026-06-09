-- src/core/storage/migrations/003_sessions.sql

CREATE TABLE sessions (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  status            TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','suspended','archived')),
  agent             TEXT NOT NULL CHECK(agent IN ('claude','codex','cursor')),
  workdir           TEXT NOT NULL,
  model             TEXT,
  mute              INTEGER NOT NULL DEFAULT 0,
  can_orchestrate   INTEGER NOT NULL DEFAULT 0,
  tmux_target       TEXT,
  agent_session_id  TEXT,
  agent_home        TEXT,
  created_at        TEXT NOT NULL,
  killed_at         TEXT
);

CREATE UNIQUE INDEX sessions_name_active
  ON sessions(name) WHERE status != 'archived';

CREATE TABLE chats (
  chat_id            TEXT PRIMARY KEY,
  active_session_id  TEXT REFERENCES sessions(id)
);

CREATE TABLE chat_history (
  chat_id     TEXT NOT NULL REFERENCES chats(chat_id) ON DELETE CASCADE,
  session_id  TEXT NOT NULL REFERENCES sessions(id),
  position    INTEGER NOT NULL,
  PRIMARY KEY (chat_id, session_id)
);

DELETE FROM messages;
ALTER TABLE messages ADD COLUMN session_id TEXT REFERENCES sessions(id);
CREATE INDEX messages_session_id_ts ON messages(session_id, ts);
