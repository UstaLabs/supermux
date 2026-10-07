// C3 launch equivalence: every broker agent's launch (worker + PA) through its core-host, mapped
// through the core's context channels, compared with tests/c3-launch/before.json (recorded on
// the pre-C3 code by scripts/c3-launch-capture.ts). Only the INTENDED differences listed below
// may differ; everything else (instruction text, MCP servers with their exact command/env,
// other args, env, generated files) must be identical.
import { expect, test } from "bun:test"
import { mkdtempSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

const root = mkdtempSync(join(tmpdir(), "c3-launch-"))
process.env.HOME = join(root, "home")
delete process.env.XDG_CONFIG_HOME
delete process.env.XDG_DATA_HOME
process.env.MUX_HOME = join(root, "mux")
process.env.MUX_STATE_DIR = join(root, "mux", "state")
const { scratchLayout, effectiveLaunch, diffKeys, AGENTS } = await import("./c3-launch/effective-launch")
const before = JSON.parse(readFileSync(join(import.meta.dirname, "c3-launch", "before.json"), "utf8")) as Record<string, any>
const s = scratchLayout(root)

const { CURSOR_C3 } = await import("./c3-launch/effective-launch")
const { cursorPreamble } = await import("../packages/supermux-core/src/context/agents.js")

/** The intended launch changes of C3, per agent (both roles unless noted). */
const INTENDED: Record<string, string[]> = {
  // Same args, plugins and instruction text; the instruction file now lives in the core's
  // session folder instead of the session home.
  "claude/worker": ["files.<home>/instructions.md"],
  // + the PA's several appended files become ONE instructions value (Claude kept only the last).
  "claude/pa": ["files.<home>/instructions.md", "instructions.text"],
  // Instructions: thread/start developerInstructions instead of <CODEX_HOME>/AGENTS.md; mux-shim:
  // app-server -c mcp_servers.* (+ default_tools_approval_mode="approve") instead of
  // config.toml; plugins: mapped by the core (skills/ → extraRoots) instead of the mux
  // marketplace + `-c plugins."x@mux".enabled`.
  "codex/worker": ["args", "files.<home>/AGENTS.md", "files.<home>/config.toml", "instructions.channel", "plugins", "skills"],
  // MCP via ACP mcpServers instead of ~/.cursor/mcp.json; instructions: the same text as the
  // leading block of the first prompt instead of <work>/.cursor/rules/mux.mdc (+ .git/info/exclude)
  // written into the repo; plugins: dropped (context.degraded): Cursor's ACP server ignores
  // --plugin-dir, so they never loaded (C0 2026-10-05).
  "cursor/worker": ["files.<home>/.cursor/mcp.json", ...CURSOR_C3],
  // Instructions: ACP _meta.rules instead of AGENTS.md written into the repo; plugins: the whole
  // plugin via --plugin-dir instead of its skills/ in config.toml; mux-shim: ACP mcpServers.
  "grok/worker": ["files.<home>/.grok/config.toml", "files.<work>/.git/info/exclude", "files.<work>/AGENTS.md", "instructions.channel", "plugins", "skills"],
  // Instructions / plugins / skills in a session OPENCODE_CONFIG instead of the session
  // XDG opencode.json (+ <home>/AGENTS.md); JS plugins as file:// entries and every plugin's
  // skills/ in skills.paths; mux-shim via ACP mcpServers.
  "opencode/worker": ["env.OPENCODE_CONFIG", "files.<home>/AGENTS.md", "files.<home>/config/opencode/opencode.json", "plugins", "skills"],
}
for (const agent of ["codex", "cursor", "grok", "opencode"]) INTENDED[`${agent}/pa`] = INTENDED[`${agent}/worker`]!
// 2026-10-07: one shared instructions builder for every agent (new text; Claude no longer gets
// --add-dir <prompts> or MUX_CORE=1, the retired reply hook's switch).
for (const key of Object.keys(INTENDED)) {
  const extra = ["instructions.text", ...(key.startsWith("claude/") ? ["args", "env.MUX_CORE"] : [])]
  INTENDED[key] = [...new Set([...INTENDED[key]!, ...extra])].sort()
}

for (const agent of AGENTS) for (const role of ["worker", "pa"] as const) {
  test(`${agent} ${role}: the launch equals the pre-C3 baseline apart from the intended changes`, async () => {
    const after = await effectiveLaunch(agent, role, s)
    const key = `${agent}/${role}`
    expect(diffKeys(before[key], after)).toEqual(INTENDED[key]!)
    // Never changed: the MCP servers (mux-shim keeps exactly its command / args / env).
    expect(after.mcpServers).toEqual(before[key].mcpServers)
    if (agent === "codex") {
      // The session policy (default mode) is the same one the broker passed statically before.
      expect(after.args).toEqual(["app-server", "-c", 'sandbox_mode="danger-full-access"', "-c", 'approval_policy="never"', "-c", 'mcp_servers.mux-shim.default_tools_approval_mode="approve"'])
      expect(after.instructions!.channel).toBe("thread/start developerInstructions")
      expect(after.files["<home>/AGENTS.md"]).toBeUndefined()
    }
    if (agent === "grok") {
      expect(after.instructions!.channel).toBe("ACP session/new _meta.rules")
      expect(Object.keys(after.files).filter((f) => f.startsWith("<work>/") && !f.startsWith("<work>/.git/"))).toEqual([])
    }
    if (agent === "cursor") {
      // The instructions, now an embedded resource in front of the first prompt; nothing in the repo.
      expect(after.instructions!.channel).toBe("first-prompt embedded resource supermux://instructions (ACP session/prompt)")
      expect(after.instructions!.text.startsWith(cursorPreamble("\u0000").split("\u0000")[0]!)).toBe(true)
      expect(after.instructions!.text).toContain('You are "c3-probe"')
      expect(Object.keys(after.files).filter((f) => f.startsWith("<work>/"))).toEqual([])
      expect(after.plugins).toEqual([])
      expect(after.args).toEqual([])
    }
    if (agent === "claude" && role === "pa") {
      const text = after.instructions!.text
      // Everything the PA's separate files carried is now in the one value Claude keeps.
      expect(text).toContain('You are "c3-probe"')
      expect(text).toContain("Your normal assistant output IS your reply")
      expect(text).toContain('answer "c3-probe"')
      expect(text).toContain("# Your soul")
      // One rules block, not one per former file.
      expect(text.split("# Working rules").length).toBe(2)
    }
  })
}
