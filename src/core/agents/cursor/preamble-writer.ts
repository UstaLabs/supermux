import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

// cursor-agent loads project rules from <workspace>/.cursor/rules/*.mdc (a
// directory of .mdc files with frontmatter), NOT from $HOME/.cursor/rules. This
// was verified empirically: a single $HOME/.cursor/rules file is silently
// ignored, while <workspace>/.cursor/rules/mux.mdc with `alwaysApply: true`
// is loaded. File placement is library-owned; this module owns TEXT only.
// Skills are not listed here — cursor discovers them natively via the plugin
// host (--plugin-dir), namespaced as `<plugin>:<name>`.

/** Instruction TEXT only (no front matter). File placement is library-owned. */
export function cursorInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  return [header, env, memory].filter(s => s && s.trim()).join("\n")
}
