# Resumable Uploads — Phase 1: Broker + FileStore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the server side of chunked, resumable uploads — three additive HTTP endpoints (`POST /upload/init`, `PATCH /upload/<id>`, `HEAD /upload/<id>`) backed by a `pending_uploads` table and five new `FileStore` methods, with a TTL sweep for abandoned partials.

**Architecture:** A large file is uploaded as an ordered sequence of chunks. `init` creates an empty `<upload_id>.part` file plus a `pending_uploads` row; each `PATCH` appends one chunk at the validated offset (filesystem size is the source of truth, so the upload survives a broker restart); the `PATCH` that reaches `total_size` triggers finalize — fsync, rename `.part` → `<upload_id>.<ext>`, move the row into `attachments`. `HEAD` returns the current offset for resume. The `upload_id` *is* the eventual `file_id`, so finalize is a rename + a row move. Everything is additive: the existing `POST /upload` single-shot path is untouched, and old clients keep working.

**Tech Stack:** TypeScript on Bun, `bun:sqlite` via the project `Db` wrapper, `bun:test`. Follows the exact patterns already in `src/core/files/store.ts` (fsync-before-rename durability, typed errors mapped to HTTP codes) and `src/channels/web/upload-routes.test.ts` (real `WebChannel` on an ephemeral port driven over `fetch`).

**Phase scope:** This is Phase 1 of 5. It produces working, independently-testable server software (`bun test` + curl). Phases 2–5 (shared KMP client, web, iOS, Android) get their own plans authored once this merges, because each depends on the exact response shapes finalized here.

---

## File Structure

- **Create** `src/core/storage/migrations/023_pending_uploads.sql` — the `pending_uploads` table DDL.
- **Modify** `src/core/storage/migrations/index.ts` — register the migration in the embedded manifest (import + `MIGRATIONS` entry). Required or the migrations-manifest test fails.
- **Modify** `src/core/files/store.ts` — add `statSync` import, three typed errors, a `chunkBytes()` helper, and five methods: `createPending`, `appendChunk`, `pendingOffset`, `finalizePending`, `gcPendingOnce`.
- **Modify** `src/core/files/store.test.ts` — unit tests for the five FileStore methods.
- **Modify** `src/channels/web/index.ts` — three route branches for `/upload/init`, `PATCH /upload/<id>`, `HEAD /upload/<id>`, mapping the typed errors to 404/409/400/413.
- **Modify** `src/channels/web/upload-routes.test.ts` — HTTP tests for the three endpoints incl. the resume round-trip.
- **Modify** `src/main.ts` — schedule `gcPendingOnce` alongside the existing `gcOnce` interval.

---

## Task 1: Migration — `pending_uploads` table

**Files:**
- Create: `src/core/storage/migrations/023_pending_uploads.sql`
- Modify: `src/core/storage/migrations/index.ts`
- Test: `src/core/files/store.test.ts` (new `describe` block added here; full method tests come in later tasks)

- [ ] **Step 1: Write the migration SQL file**

Create `src/core/storage/migrations/023_pending_uploads.sql`:

```sql
-- In-flight chunked/resumable uploads. A row is created by POST /upload/init and
-- deleted when the upload finalizes (its bytes move into `attachments`) or when
-- gcPendingOnce reaps an abandoned partial past its TTL. `path` is the
-- <upload_id>.part file; `received` mirrors that file's byte length.
CREATE TABLE pending_uploads (
  upload_id  TEXT PRIMARY KEY,
  session    TEXT NOT NULL,
  kind       TEXT NOT NULL,
  mime       TEXT,
  name       TEXT,
  total_size INTEGER NOT NULL,
  received   INTEGER NOT NULL DEFAULT 0,
  path       TEXT NOT NULL,
  origin     TEXT NOT NULL,
  device     TEXT,
  created_at TEXT NOT NULL
);
```

- [ ] **Step 2: Register the migration in the embedded manifest**

In `src/core/storage/migrations/index.ts`, add the import next to the `m022` line:

```typescript
import m023 from "./023_pending_uploads.sql" with { type: "text" }
```

and add this entry at the end of the `MIGRATIONS` array (after the `022_memory_search` entry):

```typescript
  { version: 23, name: "023_pending_uploads", sql: m023 },
```

- [ ] **Step 3: Write a failing test that the table exists after migration**

Add to `src/core/files/store.test.ts` (the `makeStore()` helper + `MIGRATIONS` import already exist at the top of the file):

```typescript
describe("pending_uploads migration", () => {
  test("creates the pending_uploads table", () => {
    const db = openDb(":memory:")
    runMigrations(db, MIGRATIONS)
    const row = db.prepare(
      "SELECT name FROM sqlite_master WHERE type='table' AND name='pending_uploads'",
    ).get() as { name: string } | undefined
    expect(row?.name).toBe("pending_uploads")
  })
})
```

- [ ] **Step 4: Run the test to verify it passes (migration applies)**

Run: `bun test src/core/files/store.test.ts -t "creates the pending_uploads table"`
Expected: PASS. (If the file was created but not registered in `index.ts`, the migrations-manifest test elsewhere fails — run `bun test src/core/storage` to confirm the manifest is consistent; expected PASS.)

- [ ] **Step 5: Commit**

```bash
git add src/core/storage/migrations/023_pending_uploads.sql src/core/storage/migrations/index.ts src/core/files/store.test.ts
git commit -m "feat(uploads): pending_uploads migration for resumable uploads

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: FileStore typed errors + imports + chunk helper

**Files:**
- Modify: `src/core/files/store.ts`

- [ ] **Step 1: Add `statSync` to the fs import**

Change the fs import line (currently line 3) to include `statSync`:

```typescript
import { closeSync, fsyncSync, mkdirSync, openSync, renameSync, statSync, unlinkSync, writeSync } from "fs"
```

- [ ] **Step 2: Add three typed errors after the existing `EmptyUploadError` class**

Insert after the `EmptyUploadError` class (currently ends line 62), before `export class FileStore`:

```typescript
/** Thrown by appendChunk/finalizePending/pendingOffset when the upload_id is
 *  unknown (never created, or already GC'd/finalized). → HTTP 404. */
export class UploadNotFoundError extends Error {
  readonly code = "UPLOAD_NOT_FOUND" as const
  constructor(message = "upload not found") {
    super(message)
    this.name = "UploadNotFoundError"
  }
}

/** Thrown by appendChunk when the client's declared offset doesn't match the
 *  bytes the server actually holds. Carries the true `offset` so the caller can
 *  echo it back (HTTP 409 + Upload-Offset) and the client can resume. */
export class OffsetConflictError extends Error {
  readonly code = "OFFSET_CONFLICT" as const
  constructor(readonly offset: number, message = "offset conflict") {
    super(message)
    this.name = "OffsetConflictError"
  }
}

/** Thrown by appendChunk when a chunk would push the stored total past the
 *  declared total_size. → HTTP 400. */
export class UploadOverflowError extends Error {
  readonly code = "UPLOAD_OVERFLOW" as const
  constructor(message = "chunk exceeds declared total size") {
    super(message)
    this.name = "UploadOverflowError"
  }
}
```

- [ ] **Step 3: Add the `chunkBytes()` module helper after the `log` const**

Insert after `const log = makeLogger("core/files/store")` (line 10):

```typescript
/** Server-dictated chunk size for resumable uploads (echoed to clients in the
 *  init response so the server owns the knob). Default 5 MB. */
export function chunkBytes(): number {
  return Number(process.env.MUX_UPLOAD_CHUNK_MB ?? 5) * 1024 * 1024
}
```

- [ ] **Step 4: Verify the file still type-checks (no test yet — these are used next task)**

Run: `bun test src/core/files/store.test.ts`
Expected: PASS (existing tests unaffected; new symbols are exported but unused so far).

- [ ] **Step 5: Commit**

```bash
git add src/core/files/store.ts
git commit -m "feat(uploads): typed errors + chunk-size helper for resumable uploads

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `FileStore.createPending`

**Files:**
- Modify: `src/core/files/store.ts`
- Test: `src/core/files/store.test.ts`

- [ ] **Step 1: Write the failing test**

Add to `src/core/files/store.test.ts`:

```typescript
describe("FileStore.createPending", () => {
  test("creates an empty .part file, a pending row, and returns id + chunk_size", async () => {
    const { store, root } = makeStore()
    const { upload_id, chunk_size } = await store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", name: "clip.mp4",
      totalSize: 1000, device: "d1", origin: "web-upload",
    })
    expect(upload_id).toMatch(/^[0-9a-f]{32}$/)
    expect(chunk_size).toBe(5 * 1024 * 1024)
    // exactly one file on disk, the empty .part, offset 0
    expect(await store.pendingOffset(upload_id)).toBe(0)
    expect(allFiles(root).length).toBe(1)
    expect(allFiles(root)[0].endsWith(".part")).toBe(true)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/core/files/store.test.ts -t "creates an empty .part file"`
Expected: FAIL — `store.createPending is not a function`.

- [ ] **Step 3: Implement `createPending`**

Add as a method inside the `FileStore` class (e.g. after `putStream`, before `get`):

```typescript
/** Begin a resumable upload: create an empty <upload_id>.part and a
 *  pending_uploads row. The upload_id becomes the final file_id on finalize. */
async createPending(input: {
  session: string
  kind: AttachmentKind
  mime?: string
  name?: string
  totalSize: number
  device?: string
  origin: AttachmentOrigin
}): Promise<{ upload_id: string; chunk_size: number }> {
  const upload_id = randomBytes(16).toString("hex")
  const shard = upload_id.slice(0, 2)
  const shardDir = join(this.rootDir, shard)
  mkdirSync(shardDir, { recursive: true, mode: 0o700 })
  // The .part is keyed by upload_id only; finalize derives the final extension
  // from mime at rename time (so we don't store ext here).
  const partPath = join(shardDir, `${upload_id}.part`)
  // Create the empty part file (0600) so appendChunk can stat + append it.
  closeSync(openSync(partPath, "w", 0o600))
  try {
    this.db.prepare(`
      INSERT INTO pending_uploads (upload_id, session, kind, mime, name, total_size, received, path, origin, device, created_at)
      VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, datetime('now'))
    `).run(
      upload_id, input.session, input.kind, input.mime ?? null, input.name ?? null,
      input.totalSize, partPath, input.origin, input.device ?? null,
    )
  } catch (err) {
    try { unlinkSync(partPath) } catch { /* best-effort */ }
    throw err
  }
  return { upload_id, chunk_size: chunkBytes() }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/core/files/store.test.ts -t "creates an empty .part file"`
Expected: PASS. (Requires `pendingOffset` — implement Task 5 concurrently, OR temporarily assert `allFiles(root).length` only. Since Task 5's method is tiny, implement `createPending`, `appendChunk`, `pendingOffset` together and run all three test blocks; ordering here is for narrative.)

> NOTE for the executor: Tasks 3–5 are interdependent (the createPending test calls `pendingOffset`). Implement all three methods, then run `bun test src/core/files/store.test.ts` once. Keep the commits separate per method if clean, or squash Tasks 3–5 into one commit — your call.

- [ ] **Step 5: Commit**

```bash
git add src/core/files/store.ts src/core/files/store.test.ts
git commit -m "feat(uploads): FileStore.createPending

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `FileStore.appendChunk`

**Files:**
- Modify: `src/core/files/store.ts`
- Test: `src/core/files/store.test.ts`

- [ ] **Step 1: Write the failing tests**

Add to `src/core/files/store.test.ts`:

```typescript
describe("FileStore.appendChunk", () => {
  async function begin(totalSize: number) {
    const made = makeStore()
    const { upload_id } = await made.store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", totalSize, origin: "web-upload",
    })
    return { ...made, upload_id }
  }

  test("appends in order, reports received + done on the last chunk", async () => {
    const { store, upload_id } = await begin(6)
    const r1 = await store.appendChunk(upload_id, 0, Buffer.from("abc"))
    expect(r1).toEqual({ received: 3, done: false })
    const r2 = await store.appendChunk(upload_id, 3, Buffer.from("def"))
    expect(r2).toEqual({ received: 6, done: true })
    expect(await store.pendingOffset(upload_id)).toBe(6)
  })

  test("wrong offset throws OffsetConflictError carrying the true offset", async () => {
    const { store, upload_id } = await begin(6)
    await store.appendChunk(upload_id, 0, Buffer.from("abc"))
    let thrown: any
    try { await store.appendChunk(upload_id, 0, Buffer.from("xxx")) } catch (e) { thrown = e }
    expect(thrown).toBeInstanceOf(OffsetConflictError)
    expect(thrown.offset).toBe(3)
  })

  test("overflow past total_size throws UploadOverflowError and stores nothing extra", async () => {
    const { store, upload_id } = await begin(4)
    await store.appendChunk(upload_id, 0, Buffer.from("ab"))
    let thrown: any
    try { await store.appendChunk(upload_id, 2, Buffer.from("cde")) } catch (e) { thrown = e } // 2+3 > 4
    expect(thrown).toBeInstanceOf(UploadOverflowError)
    expect(await store.pendingOffset(upload_id)).toBe(2)
  })

  test("unknown upload_id throws UploadNotFoundError", async () => {
    const { store } = await begin(4)
    let thrown: any
    try { await store.appendChunk("deadbeef".repeat(4), 0, Buffer.from("x")) } catch (e) { thrown = e }
    expect(thrown).toBeInstanceOf(UploadNotFoundError)
  })
})
```

Add the new error classes to the store import at the top of the test file:

```typescript
import { FileStore, PayloadTooLargeError, EmptyUploadError, OffsetConflictError, UploadOverflowError, UploadNotFoundError } from "./store"
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/core/files/store.test.ts -t "appends in order"`
Expected: FAIL — `store.appendChunk is not a function`.

- [ ] **Step 3: Implement `appendChunk`**

Add inside the `FileStore` class:

```typescript
/** Append one chunk at `offset`, which MUST equal the bytes already stored
 *  (filesystem size is the source of truth, so this survives a broker restart).
 *  Returns the new byte total and whether the upload is now complete. */
async appendChunk(
  upload_id: string,
  offset: number,
  chunk: Uint8Array | Buffer,
): Promise<{ received: number; done: boolean }> {
  const row = this.db.prepare(
    "SELECT path, total_size FROM pending_uploads WHERE upload_id = ?",
  ).get(upload_id) as { path: string; total_size: number } | undefined
  if (!row) throw new UploadNotFoundError()

  const cur = statSync(row.path).size
  if (offset !== cur) throw new OffsetConflictError(cur)

  const buf = chunk instanceof Buffer
    ? chunk
    : Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength)
  if (cur + buf.length > row.total_size) throw new UploadOverflowError()

  // Append at EOF ("a"), fsync per chunk so a resumable offset is durable.
  const fd = openSync(row.path, "a")
  try {
    writeSync(fd, buf, 0, buf.length)
    fsyncSync(fd)
  } finally {
    closeSync(fd)
  }

  const received = cur + buf.length
  this.db.prepare("UPDATE pending_uploads SET received = ? WHERE upload_id = ?").run(received, upload_id)
  return { received, done: received === row.total_size }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/core/files/store.test.ts -t "FileStore.appendChunk"`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/files/store.ts src/core/files/store.test.ts
git commit -m "feat(uploads): FileStore.appendChunk with offset/overflow validation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `FileStore.pendingOffset`

**Files:**
- Modify: `src/core/files/store.ts`
- Test: `src/core/files/store.test.ts`

- [ ] **Step 1: Write the failing test**

Add to `src/core/files/store.test.ts`:

```typescript
describe("FileStore.pendingOffset", () => {
  test("returns the byte count for an in-flight upload, null for unknown", async () => {
    const { store } = makeStore()
    const { upload_id } = await store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", totalSize: 10, origin: "web-upload",
    })
    expect(await store.pendingOffset(upload_id)).toBe(0)
    await store.appendChunk(upload_id, 0, Buffer.from("hello"))
    expect(await store.pendingOffset(upload_id)).toBe(5)
    expect(await store.pendingOffset("00000000000000000000000000000000")).toBeNull()
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/core/files/store.test.ts -t "returns the byte count"`
Expected: FAIL — `store.pendingOffset is not a function`.

- [ ] **Step 3: Implement `pendingOffset`**

Add inside the `FileStore` class:

```typescript
/** Current stored byte count for an in-flight upload (the resume offset), or
 *  null if the upload_id is unknown. Reads the filesystem size as the truth. */
async pendingOffset(upload_id: string): Promise<number | null> {
  const row = this.db.prepare(
    "SELECT path FROM pending_uploads WHERE upload_id = ?",
  ).get(upload_id) as { path: string } | undefined
  if (!row) return null
  try { return statSync(row.path).size } catch { return null }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/core/files/store.test.ts -t "returns the byte count"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/files/store.ts src/core/files/store.test.ts
git commit -m "feat(uploads): FileStore.pendingOffset (resume probe)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `FileStore.finalizePending`

**Files:**
- Modify: `src/core/files/store.ts`
- Test: `src/core/files/store.test.ts`

- [ ] **Step 1: Write the failing test**

Add to `src/core/files/store.test.ts`:

```typescript
describe("FileStore.finalizePending", () => {
  test("renames .part to final, inserts attachments row keyed by upload_id, drops pending row", async () => {
    const { store, root } = makeStore()
    const { upload_id } = await store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", name: "clip.mp4",
      totalSize: 11, device: "d1", origin: "web-upload",
    })
    await store.appendChunk(upload_id, 0, Buffer.from("hello "))
    await store.appendChunk(upload_id, 6, Buffer.from("world"))
    const fin = await store.finalizePending(upload_id)
    expect(fin).toMatchObject({ file_id: upload_id, size: 11, mime: "video/mp4", name: "clip.mp4", kind: "video" })

    // retrievable as a normal attachment, bytes intact, no .part, pending row gone
    const meta = await store.get(upload_id)
    expect(meta).toMatchObject({ kind: "video", size: 11 })
    expect(readFileSync(meta!.path).toString()).toBe("hello world")
    expect(allFiles(root).length).toBe(1)
    expect(allFiles(root)[0].endsWith(".part")).toBe(false)
    expect(await store.pendingOffset(upload_id)).toBeNull()

    // finalized file carries the device→file_id binding so a web send frame
    // can validate ownership (parity with the single-POST path).
    expect(await store.resolveOwnedWebUpload(upload_id, "d1")).toMatchObject({ file_id: upload_id, kind: "video" })
    expect(await store.resolveOwnedWebUpload(upload_id, "other-device")).toBeNull()
  })

  test("unknown upload_id throws UploadNotFoundError", async () => {
    const { store } = makeStore()
    let thrown: any
    try { await store.finalizePending("00000000000000000000000000000000") } catch (e) { thrown = e }
    expect(thrown).toBeInstanceOf(UploadNotFoundError)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/core/files/store.test.ts -t "FileStore.finalizePending"`
Expected: FAIL — `store.finalizePending is not a function`.

- [ ] **Step 3: Implement `finalizePending`**

Add inside the `FileStore` class:

```typescript
/** Complete an upload whose bytes are fully received: fsync the .part, rename it
 *  to <upload_id>.<ext>, insert the attachments row (file_id = upload_id), and
 *  delete the pending row. Mirrors put()/putStream() durability + cleanup. */
async finalizePending(upload_id: string): Promise<{
  file_id: string; size: number; mime?: string; name?: string; kind: AttachmentKind
}> {
  const row = this.db.prepare(`
    SELECT session, kind, mime, name, path, origin, device FROM pending_uploads WHERE upload_id = ?
  `).get(upload_id) as {
    session: string; kind: AttachmentKind; mime: string | null; name: string | null
    path: string; origin: string; device: string | null
  } | undefined
  if (!row) throw new UploadNotFoundError()

  const size = statSync(row.path).size
  const ext = extFromMime(row.mime ?? undefined)
  const finalPath = row.path.replace(/\.part$/, `.${ext}`)

  // Each chunk already fsync'd in appendChunk; re-fsync (r+ so fsync is valid on
  // every platform, not just Linux read-only fds) before exposing via rename.
  const fd = openSync(row.path, "r+")
  try { fsyncSync(fd) } finally { closeSync(fd) }
  renameSync(row.path, finalPath)

  try {
    this.db.prepare(`
      INSERT INTO attachments (file_id, kind, mime, size, name, path, origin, session, device, created_at, ref_count)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), 0)
    `).run(upload_id, row.kind, row.mime, size, row.name, finalPath, row.origin, row.session, row.device)
  } catch (err) {
    try { unlinkSync(finalPath) } catch { /* best-effort */ }
    throw err
  }
  this.db.prepare("DELETE FROM pending_uploads WHERE upload_id = ?").run(upload_id)
  return { file_id: upload_id, size, mime: row.mime ?? undefined, name: row.name ?? undefined, kind: row.kind }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/core/files/store.test.ts -t "FileStore.finalizePending"`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/files/store.ts src/core/files/store.test.ts
git commit -m "feat(uploads): FileStore.finalizePending

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: `FileStore.gcPendingOnce`

**Files:**
- Modify: `src/core/files/store.ts`
- Test: `src/core/files/store.test.ts`

- [ ] **Step 1: Write the failing test**

Add to `src/core/files/store.test.ts`:

```typescript
describe("FileStore.gcPendingOnce", () => {
  test("reaps a partial older than the TTL and leaves a fresh one", async () => {
    const { store, root } = makeStore()
    const stale = await store.createPending({ session: "s1", kind: "video", mime: "video/mp4", totalSize: 10, origin: "web-upload" })
    const fresh = await store.createPending({ session: "s1", kind: "video", mime: "video/mp4", totalSize: 10, origin: "web-upload" })
    // Backdate the stale row 48h so a 24h TTL reaps it.
    ;(store as any).db.prepare(
      "UPDATE pending_uploads SET created_at = datetime('now', '-48 hours') WHERE upload_id = ?",
    ).run(stale.upload_id)

    const reaped = await store.gcPendingOnce({ ttlHours: 24 })
    expect(reaped).toBe(1)
    expect(await store.pendingOffset(stale.upload_id)).toBeNull()
    expect(await store.pendingOffset(fresh.upload_id)).toBe(0)
    // only the fresh .part remains on disk
    expect(allFiles(root).length).toBe(1)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/core/files/store.test.ts -t "reaps a partial older than the TTL"`
Expected: FAIL — `store.gcPendingOnce is not a function`.

- [ ] **Step 3: Implement `gcPendingOnce`**

Add inside the `FileStore` class (mirrors the existing `gcOnce`):

```typescript
/** Delete abandoned in-flight uploads (.part file + row) older than ttlHours.
 *  On an unlink error other than ENOENT, leave the row so a later sweep retries. */
async gcPendingOnce(opts: { ttlHours: number }): Promise<number> {
  const rows = this.db.prepare(`
    SELECT upload_id, path FROM pending_uploads
    WHERE created_at < datetime('now', ?)
  `).all(`-${opts.ttlHours} hours`) as Array<{ upload_id: string; path: string }>

  for (const r of rows) {
    let unlinkOk = true
    try {
      unlinkSync(r.path)
    } catch (e: any) {
      if (e?.code === "ENOENT") {
        log.warn("filestore_gc_pending_enoent", { upload_id: r.upload_id, path: r.path })
      } else {
        log.warn("filestore_gc_pending_failed", { upload_id: r.upload_id, path: r.path, err: e?.message ?? String(e) })
        unlinkOk = false
      }
    }
    if (unlinkOk) {
      this.db.prepare("DELETE FROM pending_uploads WHERE upload_id = ?").run(r.upload_id)
    }
  }
  return rows.length
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/core/files/store.test.ts -t "reaps a partial older than the TTL"`
Expected: PASS. Then run the whole file: `bun test src/core/files/store.test.ts` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/files/store.ts src/core/files/store.test.ts
git commit -m "feat(uploads): FileStore.gcPendingOnce TTL sweep

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: `POST /upload/init` endpoint

**Files:**
- Modify: `src/channels/web/index.ts`
- Test: `src/channels/web/upload-routes.test.ts`

- [ ] **Step 1: Write the failing tests**

Add to `src/channels/web/upload-routes.test.ts` (new describe block; helpers `makeChannel`, `mintToken`, `base`, `setEnv` already exist):

```typescript
describe("POST /upload/init", () => {
  test("returns {upload_id, offset:0, chunk_size} and creates a pending upload", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime: "video/mp4", name: "clip.mp4", total_size: 12345 }),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.upload_id as string).toMatch(/^[0-9a-f]{32}$/)
    expect(body.offset).toBe(0)
    expect(body.chunk_size).toBe(5 * 1024 * 1024)
    expect(await made.store.pendingOffset(body.upload_id as string)).toBe(0)
  })

  test("total_size over cap → 413", async () => {
    setEnv("MUX_WEB_UPLOAD_MAX_MB", "0.0001") // ~104 bytes
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)
    const res = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime: "video/mp4", total_size: 500 }),
    })
    expect(res.status).toBe(413)
  })

  test("missing session → 400; no auth → 401", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)
    const bad = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ mime: "video/mp4", total_size: 10 }),
    })
    expect(bad.status).toBe(400)
    const noauth = await fetch(`${base()}/upload/init`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", total_size: 10 }),
    })
    expect(noauth.status).toBe(401)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/channels/web/upload-routes.test.ts -t "POST /upload/init"`
Expected: FAIL — 404 (route not handled) instead of 200/413/400.

- [ ] **Step 3: Implement the handler**

In `src/channels/web/index.ts`, immediately after the closing brace of the existing `if (method === "POST" && path === "/upload") { … }` block (ends line ~1223), insert:

```typescript
if (method === "POST" && path === "/upload/init") {
  const authResult = this.requireAuth(req)
  if (!authResult.ok) return new Response("unauthorized", { status: 401 })
  if (!this.fileStore) return new Response("file store not mounted", { status: 500 })
  const MAX_UPLOAD_BYTES = Number(process.env.MUX_WEB_UPLOAD_MAX_MB ?? 500) * 1024 * 1024

  let body: any
  try { body = await req.json() } catch { return new Response("bad json", { status: 400 }) }
  const session = typeof body?.session === "string" ? body.session : ""
  if (session.length === 0) return new Response("session required", { status: 400 })
  const totalSize = Number(body?.total_size)
  if (!Number.isFinite(totalSize) || totalSize <= 0) return new Response("total_size required", { status: 400 })
  if (totalSize > MAX_UPLOAD_BYTES) return new Response("payload too large", { status: 413 })

  const mime = typeof body?.mime === "string" ? body.mime : undefined
  const name = typeof body?.name === "string" ? body.name : undefined
  const kindHint = typeof body?.kind === "string" ? body.kind : undefined
  const kind: AttachmentKind = (kindHint && VALID_KINDS.includes(kindHint as AttachmentKind))
    ? (kindHint as AttachmentKind)
    : kindFromMime(mime)

  try {
    const { upload_id, chunk_size } = await this.fileStore.createPending({
      session, kind, mime, name, totalSize, device: authResult.device.name, origin: "web-upload",
    })
    log.info("upload.init", { upload_id, kind, mime, name, session, totalSize, device: authResult.device.name })
    return this.json({ upload_id, offset: 0, chunk_size })
  } catch (err: any) {
    log.error("upload.init_failed", { err: err?.message ?? String(err), device: authResult.device.name, session })
    return new Response("file store error", { status: 500 })
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/channels/web/upload-routes.test.ts -t "POST /upload/init"`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/index.ts src/channels/web/upload-routes.test.ts
git commit -m "feat(uploads): POST /upload/init endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: `PATCH /upload/<id>` endpoint (chunk append + inline finalize)

**Files:**
- Modify: `src/channels/web/index.ts`
- Test: `src/channels/web/upload-routes.test.ts`

- [ ] **Step 1: Write the failing tests**

Add to `src/channels/web/upload-routes.test.ts`:

```typescript
describe("PATCH /upload/<id>", () => {
  async function init(made: ReturnType<typeof makeChannel>, token: string, total: number, mime = "video/mp4") {
    const res = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime, name: "clip.mp4", total_size: total }),
    })
    return (await res.json()).upload_id as string
  }
  function patch(token: string, id: string, offset: number, chunk: Uint8Array) {
    return fetch(`${base()}/upload/${id}`, {
      method: "PATCH",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/offset+octet-stream", "upload-offset": String(offset) },
      body: chunk,
    })
  }

  test("two chunks: first → {offset}, last → finalized {file_id,size,mime,name}", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const id = await init(made, token, 6)

    const r1 = await patch(token, id, 0, new Uint8Array([1, 2, 3]))
    expect(r1.status).toBe(200)
    expect((await r1.json()).offset).toBe(3)

    const r2 = await patch(token, id, 3, new Uint8Array([4, 5, 6]))
    expect(r2.status).toBe(200)
    const fin = await r2.json() as Record<string, unknown>
    expect(fin.file_id).toBe(id)
    expect(fin.size).toBe(6)
    expect(fin.mime).toBe("video/mp4")
    expect(fin.name).toBe("clip.mp4")

    // downloadable as a normal attachment
    const dl = await fetch(`${base()}/files/${id}`, { headers: { authorization: `Bearer ${token}` } })
    expect(new Uint8Array(await dl.arrayBuffer())).toEqual(new Uint8Array([1, 2, 3, 4, 5, 6]))
  })

  test("wrong offset → 409 with Upload-Offset header (resume path)", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const id = await init(made, token, 6)
    await patch(token, id, 0, new Uint8Array([1, 2, 3]))
    const conflict = await patch(token, id, 0, new Uint8Array([9, 9, 9]))
    expect(conflict.status).toBe(409)
    expect(conflict.headers.get("upload-offset")).toBe("3")
  })

  test("overflow past total_size → 400", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const id = await init(made, token, 4)
    const over = await patch(token, id, 0, new Uint8Array([1, 2, 3, 4, 5]))
    expect(over.status).toBe(400)
  })

  test("unknown upload_id → 404; no auth → 401", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const missing = await patch(token, "00000000000000000000000000000000", 0, new Uint8Array([1]))
    expect(missing.status).toBe(404)
    const noauth = await fetch(`${base()}/upload/whatever`, {
      method: "PATCH", headers: { "upload-offset": "0" }, body: new Uint8Array([1]),
    })
    expect(noauth.status).toBe(401)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/channels/web/upload-routes.test.ts -t "PATCH /upload"`
Expected: FAIL — 404 for all (route not handled).

- [ ] **Step 3: Implement the handler**

In `src/channels/web/index.ts`, after the `/upload/init` block from Task 8, insert. Import the new errors at the top of the file (extend the existing `store` import on line 10):

```typescript
import { PayloadTooLargeError, EmptyUploadError, OffsetConflictError, UploadOverflowError, UploadNotFoundError } from "../../core/files/store"
```

Handler:

```typescript
if (method === "PATCH" && path.startsWith("/upload/")) {
  const authResult = this.requireAuth(req)
  if (!authResult.ok) return new Response("unauthorized", { status: 401 })
  if (!this.fileStore) return new Response("file store not mounted", { status: 500 })
  const upload_id = decodeURIComponent(path.slice("/upload/".length))

  const offset = Number(req.headers.get("upload-offset"))
  if (!Number.isInteger(offset) || offset < 0) return new Response("Upload-Offset required", { status: 400 })
  if (!req.body) return new Response("empty body", { status: 400 })

  const chunk = new Uint8Array(await req.arrayBuffer())
  try {
    const { received, done } = await this.fileStore.appendChunk(upload_id, offset, chunk)
    if (!done) return this.json({ offset: received })
    const fin = await this.fileStore.finalizePending(upload_id)
    log.info("upload.finalized", { upload_id, size: fin.size, device: authResult.device.name })
    return this.json({ file_id: fin.file_id, size: fin.size, mime: fin.mime, name: fin.name })
  } catch (err: any) {
    if (err instanceof UploadNotFoundError) return new Response("upload not found", { status: 404 })
    if (err instanceof OffsetConflictError) {
      return new Response("offset conflict", { status: 409, headers: { "upload-offset": String(err.offset) } })
    }
    if (err instanceof UploadOverflowError) return new Response("chunk exceeds total size", { status: 400 })
    log.error("upload.patch_failed", { err: err?.message ?? String(err), upload_id, device: authResult.device.name })
    return new Response("file store error", { status: 500 })
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/channels/web/upload-routes.test.ts -t "PATCH /upload"`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/index.ts src/channels/web/upload-routes.test.ts
git commit -m "feat(uploads): PATCH /upload/<id> chunk append + inline finalize

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: `HEAD /upload/<id>` endpoint (resume probe)

**Files:**
- Modify: `src/channels/web/index.ts`
- Test: `src/channels/web/upload-routes.test.ts`

- [ ] **Step 1: Write the failing test**

Add to `src/channels/web/upload-routes.test.ts`:

```typescript
describe("HEAD /upload/<id>", () => {
  test("returns 200 + Upload-Offset for an in-flight upload, 404 for unknown", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const initRes = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime: "video/mp4", total_size: 10 }),
    })
    const id = (await initRes.json()).upload_id as string
    await fetch(`${base()}/upload/${id}`, {
      method: "PATCH",
      headers: { authorization: `Bearer ${token}`, "upload-offset": "0" },
      body: new Uint8Array([1, 2, 3, 4]),
    })

    const head = await fetch(`${base()}/upload/${id}`, { method: "HEAD", headers: { authorization: `Bearer ${token}` } })
    expect(head.status).toBe(200)
    expect(head.headers.get("upload-offset")).toBe("4")

    const missing = await fetch(`${base()}/upload/00000000000000000000000000000000`, {
      method: "HEAD", headers: { authorization: `Bearer ${token}` },
    })
    expect(missing.status).toBe(404)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/channels/web/upload-routes.test.ts -t "HEAD /upload"`
Expected: FAIL — 404/undefined header where 200 + `upload-offset: 4` is expected.

- [ ] **Step 3: Implement the handler**

In `src/channels/web/index.ts`, after the `PATCH /upload/` block:

```typescript
if (method === "HEAD" && path.startsWith("/upload/")) {
  const authResult = this.requireAuth(req)
  if (!authResult.ok) return new Response(null, { status: 401 })
  if (!this.fileStore) return new Response(null, { status: 500 })
  const upload_id = decodeURIComponent(path.slice("/upload/".length))
  const offset = await this.fileStore.pendingOffset(upload_id)
  if (offset === null) return new Response(null, { status: 404 })
  return new Response(null, { status: 200, headers: { "upload-offset": String(offset) } })
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/channels/web/upload-routes.test.ts -t "HEAD /upload"`
Expected: PASS. Then the whole upload-routes file: `bun test src/channels/web/upload-routes.test.ts` — Expected: PASS (old + new).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/index.ts src/channels/web/upload-routes.test.ts
git commit -m "feat(uploads): HEAD /upload/<id> resume probe

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 11: Schedule `gcPendingOnce` + full-suite green

**Files:**
- Modify: `src/main.ts:477-480`

- [ ] **Step 1: Add the pending-upload sweep to the existing GC interval**

In `src/main.ts`, replace the existing GC interval block (lines 477–480):

```typescript
// hourly GC sweep — orphans older than 24h
const gcInterval = setInterval(() => {
  fileStore.gcOnce({ graceHours: 24 }).catch((err) => log.error("filestore_gc_failed", { err: err?.message ?? String(err) }))
}, 60 * 60 * 1000)
```

with:

```typescript
// hourly GC sweep — orphan attachments (24h) + abandoned in-flight uploads (TTL)
const pendingTtlHours = Number(process.env.MUX_UPLOAD_PENDING_TTL_HOURS ?? 24)
const gcInterval = setInterval(() => {
  fileStore.gcOnce({ graceHours: 24 }).catch((err) => log.error("filestore_gc_failed", { err: err?.message ?? String(err) }))
  fileStore.gcPendingOnce({ ttlHours: pendingTtlHours }).catch((err) => log.error("filestore_gc_pending_failed", { err: err?.message ?? String(err) }))
}, 60 * 60 * 1000)
```

- [ ] **Step 2: Type-check the whole project**

Run: `bunx tsc --noEmit -p tsconfig.json`
Expected: no NEW errors (the digest notes ~3 pre-existing typecheck errors unrelated to these files; confirm none reference `store.ts`, `web/index.ts`, or `main.ts`).

- [ ] **Step 3: Run the full affected suites**

Run: `bun test src/core/files src/channels/web`
Expected: PASS. (Broader `bun test` has ~2 known pre-existing failures per the digest — `no-legacy-names` and a `spawn-command` reply-fallback test — unrelated to this work.)

- [ ] **Step 4: Manual smoke via curl (optional but recommended)**

With a dev broker running and a bearer token in `$TOK`:

```bash
ID=$(curl -s -X POST localhost:9898/upload/init -H "authorization: Bearer $TOK" \
  -H 'content-type: application/json' -d '{"session":"s1","mime":"video/mp4","name":"a.mp4","total_size":6}' | jq -r .upload_id)
printf 'abc' | curl -s -X PATCH "localhost:9898/upload/$ID" -H "authorization: Bearer $TOK" -H 'upload-offset: 0' --data-binary @-
curl -sI -X HEAD "localhost:9898/upload/$ID" -H "authorization: Bearer $TOK" | grep -i upload-offset   # → 3
printf 'def' | curl -s -X PATCH "localhost:9898/upload/$ID" -H "authorization: Bearer $TOK" -H 'upload-offset: 3' --data-binary @-  # → {file_id,size:6,...}
```

Expected: HEAD reports offset 3; the second PATCH returns the finalized `{file_id, size:6, ...}`.

- [ ] **Step 5: Commit**

```bash
git add src/main.ts
git commit -m "feat(uploads): schedule abandoned-upload TTL sweep

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Done criteria (Phase 1)

- `bun test src/core/files src/channels/web` green, including the new resumable
  round-trip (init → patch → HEAD → patch → finalize → download).
- `POST /upload` single-shot path unchanged (its existing tests still pass).
- Abandoned partials self-clean via the hourly sweep.
- Response shape of a finalized chunked upload is byte-identical to the single-POST
  path (`{ file_id, size, mime, name }`), so Phase 2's client can treat both the
  same downstream.

## Next phases (separate plans, authored after this merges)

2. **Shared KMP** — `BrokerApi.uploadResumable()` + `ChunkSource` expect/actual +
   progress + resume-on-drop + web↔Kotlin parity tests.
3. **Web** — XHR single-POST progress + chunked client + determinate UI + retry.
4. **iOS** — file-URL sourcing for video + progress/failed composer UI + chunked.
5. **Android** — Uri slicing + progress/failed chip + chunked + error surfacing.
