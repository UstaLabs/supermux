-- src/core/storage/migrations/027_workspaces.sql
--
-- Workspaces and views. A workspace is the container the user arranges: it owns
-- a work directory, a source directory, a layout tree, and a set of views. A
-- view is a chat, a terminal, an editor, or a display.
--
-- The backfill is deliberately ONE workspace per existing session (never grouped
-- by path). A grouped move would put two agents in one container without the
-- user asking for it. One-to-one is lossless: after this migration every session
-- behaves exactly as it did before.
--
-- Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md §6

CREATE TABLE workspaces (
  id                 TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  status             TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','archived')),
  workdir            TEXT NOT NULL,
  repo_root          TEXT,
  base_branch        TEXT,
  branch             TEXT,
  layout             TEXT NOT NULL,          -- JSON LayoutNode
  active_view_id     TEXT,
  primary_session_id TEXT REFERENCES sessions(id),
  name_locked        INTEGER NOT NULL DEFAULT 0,
  sort_order         INTEGER NOT NULL DEFAULT 0,
  created_at         TEXT NOT NULL,
  archived_at        TEXT
);

CREATE TABLE views (
  id           TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  kind         TEXT NOT NULL CHECK(kind IN ('chat','terminal','editor','display')),
  title        TEXT,
  state        TEXT NOT NULL,                -- JSON ViewState
  created_at   TEXT NOT NULL
);
CREATE INDEX views_workspace ON views(workspace_id);

-- ON DELETE SET NULL, not the default NO ACTION: a hard DELETE of a workspace row
-- would otherwise be blocked by every session still pointing at it, and the views
-- CASCADE below could never fire. An orphaned session is exactly what the startup
-- self-heal repairs, so nulling the link is the honest outcome. Production never
-- hard-deletes a workspace (DELETE /workspaces/:id archives), so this is defense.
ALTER TABLE sessions ADD COLUMN workspace_id TEXT REFERENCES workspaces(id) ON DELETE SET NULL;
CREATE INDEX sessions_workspace ON sessions(workspace_id);

-- Backfill step 1: one workspace per session.
--
-- SQLite has no uuid() function. hex(randomblob(...)) is the standard way to
-- build a v4-shaped id, and it matches the dashed format randomUUID() writes
-- everywhere else in this schema.
INSERT INTO workspaces (
  id, name, status, workdir, repo_root, base_branch, branch,
  layout, primary_session_id, name_locked, sort_order, created_at, archived_at
)
SELECT
  lower(
    substr(hex(randomblob(4)), 1, 8) || '-' ||
    substr(hex(randomblob(2)), 1, 4) || '-4' ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr('89ab', abs(random()) % 4 + 1, 1) ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr(hex(randomblob(6)), 1, 12)
  ),
  s.name,
  CASE WHEN s.status = 'archived' THEN 'archived' ELSE 'active' END,
  s.workdir,
  s.repo_root,
  s.base_branch,
  s.session_branch,
  '{}',                 -- placeholder; step 4 writes the real tree
  s.id,
  0,
  s.sort_order,
  s.created_at,
  s.killed_at
FROM sessions s;

-- Backfill step 2: point each session at its workspace.
UPDATE sessions
   SET workspace_id = (SELECT w.id FROM workspaces w WHERE w.primary_session_id = sessions.id);

-- Backfill step 3: one chat view per workspace.
INSERT INTO views (id, workspace_id, kind, title, state, created_at)
SELECT
  lower(
    substr(hex(randomblob(4)), 1, 8) || '-' ||
    substr(hex(randomblob(2)), 1, 4) || '-4' ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr('89ab', abs(random()) % 4 + 1, 1) ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr(hex(randomblob(6)), 1, 12)
  ),
  w.id,
  'chat',
  NULL,
  json_object('sessionId', w.primary_session_id),
  w.created_at
FROM workspaces w;

-- Backfill step 4: a one-group layout naming that view, and the active view id.
UPDATE workspaces
   SET layout = (
         SELECT json_object(
                  'type', 'group',
                  'id', lower(
                    substr(hex(randomblob(4)), 1, 8) || '-' ||
                    substr(hex(randomblob(2)), 1, 4) || '-4' ||
                    substr(hex(randomblob(2)), 2, 3) || '-' ||
                    substr('89ab', abs(random()) % 4 + 1, 1) ||
                    substr(hex(randomblob(2)), 2, 3) || '-' ||
                    substr(hex(randomblob(6)), 1, 12)
                  ),
                  'viewIds', json_array(v.id),
                  'activeViewId', v.id
                )
           FROM views v WHERE v.workspace_id = workspaces.id
       ),
       active_view_id = (SELECT v.id FROM views v WHERE v.workspace_id = workspaces.id);
