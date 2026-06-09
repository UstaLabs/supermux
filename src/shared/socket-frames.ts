export type ToolOperation = { name: string; args: Record<string, unknown> }

export type RegisterFrame = {
  kind: "register"
  workdir: string
  pid: number
  display_name?: string
  requested_name?: string
  agent_session_id?: string
  channel_only?: boolean
}

export type RegisteredFrame = { kind: "registered"; display_name: string; session_id: string }
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
      }
    }
    case "registered":
      return { kind, display_name: stringField(obj, "display_name"), session_id: stringField(obj, "session_id") }
    case "inbound": {
      const meta = objectValue(obj.meta)
      return { kind, content: stringField(obj, "content"), meta: Object.fromEntries(Object.entries(meta).map(([k, v]) => [k, String(v)])) }
    }
    case "outbound":
    case "orchestration": {
      const op = objectValue(obj.op)
      return { kind, call_id: stringField(obj, "call_id"), op: { name: stringField(op, "name"), args: objectValue(op.args ?? {}) } }
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
