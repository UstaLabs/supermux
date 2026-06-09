-- Generic key→JSON settings, source of truth for UI-editable broker config
-- (currently the nightly curator: enable / daily time / target chat).
CREATE TABLE settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
