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
  deleted_at  TEXT,
  -- Set while the owner has stopped it (Applet info ▸ Stop): no server, no jobs, its page says so.
  stopped_at  TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_applets_live_name ON applets(name) WHERE deleted_at IS NULL;

-- An applet's secret VALUES (applet.json only names them). Given to its server and jobs as environment variables;
-- never shown to an agent. `expires_at` lets an applet's page say "Needs you" before a sign-in runs out.
CREATE TABLE IF NOT EXISTS applet_secrets (
  applet_id   TEXT NOT NULL,
  name        TEXT NOT NULL,
  value       TEXT NOT NULL,
  expires_at  TEXT,
  updated_by  TEXT,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (applet_id, name)
);

-- The values chosen in an applet's Settings (applet.json's `config` holds only their shape).
CREATE TABLE IF NOT EXISTS applet_settings (
  applet_id   TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       TEXT NOT NULL,
  updated_by  TEXT,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (applet_id, key)
);

-- One row per job run (scheduled or "Run now"): when, how it ended, and the tail of what it printed.
CREATE TABLE IF NOT EXISTS applet_job_runs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  applet_id    TEXT NOT NULL,
  job          TEXT NOT NULL,
  trigger      TEXT NOT NULL,
  started_at   TEXT NOT NULL,
  finished_at  TEXT,
  exit_code    INTEGER,
  output       TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_applet_job_runs ON applet_job_runs(applet_id, job, started_at);
