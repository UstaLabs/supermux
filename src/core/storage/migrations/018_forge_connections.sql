CREATE TABLE forge_connections (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL,
  host         TEXT NOT NULL,
  api_base     TEXT NOT NULL,
  login        TEXT NOT NULL,
  name         TEXT,
  avatar_url   TEXT,
  token        TEXT NOT NULL,
  source       TEXT NOT NULL,
  transport    TEXT NOT NULL DEFAULT 'https',
  ssh_key_path TEXT,
  ssh_key_id   TEXT,
  created_at   INTEGER NOT NULL
);
