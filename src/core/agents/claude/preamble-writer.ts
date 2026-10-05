import { existsSync, readFileSync } from "fs"
import { join } from "path"
import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"
import { home } from "../../../shared/home"
import { replyFallbackContent } from "../../runtime-assets"

const CORE_REPLY_RULE =
  "Your normal assistant output IS your reply; use the reply tool ONLY for files[]."

export function claudeWorkerInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  return [header, CORE_REPLY_RULE, env, memory].filter(s => s && s.trim()).join("\n")
}

/**
 * A personal assistant's instructions: ONE value (C3). Before C3 this text went in one
 * --append-system-prompt-file and environment.md, the per-session memory preamble and (without
 * the mux-core SessionStart hook) reply-fallback.md each in another one, but Claude keeps only
 * the LAST such file (scripts/c3-pa-prompt-probe.ts), so a PA really got only the memory
 * preamble. Now: header, reply rule, soul, environment.md (once), the per-session memory
 * preamble (the user-chosen name, the workdir soul / focus), then the reply fallback.
 */
export function claudePersonalAssistantInstructions(opts: { sessionName: string; workdir: string; replyFallback: boolean }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "personal_assistant", workdir: opts.workdir })
  const soulPath = join(home(), ".mux", "soul.md")
  const soul = existsSync(soulPath) ? readFileSync(soulPath, "utf8").trim() : ""
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("personal_assistant", opts.sessionName, opts.workdir)
  const fallback = opts.replyFallback ? replyFallbackContent() : ""
  return [header, CORE_REPLY_RULE, soul, env, memory, fallback].filter(s => s && s.trim()).join("\n")
}
