import { createHash } from "node:crypto"
import { stat } from "node:fs/promises"
import { isAbsolute } from "node:path"
import { CoreError } from "../errors.js"
import type {
  ContextCapabilities, ContextDrop, ContextItemKind, ContextPolicy, DriverContextSupport, ResolvedContext,
  ResolvedMcpServer, SessionContext,
} from "./types.js"

export type * from "./types.js"

export const MCP_SERVER_NAME = /^[A-Za-z0-9_-]+$/
const KEYS = new Set<string>(["instructions", "skills", "plugins", "mcpServers"])
const KINDS: ContextItemKind[] = ["instructions", "skills", "plugins", "mcpServers"]

function invalid(message: string): CoreError { return new CoreError("invalid_context", message) }

function stringList(value: unknown, field: string): string[] {
  if (!Array.isArray(value) || value.some(entry => typeof entry !== "string")) throw invalid(`${field} must be an array of strings`)
  return [...value]
}

/**
 * Shape check and deep copy of a caller's context (no filesystem access). `undefined` stays
 * `undefined`. Anything malformed → `invalid_context`.
 */
export function normalizeContext(value: unknown, field = "context"): SessionContext | undefined {
  if (value === undefined) return undefined
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid(`${field} must be an object`)
  const input = value as Record<string, unknown>
  for (const key of Object.keys(input)) if (!KEYS.has(key)) throw invalid(`${field}.${key} is not a context field`)
  const out: SessionContext = {}
  if (input.instructions !== undefined) {
    if (typeof input.instructions === "string") out.instructions = input.instructions
    else out.instructions = stringList(input.instructions, `${field}.instructions`)
  }
  for (const key of ["skills", "plugins"] as const) {
    if (input[key] === undefined) continue
    const paths = stringList(input[key], `${field}.${key}`)
    for (const path of paths) if (!path || !isAbsolute(path)) throw invalid(`${field}.${key} entries must be absolute paths: ${JSON.stringify(path)}`)
    out[key] = paths
  }
  if (input.mcpServers !== undefined) {
    if (!Array.isArray(input.mcpServers)) throw invalid(`${field}.mcpServers must be an array`)
    out.mcpServers = input.mcpServers.map((server, index) => normalizeServer(server, `${field}.mcpServers[${index}]`))
  }
  return out
}

function normalizeServer(value: unknown, field: string): ResolvedMcpServer {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid(`${field} must be an object`)
  const server = value as Record<string, unknown>
  for (const key of Object.keys(server)) {
    if (key !== "name" && key !== "command" && key !== "args" && key !== "env") throw invalid(`${field}.${key} is not an MCP server field`)
  }
  if (typeof server.name !== "string" || !MCP_SERVER_NAME.test(server.name)) throw invalid(`${field}.name must match ${MCP_SERVER_NAME}`)
  if (typeof server.command !== "string" || !server.command) throw invalid(`${field}.command must be a nonempty string`)
  const args = server.args === undefined ? [] : stringList(server.args, `${field}.args`)
  const env: Record<string, string> = {}
  if (server.env !== undefined) {
    if (!server.env || typeof server.env !== "object" || Array.isArray(server.env)) throw invalid(`${field}.env must be an object of strings`)
    for (const [key, entry] of Object.entries(server.env)) {
      if (!key || typeof entry !== "string") throw invalid(`${field}.env must be an object of strings`)
      env[key] = entry
    }
  }
  return { name: server.name, command: server.command, args, env }
}

export function normalizePolicy(value: unknown, field = "contextPolicy"): ContextPolicy | undefined {
  if (value === undefined) return undefined
  if (value !== "error" && value !== "warn") throw invalid(`${field} must be "error" or "warn"`)
  return value
}

function instructionList(value: SessionContext["instructions"]): string[] {
  if (value === undefined) return []
  return typeof value === "string" ? [value] : value
}

/** Core default first, then the session's own: instructions and lists concatenated in that order. */
export function mergeContexts(base: SessionContext | undefined, own: SessionContext | undefined): SessionContext {
  const instructions = [...instructionList(base?.instructions), ...instructionList(own?.instructions)]
  return {
    ...(instructions.length ? { instructions } : {}),
    ...(base?.skills?.length || own?.skills?.length ? { skills: [...base?.skills ?? [], ...own?.skills ?? []] } : {}),
    ...(base?.plugins?.length || own?.plugins?.length ? { plugins: [...base?.plugins ?? [], ...own?.plugins ?? []] } : {}),
    ...(base?.mcpServers?.length || own?.mcpServers?.length ? { mcpServers: [...base?.mcpServers ?? [], ...own?.mcpServers ?? []] } : {}),
  }
}

/** Instructions joined into one text (blank entries skipped); undefined when there are none. */
export function joinInstructions(value: SessionContext["instructions"]): string | undefined {
  const parts = instructionList(value).filter(part => part.trim())
  return parts.length ? parts.join("\n\n") : undefined
}

async function directory(path: string, field: string): Promise<void> {
  let info
  try { info = await stat(path) } catch { throw invalid(`${field} ${path} does not exist`) }
  if (!info.isDirectory()) throw invalid(`${field} ${path} must be a directory`)
}

/**
 * Validates a merged context against the filesystem: every skills/plugins entry an existing
 * absolute directory, MCP server names unique. Duplicate paths are applied once.
 */
export async function resolveContext(merged: SessionContext): Promise<ResolvedContext> {
  const context = normalizeContext(merged) ?? {}
  const skills = [...new Set(context.skills ?? [])]
  const plugins = [...new Set(context.plugins ?? [])]
  for (const path of skills) await directory(path, "skills folder")
  for (const path of plugins) await directory(path, "plugin")
  const mcpServers = (context.mcpServers ?? []) as ResolvedMcpServer[]
  const names = new Set<string>()
  for (const server of mcpServers) {
    if (names.has(server.name)) throw invalid(`Duplicate MCP server name ${server.name}`)
    names.add(server.name)
  }
  const instructions = joinInstructions(context.instructions)
  return { ...(instructions !== undefined ? { instructions } : {}), skills, plugins, mcpServers }
}

export function isEmptyContext(context: ResolvedContext): boolean {
  return context.instructions === undefined && !context.skills.length && !context.plugins.length && !context.mcpServers.length
}

/** The table for a driver that declares no context support. */
export function noContextCapabilities(note = "This driver declares no session context support"): ContextCapabilities {
  return Object.fromEntries(KINDS.map(kind => [kind, { support: "unsupported", note }])) as ContextCapabilities
}

/**
 * What this launch cannot apply: items the driver marks unsupported, instructions that differ
 * from the ones the conversation was created with (agents that fix them at creation), and the
 * driver's own finer drops (plugin parts it cannot map).
 */
export function contextDrops(
  context: ResolvedContext,
  support: DriverContextSupport | undefined,
  launch: "create" | "resume" | "fork",
  createdInstructions: string | undefined,
): ContextDrop[] {
  const capabilities = support?.capabilities ?? noContextCapabilities()
  const drops: ContextDrop[] = []
  const unsupported = (kind: ContextItemKind) => capabilities[kind]?.support === "unsupported"
  if (context.instructions !== undefined && unsupported("instructions")) {
    drops.push({ kind: "instructions", item: "instructions", reason: capabilities.instructions.note })
  } else if (support?.instructionsFixedAtCreation && launch !== "create" && (context.instructions ?? "") !== (createdInstructions ?? "")) {
    drops.push({
      kind: "instructions", item: "instructions",
      reason: "This agent fixes instructions when the conversation is created; they cannot change on resume or fork",
    })
  }
  for (const path of context.skills) if (unsupported("skills")) drops.push({ kind: "skills", item: path, reason: capabilities.skills.note })
  for (const path of context.plugins) if (unsupported("plugins")) drops.push({ kind: "plugins", item: path, reason: capabilities.plugins.note })
  for (const server of context.mcpServers) if (unsupported("mcpServers")) drops.push({ kind: "mcpServers", item: server.name, reason: capabilities.mcpServers.note })
  if (support?.drops) {
    const seen = new Set(drops.map(drop => `${drop.kind}\0${drop.item}`))
    for (const drop of support.drops(context)) {
      const key = `${drop.kind}\0${drop.item}`
      if (!seen.has(key)) { seen.add(key); drops.push(drop) }
    }
  }
  return drops
}

export function unsupportedError(agent: string, drops: ContextDrop[]): CoreError {
  const list = drops.map(drop => `${drop.kind === drop.item ? drop.kind : `${drop.kind} ${drop.item}`}: ${drop.reason}`).join("; ")
  return new CoreError("context_unsupported", `${agent} cannot apply this session context: ${list}`)
}

/** Whether two stored session contexts are the same (key order and absent-vs-empty ignored). */
export function sameContext(a: SessionContext | undefined, b: SessionContext | undefined): boolean {
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b))
}

function canonical(context: SessionContext | undefined) {
  return {
    instructions: instructionList(context?.instructions),
    skills: context?.skills ?? [],
    plugins: context?.plugins ?? [],
    mcpServers: (context?.mcpServers ?? []).map(server => ({
      name: server.name, command: server.command, args: server.args ?? [],
      env: Object.fromEntries(Object.entries(server.env ?? {}).sort(([a], [b]) => a.localeCompare(b))),
    })),
  }
}

export function isContextEmpty(context: SessionContext | undefined): boolean {
  return !context || (!instructionList(context.instructions).length && !context.skills?.length && !context.plugins?.length && !context.mcpServers?.length)
}

/**
 * Digest of what a launch applies (the context minus its drops). Equal digests mean an agent
 * process launched for one would carry the other; see LaunchContext.fingerprint.
 */
export function contextFingerprint(context: ResolvedContext, dropped: ContextDrop[]): string {
  const gone = new Set(dropped.map(drop => `${drop.kind}\0${drop.item}`))
  const kept = (kind: ContextItemKind, item: string) => !gone.has(`${kind}\0${item}`)
  const applied = {
    instructions: kept("instructions", "instructions") ? context.instructions ?? null : null,
    skills: context.skills.filter(path => kept("skills", path)),
    plugins: context.plugins.filter(path => kept("plugins", path)),
    mcpServers: context.mcpServers.filter(server => kept("mcpServers", server.name)).map(server => ({
      name: server.name, command: server.command, args: server.args,
      env: Object.fromEntries(Object.entries(server.env).sort(([a], [b]) => a.localeCompare(b))),
    })),
    parts: dropped.filter(drop => drop.item.endsWith(")")).map(drop => `${drop.kind}\0${drop.item}`).sort(),
  }
  return createHash("sha256").update(JSON.stringify(applied)).digest("hex").slice(0, 32)
}

/** The fingerprint of a launch without any session context. */
export const EMPTY_CONTEXT_FINGERPRINT = "none"
