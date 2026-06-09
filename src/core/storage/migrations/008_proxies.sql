-- Persist exposed proxies so they survive broker restarts (deploys).
-- A proxy is owned by its session (by UUID, not name) and is restored on boot
-- iff its session is still active/suspended; orphans are pruned during reconcile.
CREATE TABLE proxies (
  domain      TEXT PRIMARY KEY,
  session_id  TEXT NOT NULL,
  port        INTEGER NOT NULL,
  created_at  TEXT NOT NULL
);
CREATE INDEX idx_proxies_session ON proxies(session_id);
