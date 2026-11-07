-- The session that created this one: delegate and council children
-- (spec docs/superpowers/specs/2026-10-28-session-inbox-delegate-council-design.md §3).
-- NULL for every top-level session. Killing a session kills its children (§4).
ALTER TABLE sessions ADD COLUMN parent_session_id TEXT;
