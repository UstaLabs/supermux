import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

// opencode captures the agent's normal output (we read it from session.prompt's
// return), so — like codex/cursor — a turn that ends with only tool calls and no
// text would show the user nothing. This rule makes the model always finish with
// a text reply.
const OPENCODE_SKILLS_RULE = [
  "# opencode skills",
  "",
  "supermux installs skills as plugins, namespaced `<plugin>:<name>` " +
    "(e.g. `mux:soul`, `superpowers:brainstorming`). OpenCode has a native `skill` tool — " +
    "use it to list and load plugin skills. To USE a skill you must actually LOAD its " +
    "content via the skill tool and follow its steps. NEVER claim to have applied a skill " +
    "you have not actually loaded.",
].join("\n")

const OPENCODE_REPLY_RULE = [
  "# opencode reply requirement",
  "",
  "The user does not see tool-call output or terminal command output. " +
    "Always end every turn with a non-empty text response addressed to the user. " +
    "Do this after completing the job and also when blocked, interrupted, or unable to finish.",
].join("\n")

/** Instruction TEXT only. File placement is library-owned. */
export function openCodeInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  return [header, OPENCODE_SKILLS_RULE, OPENCODE_REPLY_RULE, env, memory].filter((s) => s && s.trim()).join("\n")
}
