import type { AgentKind } from "../../shared/agents"
import { buildMemoryPreamble } from "../memory/preamble"
import { readEnvironmentMd } from "./environment"
import { buildAgentHeader } from "./agent-header"

/**
 * A session's instructions: ONE value, handed to the core as session context (Claude: system
 * prompt; Codex: developerInstructions; Cursor: leading prompt block; Grok: session/new rules;
 * OpenCode: context instructions). Nothing is written to the workdir or the session home.
 * Order: rules header, environment.md (reference), memory index (+ soul / focus).
 */
export function sessionInstructions(opts: { agent: AgentKind; sessionName: string; workdir: string; pa?: boolean }): string {
  const role = opts.pa ? "personal_assistant" : "worker"
  return [
    buildAgentHeader({ name: opts.sessionName, role, workdir: opts.workdir, agent: opts.agent }),
    readEnvironmentMd(),
    buildMemoryPreamble(role, opts.workdir),
  ].filter(s => s && s.trim()).join("\n")
}
