-- Internal worker sessions (agent-RPC) are hidden from all user-facing
-- enumerations. 0 = normal user session, 1 = internal worker.
ALTER TABLE sessions ADD COLUMN internal INTEGER NOT NULL DEFAULT 0;
