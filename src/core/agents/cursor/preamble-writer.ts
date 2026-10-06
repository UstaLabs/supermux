import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

// Cursor's ACP server has no per-session system-prompt channel (C0, cursor-agent 2026.09.18):
// the core sends this text as the leading block of the session's first prompt (fixed at
// creation, kept by session/load). Nothing is written into the repo. Skills from plugins are
// not available on Cursor (its ACP server ignores --plugin-dir).
/** Instruction TEXT only (no front matter). File placement is library-owned. */
export function cursorInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  return [header, env, memory].filter(s => s && s.trim()).join("\n")
}
