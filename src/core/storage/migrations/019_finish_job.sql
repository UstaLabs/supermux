-- Persist the in-flight / last finish job per session (JSON), so a finish that
-- runs while the client is backgrounded is observable on reconnect / after restart.
ALTER TABLE sessions ADD COLUMN finish_job TEXT;
