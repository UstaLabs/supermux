-- Allow 'browser' (#105: a workspace's Browser tab) as a view kind.
--
-- Same rebuild as 036_antigravity_agent.sql: SQLite cannot alter a CHECK constraint, so views is
-- recreated with its six columns, its workspace FK (ON DELETE CASCADE) and its index. Nothing
-- references views, so no other table changes.
PRAGMA defer_foreign_keys=ON;

CREATE TABLE views_new (
  id           TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  kind         TEXT NOT NULL CHECK(kind IN ('chat','terminal','editor','display','browser')),
  title        TEXT,
  state        TEXT NOT NULL,                -- JSON ViewState
  created_at   TEXT NOT NULL
);

INSERT INTO views_new (id, workspace_id, kind, title, state, created_at)
SELECT id, workspace_id, kind, title, state, created_at FROM views;

DROP TABLE views;
ALTER TABLE views_new RENAME TO views;

CREATE INDEX views_workspace ON views(workspace_id);
