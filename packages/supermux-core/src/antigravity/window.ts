// #144: an Antigravity conversation's context window. agy reports a size per step but no window in its stream; its
// own conversation DB (<cli dir>/conversations/<id>.db, table gen_metadata) keeps context_window_metadata in each
// generation's protobuf: field 1.9.10.4 = max_context_tokens (decoded on agy 1.5.9: 256000 for gemini-4-argon,
// 840000 for claude-opus-5-5). Read-only and defensive: any surprise is "unknown", never an error.
import { existsSync } from "node:fs"
import { join } from "node:path"

/** The varint-keyed fields of one protobuf message; undefined when the bytes don't parse as one. */
function fields(bytes: Uint8Array): Array<{ field: number; wire: number; value: bigint | Uint8Array }> | undefined {
  const out: Array<{ field: number; wire: number; value: bigint | Uint8Array }> = []
  let i = 0
  const varint = (): bigint | undefined => {
    let result = 0n, shift = 0n
    while (i < bytes.length) {
      const b = bytes[i++]!
      result |= BigInt(b & 0x7f) << shift
      if (b < 0x80) return result
      shift += 7n
      if (shift > 63n) return undefined
    }
    return undefined
  }
  while (i < bytes.length) {
    const key = varint()
    if (key === undefined) return undefined
    const field = Number(key >> 3n), wire = Number(key & 7n)
    if (field === 0) return undefined
    if (wire === 0) { const v = varint(); if (v === undefined) return undefined; out.push({ field, wire, value: v }) }
    else if (wire === 1) { if (i + 8 > bytes.length) return undefined; out.push({ field, wire, value: bytes.subarray(i, i + 8) }); i += 8 }
    else if (wire === 5) { if (i + 4 > bytes.length) return undefined; out.push({ field, wire, value: bytes.subarray(i, i + 4) }); i += 4 }
    else if (wire === 2) {
      const n = varint()
      if (n === undefined || i + Number(n) > bytes.length) return undefined
      out.push({ field, wire, value: bytes.subarray(i, i + Number(n)) }); i += Number(n)
    } else return undefined
  }
  return out
}

/** The varint at [path] (nested message fields, then the number), or undefined. */
export function protoNumberAt(bytes: Uint8Array, path: number[]): number | undefined {
  let current: Uint8Array = bytes
  for (let depth = 0; depth < path.length; depth++) {
    const parsed = fields(current)
    const hit = parsed?.find(f => f.field === path[depth])
    if (!hit) return undefined
    if (depth === path.length - 1) return typeof hit.value === "bigint" && hit.wire === 0 ? Number(hit.value) : undefined
    if (!(hit.value instanceof Uint8Array)) return undefined
    current = hit.value
  }
  return undefined
}

const MAX_CONTEXT_TOKENS = [1, 9, 10, 4]

type Db = { prepare?: (sql: string) => { get: (...a: unknown[]) => unknown }; query?: (sql: string) => { get: (...a: unknown[]) => unknown }; close: () => void }

/** Opens [file] read-only with whichever SQLite the runtime has (Bun's, or Node's own); undefined when none. */
async function openReadOnly(file: string): Promise<Db | undefined> {
  try {
    if ((globalThis as { Bun?: unknown }).Bun) {
      const { Database } = await import("bun:sqlite" as string) as { Database: new (f: string, o: { readonly: boolean }) => Db }
      return new Database(file, { readonly: true })
    }
    const { DatabaseSync } = await import("node:sqlite" as string) as { DatabaseSync: new (f: string, o: { readOnly: boolean }) => Db }
    return new DatabaseSync(file, { readOnly: true })
  } catch { return undefined }
}

/** The conversation's window from its newest generation, or undefined (no DB, no row, an unknown shape). */
export async function readConversationWindow(cliDir: string, conversationId: string): Promise<number | undefined> {
  if (!/^[A-Za-z0-9-]+$/.test(conversationId)) return undefined
  const file = join(cliDir, "conversations", `${conversationId}.db`)
  if (!existsSync(file)) return undefined
  const db = await openReadOnly(file)
  if (!db) return undefined
  try {
    const sql = "SELECT data FROM gen_metadata ORDER BY idx DESC LIMIT 1"
    const row = (db.query ? db.query(sql) : db.prepare!(sql)).get() as { data?: unknown } | undefined
    const data = row?.data
    const bytes = data instanceof Uint8Array ? data : undefined
    if (!bytes) return undefined
    const max = protoNumberAt(bytes, MAX_CONTEXT_TOKENS)
    return max != null && max > 0 && max < 100_000_000 ? max : undefined
  } catch { return undefined }
  finally { try { db.close() } catch { /* */ } }
}
