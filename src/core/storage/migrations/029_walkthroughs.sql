CREATE TABLE IF NOT EXISTS walkthroughs (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  title TEXT NOT NULL,
  base_spec TEXT NOT NULL,
  revision INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  is_current INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS walkthrough_steps (
  id TEXT PRIMARY KEY,
  walkthrough_id TEXT NOT NULL,
  ord INTEGER NOT NULL,
  title TEXT NOT NULL,
  body_md TEXT NOT NULL,
  repo TEXT,
  path TEXT,
  side TEXT NOT NULL DEFAULT 'RIGHT',
  anchor_line INTEGER,
  range_start INTEGER,
  range_end INTEGER,
  anchor_context TEXT,
  anchor_status TEXT NOT NULL DEFAULT 'ok'
);
CREATE INDEX IF NOT EXISTS idx_walkthroughs_session ON walkthroughs(session_id);
CREATE INDEX IF NOT EXISTS idx_walkthrough_steps_wt ON walkthrough_steps(walkthrough_id);
