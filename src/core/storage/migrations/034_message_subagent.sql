-- A user's message to one subagent is logged as a compact "↪ to <subagent>: <text>" line;
-- subagent_id says which subagent it went to, so clients can render it as such.
ALTER TABLE messages ADD COLUMN subagent_id TEXT;
