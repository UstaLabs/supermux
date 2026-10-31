-- Council (spec docs/superpowers/specs/2026-10-28-session-inbox-delegate-council-design.md §5, §6).
-- Beyond §6: councils.call_id (the caller's tool call the card sits on) and council_reports.id
-- (council_report returns it).
CREATE TABLE councils (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  caller_session_id TEXT NOT NULL,
  question          TEXT NOT NULL,
  current_round     INTEGER NOT NULL DEFAULT 1,
  status            TEXT NOT NULL DEFAULT 'open',
  call_id           TEXT,
  created_at        TEXT NOT NULL
);
CREATE INDEX idx_councils_caller ON councils(caller_session_id);
CREATE TABLE council_members (
  council_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  letter     TEXT NOT NULL,
  PRIMARY KEY (council_id, letter)
);
CREATE UNIQUE INDEX idx_council_members_session ON council_members(session_id);
CREATE TABLE council_rounds (
  council_id   TEXT NOT NULL,
  round        INTEGER NOT NULL,
  focus        TEXT,
  started_at   TEXT NOT NULL,
  completed_at TEXT,
  PRIMARY KEY (council_id, round)
);
CREATE TABLE council_reports (
  id                TEXT PRIMARY KEY,
  council_id        TEXT NOT NULL,
  round             INTEGER NOT NULL,
  member_session_id TEXT NOT NULL,
  seq               INTEGER NOT NULL,
  text              TEXT NOT NULL,
  at                TEXT NOT NULL
);
CREATE INDEX idx_council_reports_council ON council_reports(council_id, round, seq);
CREATE TABLE council_member_status (
  council_id        TEXT NOT NULL,
  round             INTEGER NOT NULL,
  member_session_id TEXT NOT NULL,
  status            TEXT NOT NULL,
  at                TEXT NOT NULL,
  PRIMARY KEY (council_id, round, member_session_id)
);
-- A forwarded report in the caller's log carries its council, round and member letter (§5.1).
ALTER TABLE messages ADD COLUMN council_id TEXT;
ALTER TABLE messages ADD COLUMN council_round INTEGER;
ALTER TABLE messages ADD COLUMN council_letter TEXT;
