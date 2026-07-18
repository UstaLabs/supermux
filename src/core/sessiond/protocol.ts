export const PROTOCOL_VERSION = 1 as const

type RequestEnvelope = {
  id: string
  version: typeof PROTOCOL_VERSION
  secret: string
}

export type SessiondRequest = RequestEnvelope & (
  | { op: "hello" }
  | {
    op: "create"
    group: string
    name: string
    cwd: string
    argv: string[]
    env: Record<string, string>
    cols?: number
    rows?: number
  }
  | { op: "list"; group?: string }
  | { op: "resolve"; group: string; name: string }
  | { op: "livePid"; targetId: string }
  | { op: "write"; targetId: string; dataBase64: string }
  | { op: "sendKeys"; targetId: string; keys: string[] }
  | { op: "resize"; targetId: string; cols: number; rows: number }
  | { op: "capture"; targetId: string; raw?: boolean }
  | { op: "attach"; targetId: string; viewerId: string }
  | { op: "detach"; targetId: string; viewerId: string }
  | { op: "interrupt"; targetId: string }
  | { op: "kill"; targetId: string }
)

export type SessiondResponse<T = unknown> = {
  id: string
  ok: boolean
  value?: T
  error?: string
}

export type SessiondEvent = {
  event: "data" | "exit"
  targetId: string
  viewerId?: string
  dataBase64?: string
  code?: number
}

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value)
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
  return value
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
  if (!base64.test(value)) throw new Error(`${field} must be valid base64`)
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
  const envelope = { id, version, secret }

  switch (op) {
    case "hello":
      return { ...envelope, op }
    case "create": {
      const cols = optionalDimension(input, "cols")
      const rows = optionalDimension(input, "rows")
      return {
        ...envelope,
        op,
        group: requireString(input, "group"),
        name: requireString(input, "name"),
        cwd: requireString(input, "cwd"),
        argv: requireStringArray(input, "argv", true),
        env: requireStringRecord(input, "env"),
        ...(cols === undefined ? {} : { cols }),
        ...(rows === undefined ? {} : { rows }),
      }
    }
    case "list": {
      const group = optionalString(input, "group")
      return { ...envelope, op, ...(group === undefined ? {} : { group }) }
    }
    case "resolve":
      return { ...envelope, op, group: requireString(input, "group"), name: requireString(input, "name") }
    case "livePid":
    case "interrupt":
    case "kill":
      return { ...envelope, op, targetId: requireString(input, "targetId") }
    case "write":
      return {
        ...envelope,
        op,
        targetId: requireString(input, "targetId"),
        dataBase64: requireBase64(input, "dataBase64"),
      }
    case "sendKeys":
      return {
        ...envelope,
        op,
        targetId: requireString(input, "targetId"),
        keys: requireStringArray(input, "keys"),
      }
    case "resize":
      return {
        ...envelope,
        op,
        targetId: requireString(input, "targetId"),
        cols: requireDimension(input, "cols"),
        rows: requireDimension(input, "rows"),
      }
    case "capture": {
      const raw = optionalBoolean(input, "raw")
      return {
        ...envelope,
        op,
        targetId: requireString(input, "targetId"),
        ...(raw === undefined ? {} : { raw }),
      }
    }
    case "attach":
    case "detach":
      return {
        ...envelope,
        op,
        targetId: requireString(input, "targetId"),
        viewerId: requireString(input, "viewerId"),
      }
    default:
      throw new Error(`unknown operation: ${op}`)
  }
}
