import type { Migration } from "../db"

// Migrations are imported as text so they are inlined into the production bundle
// (mux-head-bundle.js). Reading them from the filesystem via import.meta.dir works
// in dev but crashes the bundled broker — the bundle has no sibling migrations dir.
//
// When adding a migration: drop the NNN_*.sql file in this directory AND add a line
// here. The migrations-embedded test fails if the manifest drifts from disk.
import m001 from "./001_init.sql" with { type: "text" }
import m002 from "./002_push_subscriptions.sql" with { type: "text" }
import m003 from "./003_sessions.sql" with { type: "text" }
import m004 from "./004_base_commit.sql" with { type: "text" }
import m006 from "./006_base_commits.sql" with { type: "text" }
import m007 from "./007_session_roles.sql" with { type: "text" }
import m008 from "./008_proxies.sql" with { type: "text" }
import m009 from "./009_settings.sql" with { type: "text" }
import m010 from "./010_reasoning_level.sql" with { type: "text" }
import m011 from "./011_device_push_tokens.sql" with { type: "text" }
import m012 from "./012_proxies_is_public.sql" with { type: "text" }
import m013 from "./013_opencode_agent.sql" with { type: "text" }
import m014 from "./014_tmux_window_id.sql" with { type: "text" }
import m015 from "./015_worktree_session.sql" with { type: "text" }
import m016 from "./016_review_comments.sql" with { type: "text" }
import m017 from "./017_forge_connections.sql" with { type: "text" }

export const MIGRATIONS: Migration[] = [
  { version: 1, name: "001_init", sql: m001 },
  { version: 2, name: "002_push_subscriptions", sql: m002 },
  { version: 3, name: "003_sessions", sql: m003 },
  { version: 4, name: "004_base_commit", sql: m004 },
  { version: 6, name: "006_base_commits", sql: m006 },
  { version: 7, name: "007_session_roles", sql: m007 },
  { version: 8, name: "008_proxies", sql: m008 },
  { version: 9, name: "009_settings", sql: m009 },
  { version: 10, name: "010_reasoning_level", sql: m010 },
  { version: 11, name: "011_device_push_tokens", sql: m011 },
  { version: 12, name: "012_proxies_is_public", sql: m012 },
  { version: 13, name: "013_opencode_agent", sql: m013 },
  { version: 14, name: "014_tmux_window_id", sql: m014 },
  { version: 15, name: "015_worktree_session", sql: m015 },
  { version: 16, name: "016_review_comments", sql: m016 },
  { version: 17, name: "017_forge_connections", sql: m017 },
].sort((a, b) => a.version - b.version)
