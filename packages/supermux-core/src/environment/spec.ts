import type { McpServerSpec } from "./types.js"

const MCP_NAME = /^[A-Za-z0-9_-]+$/

export function requireSpec(spec: object, fields: readonly string[]): void {
  if (!spec || typeof spec !== "object") throw new TypeError("spec is required")
  for (const field of fields) {
    let cur: unknown = spec
    for (const part of field.split(".")) {
      if (cur === null || cur === undefined || typeof cur !== "object" || !(part in (cur as object))) {
        throw new TypeError(`${field} is required`)
      }
      cur = (cur as Record<string, unknown>)[part]
    }
    if (cur === undefined) throw new TypeError(`${field} is required`)
  }
}

export function validateMcpServerNames(servers: McpServerSpec[]): void {
  if (!Array.isArray(servers)) throw new TypeError("mcpServers is required")
  servers.forEach((server, index) => {
    if (!server || typeof server !== "object" || !MCP_NAME.test(server.name ?? "")) {
      throw new TypeError(`mcpServers[${index}].name must match /^[A-Za-z0-9_-]+$/`)
    }
  })
}

export const ENVIRONMENT_FIELDS = ["home", "workdir", "mcpServers", "skillsPaths", "instructions"] as const
