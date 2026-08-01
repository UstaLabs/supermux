import { mkdtempSync, readdirSync, rmSync, statSync } from "fs"
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
// Sweep leftovers from earlier runs BEFORE creating this run's dir. These used to
// accumulate forever: a full-suite run leaves one behind (441 of them under
// `--isolate`, which re-runs this preload per file), /tmp is a 14G tmpfs on a dev
// box, and once it fills the suite fails with "Disk quota exceeded" for reasons
// that have nothing to do with the code under test. An hour is well clear of any
// run in flight, including a parallel one.
const TMP = tmpdir()
const STALE_MS = 60 * 60 * 1000
try {
  const cutoff = Date.now() - STALE_MS
  for (const e of readdirSync(TMP)) {
    if (!e.startsWith("mux-test-state-")) continue
    const p = join(TMP, e)
    try {
      if (statSync(p).mtimeMs < cutoff) rmSync(p, { recursive: true, force: true })
    } catch { /* raced with another run's cleanup */ }
  }
} catch { /* best effort */ }

const stateDir = mkdtempSync(join(TMP, "mux-test-state-"))
process.env.MUX_STATE_DIR = stateDir

// ...and remove this run's dir on the way out. Does not fire under `--isolate`
// (each file gets a fresh global), which is what the sweep above is for.
process.on("exit", () => {
  try { rmSync(stateDir, { recursive: true, force: true }) } catch { /* best effort */ }
})
