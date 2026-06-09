-- src/core/storage/migrations/002_push_subscriptions.sql

CREATE TABLE push_subscriptions (
  device       TEXT PRIMARY KEY,
  endpoint     TEXT NOT NULL,
  p256dh       TEXT NOT NULL,
  auth         TEXT NOT NULL,
  user_agent   TEXT,
  created_at   TEXT NOT NULL,
  last_used_at TEXT
);
