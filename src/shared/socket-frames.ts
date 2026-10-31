export type ToolOperation = { name: string; args: Record<string, unknown>; callId?: string }

/** Which broker handler a tool call goes to, and under which op name. */
export type ToolRoute = { kind: "outbound" | "orchestration"; op: string }

/** A tool as the broker serves it to the external shim: its MCP definition and its route. */
export type ShimTool = { name: string; description: string; inputSchema: Record<string, unknown>; annotations?: { readOnlyHint?: boolean }; route: ToolRoute }

export type RegisterFrame = {
  kind: "register"
  workdir: string
  pid: number
  display_name?: string
  requested_name?: string
  agent_session_id?: string
  channel_only?: boolean
  /** The MCP server the shim runs as (mux-shim, mux-rpc, mux-channel); a shim built before this field sends none. */
  server?: string
}

/** `tools`: the list the shim serves, sent on every register (so also after a broker restart). */
export type RegisteredFrame = { kind: "registered"; display_name: string; session_id: string; council_member?: boolean; tools?: ShimTool[] }
export type InboundFrame = { kind: "inbound"; content: string; meta: Record<string, string> }
export type OutboundFrame = { kind: "outbound"; call_id: string; op: ToolOperation }
export type OrchestrationFrame = { kind: "orchestration"; call_id: string; op: ToolOperation }
export type ResultFrame = { kind: "result"; call_id: string; ok: boolean; value?: unknown; error?: string }
export type PingFrame = { kind: "ping" }
export type PongFrame = { kind: "pong" }

export type SocketFrame =
  | RegisterFrame
  | RegisteredFrame
  | InboundFrame
  | OutboundFrame
  | OrchestrationFrame
  | ResultFrame
  | PingFrame
  | PongFrame

function objectValue(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("socket frame must be an object")
  return value as Record<string, unknown>
}

function stringField(obj: Record<string, unknown>, key: string): string {
  const value = obj[key]
  if (typeof value !== "string") throw new Error(`${String(obj.kind)}.${key} must be a string`)
  return value
}

function optionalStringField(obj: Record<string, unknown>, key: string): string | undefined {
  const value = obj[key]
  if (value === undefined) return undefined
  if (typeof value !== "string") throw new Error(`${String(obj.kind)}.${key} must be a string`)
  return value
}

function parseShimTools(value: unknown): ShimTool[] {
  if (!Array.isArray(value)) throw new Error("registered.tools must be an array")
  return value.map((item, i) => {
    const at = `registered.tools[${i}]`
    const tool = objectValue(item)
    const field = (key: string) => {
      if (typeof tool[key] !== "string") throw new Error(`${at}.${key} must be a string`)
      return tool[key] as string
    }
    const inputSchema = tool.inputSchema
    if (!inputSchema || typeof inputSchema !== "object" || Array.isArray(inputSchema)) throw new Error(`${at}.inputSchema must be an object`)
    const route = objectValue(tool.route)
    if (route.kind !== "outbound" && route.kind !== "orchestration") throw new Error(`${at}.route.kind must be outbound or orchestration`)
    if (typeof route.op !== "string") throw new Error(`${at}.route.op must be a string`)
    const annotations = tool.annotations && typeof tool.annotations === "object" && !Array.isArray(tool.annotations)
      ? tool.annotations as { readOnlyHint?: unknown } : undefined
    return {
      name: field("name"), description: field("description"), inputSchema: inputSchema as Record<string, unknown>,
      ...(typeof annotations?.readOnlyHint === "boolean" ? { annotations: { readOnlyHint: annotations.readOnlyHint } } : {}),
      route: { kind: route.kind, op: route.op },
    }
  })
}

export function parseSocketFrame(value: unknown): SocketFrame {
  const obj = objectValue(value)
  const kind = obj.kind
  if (typeof kind !== "string") throw new Error("socket frame kind must be a string")
  switch (kind) {
    case "register": {
      const pid = obj.pid
      if (typeof pid !== "number") throw new Error("register.pid must be a number")
      return {
        kind,
        workdir: stringField(obj, "workdir"),
        pid,
        display_name: optionalStringField(obj, "display_name"),
        requested_name: optionalStringField(obj, "requested_name"),
        agent_session_id: optionalStringField(obj, "agent_session_id"),
        channel_only: obj.channel_only === true,
        server: optionalStringField(obj, "server"),
      }
    }
    case "registered":
      return {
        kind, display_name: stringField(obj, "display_name"), session_id: stringField(obj, "session_id"),
        ...(obj.council_member === true ? { council_member: true } : {}),
        ...(obj.tools !== undefined ? { tools: parseShimTools(obj.tools) } : {}),
      }
    case "inbound": {
      const meta = objectValue(obj.meta)
      return { kind, content: stringField(obj, "content"), meta: Object.fromEntries(Object.entries(meta).map(([k, v]) => [k, String(v)])) }
    }
    case "outbound":
    case "orchestration": {
      const op = objectValue(obj.op)
      return { kind, call_id: stringField(obj, "call_id"), op: { name: stringField(op, "name"), args: objectValue(op.args ?? {}), ...(typeof op.callId === "string" && op.callId ? { callId: op.callId } : {}) } }
    }
    case "result":
      return { kind, call_id: stringField(obj, "call_id"), ok: obj.ok === true, value: obj.value, error: optionalStringField(obj, "error") }
    case "ping":
    case "pong":
      return { kind }
    default:
      throw new Error(`unknown socket frame kind: ${kind}`)
  }
}
