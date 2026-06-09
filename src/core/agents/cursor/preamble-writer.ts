import { writeFileSync, mkdirSync, chmodSync, existsSync, appendFileSync, readFileSync } from "fs"
import { join } from "path"
import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

// cursor-agent loads project rules from <workspace>/.cursor/rules/*.mdc (a
// directory of .mdc files with frontmatter), NOT from $HOME/.cursor/rules. This
// was verified empirically: a single $HOME/.cursor/rules file is silently
// ignored, while <workspace>/.cursor/rules/mux.mdc with `alwaysApply: true`
// is loaded. So the rule must go into the session's working directory.
const RULE_REL = join(".cursor", "rules", "mux.mdc")

export function writeCursorPreamble(opts: { workdir: string; sessionName: string }): void {
  const rulesDir = join(opts.workdir, ".cursor", "rules")
  mkdirSync(rulesDir, { recursive: true })

  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  // alwaysApply frontmatter makes cursor load the rule on every turn.
  const frontmatter = "---\ndescription: supermux session rules\nalwaysApply: true\n---\n\n"
  // Skills are not listed here — cursor discovers them natively via the plugin
  // host (--plugin-dir), namespaced as `<plugin>:<name>`.
  const body = [header, env, memory].filter(s => s && s.trim()).join("\n")
  const content = frontmatter + body
  writeFileSync(join(opts.workdir, RULE_REL), content, { encoding: "utf8", mode: 0o644 })

  // The rule lives inside the user's project dir, so keep it out of their git
  // status/commits via a LOCAL exclude (.git/info/exclude) — never touch the
  // tracked .gitignore. No-op when the workspace is not a git repo.
  excludeFromGit(opts.workdir)
}

function excludeFromGit(workdir: string): void {
  const infoDir = join(workdir, ".git", "info")
  if (!existsSync(infoDir)) return
  const excludePath = join(infoDir, "exclude")
  const line = RULE_REL.split(/[\\/]/).join("/")
  const current = existsSync(excludePath) ? readFileSync(excludePath, "utf8") : ""
  if (current.split("\n").includes(line)) return
  appendFileSync(excludePath, (current.endsWith("\n") || current === "" ? "" : "\n") + line + "\n", "utf8")
}
