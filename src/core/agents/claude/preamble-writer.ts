import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

export function claudeWorkerInstructions(opts: { sessionName: string; workdir: string }): string {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  return [header, env, memory].filter(s => s && s.trim()).join("\n")
}
