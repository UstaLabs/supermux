-- A picture's or clip's DISPLAYED size and a clip's length, read from the file's own header
-- (src/core/files/media-meta.ts) when it is stored, or on the first request for a file stored
-- before this existed. media_probed = 1 once the header has been read, found or not, so a file is
-- never read twice.
ALTER TABLE attachments ADD COLUMN width INTEGER;
ALTER TABLE attachments ADD COLUMN height INTEGER;
ALTER TABLE attachments ADD COLUMN duration_ms INTEGER;
ALTER TABLE attachments ADD COLUMN media_probed INTEGER NOT NULL DEFAULT 0;
-- The picture the sending phone made of a clip: another row of this table.
ALTER TABLE attachments ADD COLUMN poster_file_id TEXT;
