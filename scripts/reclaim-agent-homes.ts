#!/usr/bin/env bun
// One-off reclaim of agent home dirs under state/agents/{cursor,codex}.
//   1. GC: delete homes with NO registry entry at all (true orphans).
//   2. Dedup: collapse every surviving cursor home's runtime to a symlink at
//      the one shared copy.
// Archived sessions are KEPT (resumable). Uses the same functions the broker
// runs at startup. Safe to run while the broker is up *as long as* no
// cursor/codex session is actively executing a turn.
import { Database } from "bun:sqlite"
import { join } from "path"
import { execSync } from "child_process"
import { STATE_DIR } from "../src/shared/paths"
import { gcOrphanAgentHomes, reclaimCursorHomes } from "../src/core/agents/shared-runtime"

const du = (p: string) => {
  try {
    return execSync(`du -sh ${p} 2>/dev/null`).toString().trim().split("\t")[0] || "?"
  } catch {
    return "?"
  }
}

const dbPath = join(STATE_DIR, "db.sqlite3")
const db = new Database(dbPath, { readonly: true })
const rows = db.query("SELECT agent_home FROM sessions WHERE agent_home IS NOT NULL AND agent_home != ''").all() as { agent_home: string }[]
const knownHomes = new Set(rows.map((r) => r.agent_home))
db.close()

const agentsDir = join(STATE_DIR, "agents")
console.log(`STATE_DIR        = ${STATE_DIR}`)
console.log(`known homes      = ${knownHomes.size}`)
console.log(`BEFORE agents/   = ${du(agentsDir)}`)

const { removed } = gcOrphanAgentHomes(knownHomes)
console.log(`\norphans removed  = ${removed.length}`)
for (const r of removed) console.log(`  - ${r}`)

const { linked } = reclaimCursorHomes()
console.log(`\ncursor homes linked = ${linked.length}`)

const sharedDir = join(STATE_DIR, "shared", "cursor-agent")
console.log(`\nAFTER agents/    = ${du(agentsDir)}`)
console.log(`shared cursor    = ${du(sharedDir)}`)
