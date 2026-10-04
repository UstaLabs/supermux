/**
 * Session context in flight (slice C1b): patch validation, the per-item plan (live / reload /
 * append / unsupported) and the session's own context after a patch. Core wires it to the
 * running session (core.ts `updateContext`).
 */
import { isAbsolute } from "node:path"
import { CoreError } from "../errors.js"
import { MCP_SERVER_NAME, joinInstructions, normalizeContext } from "./index.js"
import type {
  ContextApplied, ContextCapabilities, ContextChange, ContextItemKind, ContextPatch, ContextUpdateSupport, DriverContextSupport,
  LaunchContext, ResolvedMcpServer, RuntimeContextControl, SessionContext, UpdateContextOptions,
} from "./types.js"

function invalid(message: string): CoreError { return new CoreError("invalid_context", message) }

const PATCH_KEYS = new Set(["instructions", "skills", "plugins", "mcpServers"])

/** Shape check and deep copy of a patch (no filesystem access). Anything malformed → invalid_context. */
export function normalizePatch(value: unknown): ContextPatch {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid("patch must be an object")
  const input = value as Record<string, unknown>
  for (const key of Object.keys(input)) if (!PATCH_KEYS.has(key)) throw invalid(`patch.${key} is not a context field`)
  const out: ContextPatch = {}
  if (input.instructions !== undefined) {
    const normalized = normalizeContext({ instructions: input.instructions }, "patch")
    out.instructions = normalized!.instructions!
  }
  for (const key of ["skills", "plugins"] as const) {
    if (input[key] === undefined) continue
    const ops = operations(input[key], `patch.${key}`)
    const paths = (list: unknown, field: string) => {
      if (list === undefined) return undefined
      if (!Array.isArray(list) || list.some(entry => typeof entry !== "string" || !entry || !isAbsolute(entry))) throw invalid(`${field} must be an array of absolute paths`)
      return [...list] as string[]
    }
    const add = paths(ops.add, `patch.${key}.add`), remove = paths(ops.remove, `patch.${key}.remove`)
    out[key] = { ...(add ? { add } : {}), ...(remove ? { remove } : {}) }
  }
  if (input.mcpServers !== undefined) {
    const ops = operations(input.mcpServers, "patch.mcpServers")
    const add = ops.add === undefined ? undefined : normalizeContext({ mcpServers: ops.add }, "patch.mcpServers.add")!.mcpServers!
    let remove: string[] | undefined
    if (ops.remove !== undefined) {
      if (!Array.isArray(ops.remove) || ops.remove.some(name => typeof name !== "string" || !MCP_SERVER_NAME.test(name))) throw invalid(`patch.mcpServers.remove must be an array of MCP server names`)
      remove = [...ops.remove] as string[]
    }
    out.mcpServers = { ...(add ? { add } : {}), ...(remove ? { remove } : {}) }
  }
  return out
}

function operations(value: unknown, field: string): { add?: unknown; remove?: unknown } {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid(`${field} must be an object with add and/or remove`)
  for (const key of Object.keys(value)) if (key !== "add" && key !== "remove") throw invalid(`${field}.${key} is not add or remove`)
  return value as { add?: unknown; remove?: unknown }
}

export function normalizeUpdateOptions(value: unknown): UpdateContextOptions {
  if (value === undefined) return {}
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new CoreError("invalid_input", "options must be an object")
  const input = value as Record<string, unknown>
  for (const key of Object.keys(input)) if (!["reload", "holdOnCacheImpact", "codexInstructions"].includes(key)) throw new CoreError("invalid_input", `options.${key} is not an updateContext option`)
  if (input.reload !== undefined && input.reload !== "allow" && input.reload !== "never") throw new CoreError("invalid_input", "options.reload must be \"allow\" or \"never\"")
  if (input.holdOnCacheImpact !== undefined && typeof input.holdOnCacheImpact !== "boolean") throw new CoreError("invalid_input", "options.holdOnCacheImpact must be a boolean")
  if (input.codexInstructions !== undefined && input.codexInstructions !== "append") throw new CoreError("invalid_input", "options.codexInstructions must be \"append\"")
  return {
    ...(input.reload !== undefined ? { reload: input.reload as "allow" | "never" } : {}),
    ...(input.holdOnCacheImpact !== undefined ? { holdOnCacheImpact: input.holdOnCacheImpact as boolean } : {}),
    ...(input.codexInstructions !== undefined ? { codexInstructions: "append" as const } : {}),
  }
}

export const changeKey = (change: ContextChange) => `${change.kind}\0${change.op}\0${change.item}`

/**
 * The changes a patch makes to the session's own context, validated against it and the core
 * default: an added path or server must be new, a removed one must be the session's own (the
 * core default cannot be removed per session), and nothing is added and removed at once.
 */
export function patchChanges(own: SessionContext | undefined, base: SessionContext | undefined, patch: ContextPatch): ContextChange[] {
  const changes: ContextChange[] = []
  if (patch.instructions !== undefined) {
    const before = joinInstructions([...list(base?.instructions), ...list(own?.instructions)])
    const after = joinInstructions([...list(base?.instructions), ...list(patch.instructions)])
    if ((before ?? "") !== (after ?? "")) changes.push({ kind: "instructions", op: "replace", item: "instructions" })
  }
  for (const kind of ["skills", "plugins"] as const) {
    const ops = patch[kind]
    if (!ops) continue
    const mine = own?.[kind] ?? [], theirs = base?.[kind] ?? []
    const add = ops.add ?? [], remove = ops.remove ?? []
    duplicates(add, `${kind}.add`); duplicates(remove, `${kind}.remove`)
    for (const path of remove) {
      if (add.includes(path)) throw invalid(`${kind}: ${path} is both added and removed`)
      if (!mine.includes(path)) throw invalid(theirs.includes(path) ? `${kind}: ${path} comes from the core default and cannot be removed per session` : `${kind}: ${path} is not in the session's context`)
      changes.push({ kind, op: "remove", item: path })
    }
    for (const path of add) {
      if (mine.includes(path) || theirs.includes(path)) throw invalid(`${kind}: ${path} is already in the session's context`)
      changes.push({ kind, op: "add", item: path })
    }
  }
  const servers = patch.mcpServers
  if (servers) {
    const mine = (own?.mcpServers ?? []).map(server => server.name), theirs = (base?.mcpServers ?? []).map(server => server.name)
    const add = servers.add ?? [], remove = servers.remove ?? []
    duplicates(add.map(server => server.name), "mcpServers.add"); duplicates(remove, "mcpServers.remove")
    for (const name of remove) {
      if (add.some(server => server.name === name)) throw invalid(`mcpServers: ${name} is both added and removed`)
      if (!mine.includes(name)) throw invalid(theirs.includes(name) ? `mcpServers: ${name} comes from the core default and cannot be removed per session` : `mcpServers: ${name} is not in the session's context`)
      changes.push({ kind: "mcpServers", op: "remove", item: name })
    }
    for (const server of add) {
      if (mine.includes(server.name) || theirs.includes(server.name)) throw invalid(`mcpServers: a server named ${server.name} is already in the session's context`)
      changes.push({ kind: "mcpServers", op: "add", item: server.name })
    }
  }
  return changes
}

function list(value: SessionContext["instructions"]): string[] {
  return value === undefined ? [] : typeof value === "string" ? [value] : value
}

function duplicates(values: string[], field: string): void {
  if (new Set(values).size !== values.length) throw invalid(`${field} lists an entry twice`)
}

/** The session's own context with `changes` (a subset of `patchChanges(own, …, patch)`) applied. */
export function applyChanges(own: SessionContext | undefined, patch: ContextPatch, changes: ContextChange[]): SessionContext {
  const next: SessionContext = structuredClone(own ?? {})
  const has = (kind: ContextItemKind, op: ContextChange["op"], item: string) => changes.some(change => change.kind === kind && change.op === op && change.item === item)
  if (changes.some(change => change.kind === "instructions")) {
    if (list(patch.instructions).length) next.instructions = structuredClone(patch.instructions!)
    else delete next.instructions
  }
  for (const kind of ["skills", "plugins"] as const) {
    const kept = (next[kind] ?? []).filter(path => !has(kind, "remove", path))
    const added = (patch[kind]?.add ?? []).filter(path => has(kind, "add", path))
    const all = [...kept, ...added]
    if (all.length) next[kind] = all
    else delete next[kind]
  }
  const keptServers = (next.mcpServers ?? []).filter(server => !has("mcpServers", "remove", server.name))
  const addedServers = (patch.mcpServers?.add ?? []).filter(server => has("mcpServers", "add", server.name)) as ResolvedMcpServer[]
  const servers = [...keptServers, ...structuredClone(addedServers)]
  if (servers.length) next.mcpServers = servers
  else delete next.mcpServers
  return next
}

/** The in-flight table of a driver without one: every supported item is a reload. */
export function reloadOnlyUpdates(capabilities: ContextCapabilities, note = "This driver applies context only at launch: a change relaunches the agent"): ContextUpdateSupport {
  const instructions = capabilities.instructions.support === "unsupported" ? "unsupported" as const : "reload" as const
  return {
    instructions: { how: instructions, note: instructions === "unsupported" ? capabilities.instructions.note : note },
    skills: { add: "reload", remove: "reload", note },
    plugins: { add: "reload", remove: "reload", note },
    mcpServers: { add: "reload", remove: "reload", note },
  }
}

export type PlanInput = {
  agent: string
  changes: ContextChange[]
  support: DriverContextSupport | undefined
  capabilities: ContextCapabilities
  /** The open runtime's live control; undefined for a session that is not open. */
  runtime: RuntimeContextControl | undefined
  /** The full context after every change that is not unsupported for a static reason. */
  next: LaunchContext | undefined
  options: UpdateContextOptions
  open: boolean
  /** The patch leaves the session with no instructions of its own. */
  instructionsRemoved?: boolean
}

/**
 * Per change: `live` when the driver declares it and the open runtime confirms it, `append` for
 * opted-in Codex instructions, otherwise `reload` (when allowed), otherwise `unsupported`. A
 * session that is not open gets `reload` for everything its next launch can apply.
 */
export function planChanges(input: PlanInput): ContextApplied[] {
  const update = input.support?.update ?? reloadOnlyUpdates(input.capabilities)
  const reloadAllowed = input.options.reload !== "never"
  return input.changes.map(change => {
    const capability = input.capabilities[change.kind]
    if (capability.support === "unsupported") return { ...change, how: "unsupported", reason: capability.note }
    if (change.kind === "instructions") {
      const how = update.instructions.how
      if (how === "unsupported") return { ...change, how: "unsupported", reason: update.instructions.note }
      if (how === "append") {
        if (input.options.codexInstructions !== "append") {
          return { ...change, how: "unsupported", reason: `${update.instructions.note}; pass codexInstructions: "append" to add the new instructions to the conversation instead` }
        }
        if (!input.open || !input.runtime?.appendInstructions) return { ...change, how: "unsupported", reason: "Appending instructions needs the open session" }
        if (input.instructionsRemoved || input.next?.instructions === undefined) return { ...change, how: "unsupported", reason: "Appended instructions cannot remove instructions" }
        return { ...change, how: "append" }
      }
      if (!input.open) return { ...change, how: "reload" }
      return reloadAllowed ? { ...change, how: "reload" } : { ...change, how: "unsupported", reason: `${update.instructions.note}, and reload is "never"` }
    }
    if (!input.open) return { ...change, how: "reload" }
    const op = change.op === "remove" ? "remove" : "add"
    if (update[change.kind][op] === "live" && input.runtime && input.next && input.runtime.live(change, input.next)) return { ...change, how: "live" }
    return reloadAllowed ? { ...change, how: "reload" } : { ...change, how: "unsupported", reason: `${update[change.kind].note}; it needs a relaunch, and reload is "never"` }
  })
}
