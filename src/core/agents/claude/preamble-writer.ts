import { existsSync, mkdirSync, readFileSync, writeFileSync } from "fs"
import { createHash } from "node:crypto"
import { join, resolve } from "path"
import { buildMemoryPreamble } from "../../memory/preamble"
import type { AgentRole } from "../../memory/injector"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"
import { home } from "../../../shared/home"
import { STATE_DIR } from "../../../shared/paths"

const CORE_REPLY_RULE =
  "Your normal assistant output IS your reply; use the reply tool ONLY for files[]."

export function claudeWorkerInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  return [header, CORE_REPLY_RULE, env, memory].filter(s => s && s.trim()).join("\n")
}

export function claudePersonalAssistantInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "personal_assistant", workdir: opts.workdir })
  const soulPath = join(home(), ".mux", "soul.md")
  const soul = existsSync(soulPath) ? readFileSync(soulPath, "utf8").trim() : ""
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("personal_assistant")
  return [header, CORE_REPLY_RULE, soul, env, memory].filter(s => s && s.trim()).join("\n")
}

export function writeSessionMemoryPreamble(sessionId: string, displayName: string, role: AgentRole, workdir?: string): string {
  const dir = resolve(STATE_DIR, "memory-preambles")
  mkdirSync(dir, { recursive: true })
  const preamble = buildMemoryPreamble(role, displayName, workdir)
  const filename = `${createHash("sha256").update(sessionId, "utf8").digest("hex")}.md`
  const path = resolve(dir, filename)
  writeFileSync(path, preamble, "utf8")
  return path
}
