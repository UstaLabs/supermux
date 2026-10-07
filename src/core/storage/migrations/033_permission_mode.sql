-- Per-session permission mode (catalog id in src/core/agents/permission-modes.ts).
-- NULL = the agent's catalog default. The prompts INTEGER column is kept but
-- nothing reads it anymore; this backfill is the last consumer.
ALTER TABLE sessions ADD COLUMN permission_mode TEXT;

UPDATE sessions SET permission_mode = CASE
  WHEN prompts = 1 AND agent = 'codex' THEN 'ask'
  WHEN prompts = 1 THEN 'ask'
  ELSE NULL
END;
