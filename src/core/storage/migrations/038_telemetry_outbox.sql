-- Telemetry events waiting to be sent (TELEMETRY.md "How it's sent"), and the last 50 events
-- recorded, sent or not, for Settings → Telemetry → View recent events.
CREATE TABLE telemetry_outbox (
  uuid       TEXT PRIMARY KEY,
  event_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  attempts   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX telemetry_outbox_created ON telemetry_outbox(created_at);

CREATE TABLE telemetry_recent (
  uuid       TEXT PRIMARY KEY,
  event_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  sent_at    INTEGER
);
