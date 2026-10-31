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
const { scratchLayout, effectiveLaunch, diffKeys, openCodeConfigOf, AGENTS, CODEX_INBOX_RULES, CODEX_INBOX_RULES_FILE } = await import("./c3-launch/effective-launch")
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
  // Session inbox: + the exec-policy rules file that lets its shell read its own inbox file (see CODEX_INBOX_RULES).
  "codex/worker": ["args", "files.<home>/AGENTS.md", "files.<home>/config.toml", "files.<home>/rules/supermux-inbox-sess-c3.rules", "instructions.channel", "plugins", "skills"],
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
// 2026-10-29 (Agents): the role comes from the session row, so Claude no longer gets MUX_SESSION_ROLE (nothing read it).
for (const key of Object.keys(INTENDED)) {
  const extra = ["instructions.text", ...(key.startsWith("claude/") ? ["args", "env.MUX_CORE", "env.MUX_SESSION_ROLE"] : [])]
  INTENDED[key] = [...new Set([...INTENDED[key]!, ...extra])].sort()
}
// Session inbox: OpenCode's config allows its own `external_directory` ask for ~/.mux/session_inboxes
// (permission.external_directory, part of the non-context keys of opencode.json), so reading an
// inbox never prompts.
for (const key of ["opencode/worker", "opencode/pa"]) INTENDED[key] = [...INTENDED[key]!, "files.<opencode.json non-context keys>"].sort()

// How each agent is told to read its own inbox file (session sess-c3): the shell commands where they need no prompt (Claude's exact
// rules, Grok's built-in list, Codex's rules, which name the host binaries by ABSOLUTE path: here the fakes in <ROOT>/bin), the
// file-read tool everywhere else.
const INBOX_FILE = "<ROOT>/mux/session_inboxes/sess-c3.jsonl"
const INBOX_READ_LINE: Record<string, string> = {
  claude: `From a shell, read it with exactly \`cat ${INBOX_FILE}\` or \`tail ${INBOX_FILE}\`.`,
  grok: `From a shell, read it with exactly \`cat ${INBOX_FILE}\` or \`tail ${INBOX_FILE}\`.`,
  codex: `From a shell, read it with exactly \`<ROOT>/bin/cat ${INBOX_FILE}\` or \`<ROOT>/bin/tail ${INBOX_FILE}\`.`,
  cursor: "Read it with your file-read tool.",
  opencode: "Read it with your file-read tool.",
}

for (const agent of AGENTS) for (const role of ["worker", "pa"] as const) {
  test(`${agent} ${role}: the launch equals the pre-C3 baseline apart from the intended changes`, async () => {
    const after = await effectiveLaunch(agent, role, s)
    const key = `${agent}/${role}`
    expect(diffKeys(before[key], after)).toEqual(INTENDED[key]!)
    // The inbox block tells this agent how to read its file: exactly one such line, the one that is true for it.
    expect(after.instructions!.text.split("\n").filter((line) => line.startsWith("From a shell") || line.startsWith("Read it with your file-read tool"))).toEqual([INBOX_READ_LINE[agent]!])
    // Never changed: the MCP servers (mux-shim keeps exactly its command / args / env).
    expect(after.mcpServers).toEqual(before[key].mcpServers)
    if (agent === "opencode") {
      // The one change inside opencode.json's non-context keys is the session inbox rule. `diffKeys` holds that file as
      // one string and would pass any change in it (a "*": "allow" catch-all, edit / bash / webfetch no longer asked).
      const baseline = openCodeConfigOf(before[key])
      expect(openCodeConfigOf(after)).toEqual({ ...baseline, permission: { ...baseline.permission, external_directory: { "<ROOT>/mux/session_inboxes/*": "allow" } } })
    }
    if (agent === "codex") {
      // The one new file in the session home is the inbox rules file, and its text is exactly this: cat / head / tail / wc -l
      // on the session's own inbox file, nothing broader (the file key alone would pass any text).
      expect(Object.keys(after.files).filter((file) => file.startsWith("<home>/rules/"))).toEqual([CODEX_INBOX_RULES_FILE])
      expect(after.files[CODEX_INBOX_RULES_FILE]).toBe(CODEX_INBOX_RULES)
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
      // An Agent's preamble names its memory files by id; no soul is inlined.
      expect(text).toContain("an agent in supermux. Your agent id is sess-c3.")
      expect(text).toContain("<ROOT>/mux/agent-memory/sess-c3.md")
      expect(text).not.toContain("# Your soul")
      // One rules block, not one per former file.
      expect(text.split("# Working rules").length).toBe(2)
    }
  })
}
