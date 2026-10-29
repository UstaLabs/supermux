import { existsSync, readFileSync } from "fs"
import { basename, join } from "path"
import { agentMemoryPaths, appendAgentMemory, ensureAgentMemoryFiles } from "../memory/agent-memory"

export type MigratingAgent = { id: string; workdir: string; is_default: boolean; created_at: string }

/** The lines of the retired soul.md seed template (it was seeded into every new mux home). */
const SOUL_TEMPLATE_LINES = new Set([
  "# Soul",
  "Customize this file to define your main agent's personality.",
  "## Identity",
  "- You are the user's personal AI assistant and engineering partner.",
  "## Communication Style",
  "- Concise by default. One sentence when one sentence works.",
  '- No filler ("Great question!", "I\'d be happy to help!")',
  "- Match the user's energy — casual when casual, focused when focused.",
  "## Values",
  "- Correctness over speed, but speed matters too",
  "- Action over discussion — once direction is clear, execute",
  '- Honesty — say "I don\'t know" or "this is a bad idea" when true',
  "## Boundaries",
  "- Ask before destructive operations",
  "- Never share personal/ content with worker agents",
])

/** True when soul.md is the seed template (whole or in part, possibly name-seeded by the old
 *  `seedSoulName`): every line is a template line, so it carries nothing the user wrote. */
function isPristineSoul(text: string): boolean {
  const lines = text.split("\n").map(l => l.trim()).filter(Boolean)
  const name = lines[0]?.startsWith("# ") ? lines[0].slice(2) : ""
  return lines.length > 0 && lines.every((line, i) => {
    if (SOUL_TEMPLATE_LINES.has(line)) return true
    if (!name) return false
    // seedSoulName's three substitutions.
    return (i === 0 && line === `# ${name}`)
      || line === `Customize this file to define ${name}'s personality.`
      || line === `- You are ${name}, the user's personal AI assistant and engineering partner.`
  })
}

/** One-time move of the retired identity files into Agent memory (spec §6); boot runs it through
 *  [runAgentMemoryMigrationOnce]. Covers archived Agents too, so a restored one finds its memory ready:
 *  - `~/.mux/soul.md` → the default Agent (else the oldest) under "Identity (migrated from soul.md)";
 *  - each Agent's `<workdir>/focus.md` and `<workdir>/soul.md` → that Agent, under "Identity (migrated from
 *    focus.md)" and "Identity (migrated from <folder>/soul.md)" (so a default Agent keeps both souls).
 *  Idempotent: a section is appended only when its heading is not in the history yet. Originals are
 *  never modified or deleted. Every Agent ends up with both memory files. */
export function migrateToAgentMemory(opts: { muxHome: string; agents: MigratingAgent[] }): void {
  if (opts.agents.length === 0) return
  const read = (p: string) => (existsSync(p) ? readFileSync(p, "utf8").trim() : "")
  const add = (id: string, heading: string, body: string) => {
    if (!body) return
    const history = readFileSync(agentMemoryPaths(id, opts.muxHome).history, "utf8")
    if (!history.includes(`## ${heading}\n`)) appendAgentMemory(id, heading, body, opts.muxHome)
  }
  for (const a of opts.agents) ensureAgentMemoryFiles(a.id, opts.muxHome)

  const shared = read(join(opts.muxHome, "soul.md"))
  // Same choice as registry defaultPA(): the flagged row, else the oldest Agent.
  const target = opts.agents.find(a => a.is_default)
    ?? opts.agents.slice().sort((a, b) => a.created_at.localeCompare(b.created_at))[0]!
  if (!isPristineSoul(shared)) add(target.id, "Identity (migrated from soul.md)", shared)

  for (const a of opts.agents) {
    add(a.id, "Identity (migrated from focus.md)", read(join(a.workdir, "focus.md")))
    add(a.id, `Identity (migrated from ${basename(a.workdir)}/soul.md)`, read(join(a.workdir, "soul.md")))
  }
}

/** Settings key of the one-time marker (a key→JSON row like `telemetry.installId`). */
export const SETTINGS_KEY_AGENT_MEMORY_MIGRATED = "migrations.agentMemory"

/** Run [migrateToAgentMemory] once per install: skipped when the marker is set, the marker set
 *  after a run that did not throw. So Agents created later never absorb the retired files, and a
 *  migrated section the user deleted stays deleted. True when it ran. */
export function runAgentMemoryMigrationOnce(opts: {
  muxHome: string
  agents: MigratingAgent[]
  settings: { get<T>(key: string): T | undefined; set(key: string, value: unknown): void }
}): boolean {
  if (opts.settings.get(SETTINGS_KEY_AGENT_MEMORY_MIGRATED)) return false
  migrateToAgentMemory({ muxHome: opts.muxHome, agents: opts.agents })
  opts.settings.set(SETTINGS_KEY_AGENT_MEMORY_MIGRATED, { at: new Date().toISOString() })
  return true
}
