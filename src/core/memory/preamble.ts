import { existsSync, readFileSync } from "fs"
import { join } from "path"
import { getMuxHome, initMux } from "./init"
import { rebuildIndex } from "./rebuild"
import type { AgentRole } from "./injector"
import { buildNamingRule } from "../session-manager/naming"

export function buildMemoryPreamble(role: AgentRole, name?: string, workdir?: string): string {
  const home = getMuxHome()
  if (!existsSync(join(home, "agents.md"))) initMux(home)
  rebuildIndex(home)
  const agentsIndex = readFileSync(join(home, "agents.md"), "utf8").trim()

  const lines: string[] = []

  // Identity FIRST, and stated emphatically. Claude's base system prompt says
  // "You are Claude Code", so without an explicit override it answers "Claude
  // Code" to "what's your name?". The broker named this session (the user picks
  // the PA's name in the wizard); make Claude actually own that name. codex/
  // cursor get their name via buildAgentHeader; Claude only gets this preamble.
  const named = name ? `"${name}", ` : ""
  if (name) {
    lines.push(
      `Your name is "${name}" — the user chose it for this session. When asked ` +
        `your name, answer "${name}" (not "Claude" or "Claude Code").`,
      "",
    )
  }

  lines.push("# Shared Memory System", "")

  if (role === "main") {
    lines.push(`You are ${named}the main agent (personal assistant).`)
  } else {
    lines.push(`You are ${named}a worker agent.`)
  }

  lines.push("")
  lines.push(`Your shared memory lives at \`${home}\`. Available knowledge domains:`)
  lines.push("")
  lines.push(agentsIndex)
  lines.push("")
  lines.push(
    `Read the specific \`${home}/domains/<topic>.md\` file when a domain is ` +
      `relevant to your task. Write durable findings back per the rules in your ` +
      `environment instructions (append under a \`## Title (YYYY-MM-DD)\` heading; ` +
      `use \`domains/_inbox.md\` if unsure where it belongs).`
  )

  if (role === "main") {
    const workdirSoul = workdir && existsSync(join(workdir, "soul.md"))
      ? readFileSync(join(workdir, "soul.md"), "utf8").trim()
      : ""
    if (workdirSoul) {
      lines.push("")
      lines.push(workdirSoul)
      lines.push("")
      lines.push(
        `Also read the files in \`${home}/personal/\` for the user's identity and preferences.`
      )
    } else {
      lines.push("")
      lines.push(
        `As the personal assistant, also read \`${home}/soul.md\` and the files in ` +
          `\`${home}/personal/\` for the user's identity and preferences. ` +
          `Workers do not receive these.`
      )
    }
  }

  if (workdir && existsSync(join(workdir, "focus.md"))) {
    const focus = readFileSync(join(workdir, "focus.md"), "utf8").trim()
    if (focus) {
      lines.push("")
      lines.push("# Current Focus")
      lines.push("")
      lines.push(focus)
    }
  }

  if (role !== "main" && name) {
    lines.push("")
    lines.push(buildNamingRule(name))
  }

  lines.push("")
  return lines.join("\n")
}
