-- Agent-returned errors are stored as regular outbound chat messages, flagged
-- with error=1 so clients can render them distinctly (icon/style) instead of
-- the broker baking decorations into the text.
ALTER TABLE messages ADD COLUMN error INTEGER NOT NULL DEFAULT 0;
