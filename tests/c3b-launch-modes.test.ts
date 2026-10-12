// C3b launch modes. "external" (default) must be byte-for-byte the C3a launch
// (tests/c3-launch/c3a.json, recorded on the C3a code by `bun scripts/c3-launch-capture.ts c3a`);
// "host" may differ ONLY in the mux-shim MCP entry, which becomes the core's bridge to the
// broker's host server. Cursor: since the C0 cursor cells (2026-10-05) its instructions are the
// first prompt's preamble instead of the repo rule and its plugins are dropped (CURSOR_C3, the
// same in both modes); it follows the mode like every agent.
import { afterAll, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

const root = mkdtempSync(join(tmpdir(), "c3b-launch-"))
process.env.HOME = join(root, "home")
delete process.env.XDG_CONFIG_HOME
delete process.env.XDG_DATA_HOME
process.env.MUX_HOME = join(root, "mux")
process.env.MUX_STATE_DIR = join(root, "mux", "state")
const { scratchLayout, effectiveLaunch, diffKeys, AGENTS, CURSOR_C3 } = await import("./c3-launch/effective-launch")
const { setMuxShimMode } = await import("../src/core/mux-tools/mode")
const c3a = JSON.parse(readFileSync(join(import.meta.dirname, "c3-launch", "c3a.json"), "utf8")) as Record<string, any>
const s = scratchLayout(root)
afterAll(() => setMuxShimMode("external"))
/** Intended since C3a (2026-10-07): one shared instructions builder for every agent (new text);
 *  Claude no longer gets --add-dir <prompts> or MUX_CORE=1 (the retired reply hook's switch). */
function sinceC3a(agent: string): string[] {
  if (agent === "cursor") return CURSOR_C3
  return agent === "claude" ? ["args", "env.MUX_CORE", "instructions.text"] : ["instructions.text"]
}
/** Every capture is a NEW session (a second capture in the same state would be a resume). */
function fresh(agent: string, role: string): void {
  for (const dir of [join(root, "core", agent, role), join(root, "agents", agent, role), join(root, `work-${agent}-${role}`)]) rmSync(dir, { recursive: true, force: true })
}

for (const agent of AGENTS) for (const role of ["worker", "pa"] as const) {
  test(`external: ${agent} ${role} launches exactly as C3a`, async () => {
    setMuxShimMode("external")
    fresh(agent, role)
    const after = await effectiveLaunch(agent, role, s)
    expect(diffKeys(c3a[`${agent}/${role}`], after)).toEqual(sinceC3a(agent))
    expect(after.mcpServers).toEqual(c3a[`${agent}/${role}`].mcpServers)
  })
}

const BRIDGE_ENV = ["SUPERMUX_MCP_SESSION", "SUPERMUX_MCP_SOCKET", "SUPERMUX_MCP_TOKEN"]

for (const agent of AGENTS) for (const role of ["worker", "pa"] as const) {
  test(`host: ${agent} ${role} differs from C3a only in the mux-shim entry (the core's bridge)`, async () => {
    setMuxShimMode("host")
    fresh(agent, role)
    const after = await effectiveLaunch(agent, role, s)
    const before = c3a[`${agent}/${role}`]
    expect(diffKeys(before, after)).toEqual([...sinceC3a(agent), "mcpServers"].sort())
    const others = (list: any[]) => list.filter((server) => server.name !== "mux-shim")
    expect(others(after.mcpServers)).toEqual(others(before.mcpServers))
    const shim: any[] = after.mcpServers.filter((server: any) => server.name === "mux-shim")
    expect(shim).toHaveLength(1)
    expect(shim[0].command).toBe(process.execPath)
    expect(shim[0].args.slice(-2)).toEqual(["--server", "mux-shim"])
    expect(shim[0].args[0]).toMatch(/packages\/supermux-core\/(src|dist)\/mcp\/bridge\.(ts|js)$/)
    expect(Object.keys(shim[0].env).sort()).toEqual(BRIDGE_ENV)
    expect(shim[0].env.SUPERMUX_MCP_SESSION).toBe("sess-c3")
    // No broker socket identity in the bridge: identity is the core's token.
    expect(JSON.stringify(shim[0])).not.toContain("MUX_SOCKETS_DIR")
  })
}
