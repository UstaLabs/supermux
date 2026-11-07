// #102: a per-session plugin override is keyed by the session's id. Keyed by its name, it was lost when the session
// renamed itself (every worker does on its first turn) and inherited by a later session given that name.
import { expect, test } from "bun:test"
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

const root = mkdtempSync(join(tmpdir(), "plugin-overrides-"))
process.env.HOME = join(root, "home")
delete process.env.XDG_CONFIG_HOME
delete process.env.XDG_DATA_HOME
process.env.MUX_HOME = join(root, "mux")
process.env.MUX_STATE_DIR = join(root, "mux", "state")
const { scratchLayout, effectiveLaunch } = await import("./c3-launch/effective-launch")
const s = scratchLayout(root)

// The harness session is id "sess-c3", named "c3-probe".
const file = join(s.muxHome, "plugins.json")
const registry = JSON.parse(readFileSync(file, "utf8"))
registry.plugins.find((p: { name: string }) => p.name === "thirdparty").perSessionOverrides = { "sess-c3": { enabled: false } }
writeFileSync(file, JSON.stringify(registry))

for (const agent of ["claude", "codex", "grok", "opencode"] as const) {
  test(`${agent}: a plugin switched off for the session's id is not launched; the others are`, async () => {
    const launch = await effectiveLaunch(agent, "worker", s)
    // A mapping agent reports a plugin through its skills folder (OpenCode's `plugins` are JS plugin entries only).
    const reached = [...launch.plugins, ...launch.skills]
    expect(reached.filter((p) => p.includes("thirdparty"))).toEqual([])
    expect(reached.some((p) => p.includes("mux-core"))).toBe(true)
  })
}
