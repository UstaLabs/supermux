import { existsSync, readFileSync } from "fs"
import { join } from "path"
import { getMuxHome, initMux } from "./init"
import { rebuildIndex } from "./rebuild"
import type { AgentRole } from "./injector"

function readTrimmed(path: string | undefined): string {
  return path && existsSync(path) ? readFileSync(path, "utf8").trim() : ""
}

/**
 * The memory part of a session's instructions: the live domain index, then (PA only) the soul,
 * then the workdir's focus. The rules for reading/writing memory live in the agent header and
 * environment.md; this only carries the data.
 */
export function buildMemoryPreamble(role: AgentRole, workdir?: string): string {
  const home = getMuxHome()
  if (!existsSync(join(home, "agents.md"))) initMux(home)
  rebuildIndex(home)
  const lines = [readFileSync(join(home, "agents.md"), "utf8").trim()]

  if (role === "main" || role === "personal_assistant") {
    // A workdir soul.md overrides the shared one (mux:new-personal-agent writes it).
    const soul = readTrimmed(workdir && join(workdir, "soul.md")) || readTrimmed(join(home, "soul.md"))
    if (soul) lines.push("", "# Your soul", "", soul)
    lines.push("", `As the personal assistant, also read the files in \`${home}/personal/\` for the user's identity and preferences. Workers do not receive these.`)
  }

  const focus = readTrimmed(workdir && join(workdir, "focus.md"))
  if (focus) lines.push("", "# Current Focus", "", focus)

  lines.push("")
  return lines.join("\n")
}
