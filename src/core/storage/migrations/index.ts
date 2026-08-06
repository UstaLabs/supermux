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
import m017 from "./017_read_status_drafts.sql" with { type: "text" }
import m018 from "./018_forge_connections.sql" with { type: "text" }
import m019 from "./019_finish_job.sql" with { type: "text" }
import m020 from "./020_internal_session.sql" with { type: "text" }
import m021 from "./021_device_push_routing.sql" with { type: "text" }
import m022 from "./022_memory_search.sql" with { type: "text" }
import m023 from "./023_pending_uploads.sql" with { type: "text" }
import m024 from "./024_grok_agent.sql" with { type: "text" }
import m025 from "./025_self_renamed.sql" with { type: "text" }
import m026 from "./026_user_status.sql" with { type: "text" }
import m027 from "./027_workspaces.sql" with { type: "text" }

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
  { version: 17, name: "017_read_status_drafts", sql: m017 },
  { version: 18, name: "018_forge_connections", sql: m018 },
  { version: 19, name: "019_finish_job", sql: m019 },
  { version: 20, name: "020_internal_session", sql: m020 },
  { version: 21, name: "021_device_push_routing", sql: m021 },
  { version: 22, name: "022_memory_search", sql: m022 },
  { version: 23, name: "023_pending_uploads", sql: m023 },
  { version: 24, name: "024_grok_agent", sql: m024 },
  { version: 25, name: "025_self_renamed", sql: m025 },
  { version: 26, name: "026_user_status", sql: m026 },
  { version: 27, name: "027_workspaces", sql: m027 },
].sort((a, b) => a.version - b.version)
