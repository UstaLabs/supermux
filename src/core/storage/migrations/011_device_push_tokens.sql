-- src/core/storage/migrations/010_device_push_tokens.sql
-- Native push registration (APNs/FCM), parallel to the web push_subscriptions
-- table. One row per device install; platform selects the sender.
CREATE TABLE device_push_tokens (
  device       TEXT PRIMARY KEY,
  platform     TEXT NOT NULL CHECK (platform IN ('ios', 'android')),
  token        TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  last_used_at TEXT
);
