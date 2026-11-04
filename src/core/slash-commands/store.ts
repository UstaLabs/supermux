import type { Db } from "../storage/db"
import type { AgentKind } from "../agents/types"
import type { AgentCommandList } from "./types"

/** Folders kept per agent; older rows go (worktree sessions each add one). */
const MAX_FOLDERS_PER_AGENT = 50

/** The command list each agent last reported in a folder (table agent_commands, migration 046). */
export interface CommandStore {
  /** The list the agent last reported in `workdir`. */
  load(kind: AgentKind, workdir: string): AgentCommandList | undefined
  /** The agent's most recently changed list, from any folder. */
  latest(kind: AgentKind): AgentCommandList | undefined
  /** Stores `list` for each folder. A folder that already holds the same list is left alone. */
  save(kind: AgentKind, workdirs: string[], list: AgentCommandList): void
}

function parse(json: string | undefined): AgentCommandList | undefined {
  if (!json) return undefined
  try {
    const value = JSON.parse(json) as AgentCommandList
    return Array.isArray(value?.commands) ? value : undefined
  } catch {
    return undefined
  }
}

export function sqliteCommandStore(db: Db, now: () => number = Date.now): CommandStore {
  const loadOne = db.query("SELECT commands FROM agent_commands WHERE agent = ? AND workdir = ?")
  const latestOne = db.query("SELECT commands FROM agent_commands WHERE agent = ? ORDER BY updated_at DESC LIMIT 1")
  const upsert = db.query(
    "INSERT INTO agent_commands (agent, workdir, commands, updated_at) VALUES (?, ?, ?, ?) " +
      "ON CONFLICT (agent, workdir) DO UPDATE SET commands = excluded.commands, updated_at = excluded.updated_at",
  )
  const prune = db.query(
    "DELETE FROM agent_commands WHERE agent = ? AND workdir NOT IN " +
      "(SELECT workdir FROM agent_commands WHERE agent = ? ORDER BY updated_at DESC LIMIT ?)",
  )
  // Parsed rows, so a web snapshot (every session's list) reads no SQLite and parses no JSON. Only this store
  // writes the table; a save drops the agent's entries (it may prune any of them).
  const rows = new Map<string, AgentCommandList | undefined>()
  const latestRows = new Map<AgentKind, AgentCommandList | undefined>()
  const key = (kind: AgentKind, workdir: string) => `${kind}\0${workdir}`
  return {
    load(kind, workdir) {
      const k = key(kind, workdir)
      if (!rows.has(k)) rows.set(k, parse((loadOne.get(kind, workdir) as { commands: string } | null)?.commands))
      return rows.get(k)
    },
    latest(kind) {
      if (!latestRows.has(kind)) latestRows.set(kind, parse((latestOne.get(kind) as { commands: string } | null)?.commands))
      return latestRows.get(kind)
    },
    save(kind, workdirs, list) {
      const json = JSON.stringify(list)
      let wrote = false
      for (const workdir of new Set(workdirs)) {
        if ((loadOne.get(kind, workdir) as { commands: string } | null)?.commands === json) continue
        upsert.run(kind, workdir, json, now())
        wrote = true
      }
      if (!wrote) return
      prune.run(kind, kind, MAX_FOLDERS_PER_AGENT)
      for (const k of rows.keys()) if (k.startsWith(`${kind}\0`)) rows.delete(k)
      latestRows.delete(kind)
    },
  }
}
