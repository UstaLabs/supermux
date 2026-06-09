-- De-personalize identity: replace name==='dockie' special-casing with a role model.
ALTER TABLE sessions ADD COLUMN role TEXT NOT NULL DEFAULT 'worker'
  CHECK(role IN ('personal_assistant','worker'));
ALTER TABLE sessions ADD COLUMN is_default INTEGER NOT NULL DEFAULT 0;

-- Backfill the owner's existing main session (historically the literal name 'dockie')
-- as the personal assistant + default. The session keeps its name; only the
-- special-casing-by-name goes away. Other existing rows stay role='worker'.
UPDATE sessions SET role = 'personal_assistant', is_default = 1
  WHERE name = 'dockie' AND status != 'archived';

-- Guard: at most one default — keep the oldest if several got flagged.
UPDATE sessions SET is_default = 0
  WHERE is_default = 1
    AND id NOT IN (SELECT id FROM sessions WHERE is_default = 1 ORDER BY created_at ASC LIMIT 1);
