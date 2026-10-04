-- Per-session account id (supermux-core accounts; slice A3a). NULL = the agent's
-- system account (the CLI's own login, today's behaviour). Display mirror of the
-- core session record, which stays authoritative for launches.
ALTER TABLE sessions ADD COLUMN account TEXT;
