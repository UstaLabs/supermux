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
const { scratchLayout, effectiveLaunch, diffKeys, openCodeConfigOf, AGENTS, CURSOR_C3, CODEX_INBOX_RULES, CODEX_INBOX_RULES_FILE } = await import("./c3-launch/effective-launch")
const { setMuxShimMode } = await import("../src/core/mux-tools/mode")
const c3a = JSON.parse(readFileSync(join(import.meta.dirname, "c3-launch", "c3a.json"), "utf8")) as Record<string, any>
const s = scratchLayout(root)
afterAll(() => setMuxShimMode("external"))
/** Intended since C3a (2026-10-07): one shared instructions builder for every agent (new text);
 *  Claude no longer gets --add-dir <prompts> or MUX_CORE=1 (the retired reply hook's switch), nor MUX_SESSION_ROLE. */
function sinceC3a(agent: string): string[] {
  if (agent === "cursor") return CURSOR_C3
  // Session inbox: OpenCode's config allows its `external_directory` ask for ~/.mux/session_inboxes (a new
  // permission.external_directory in opencode.json), so reading an inbox never prompts.
  if (agent === "opencode") return ["files.<home>/config/opencode/opencode.json", "files.<opencode.json non-context keys>", "instructions.text"]
  // Session inbox: Codex's home gains the exec-policy rules file that lets its shell read its own inbox file.
  if (agent === "codex") return ["files.<home>/rules/supermux-inbox-sess-c3.rules", "instructions.text"]
  // 2026-10-29 (Agents): Claude's MUX_SESSION_ROLE is gone (the role comes from the session row; nothing read it).
  return agent === "claude" ? ["args", "env.MUX_CORE", "env.MUX_SESSION_ROLE", "instructions.text"] : ["instructions.text"]
}
/**
 * OpenCode's opencode.json outside its context keys is the C3a one plus exactly the session inbox rule. The diff keys hold
 * that file as ONE string, so they would pass any change inside it (a "*": "allow" catch-all, edit / bash / webfetch no longer asked).
 */
function expectOnlyInboxRuleAdded(baseline: unknown, after: { files: Record<string, string> }): void {
  const before = openCodeConfigOf(baseline as { files: Record<string, string> })
  expect(openCodeConfigOf(after)).toEqual({ ...before, permission: { ...before.permission, external_directory: { "<ROOT>/mux/session_inboxes/*": "allow" } } })
}
/** Codex's session home gains exactly one file, the inbox rules, with exactly this text (the diff key alone would pass any text). */
function expectOnlyInboxRulesFile(after: { files: Record<string, string> }): void {
  expect(Object.keys(after.files).filter((file) => file.startsWith("<home>/rules/"))).toEqual([CODEX_INBOX_RULES_FILE])
  expect(after.files[CODEX_INBOX_RULES_FILE]).toBe(CODEX_INBOX_RULES)
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
    if (agent === "opencode") expectOnlyInboxRuleAdded(c3a[`${agent}/${role}`], after)
    if (agent === "codex") expectOnlyInboxRulesFile(after)
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
    if (agent === "opencode") expectOnlyInboxRuleAdded(before, after)
    if (agent === "codex") expectOnlyInboxRulesFile(after)
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
