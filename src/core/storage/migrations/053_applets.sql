-- #117: applets, agent-built personal web tools that supermux owns, serves and keeps running. One row per folder in
-- ~/.mux/applets/<name>/. No session id on purpose: closing or killing a session never touches an applet. `id` never
-- changes; `name` is the folder and the /a/<name>/ route, and can be renamed. `updated_by` records who changed it
-- (device:<name>, agent:<session id>, host). A deleted applet keeps its row for 30 days (deleted_at), with its folder.
CREATE TABLE IF NOT EXISTS applets (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  title       TEXT NOT NULL,
  icon        TEXT,
  status      TEXT NOT NULL DEFAULT 'ok',
  updated_by  TEXT,
  updated_at  TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  deleted_at  TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_applets_live_name ON applets(name) WHERE deleted_at IS NULL;
