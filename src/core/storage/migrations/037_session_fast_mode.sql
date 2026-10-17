-- Per-session fast mode (Claude's fastMode setting, Codex's Fast service tier).
-- 1 = asked for; it only takes effect on a model that supports it.
ALTER TABLE sessions ADD COLUMN fast_mode INTEGER NOT NULL DEFAULT 0;
