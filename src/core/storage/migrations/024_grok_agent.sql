-- Allow 'grok' as a session agent kind.
--
-- Same rebuild dance as 013_opencode_agent.sql: SQLite cannot modify a CHECK
-- constraint in place, so the sessions table is recreated with the widened
-- constraint. `defer_foreign_keys=ON` lets this run inside the migration
-- transaction (a plain `foreign_keys=OFF` is a no-op once a transaction is
-- open). All rows are copied before the drop, so every reference into
-- sessions(id) is consistent again by COMMIT.
--
-- The column list below folds in everything 014-023 added via ALTER TABLE
-- (tmux_window_id, repo_root, base_branch, session_branch, last_read_at, draft,
-- finish_job, internal), so the rebuilt table matches the live schema exactly.
PRAGMA defer_foreign_keys=ON;

CREATE TABLE sessions_new (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  status            TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','suspended','archived')),
  agent             TEXT NOT NULL CHECK(agent IN ('claude','codex','cursor','opencode','grok')),
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
  internal          INTEGER NOT NULL DEFAULT 0
);

INSERT INTO sessions_new
  SELECT id, name, status, agent, workdir, model, mute, can_orchestrate,
         tmux_target, agent_session_id, agent_home, created_at, killed_at,
         base_commit, base_commits, role, is_default, reasoning_level,
         tmux_window_id, repo_root, base_branch, session_branch, last_read_at,
         draft, finish_job, internal
  FROM sessions;

DROP TABLE sessions;
ALTER TABLE sessions_new RENAME TO sessions;

CREATE UNIQUE INDEX sessions_name_active
  ON sessions(name) WHERE status != 'archived';
