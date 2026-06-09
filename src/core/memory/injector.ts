import { getMuxHome, initMux } from "./init"
import { rebuildIndex } from "./rebuild"

export type AgentRole = "main" | "worker"

export interface InjectionOpts {
  role: AgentRole
  taskDescription?: string
}

export function buildMemoryInjection(opts: InjectionOpts): string {
  const home = initMux()
  rebuildIndex(home)

  const lines: string[] = []

  if (opts.role === "main") {
    lines.push(`Read ${home}/agents.md. You are the main agent.`)
  } else {
    lines.push(`Read ${home}/agents.md. You are a worker agent.`)
    if (opts.taskDescription) {
      lines.push(`Task: ${opts.taskDescription}`)
    }
  }

  return lines.join("\n")
}
