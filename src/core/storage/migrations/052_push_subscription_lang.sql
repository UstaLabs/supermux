-- #123: the web device's language, so its push previews and broker notices are worded in it (null = English).
ALTER TABLE push_subscriptions ADD COLUMN lang TEXT;
