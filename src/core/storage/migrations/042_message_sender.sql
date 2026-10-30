-- Who sent an inbox message, as it was when it was delivered (decision #19, 2026-10-30): clients show
-- the sender's name and agent without a lookup (a hidden child or a council is in no session list).
ALTER TABLE messages ADD COLUMN from_session_name TEXT;
ALTER TABLE messages ADD COLUMN from_agent TEXT;
