-- #163: what may notify a person, per ACCOUNT (never per device: the same on every device they use). One row per user
-- ("owner" until users exist); no row = a new account's defaults (agents all, sessions needs, applets on). A level is
-- all | needs | off (applets: on | off); "needs" lets through only what blocks an agent (a question, a permission
-- prompt) or an applet notice marked needsYou. needs_you: those break through Off. notices: supermux's own (a worktree
-- finished, the memory tidy-up). show_tip: the one-time "Sessions: Needs me" offer for an account from before #163.
-- updated_by is the actor ({"kind":"device","name":…}), the team rule.
CREATE TABLE notification_prefs (
  user_id    TEXT PRIMARY KEY,
  agents     TEXT NOT NULL,
  sessions   TEXT NOT NULL,
  applets    TEXT NOT NULL,
  needs_you  INTEGER NOT NULL DEFAULT 1,
  notices    INTEGER NOT NULL DEFAULT 1,
  show_tip   INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  updated_by TEXT
);
-- One agent, session (target_kind 'session': an agent is a session) or applet (its stable id) with its own level.
CREATE TABLE notification_overrides (
  user_id     TEXT NOT NULL,
  target_kind TEXT NOT NULL,
  target_id   TEXT NOT NULL,
  level       TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  updated_by  TEXT,
  PRIMARY KEY (user_id, target_kind, target_id)
);
-- An account from before #163 keeps what it had: everything notifies, and each muted session is Off for that session
-- (its questions still break through, as before). A fresh install has no sessions yet and gets the new defaults.
INSERT INTO notification_prefs (user_id, agents, sessions, applets, needs_you, notices, show_tip, updated_at, updated_by)
  SELECT 'owner', 'all', 'all', 'on', 1, 1, 1, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), 'migration'
  WHERE EXISTS (SELECT 1 FROM sessions);
INSERT INTO notification_overrides (user_id, target_kind, target_id, level, updated_at, updated_by)
  SELECT 'owner', 'session', id, 'off', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), 'migration' FROM sessions WHERE mute = 1;
