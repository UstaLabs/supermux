import { writeFileSync, mkdirSync, chmodSync } from "fs"
import { join } from "path"
import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

const CODEX_REPLY_RULE = [
  "# Codex reply requirement",
  "",
  "The user does not see tool-call output or terminal command output. " +
    "Always end every turn with a non-empty text response addressed to the user. " +
    "Do this after completing the job and also when blocked, interrupted, or unable to finish.",
].join("\n")

// codex sessions are always workers (PAs run on Claude). The file leads with a
// short, commanding identity+rules header (named, imperative) so the model
// actually follows the memory/reply rules, then the environment reference and
// the memory index below it. Reply mechanics (stream text + reply tool for files)
// live in the header; there is no separate codex-preamble.md anymore. Skills are no longer listed here —
// they reach codex via the plugin host (the marketplace install), discovered
// natively as `<plugin>:<name>`; a hand-built index would only duplicate and
// drift from that.
export function writeCodexPreamble(opts: { codexHome: string; sessionName: string; workdir: string }): void {
  mkdirSync(opts.codexHome, { recursive: true, mode: 0o700 })
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  const content = [header, CODEX_REPLY_RULE, env, memory].filter(s => s && s.trim()).join("\n")
  const dest = join(opts.codexHome, "AGENTS.md")
  writeFileSync(dest, content, { encoding: "utf8", mode: 0o600 })
  chmodSync(dest, 0o600)
}
