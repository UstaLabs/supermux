import { existsSync, readdirSync, readFileSync } from "fs"
import { basename, dirname, join } from "path"
import { agentCommand, type CommandOrigin, type SlashCommand } from "../types"

type SkillFrontmatter = { name?: string; description?: string; hidden: boolean }

/** A frontmatter value: one line (quotes dropped), or a `>`/`|` block of indented lines joined with spaces. */
function frontmatterValue(lines: string[], key: string): string | undefined {
  const at = lines.findIndex((line) => line.startsWith(`${key}:`))
  if (at < 0) return undefined
  const inline = lines[at]!.slice(key.length + 1).trim()
  if (!/^[>|][-+]?$/.test(inline)) return inline.replace(/^(["'])(.*)\1$/, "$2") || undefined
  const block: string[] = []
  for (const line of lines.slice(at + 1)) {
    if (!/^\s+\S/.test(line)) break
    block.push(line.trim())
  }
  return block.join(" ") || undefined
}

function readSkillFrontmatter(skillMd: string): SkillFrontmatter {
  try {
    const m = readFileSync(skillMd, "utf8").match(/^---\r?\n([\s\S]*?)\r?\n---/)
    if (!m) return { hidden: false }
    const lines = m[1]!.split(/\r?\n/)
    return {
      name: frontmatterValue(lines, "name"),
      description: frontmatterValue(lines, "description"),
      // agy's own switch: the skill stays usable by the agent but is no slash command.
      hidden: frontmatterValue(lines, "disable-slash-command") === "true",
    }
  } catch {
    return { hidden: false }
  }
}

/**
 * The plugin a `<plugin>/skills` dir belongs to: the name in its `.claude-plugin/plugin.json` (supermux's
 * "mux-core" folder is the "mux" plugin), else the plugin folder's own name.
 */
export function pluginOfSkillsDir(skillsDir: string): string {
  const pluginDir = dirname(skillsDir)
  try {
    const name = JSON.parse(readFileSync(join(pluginDir, ".claude-plugin", "plugin.json"), "utf8"))?.name
    if (typeof name === "string" && name) return name
  } catch {}
  return basename(pluginDir)
}

/**
 * Read-only scan of skills dirs (`<dir>/<name>/SKILL.md`): name and description from the frontmatter (the
 * folder name when it has none), a skill marked `disable-slash-command: true` left out, the first of a name
 * kept. [origin] says where each dir's skills come from. Only for an agent that reports no list of its own
 * (Antigravity).
 */
export function scanSkillDirs(skillsDirs: string[], origin?: (dir: string) => CommandOrigin | undefined): SlashCommand[] {
  const names = new Set<string>()
  const out: SlashCommand[] = []
  for (const dir of skillsDirs) {
    if (!existsSync(dir)) continue
    const from = origin?.(dir)
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue
      const skillMd = join(dir, entry.name, "SKILL.md")
      if (!existsSync(skillMd)) continue
      const meta = readSkillFrontmatter(skillMd)
      const name = meta.name ?? entry.name
      if (meta.hidden || !name || names.has(name)) continue
      names.add(name)
      out.push(agentCommand({ name, sigil: "/", description: meta.description, origin: from }))
    }
  }
  return out
}
