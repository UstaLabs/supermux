-- Per-proxy opt-in: skip device-token auth on the subdomain when is_public = 1.
ALTER TABLE proxies ADD COLUMN is_public INTEGER NOT NULL DEFAULT 0;
