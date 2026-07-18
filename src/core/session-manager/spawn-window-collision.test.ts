import { afterAll, describe, expect, test } from "bun:test"
import { join } from "path"
import { rmSync } from "fs"
import { AgentKind } from "../../shared/agents"
import { STATE_DIR } from "../../shared/paths"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import type { SessionBackend } from "../runtime/session-backend"

// Regression test for the "new session kills the prior same-repo session" bug.
//
// Every worker on a repo gets a tmux WINDOW named after the repo base (e.g.
// "supermux"). Sessions then rename their DISPLAY name, but the tmux window keeps
// its original name. The old spawn path resolved the new window name against only
// the DISPLAY names (so the repo base was "free" again) and then ran
//   while (listSessionWindows().includes(name)) killSessionWindow({ window: name })
// which `tmux kill-window -t mux:<name>` — killing the EXISTING live window that
// shared the name, i.e. the previously-active session. The fix resolves the name
// against existing tmux window names too, so it never collides (and never kills).

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const NAMES = ["ztest-spawn-collision", "ztest-spawn-collision-2", "ztest-spawn-unique"]

afterAll(() => {
  // buildClaudeSpawnCommand writes a per-session memory preamble — clean ours.
  for (const n of NAMES) {
    try { rmSync(join(STATE_DIR, "memory-preambles", `${n}.md`)) } catch {}
  }
})

describe("Claude spawn — tmux window-name collision", () => {
  test("a new session whose name matches an existing window gets a unique window name (does NOT reuse/kill it)", async () => {
    const reg = registry()
    const occupied = "ztest-spawn-collision"
    const existingWindows = [occupied] // a live session already owns this window name
    const spawnedWindows: string[] = []

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      // Injected so the test never touches the real tmux server.
      sessionBackend: {
        list: async () => existingWindows.map((name, i) => ({ id: `target-${i}`, name, pid: i + 1, alive: true })),
        create: async (opts: Parameters<SessionBackend["create"]>[0]) => { spawnedWindows.push(opts.name); return { id: "target-new", name: opts.name, pid: 99, alive: true } },
      } as unknown as SessionBackend,
      postSpawnReady: async () => {},
    }, {
      workdir: process.cwd(),
      requestedName: occupied,
      agent: AgentKind.Claude,
    })

    // Must create a DISTINCT window, not reuse (and not kill) the occupied one.
    expect(spawnedWindows).toEqual(["ztest-spawn-collision-2"])
    expect(result.name).toBe("ztest-spawn-collision-2")
  })

  test("a new session with a free name keeps that name as-is", async () => {
    const reg = registry()
    const spawnedWindows: string[] = []

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      sessionBackend: {
        list: async () => [],
        create: async (opts: Parameters<SessionBackend["create"]>[0]) => { spawnedWindows.push(opts.name); return { id: "target-new", name: opts.name, pid: 99, alive: true } },
      } as unknown as SessionBackend,
      postSpawnReady: async () => {},
    }, {
      workdir: process.cwd(),
      requestedName: "ztest-spawn-unique",
      agent: AgentKind.Claude,
    })

    expect(spawnedWindows).toEqual(["ztest-spawn-unique"])
    expect(result.name).toBe("ztest-spawn-unique")
  })
})
