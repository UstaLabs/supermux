-- Core-backed worker rows (pid 0, no tmux/hooks/transcript tailer).
-- 0 = tmux/legacy path, 1 = supermux-core host path.
ALTER TABLE sessions ADD COLUMN core INTEGER NOT NULL DEFAULT 0;
