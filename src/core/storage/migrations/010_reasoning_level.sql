-- Per-session explicit reasoning effort override (Claude --effort, Codex model_reasoning_effort).
ALTER TABLE sessions ADD COLUMN reasoning_level TEXT;
