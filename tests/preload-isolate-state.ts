import { afterAll } from "bun:test"
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
// Sweep leftovers from earlier runs BEFORE creating this run's dir. A leaked
// full-suite dir weighs ~766M (the cursor-agent runtime payload, ~574M, gets
// cpSync'd into shared/cursor-agent, plus ~192M of codex agent homes); /tmp is
// a tmpfs on a dev box, and once leftovers accumulate the suite fails with
// "Disk quota exceeded" for reasons unrelated to the code under test. The
// afterAll below removes this run's own dir; the sweep is the self-healing
// backstop for runs that crashed or were killed before afterAll could fire.
// 24h is well clear of any run in flight, including a parallel one.
const TMP = tmpdir()
const STALE_MS = 24 * 60 * 60 * 1000
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

// ...and remove this run's dir on the way out. `process.on("exit")` is dead
// code here: bun (verified on 1.3.14) never emits "exit"/"beforeExit" when a
// `bun test` run ends, which is exactly how every run used to leak its dir.
// A hook registered via bun:test's afterAll in a preload DOES run — once,
// after the last test file (and under `--isolate`, once per file, each file
// cleaning its own fresh dir). Keep the exit hook anyway as a no-cost backstop
// for any future runner mode that does emit it; removeState is idempotent.
const removeState = () => {
  try { rmSync(stateDir, { recursive: true, force: true }) } catch { /* best effort */ }
}
afterAll(removeState)
process.on("exit", removeState)
