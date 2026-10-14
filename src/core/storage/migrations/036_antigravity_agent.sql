-- Allow 'antigravity' (Google's agy CLI) as a session agent kind.
--
-- Same rebuild as 024_grok_agent.sql: SQLite cannot alter a CHECK constraint, so sessions is
-- recreated. runMigrations turns foreign_keys OFF around the transaction; defer_foreign_keys is
-- kept for parity with 013/024. The column list folds in everything 025-035 added via ALTER TABLE
-- (self_renamed, user_status+CHECK, sort_order, draft_payload, workspace_id FK, prompts, core,
-- permission_mode, account), and both indexes are recreated, so the table matches the live schema.
PRAGMA defer_foreign_keys=ON;

CREATE TABLE sessions_new (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  status            TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','suspended','archived')),
  agent             TEXT NOT NULL CHECK(agent IN ('claude','codex','cursor','opencode','grok','antigravity')),
  workdir           TEXT NOT NULL,
  model             TEXT,
  mute              INTEGER NOT NULL DEFAULT 0,
  can_orchestrate   INTEGER NOT NULL DEFAULT 0,
  tmux_target       TEXT,
  agent_session_id  TEXT,
  agent_home        TEXT,
  created_at        TEXT NOT NULL,
  killed_at         TEXT,
  base_commit       TEXT,
  base_commits      TEXT,
  role              TEXT NOT NULL DEFAULT 'worker' CHECK(role IN ('personal_assistant','worker')),
  is_default        INTEGER NOT NULL DEFAULT 0,
  reasoning_level   TEXT,
  tmux_window_id    TEXT,
  repo_root         TEXT,
  base_branch       TEXT,
  session_branch    TEXT,
  last_read_at      TEXT,
  draft             TEXT,
  finish_job        TEXT,
  internal          INTEGER NOT NULL DEFAULT 0,
  self_renamed      INTEGER NOT NULL DEFAULT 0,
  user_status       TEXT NOT NULL DEFAULT 'in_progress' CHECK(user_status IN ('draft','in_progress','settled')),
  sort_order        INTEGER NOT NULL DEFAULT 0,
  draft_payload     TEXT,
  workspace_id      TEXT REFERENCES workspaces(id) ON DELETE SET NULL,
  prompts           INTEGER NOT NULL DEFAULT 0,
  core              INTEGER NOT NULL DEFAULT 0,
  permission_mode   TEXT,
  account           TEXT
);

INSERT INTO sessions_new (
  id, name, status, agent, workdir, model, mute, can_orchestrate, tmux_target, agent_session_id,
  agent_home, created_at, killed_at, base_commit, base_commits, role, is_default, reasoning_level,
  tmux_window_id, repo_root, base_branch, session_branch, last_read_at, draft, finish_job, internal,
  self_renamed, user_status, sort_order, draft_payload, workspace_id, prompts, core, permission_mode, account
)
SELECT
  id, name, status, agent, workdir, model, mute, can_orchestrate, tmux_target, agent_session_id,
  agent_home, created_at, killed_at, base_commit, base_commits, role, is_default, reasoning_level,
  tmux_window_id, repo_root, base_branch, session_branch, last_read_at, draft, finish_job, internal,
  self_renamed, user_status, sort_order, draft_payload, workspace_id, prompts, core, permission_mode, account
FROM sessions;

DROP TABLE sessions;
ALTER TABLE sessions_new RENAME TO sessions;

CREATE UNIQUE INDEX sessions_name_active ON sessions(name) WHERE status != 'archived';
CREATE INDEX sessions_workspace ON sessions(workspace_id);
