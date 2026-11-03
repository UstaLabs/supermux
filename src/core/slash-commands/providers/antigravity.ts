import { existsSync } from "fs"
import { dirname, join } from "path"
import { AgentKind } from "../../../shared/agents"
import { home } from "../../../shared/home"
import { agentCommand, type AgentCommandList, type AgentCommandProvider, type ProviderCtx, type SlashCommand } from "../types"
import { pluginOfSkillsDir, scanSkillDirs } from "./skill-dirs"

/**
 * agy's own commands: its 9 system commands and the 2 built-in skills it mounts, as agy 1.4.12 lists them
 * itself (its `agy acp` command push, captured 2026-11-03 in a folder with no skills of its own). They are
 * fixed per agy version; stream-json agy expands them as prompt text too (verified: `/grill-me` arrives with
 * its template). agy has no way to list them without a session, and supermux never starts one to ask.
 */
export const AGY_SYSTEM_COMMANDS: ReadonlyArray<{ name: string; description: string }> = [
  { name: "goal", description: "Run until the specified goal is completely finished." },
  { name: "compact", description: "Compact the conversation now (summarize history to free up the context window)." },
  { name: "schedule", description: "Run an instruction on a recurring schedule or as a one-time timer." },
  { name: "browser", description: "Invoke a browser agent for web tasks." },
  { name: "plan", description: "Plan carefully before executing a task." },
  { name: "grill-me", description: "Interview me to align on a plan." },
  { name: "teamwork", description: "Invoke a team of agents to autonomously tackle large projects." },
  { name: "learn", description: "Reflect on recent successes or corrections to capture reusable skills or rules." },
  { name: "boost", description: "Invoke the Boost multi-agent orchestrator for complex tasks." },
  { name: "agy-customizations", description: "Comprehensive guide and reference for the Antigravity Customization System. Use to explain how customizations work, their loading priority, discovery mechanisms, and to guide the creation of skills, rules, plugins, hooks, and MCP servers." },
  { name: "antigravity-guide", description: "Provides a comprehensive guide, quick reference, and sitemap for Google Antigravity (AGY), including the Antigravity CLI (agy), Antigravity 2.0, Antigravity IDE, Python SDK, slash commands, keybindings, and customizations (skills, rules, MCP, sidecars). Activate this skill when the user asks questions about how to use, configure, or customize Antigravity, AGY, the agy CLI, the Antigravity IDE, or Antigravity 2.0." },
]

/** The workspace customization folders agy reads skills from, at each level (its own docs, verified live on 1.4.12). */
const WORKSPACE_ROOTS = [".agents", ".agent", "_agents", "_agent"]

/** `workdir` and its parents up to the git repository's root; outside a repository only `workdir` (agy walks up only inside git). */
function workspaceLevels(workdir: string): string[] {
  const levels: string[] = []
  for (let dir = workdir; ; dir = dirname(dir)) {
    levels.push(dir)
    if (existsSync(join(dir, ".git"))) return levels
    if (dirname(dir) === dir) return [workdir]
  }
}

/** The folder's own skill dirs, nearest first: `<level>/{.agents,.agent,_agents,_agent}/skills`. */
export function antigravityWorkspaceSkillDirs(workdir: string): string[] {
  return workspaceLevels(workdir).flatMap((level) => WORKSPACE_ROOTS.map((root) => join(level, root, "skills")))
}

// agy reports no command list without a session (1.4.12: its stream-json init frame lists tools only and its
// input takes only user turns), and supermux never starts one to learn it. So the list is what agy would load,
// read from disk, nearest first like agy's own precedence: the folder's skills (up to the git root), the user's
// global skills (~/.gemini/config/skills, linked into every session's shadow HOME), the session's plugin skills
// (handed in via agentContext), then agy's own commands. A name is listed once; a skill marked
// `disable-slash-command: true` is left out. Skills are text-insert: `/name ` goes as a normal prompt and agy
// runs it. Not read: skills.json/plugins.json declarations and a plugin's enabled state. Each entry's source is
// the folder it was read from.
export class AntigravityCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Antigravity

  async list(ctx: ProviderCtx): Promise<AgentCommandList | undefined> {
    const a = ctx.agentContext as { skillsDirs?: string[]; userHome?: string } | undefined
    const own = scanSkillDirs(antigravityWorkspaceSkillDirs(ctx.workdir), () => ({ source: "project" }))
    const userDir = join(a?.userHome ?? home(), ".gemini", "config", "skills")
    const global = scanSkillDirs([userDir, ...(a?.skillsDirs ?? [])], (dir) =>
      dir === userDir ? { source: "user" } : { source: "plugin", plugin: pluginOfSkillsDir(dir) },
    )
    const builtins = AGY_SYSTEM_COMMANDS.map((c) => agentCommand({ name: c.name, sigil: "/", description: c.description, origin: { source: "builtin" } }))
    const seen = new Set<string>()
    const commands: SlashCommand[] = []
    for (const cmd of [...own, ...global, ...builtins]) {
      if (seen.has(cmd.id)) continue
      seen.add(cmd.id)
      commands.push(cmd)
    }
    const ownIds = new Set(own.map((c) => c.id))
    return { commands, projectIds: commands.filter((c) => ownIds.has(c.id)).map((c) => c.id) }
  }
}
