-- Who sent an inbox message (decision #19, 2026-10-30): the sender's name when it sent it, and the sending
-- session's agent, looked up at hand-over. Clients show them without a lookup (a hidden child or a council is in no session list).
ALTER TABLE messages ADD COLUMN from_session_name TEXT;
ALTER TABLE messages ADD COLUMN from_agent TEXT;
