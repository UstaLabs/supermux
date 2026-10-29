import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "fs"
import { join } from "path"
import { getMuxHome } from "./init"

/** An Agent's own memory (spec §4): dated history + curator digest, keyed by the Agent's session id.
 *  `agent-memory/`, not `agents/`: `~/.mux/agents.md` is already the domain index. */
export type AgentMemoryPaths = { dir: string; history: string; digest: string }

const SAFE_ID = /^[A-Za-z0-9_-]+$/

export function agentMemoryPaths(id: string, home: string = getMuxHome()): AgentMemoryPaths {
  if (!SAFE_ID.test(id)) throw new Error(`invalid agent id: ${JSON.stringify(id)}`)
  const dir = join(home, "agent-memory")
  return { dir, history: join(dir, `${id}.md`), digest: join(dir, `${id}.digest.md`) }
}

/** Both files exist from the Agent's creation on, so its fixed preamble can name them. Never clobbers. */
export function ensureAgentMemoryFiles(id: string, home?: string): AgentMemoryPaths {
  const p = agentMemoryPaths(id, home)
  mkdirSync(p.dir, { recursive: true, mode: 0o700 })
  for (const file of [p.history, p.digest]) if (!existsSync(file)) writeFileSync(file, "", { encoding: "utf8", mode: 0o600 })
  return p
}

/** Appends `## <heading>` + body to the history file (the migration's writer; Agents append themselves). */
export function appendAgentMemory(id: string, heading: string, body: string, home?: string): void {
  const p = ensureAgentMemoryFiles(id, home)
  const current = readFileSync(p.history, "utf8")
  const sep = current.length === 0 ? "" : current.endsWith("\n\n") ? "" : current.endsWith("\n") ? "\n" : "\n\n"
  appendFileSync(p.history, `${sep}## ${heading}\n\n${body.trim()}\n`, "utf8")
}
