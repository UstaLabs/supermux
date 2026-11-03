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
  return {
    load(kind, workdir) {
      return parse((loadOne.get(kind, workdir) as { commands: string } | null)?.commands)
    },
    latest(kind) {
      return parse((latestOne.get(kind) as { commands: string } | null)?.commands)
    },
    save(kind, workdirs, list) {
      const json = JSON.stringify(list)
      let wrote = false
      for (const workdir of new Set(workdirs)) {
        if ((loadOne.get(kind, workdir) as { commands: string } | null)?.commands === json) continue
        upsert.run(kind, workdir, json, now())
        wrote = true
      }
      if (wrote) prune.run(kind, kind, MAX_FOLDERS_PER_AGENT)
    },
  }
}
