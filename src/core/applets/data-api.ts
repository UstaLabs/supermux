import { Database } from "bun:sqlite"
import { randomUUID } from "crypto"
import { existsSync, mkdirSync, readdirSync, rmSync, statSync } from "fs"
import { isAbsolute, join, relative } from "path"

/**
 * #117: the data API a static applet keeps its data with — plain fetch() from its page, so it works from any
 * language and in any browser. Served by the broker at /a/<name>/api/ for applets with no server of their own (a
 * server applet owns its whole route), stored in the applet's data/app.db and data/files/, so the nightly backups
 * take it like any other data.
 *
 *   GET    api/collections/<c>/records[?field=value…][&sort=-created][&limit=100]   → { items: [...] }
 *   POST   api/collections/<c>/records          {…}   → the record, with id, created, updated
 *   GET    api/collections/<c>/records/<id>
 *   PATCH  api/collections/<c>/records/<id>     {…}   → merged into the record
 *   DELETE api/collections/<c>/records/<id>
 *   GET    api/collections/<c>/events                 → server-sent events: {action, record} per change
 *   GET|PUT|DELETE api/kv/<key>                       → any JSON value
 *   GET    api/files                                  → { items: [{ path, size, updated }] }
 *   GET|PUT|DELETE api/files/<path>                   → the bytes, as sent
 */

const NAME = /^[A-Za-z0-9_-]{1,64}$/
const MAX_BODY = 10 * 1024 * 1024
const MAX_LIMIT = 1000

type Listener = (event: string) => void

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } })
const fail = (status: number, message: string) => json({ error: message }, status)

/** One applet's store; opened on first use and kept, one per data folder. */
class AppletData {
  private db: Database
  private listeners = new Map<string, Set<Listener>>()

  constructor(readonly dataDir: string) {
    mkdirSync(dataDir, { recursive: true })
    this.db = new Database(join(dataDir, "app.db"), { create: true })
    this.db.run("PRAGMA journal_mode = WAL")
    this.db.run(`CREATE TABLE IF NOT EXISTS _records (
      collection TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, created TEXT NOT NULL, updated TEXT NOT NULL,
      PRIMARY KEY (collection, id))`)
    this.db.run("CREATE INDEX IF NOT EXISTS _records_created ON _records(collection, created)")
    this.db.run("CREATE TABLE IF NOT EXISTS _kv (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated TEXT NOT NULL)")
  }

  close(): void { this.db.close() }

  private emit(collection: string, action: string, record: unknown): void {
    const event = JSON.stringify({ action, record })
    for (const l of this.listeners.get(collection) ?? []) l(event)
  }

  subscribe(collection: string, l: Listener): () => void {
    let set = this.listeners.get(collection)
    if (!set) this.listeners.set(collection, (set = new Set()))
    set.add(l)
    return () => { set!.delete(l) }
  }

  private row(r: { id: string; data: string; created: string; updated: string }) {
    return { ...(JSON.parse(r.data) as Record<string, unknown>), id: r.id, created: r.created, updated: r.updated }
  }

  list(collection: string, q: URLSearchParams) {
    const limit = Math.min(MAX_LIMIT, Math.max(1, Number(q.get("limit")) || 100))
    const sort = q.get("sort") ?? "created"
    const desc = sort.startsWith("-")
    const key = sort.replace(/^-/, "")
    const filters = [...q.entries()].filter(([k]) => k !== "limit" && k !== "sort")
    const where = ["collection = ?"]
    const args: (string | number)[] = [collection]
    for (const [k, v] of filters) {
      if (!NAME.test(k)) continue
      if (k === "id") { where.push("id = ?"); args.push(v); continue }
      // Values compare as text, so ?done=1 matches 1 and "1".
      where.push(`CAST(json_extract(data, '$.' || ?) AS TEXT) = ?`)
      args.push(k, v)
    }
    const order = key === "created" || key === "updated" || key === "id" ? key : NAME.test(key) ? `json_extract(data, '$.${key}')` : "created"
    const rows = this.db.query(`SELECT id, data, created, updated FROM _records WHERE ${where.join(" AND ")} ORDER BY ${order} ${desc ? "DESC" : "ASC"} LIMIT ${limit}`).all(...args) as { id: string; data: string; created: string; updated: string }[]
    return { items: rows.map((r) => this.row(r)) }
  }

  get(collection: string, id: string) {
    const r = this.db.query("SELECT id, data, created, updated FROM _records WHERE collection = ? AND id = ?").get(collection, id) as { id: string; data: string; created: string; updated: string } | null
    return r ? this.row(r) : null
  }

  create(collection: string, body: Record<string, unknown>) {
    const now = new Date().toISOString()
    const { id: wanted, created: _c, updated: _u, ...data } = body
    const id = typeof wanted === "string" && NAME.test(wanted) ? wanted : randomUUID()
    if (this.get(collection, id)) return null
    this.db.query("INSERT INTO _records (collection, id, data, created, updated) VALUES (?, ?, ?, ?, ?)").run(collection, id, JSON.stringify(data), now, now)
    const rec = this.get(collection, id)!
    this.emit(collection, "create", rec)
    return rec
  }

  update(collection: string, id: string, body: Record<string, unknown>) {
    const cur = this.get(collection, id)
    if (!cur) return null
    const { id: _i, created, updated: _u, ...old } = cur
    const { id: _bi, created: _bc, updated: _bu, ...patch } = body
    const now = new Date().toISOString()
    this.db.query("UPDATE _records SET data = ?, updated = ? WHERE collection = ? AND id = ?").run(JSON.stringify({ ...old, ...patch }), now, collection, id)
    const rec = this.get(collection, id)!
    this.emit(collection, "update", rec)
    void created
    return rec
  }

  remove(collection: string, id: string): boolean {
    const cur = this.get(collection, id)
    if (!cur) return false
    this.db.query("DELETE FROM _records WHERE collection = ? AND id = ?").run(collection, id)
    this.emit(collection, "delete", { id })
    return true
  }

  kvGet(key: string): { found: boolean; value?: unknown } {
    const r = this.db.query("SELECT value FROM _kv WHERE key = ?").get(key) as { value: string } | null
    return r ? { found: true, value: JSON.parse(r.value) } : { found: false }
  }

  kvSet(key: string, value: unknown): void {
    this.db.query("INSERT INTO _kv (key, value, updated) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated = excluded.updated")
      .run(key, JSON.stringify(value), new Date().toISOString())
  }

  kvDelete(key: string): boolean {
    return this.db.query("DELETE FROM _kv WHERE key = ?").run(key).changes > 0
  }
}

/** A path under data/files/ from the URL's rest, or null: never outside it. */
function filePath(dataDir: string, rel: string): string | null {
  let p: string
  try { p = decodeURIComponent(rel) } catch { return null }
  if (!p || p.includes("\0") || p.split(/[\\/]/).some((s) => s === ".." || s === "")) return null
  const root = join(dataDir, "files")
  const full = join(root, p)
  const inside = relative(root, full)
  return inside && !inside.startsWith("..") && !isAbsolute(inside) ? full : null
}

function listFiles(root: string, base = root, out: { path: string; size: number; updated: string }[] = []) {
  if (!existsSync(root)) return out
  for (const n of readdirSync(root)) {
    const p = join(root, n)
    const st = statSync(p)
    if (st.isDirectory()) listFiles(p, base, out)
    else out.push({ path: relative(base, p).split("\\").join("/"), size: st.size, updated: st.mtime.toISOString() })
    if (out.length >= 5000) break
  }
  return out
}

async function readJson(req: Request): Promise<Record<string, unknown> | null> {
  const len = Number(req.headers.get("content-length") ?? 0)
  if (len > MAX_BODY) return null
  try {
    const b = await req.json()
    return b && typeof b === "object" && !Array.isArray(b) ? b as Record<string, unknown> : null
  } catch {
    return null
  }
}

/** The data API of every static applet, one store per data folder. */
export class AppletDataApi {
  private stores = new Map<string, AppletData>()

  private store(dataDir: string): AppletData {
    let s = this.stores.get(dataDir)
    if (!s) this.stores.set(dataDir, (s = new AppletData(dataDir)))
    return s
  }

  /** Closes an applet's store (deleted, or its folder moved). */
  forget(dataDir: string): void {
    this.stores.get(dataDir)?.close()
    this.stores.delete(dataDir)
  }

  closeAll(): void {
    for (const s of this.stores.values()) s.close()
    this.stores.clear()
  }

  /** [rest] is the path after /a/<name>/api (starting with "/"). */
  async handle(req: Request, dataDir: string, rest: string): Promise<Response> {
    const url = new URL(req.url)
    const method = req.method.toUpperCase()
    const parts = rest.split("/").filter(Boolean) // platform-ok: URL path segments
    const s = this.store(dataDir)

    if (parts[0] === "collections" && parts[1] && parts[2] === "records") {
      const c = parts[1]
      if (!NAME.test(c)) return fail(400, "a collection name is letters, digits, - and _")
      const id = parts[3]
      if (!id) {
        if (method === "GET") return json(s.list(c, url.searchParams))
        if (method === "POST") {
          const body = await readJson(req)
          if (!body) return fail(400, "send a JSON object (at most 10 MB)")
          const rec = s.create(c, body)
          return rec ? json(rec, 201) : fail(409, "a record with that id exists")
        }
        return fail(405, "use GET or POST")
      }
      if (parts.length > 4) return fail(404, "no such route")
      if (method === "GET") { const r = s.get(c, id); return r ? json(r) : fail(404, "no such record") }
      if (method === "PATCH" || method === "PUT") {
        const body = await readJson(req)
        if (!body) return fail(400, "send a JSON object (at most 10 MB)")
        const r = s.update(c, id, body)
        return r ? json(r) : fail(404, "no such record")
      }
      if (method === "DELETE") return s.remove(c, id) ? json({ ok: true }) : fail(404, "no such record")
      return fail(405, "use GET, PATCH or DELETE")
    }

    if (parts[0] === "collections" && parts[1] && parts[2] === "events" && method === "GET") {
      const c = parts[1]
      if (!NAME.test(c)) return fail(400, "a collection name is letters, digits, - and _")
      let off: (() => void) | undefined
      let ping: ReturnType<typeof setInterval> | undefined
      const stream = new ReadableStream<string>({
        start(controller) {
          controller.enqueue(": connected\n\n")
          off = s.subscribe(c, (e) => { try { controller.enqueue(`data: ${e}\n\n`) } catch { /* closed */ } })
          // A comment every 20 s keeps proxies and the server's idle timeout from closing a quiet stream.
          ping = setInterval(() => { try { controller.enqueue(": ping\n\n") } catch { /* closed */ } }, 20_000)
          req.signal.addEventListener("abort", () => { off?.(); if (ping) clearInterval(ping); try { controller.close() } catch { /* closed */ } })
        },
        cancel() { off?.(); if (ping) clearInterval(ping) },
      })
      return new Response(stream, { headers: { "content-type": "text/event-stream", "cache-control": "no-store" } })
    }

    if (parts[0] === "kv" && parts[1] && parts.length === 2) {
      const key = parts[1]
      if (!NAME.test(key)) return fail(400, "a key is letters, digits, - and _")
      if (method === "GET") { const r = s.kvGet(key); return r.found ? json(r.value) : fail(404, "no such key") }
      if (method === "PUT") {
        const len = Number(req.headers.get("content-length") ?? 0)
        if (len > MAX_BODY) return fail(413, "at most 10 MB")
        let value: unknown
        try { value = await req.json() } catch { return fail(400, "send a JSON value") }
        s.kvSet(key, value)
        return json({ ok: true })
      }
      if (method === "DELETE") return s.kvDelete(key) ? json({ ok: true }) : fail(404, "no such key")
      return fail(405, "use GET, PUT or DELETE")
    }

    if (parts[0] === "files") {
      if (parts.length === 1) return method === "GET" ? json({ items: listFiles(join(dataDir, "files")) }) : fail(405, "use GET")
      const p = filePath(dataDir, parts.slice(1).join("/"))
      if (!p) return fail(400, "that path is outside the applet's files")
      if (method === "GET") {
        if (!existsSync(p) || !statSync(p).isFile()) return fail(404, "no such file")
        const f = Bun.file(p)
        return new Response(f, { headers: { "content-type": f.type || "application/octet-stream", "cache-control": "no-store" } })
      }
      if (method === "PUT") {
        const len = Number(req.headers.get("content-length") ?? 0)
        if (len > 100 * 1024 * 1024) return fail(413, "at most 100 MB")
        mkdirSync(join(p, ".."), { recursive: true })
        await Bun.write(p, await req.arrayBuffer())
        return json({ ok: true })
      }
      if (method === "DELETE") {
        if (!existsSync(p)) return fail(404, "no such file")
        rmSync(p, { force: true })
        return json({ ok: true })
      }
      return fail(405, "use GET, PUT or DELETE")
    }
    return fail(404, "no such route: see the data API in supermux applet --help")
  }
}
