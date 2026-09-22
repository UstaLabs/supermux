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

/** Instruction TEXT only. File placement is library-owned. */
export function codexInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  return [header, CODEX_REPLY_RULE, env, memory].filter(s => s && s.trim()).join("\n")
}
