-- Derived, rebuildable keyword search over the ~/.mux knowledge base and past
-- sessions. Markdown files + the messages table remain the source of truth;
-- these FTS5 tables are wiped and repopulated by SearchStore.

-- Knowledge: one row per markdown section (heading + body).
-- scope: 'domain' | 'digest' | 'personal' | 'conventions'
-- is_personal: 1 for personal/ + soul.md content (hidden from worker queries).
CREATE VIRTUAL TABLE memory_fts USING fts5(
  scope UNINDEXED,
  name,
  heading,
  body,
  path UNINDEXED,
  is_personal UNINDEXED
);

-- Sessions: one row per broker message (user-facing text only).
CREATE VIRTUAL TABLE session_fts USING fts5(
  session_id UNINDEXED,
  ts UNINDEXED,
  text
);
