/**
 * Host MCP servers (slice C2): MCP servers whose tools are TypeScript functions in the host
 * process. `mcpServer()` builds one; `tool()` declares a tool. All MCP logic runs on the official
 * SDK (`@modelcontextprotocol/server` v2): one SDK `McpServer` per (session, server) connection.
 */
import { McpServer, isCallToolResult, type CallToolResult } from "@modelcontextprotocol/server"
import { z } from "zod"
import { CoreError } from "../errors.js"
import { HOST_SERVER_BRAND, isHostMcpServer } from "./brand.js"

export { isHostMcpServer }

/** What a tool's `run` (and a `create` factory) learns about the caller. */
export type ToolContext = {
  /** The core session whose agent (or one of its subagents) made the call. */
  sessionId: string
  agent: string
  account?: string
  /** The host server's name. */
  server: string
  /**
   * Tool calls: aborted when the agent cancels the call (`notifications/cancelled`) or the session
   * is interrupted. `create` factories: aborted when the connection closes.
   */
  signal: AbortSignal
}

/** A connection's context, as `create` gets it. */
export type ConnectionContext = ToolContext

/** `string` → one text block; a `CallToolResult` (`{ content: [...] }`) as is; anything else → JSON text (and `structuredContent` when the tool has `output`). A throw → an `isError` result with the message. */
export type ToolResult = string | CallToolResult | unknown

type AnySchema = z.ZodType

export type ToolDefinition<I extends AnySchema | undefined = undefined, O extends AnySchema | undefined = undefined> = {
  description: string
  title?: string
  /** zod 4 schema of the arguments (usually `z.object({...})`); validated before `run`. Omitted: no arguments. */
  input?: I
  /** zod 4 schema of the structured result; a non-text result is then sent as `structuredContent` too. */
  output?: O
  annotations?: { readOnlyHint?: boolean; destructiveHint?: boolean; idempotentHint?: boolean; openWorldHint?: boolean; title?: string }
  run(
    args: I extends AnySchema ? z.output<I> : Record<string, never>,
    ctx: ToolContext,
  ): Promise<O extends AnySchema ? z.input<O> | CallToolResult | string : ToolResult> | (O extends AnySchema ? z.input<O> | CallToolResult | string : ToolResult)
}

const TOOL = Symbol.for("supermux-core.mcp.tool")

/** A tool built with `tool()`. */
export type HostTool = {
  readonly [TOOL]: true
  readonly description: string
  readonly title?: string
  readonly input?: AnySchema
  readonly output?: AnySchema
  readonly annotations?: ToolDefinition["annotations"]
  readonly run: (args: never, ctx: ToolContext) => unknown
}

export function tool<I extends AnySchema | undefined = undefined, O extends AnySchema | undefined = undefined>(definition: ToolDefinition<I, O>): HostTool {
  if (!definition || typeof definition !== "object") throw new TypeError("tool() takes a definition object")
  if (typeof definition.description !== "string") throw new TypeError("tool description must be a string")
  if (typeof definition.run !== "function") throw new TypeError("tool run must be a function")
  return Object.freeze({ ...definition, [TOOL]: true as const }) as unknown as HostTool
}

export const isHostTool = (value: unknown): value is HostTool => !!value && typeof value === "object" && (value as Record<symbol, unknown>)[TOOL] === true

/**
 * "reload" (default): on an agent that ignores `tools/list_changed` (Codex, Grok, Cursor), a tool
 * added or removed on a live server relaunches each affected session between turns.
 * "live-only": never relaunch; such an agent sees the change at its next launch.
 */
export type ToolChanges = "reload" | "live-only"

export type McpServerOptions =
  | { name: string; instructions?: string; version?: string; toolChanges?: ToolChanges; tools: Record<string, HostTool>; create?: never }
  | { name: string; instructions?: string; version?: string; toolChanges?: ToolChanges; create: (ctx: ConnectionContext) => McpServer | Promise<McpServer>; tools?: never }

export type ToolChange = { op: "add" | "remove"; tool: string }

export const MCP_SERVER_NAME = /^[A-Za-z0-9_-]+$/
const TOOL_NAME = /^[A-Za-z0-9_.-]{1,128}$/

/** A host MCP server: pass it in a session context (or `createCore({ mcpServers })`). Its tools run in this process. */
export class HostMcpServer {
  readonly [HOST_SERVER_BRAND] = true as const
  readonly kind = "host" as const
  readonly name: string
  readonly instructions?: string
  readonly version: string
  readonly toolChanges: ToolChanges
  /** Present for a `create` server (any SDK McpServer); absent for a `tools` server. */
  readonly create?: (ctx: ConnectionContext) => McpServer | Promise<McpServer>
  private readonly toolMap = new Map<string, HostTool>()
  private readonly listeners = new Set<(change: ToolChange) => void>()

  constructor(options: McpServerOptions) {
    if (!options || typeof options !== "object") throw new CoreError("invalid_input", "mcpServer() takes an options object")
    if (typeof options.name !== "string" || !MCP_SERVER_NAME.test(options.name)) throw new CoreError("invalid_input", `MCP server name must match ${MCP_SERVER_NAME}`)
    if (options.instructions !== undefined && typeof options.instructions !== "string") throw new CoreError("invalid_input", "instructions must be a string")
    if (options.toolChanges !== undefined && options.toolChanges !== "reload" && options.toolChanges !== "live-only") throw new CoreError("invalid_input", "toolChanges must be \"reload\" or \"live-only\"")
    const hasTools = options.tools !== undefined, hasCreate = options.create !== undefined
    if (hasTools === hasCreate) throw new CoreError("invalid_input", "mcpServer() takes exactly one of tools or create")
    this.name = options.name
    if (options.instructions !== undefined) this.instructions = options.instructions
    this.version = options.version ?? "1.0.0"
    this.toolChanges = options.toolChanges ?? "reload"
    if (hasCreate) {
      if (typeof options.create !== "function") throw new CoreError("invalid_input", "create must be a function")
      this.create = options.create
    } else {
      if (!options.tools || typeof options.tools !== "object") throw new CoreError("invalid_input", "tools must be an object of tool() values")
      for (const [name, value] of Object.entries(options.tools)) this.check(name, value)
      for (const [name, value] of Object.entries(options.tools)) this.toolMap.set(name, value)
    }
  }

  /** Tool names (a `create` server: empty, its tools live on the SDK servers it builds). */
  get tools(): string[] { return [...this.toolMap.keys()] }

  /** @internal */
  tool(name: string): HostTool | undefined { return this.toolMap.get(name) }

  /** Adds a tool, live in every session attached to this server (see API.md "Host MCP servers"). */
  add(name: string, value: HostTool): this {
    if (this.create) throw new CoreError("unsupported_operation", `${this.name} is a create() server: change tools on the SDK servers it builds`)
    this.check(name, value)
    if (this.toolMap.has(name)) throw new CoreError("invalid_input", `Tool ${name} already exists on ${this.name}`)
    this.toolMap.set(name, value)
    this.notify({ op: "add", tool: name })
    return this
  }

  /** Removes a tool from every attached session. Unknown names are ignored (returns false). */
  remove(name: string): boolean {
    if (this.create) throw new CoreError("unsupported_operation", `${this.name} is a create() server: change tools on the SDK servers it builds`)
    if (!this.toolMap.delete(name)) return false
    this.notify({ op: "remove", tool: name })
    return true
  }

  /** @internal Tool add/remove listener (live connections, the core's reload scheduling). */
  onChange(listener: (change: ToolChange) => void): () => void {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  /** @internal A new SDK server for one connection. */
  async build(ctx: ConnectionContext): Promise<{ server: McpServer; dispose(): void }> {
    if (this.create) {
      const server = await this.create(ctx)
      if (!(server instanceof McpServer)) throw new CoreError("invalid_input", `${this.name}: create() must return an McpServer from @modelcontextprotocol/server`)
      return { server, dispose() {} }
    }
    const server = new McpServer(
      { name: this.name, version: this.version },
      { capabilities: { tools: { listChanged: true } }, ...(this.instructions !== undefined ? { instructions: this.instructions } : {}) },
    )
    const registered = new Map<string, { remove(): void }>()
    const register = (name: string) => {
      const value = this.toolMap.get(name)
      if (value) registered.set(name, registerTool(server, name, value, ctx))
    }
    for (const name of this.toolMap.keys()) register(name)
    if (!registered.size) {
      // The SDK installs tools/list and tools/call with the first tool: an empty server still answers.
      registerTool(server, "__supermux_placeholder", tool({ description: "", run: () => "" }), ctx).remove()
    }
    const unsubscribe = this.onChange(change => {
      // The SDK sends notifications/tools/list_changed on a connected server.
      if (change.op === "add") register(change.tool)
      else { registered.get(change.tool)?.remove(); registered.delete(change.tool) }
    })
    return { server, dispose: unsubscribe }
  }

  private check(name: string, value: HostTool): void {
    if (typeof name !== "string" || !TOOL_NAME.test(name)) throw new CoreError("invalid_input", `Tool name must match ${TOOL_NAME}`)
    if (!isHostTool(value)) throw new CoreError("invalid_input", `Tool ${name} must be built with tool()`)
  }

  private notify(change: ToolChange): void {
    for (const listener of [...this.listeners]) {
      try { listener(change) } catch { /* a listener never breaks the change */ }
    }
  }
}

export function mcpServer(options: McpServerOptions): HostMcpServer { return new HostMcpServer(options) }

function registerTool(server: McpServer, name: string, value: HostTool, connection: ToolContext): { remove(): void } {
  const input = value.input ?? z.object({})
  return server.registerTool(name, {
    description: value.description,
    ...(value.title !== undefined ? { title: value.title } : {}),
    inputSchema: input as never,
    ...(value.output !== undefined ? { outputSchema: value.output as never } : {}),
    ...(value.annotations !== undefined ? { annotations: value.annotations } : {}),
  }, (async (args: unknown, extra: { mcpReq: { signal: AbortSignal } }) => {
    const ctx: ToolContext = { ...connection, signal: extra.mcpReq.signal }
    // A throw becomes an isError result (the SDK's tools/call handler catches it).
    const result = await (value.run as (args: unknown, ctx: ToolContext) => unknown)(args ?? {}, ctx)
    return toCallToolResult(result, value.output !== undefined)
  }) as never)
}

/** A `run` result as an MCP CallToolResult (see ToolResult). */
export function toCallToolResult(result: unknown, structured: boolean): CallToolResult {
  if (typeof result === "string") return { content: [{ type: "text", text: result }] }
  if (isCallToolResult(result)) return result as CallToolResult
  if (result === undefined) return { content: [] }
  const text = JSON.stringify(result)
  return {
    content: [{ type: "text", text: text ?? String(result) }],
    ...(structured && result !== null && typeof result === "object" ? { structuredContent: result as Record<string, unknown> } : {}),
  }
}
