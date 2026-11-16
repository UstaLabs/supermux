-- #144: the "Compacted" line in a chat: {status, before?, after?, trigger?, note?, summary?} as JSON, like a card.
ALTER TABLE messages ADD COLUMN compaction TEXT;
