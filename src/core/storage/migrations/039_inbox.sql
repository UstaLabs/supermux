-- Session inbox (spec docs/superpowers/specs/2026-10-28-session-inbox-delegate-council-design.md §1).
-- The messages live in ~/.mux/session_inboxes/<session-id>.jsonl. The database keeps only how far
-- supermux has delivered each inbox, and which session sent a delivered message.
CREATE TABLE inbox_delivery (
  session_id        TEXT PRIMARY KEY,
  last_delivered_id TEXT NOT NULL,
  updated_at        TEXT NOT NULL
);
ALTER TABLE messages ADD COLUMN from_session_id TEXT;
