-- Per-session opt-in for permission prompts (default off = auto-approve as today).
-- 0 = off, 1 = on.
ALTER TABLE sessions ADD COLUMN prompts INTEGER NOT NULL DEFAULT 0;
