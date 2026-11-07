-- council_remove takes a member out of its council: it leaves the card (and its reports with it). The row
-- stays, so its letter is never handed out again and its reports keep their author.
ALTER TABLE council_members ADD COLUMN removed_at TEXT;
