export const PROTOCOL_VERSION = 1 as const

type RequestEnvelope = {
  id: string
  version: typeof PROTOCOL_VERSION
  secret: string
}

export type SessiondRequest = RequestEnvelope & (
  | { op: "hello"; args: Record<string, never> }
  | {
    op: "create"
    args: {
      group: string
      name: string
      cwd: string
      argv: string[]
      env: Record<string, string>
      cols?: number
      rows?: number
    }
  }
  | { op: "list"; args: { group?: string } }
  | { op: "resolve"; args: { group: string; name: string } }
  | { op: "livePid"; args: { targetId: string } }
  | { op: "write"; args: { targetId: string; dataBase64: string } }
  | { op: "sendKeys"; args: { targetId: string; keys: string[] } }
  | { op: "resize"; args: { targetId: string; cols: number; rows: number } }
  | { op: "capture"; args: { targetId: string; raw?: boolean } }
  | { op: "attach"; args: { targetId: string; viewerId: string } }
  | { op: "detach"; args: { targetId: string; viewerId: string } }
  | { op: "interrupt"; args: { targetId: string } }
  | { op: "kill"; args: { targetId: string } }
)

export type SessiondResponse<T = unknown> = {
  id: string
  ok: boolean
  value?: T
  error?: string
}

export type SessiondEvent = {
  event: "data" | "exit" | "viewerFailure"
  targetId: string
  viewerId?: string
  dataBase64?: string
  code?: number
  reason?: string
}

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function requireRecord(record: UnknownRecord, field: string): UnknownRecord {
  const value = record[field]
  if (!isRecord(value)) throw new Error(`${field} must be an object`)
  return value
}

function requireString(record: UnknownRecord, field: string): string {
  const value = record[field]
  if (typeof value !== "string") throw new Error(`${field} must be a string`)
  return value
}

function requireNonemptyString(record: UnknownRecord, field: string): string {
  const value = record[field]
  if (typeof value !== "string" || value.length === 0) throw new Error(`${field} must be a nonempty string`)
  return value
}

function optionalString(record: UnknownRecord, field: string): string | undefined {
  const value = record[field]
  if (value === undefined) return undefined
  if (typeof value !== "string") throw new Error(`${field} must be a string when provided`)
  return value
}

function requireStringArray(record: UnknownRecord, field: string, nonempty = false): string[] {
  const value = record[field]
  if (!Array.isArray(value)) throw new Error(`${field} must be an array of strings`)
  if (nonempty && value.length === 0) throw new Error(`${field} must be a nonempty array of strings`)
  if (!value.every((entry): entry is string => typeof entry === "string"))
    throw new Error(`${field} must contain only string entries`)
  return [...value]
}

function requireStringRecord(record: UnknownRecord, field: string): Record<string, string> {
  const value = record[field]
  if (!isRecord(value)) throw new Error(`${field} must be a string-to-string record`)
  const entries: Array<[string, string]> = []
  for (const [key, entry] of Object.entries(value)) {
    if (typeof entry !== "string") throw new Error(`${field} must be a record with string values`)
    entries.push([key, entry])
  }
  return Object.fromEntries(entries)
}

function requireDimension(record: UnknownRecord, field: "cols" | "rows"): number {
  const value = record[field]
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1 || value > 1000)
    throw new Error(`${field} must be an integer between 1 and 1000`)
  return value
}

function optionalDimension(record: UnknownRecord, field: "cols" | "rows"): number | undefined {
  if (record[field] === undefined) return undefined
  return requireDimension(record, field)
}

function requireBoolean(record: UnknownRecord, field: string): boolean {
  const value = record[field]
  if (typeof value !== "boolean") throw new Error(`${field} must be a boolean`)
  return value
}

function optionalBoolean(record: UnknownRecord, field: string): boolean | undefined {
  if (record[field] === undefined) return undefined
  return requireBoolean(record, field)
}

function requireBase64(record: UnknownRecord, field: string): string {
  const value = requireString(record, field)
  const base64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/
  if (!base64.test(value) || Buffer.from(value, "base64").toString("base64") !== value)
    throw new Error(`${field} must be valid base64`)
  return value
}

/** Parse and validate an untrusted request before it reaches session dispatch. */
export function parseRequest(input: unknown): SessiondRequest {
  if (!isRecord(input)) throw new Error("request must be an object")

  const id = requireNonemptyString(input, "id")
  const secret = requireNonemptyString(input, "secret")
  const version = input.version
  if (typeof version !== "number") throw new Error("version must be a number")
  if (version !== PROTOCOL_VERSION) throw new Error(`unsupported protocol version: ${version}`)
  const op = requireString(input, "op")
  const args = requireRecord(input, "args")
  const envelope = { id, version, secret }

  switch (op) {
    case "hello":
      return { ...envelope, op, args: {} }
    case "create": {
      const cols = optionalDimension(args, "cols")
      const rows = optionalDimension(args, "rows")
      return {
        ...envelope,
        op,
        args: {
          group: requireString(args, "group"),
          name: requireString(args, "name"),
          cwd: requireString(args, "cwd"),
          argv: requireStringArray(args, "argv", true),
          env: requireStringRecord(args, "env"),
          ...(cols === undefined ? {} : { cols }),
          ...(rows === undefined ? {} : { rows }),
        },
      }
    }
    case "list": {
      const group = optionalString(args, "group")
      return { ...envelope, op, args: { ...(group === undefined ? {} : { group }) } }
    }
    case "resolve":
      return {
        ...envelope,
        op,
        args: { group: requireString(args, "group"), name: requireString(args, "name") },
      }
    case "livePid":
    case "interrupt":
    case "kill":
      return { ...envelope, op, args: { targetId: requireString(args, "targetId") } }
    case "write":
      return {
        ...envelope,
        op,
        args: {
          targetId: requireString(args, "targetId"),
          dataBase64: requireBase64(args, "dataBase64"),
        },
      }
    case "sendKeys":
      return {
        ...envelope,
        op,
        args: { targetId: requireString(args, "targetId"), keys: requireStringArray(args, "keys") },
      }
    case "resize":
      return {
        ...envelope,
        op,
        args: {
          targetId: requireString(args, "targetId"),
          cols: requireDimension(args, "cols"),
          rows: requireDimension(args, "rows"),
        },
      }
    case "capture": {
      const raw = optionalBoolean(args, "raw")
      return {
        ...envelope,
        op,
        args: {
          targetId: requireString(args, "targetId"),
          ...(raw === undefined ? {} : { raw }),
        },
      }
    }
    case "attach":
    case "detach":
      return {
        ...envelope,
        op,
        args: {
          targetId: requireString(args, "targetId"),
          viewerId: requireString(args, "viewerId"),
        },
      }
    default:
      throw new Error(`unknown operation: ${op}`)
  }
}
