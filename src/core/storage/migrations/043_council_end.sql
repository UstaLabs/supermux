-- A council ends (docs/2026-10-31-council-lifecycle-design.md §3): its caller ends it with a verdict
-- (council_end), the user ends it (Stop council), or its last member is gone. status: 'open' | 'ended'.
ALTER TABLE councils ADD COLUMN ended_by TEXT;
ALTER TABLE councils ADD COLUMN verdict TEXT;
ALTER TABLE councils ADD COLUMN ended_at TEXT;
-- Councils from before had no end: every one of them is over now, so nothing watches its members again.
UPDATE councils SET status = 'ended', ended_by = 'empty', ended_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE status = 'open';
