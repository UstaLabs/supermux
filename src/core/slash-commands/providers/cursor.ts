import { existsSync, readdirSync } from "fs"
import { join } from "path"
import { home } from "../../../shared/home"
import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandProvider, type ProviderCtx, type SlashCommand } from "../types"

interface AcpCommand { name: string; description?: string }

export function mapCursorCommands(cmds: AcpCommand[]): SlashCommand[] {
  return cmds.map((c) => agentCommand({ name: c.name, sigil: "/", description: c.description }))
}

/** Extracts the `<dir>` values from `--plugin-dir <dir>` flag pairs. */
export function pluginDirsFromArgs(args: string[]): string[] {
  const dirs: string[] = []
  for (let i = 0; i < args.length - 1; i++) if (args[i] === "--plugin-dir") dirs.push(args[i + 1]!)
  return dirs
}

/**
 * Read-only disk scan for cursor commands + skills, used as a fallback when no
 * ACP `available_commands_update` has been pushed for the session. Scans:
 *   - `.cursor/commands/<name>.md` (name = filename) — project + global (~/.cursor)
 *   - `.cursor/skills/<name>/SKILL.md` (name = dir) — project + global
 *   - each `--plugin-dir <dir>` plugin's `commands/*.md` + `skills/<name>/SKILL.md`
 * Deduped by name.
 */
export function scanCursorCommandsFromDisk(workdir: string, pluginDirs: string[] = []): SlashCommand[] {
  const names = new Set<string>()
  const out: SlashCommand[] = []
  const add = (name: string) => {
    if (!name || names.has(name)) return
    names.add(name)
    out.push(agentCommand({ name, sigil: "/" }))
  }
  const scanCommandsDir = (dir: string) => {
    if (!existsSync(dir)) return
    for (const f of readdirSync(dir)) if (f.endsWith(".md")) add(f.slice(0, -3))
  }
  const scanSkillsDir = (dir: string) => {
    if (!existsSync(dir)) return
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory() && existsSync(join(dir, entry.name, "SKILL.md"))) add(entry.name)
    }
  }
  for (const base of [join(workdir, ".cursor"), join(home(), ".cursor")]) {
    scanCommandsDir(join(base, "commands"))
    scanSkillsDir(join(base, "skills"))
  }
  for (const pluginDir of pluginDirs) {
    scanCommandsDir(join(pluginDir, "commands"))
    scanSkillsDir(join(pluginDir, "skills"))
  }
  return out
}

// Cursor speaks ACP and pushes an `available_commands_update` notification
// (`{sessionUpdate, availableCommands:[{name,description}]}`, skills included).
// This provider prefers the pushed list (fed in via update()); if none has
// arrived for the session it falls back to a read-only disk scan.
export class CursorCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Cursor
  private latest = new Map<string, SlashCommand[]>()
  private readonly scanDisk: (workdir: string, pluginDirs: string[]) => SlashCommand[]

  constructor(opts: { scanDisk?: (workdir: string, pluginDirs: string[]) => SlashCommand[] } = {}) {
    this.scanDisk = opts.scanDisk ?? scanCursorCommandsFromDisk
  }

  /** Called by the cursor runner when an ACP available_commands_update arrives. */
  update(sessionName: string, cmds: AcpCommand[]): void {
    this.latest.set(sessionName, mapCursorCommands(cmds))
  }

  async list(ctx: ProviderCtx): Promise<SlashCommand[]> {
    return this.latest.get(ctx.sessionName) ?? this.scanDisk(ctx.workdir, pluginDirsFromArgs(ctx.pluginSpawnArgs))
  }

  forget(sessionName: string): void {
    this.latest.delete(sessionName)
  }
}
