import { writeFileSync, existsSync, appendFileSync, readFileSync } from "fs"
import { join } from "path"
import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

// grok merges AGENTS.md from the git root down to cwd, and honors AGENTS.override.md
// with precedence. To avoid clobbering a user's own AGENTS.md, we write the supermux
// preamble to AGENTS.md ONLY when none exists; otherwise we use AGENTS.override.md.
// Either way the file we create is git-excluded (never touches the tracked tree).
export function writeGrokPreamble(opts: { workdir: string; sessionName: string }): void {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  const body = [header, env, memory].filter((s) => s && s.trim()).join("\n\n")

  const target = existsSync(join(opts.workdir, "AGENTS.md")) ? "AGENTS.override.md" : "AGENTS.md"
  writeFileSync(join(opts.workdir, target), body, { encoding: "utf8", mode: 0o644 })
  excludeFromGit(opts.workdir, target)
}

function excludeFromGit(workdir: string, rel: string): void {
  const infoDir = join(workdir, ".git", "info")
  if (!existsSync(infoDir)) return
  const excludePath = join(infoDir, "exclude")
  const current = existsSync(excludePath) ? readFileSync(excludePath, "utf8") : ""
  if (current.split("\n").includes(rel)) return
  appendFileSync(excludePath, (current.endsWith("\n") || current === "" ? "" : "\n") + rel + "\n", "utf8")
}
