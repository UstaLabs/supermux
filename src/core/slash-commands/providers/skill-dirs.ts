import { existsSync, readdirSync, readFileSync } from "fs"
import { join } from "path"
import { agentCommand, type SlashCommand } from "../types"

function skillNameFromSkillMd(skillMd: string, fallback: string): string {
  try {
    const content = readFileSync(skillMd, "utf8")
    const m = content.match(/^---\r?\n([\s\S]*?)\r?\n---/)
    if (m) {
      const nameLine = m[1]!.match(/^name:\s*(.+)$/m)
      if (nameLine) return nameLine[1]!.trim()
    }
  } catch {}
  return fallback
}

/**
 * Read-only scan of skills dirs (`<dir>/<name>/SKILL.md`), names from the
 * frontmatter. Only for an agent that reports no list of its own (Antigravity).
 */
export function scanSkillDirs(skillsDirs: string[]): SlashCommand[] {
  const names = new Set<string>()
  const out: SlashCommand[] = []
  for (const dir of skillsDirs) {
    if (!existsSync(dir)) continue
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue
      const skillMd = join(dir, entry.name, "SKILL.md")
      if (!existsSync(skillMd)) continue
      const name = skillNameFromSkillMd(skillMd, entry.name)
      if (!name || names.has(name)) continue
      names.add(name)
      out.push(agentCommand({ name, sigil: "/" }))
    }
  }
  return out
}
