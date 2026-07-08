import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"

// bun test preload: runs before ANY test file (or the src modules they import)
// is loaded. shared/paths.ts resolves STATE_DIR once, at first import, from
// MUX_STATE_DIR — so it must point at a throwaway dir BEFORE that first import.
//
// Per-file `process.env.MUX_STATE_DIR = mkdtempSync(...)` lines are not enough:
// in a multi-file run, whichever test file imports shared/paths first wins, and
// if that file never set the env, STATE_DIR caches to the LIVE ~/.mux/state for
// the whole process. Every full-suite run then silently rewrote production
// state — claude-hooks.json + internal-hook-secret got test values ("deadbeef",
// then secretless), which broke the agent-status hooks of running sessions and
// is the same mechanism behind the devices.json test-pollution gotcha.
process.env.MUX_STATE_DIR = mkdtempSync(join(tmpdir(), "mux-test-state-"))
