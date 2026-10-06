/**
 * C3: which of the PA's appended system-prompt files does Claude actually keep?
 *
 * Builds a PA launch through the broker's Claude core-host (`prepare` + driver factory, with a
 * capturing fake driver) in a scratch MUX_HOME under ~/.cache/context-c3/, where every prompt
 * source carries its own token, then runs raw `claude -p` (haiku, no session persistence) with
 * exactly the captured `--append-system-prompt-file` / `--add-dir` args and asks which tokens it
 * can see. Real homes are untouched apart from Claude Code's own bookkeeping.
 *
 *   bun scripts/c3-pa-prompt-probe.ts
 */
import { mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { homedir } from "node:os"
import { spawnSync } from "node:child_process"

const run = join(homedir(), ".cache", "context-c3", `pa-probe-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const muxHome = join(run, "mux")
const workdir = join(run, "work")
mkdirSync(join(muxHome, "plugins", "mux-core", "hooks"), { recursive: true })
mkdirSync(join(muxHome, "plugins", "mux-core", ".claude-plugin"), { recursive: true })
mkdirSync(workdir, { recursive: true })
writeFileSync(join(muxHome, "plugins", "mux-core", ".claude-plugin", "plugin.json"), JSON.stringify({ name: "mux", version: "0.0.0" }))
writeFileSync(join(muxHome, "plugins", "mux-core", "hooks", "session-start"), "#!/bin/sh\n", { mode: 0o755 })
writeFileSync(join(muxHome, "plugins.json"), JSON.stringify({ version: 1, plugins: [{ name: "mux-core", source: { type: "local", path: join(muxHome, "plugins", "mux-core") }, enabled: true, scopes: ["claude"] }] }))
// Token per source: soul.md → the instructions file; focus.md → the per-session memory preamble.
writeFileSync(join(muxHome, "soul.md"), "PA-TOKEN-SOUL-7731 (from soul.md, part of the instructions file)\n")
writeFileSync(join(workdir, "focus.md"), "PA-TOKEN-FOCUS-4410 (from focus.md, part of the memory preamble file)\n")
process.env.MUX_HOME = muxHome
process.env.MUX_STATE_DIR = join(muxHome, "state")

const { createClaudeCoreHost } = await import("../src/core/agents/claude/core-host")
const captured: string[][] = []
const host = createClaudeCoreHost({
  stateDirectory: join(run, "core"),
  driverFactory: (options) => {
    captured.push(options.args)
    return { id: "claude", async open() { throw new Error("probe: launch captured") } }
  },
})
const extra = { sessionHome: join(run, "home"), sessionName: "probe-pa", sessionId: "probe-pa-id", workdir, cwd: workdir, pa: true }
await host.register({ id: "probe-pa-id", env: {}, extra }).start({ cwd: workdir }).catch(() => {})
await host.close({ agents: "shutdown" })
const args = captured[0] ?? []
const files = args.flatMap((arg, i) => arg === "--append-system-prompt-file" ? [args[i + 1]!] : [])
console.log("captured --append-system-prompt-file order:")
for (const file of files) console.log("  ", file)

// The environment.md file has no token of its own: name it by a phrase only it contains.
const question = "List every token of the form PA-TOKEN-<WORD>-<DIGITS> that appears anywhere in your system prompt, " +
  "and say whether your system prompt contains the heading '# Shared Memory System'. Answer on one line: TOKENS=<comma list or NONE>; MEMORY=<yes|no>. Do not use tools."
const flags = args.filter((_, i) => args[i] === "--append-system-prompt-file" || args[i - 1] === "--append-system-prompt-file")
const result = spawnSync("claude", ["-p", question, "--model", "haiku", "--no-session-persistence", "--strict-mcp-config", "--tools", "", ...flags], { cwd: workdir, encoding: "utf8", timeout: 180_000 })
console.log("claude answered:", (result.stdout || result.stderr).trim())
console.log("scratch:", run)
