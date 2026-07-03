# Broker + Inbound — Video Upload Phase 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Give the broker a distinct `"video"` attachment kind and a RAM-safe streaming upload path (`FileStore.putStream` + a dual-mode `/upload`) so clients can upload videos up to 500 MB, and map inbound Telegram/WhatsApp video to the new kind.

**Architecture:** Attachments are stored by a durable `FileStore` (`<file_id>.part` → `fsync` → `rename` → sqlite row). This plan (1) introduces a `"video"` kind across the kind/mime/channel type layer, (2) adds `FileStore.putStream`, which pipes a `ReadableStream` to disk while enforcing a byte cap and throwing a distinguishable `PayloadTooLargeError`, and (3) makes the web `/upload` handler branch on `Content-Type`: `multipart/form-data` keeps the legacy buffered path (small cap), everything else streams the raw request body (500 MB cap). Inbound Telegram `m.video` and WhatsApp `video` are normalized to `"video"`.

**Tech Stack:** TypeScript on Bun; `bun:sqlite`; `Bun.serve` HTTP; `bun test` runner.

**Depends on:** nothing — this lands FIRST; all client plans depend on it.

---

## File structure

| File | Create/Modify | Responsibility |
| --- | --- | --- |
| `src/core/files/kinds.ts` | Modify | Add `"video"` to `AttachmentKind`; map `video/* → "video"`. |
| `src/core/files/mime.ts` | Modify | Add `video/quicktime→mov`, `video/x-matroska→mkv`, `video/x-m4v→m4v`. |
| `src/channels/channel.ts` | Modify | Add `"video"` to `OutboundAttachmentRef.kind` and `InboundAttachment.kind`. |
| `src/core/push/hook.ts` | Modify | Add `case "video"` to `extractPreview` so an outbound video shows "🎥 Video". |
| `src/core/files/store.ts` | Modify | Add `PayloadTooLargeError` + `FileStore.putStream(input, source)`. |
| `src/channels/web/index.ts` | Modify | Dual-mode `/upload`; `VALID_KINDS += "video"`; caps 500 MB / 25 MB multipart; import `PayloadTooLargeError`. |
| `src/channels/telegram/inbound.ts` | Modify | `pickRawAttachment` gains an `m.video → "video"` branch. |
| `src/channels/whatsapp/inbound.ts` | Modify | `mediaKind` maps `video → "video"`; fix stale comment. |
| `src/core/files/kinds.test.ts` | Create | Unit-test `kindFromMime` video mapping. |
| `src/core/files/mime.test.ts` | Create | Unit-test `extFromMime` for mov/mkv/m4v. |
| `src/core/push/hook.test.ts` | Modify | Add an `extractPreview` video-label assertion. |
| `src/core/files/store.test.ts` | Create | Unit-test `putStream` (happy, cap-abort, insert-failure cleanup). |
| `src/channels/web/upload-routes.test.ts` | Create | HTTP-test the dual-mode `/upload` endpoint. |
| `src/channels/telegram/inbound.test.ts` | Create | Test `m.video → "video"`, `m.video_note → "video_note"`. |
| `src/channels/whatsapp/inbound.test.ts` | Modify | Add a `video → "video"` case. |

**Shared contract (must match the client plans exactly):** kind string is `"video"`; streaming request is `POST /upload`, `Content-Type: application/octet-stream`, headers `X-Mux-Session` (required), `X-Mux-Mime`, `X-Mux-Filename` (percent-encoded), `X-Mux-Kind` (optional); body = raw bytes; response `{file_id,size,mime,name}`; cap 500 MB.

**Test runner note:** this repo runs `bun test`. `bun test <path>` runs a single file. `bun run typecheck` runs `tsc --noEmit` (used to verify the type-only union changes, which `bun test` does not typecheck).

---

### Task 1: `"video"` attachment kind, MIME extensions, channel unions

**Files:**
- Modify `src/core/files/kinds.ts` (lines 2, 8)
- Modify `src/core/files/mime.ts` (MIME_EXT object, after line 15)
- Modify `src/channels/channel.ts` (lines 10, 27)
- Create test `src/core/files/kinds.test.ts`
- Create test `src/core/files/mime.test.ts`
- Modify `src/core/push/hook.ts` (add `case "video"` to `extractPreview`, ~line 15)
- Modify test `src/core/push/hook.test.ts` (add a video-preview assertion)

- [ ] Write the failing kind test. Create `src/core/files/kinds.test.ts` with the COMPLETE contents:
  ```ts
  import { describe, expect, test } from "bun:test"
  import { kindFromMime } from "./kinds"

  describe("kindFromMime", () => {
    test("video/* → 'video' (was video_note)", () => {
      expect(kindFromMime("video/mp4")).toBe("video")
      expect(kindFromMime("video/quicktime")).toBe("video")
      expect(kindFromMime("video/webm")).toBe("video")
    })

    test("non-video mappings unchanged", () => {
      expect(kindFromMime("image/png")).toBe("photo")
      expect(kindFromMime("audio/ogg")).toBe("audio")
      expect(kindFromMime("application/pdf")).toBe("document")
      expect(kindFromMime(undefined)).toBe("document")
    })
  })
  ```

- [ ] Write the failing mime test. Create `src/core/files/mime.test.ts` with the COMPLETE contents:
  ```ts
  import { describe, expect, test } from "bun:test"
  import { extFromMime } from "./mime"

  describe("extFromMime", () => {
    test("new video container types map to real extensions", () => {
      expect(extFromMime("video/quicktime")).toBe("mov")
      expect(extFromMime("video/x-matroska")).toBe("mkv")
      expect(extFromMime("video/x-m4v")).toBe("m4v")
    })

    test("existing video types unchanged", () => {
      expect(extFromMime("video/mp4")).toBe("mp4")
      expect(extFromMime("video/webm")).toBe("webm")
    })

    test("unknown/undefined → bin", () => {
      expect(extFromMime("video/3gpp")).toBe("bin")
      expect(extFromMime(undefined)).toBe("bin")
    })
  })
  ```

- [ ] Run the tests and watch them FAIL:
  ```
  bun test src/core/files/kinds.test.ts src/core/files/mime.test.ts
  ```
  Expected FAIL — `kindFromMime`: `expect(received).toBe(expected)` → `Expected: "video"  Received: "video_note"`; `extFromMime`: `Expected: "mov"  Received: "bin"`.

- [ ] Implement `kinds.ts`. Replace the entire file with the COMPLETE contents:
  ```ts
  // src/core/files/kinds.ts
  export type AttachmentKind = "photo" | "document" | "voice" | "audio" | "video" | "video_note"

  export function kindFromMime(mime: string | undefined): AttachmentKind {
    if (!mime) return "document"
    if (mime.startsWith("image/")) return "photo"
    if (mime.startsWith("audio/")) return "audio"
    if (mime.startsWith("video/")) return "video"
    return "document"
  }
  ```

- [ ] Implement `mime.ts`. In `src/core/files/mime.ts`, find the two existing video lines (line 14-15):
  ```ts
    "video/mp4": "mp4",
    "video/webm": "webm",
  ```
  and replace them with (keep mp4/webm; add three):
  ```ts
    "video/mp4": "mp4",
    "video/webm": "webm",
    "video/quicktime": "mov",
    "video/x-matroska": "mkv",
    "video/x-m4v": "m4v",
  ```

- [ ] Implement `channel.ts` unions. In `src/channels/channel.ts`, change line 10 from:
  ```ts
    kind: "photo" | "document" | "voice" | "audio" | "video_note"
  ```
  to:
  ```ts
    kind: "photo" | "document" | "voice" | "audio" | "video" | "video_note"
  ```
  and change line 27 from:
  ```ts
    kind: "voice" | "photo" | "document" | "audio" | "video_note"
  ```
  to:
  ```ts
    kind: "voice" | "photo" | "document" | "audio" | "video" | "video_note"
  ```

- [ ] Run the tests and watch them PASS:
  ```
  bun test src/core/files/kinds.test.ts src/core/files/mime.test.ts
  ```
  Expected PASS — all cases green.

- [ ] Verify the additive type change compiles (the `channel.ts` union widening is type-only and cannot be exercised by `bun test`):
  ```
  bun run typecheck
  ```
  Expected: no errors introduced by `kinds.ts` / `channel.ts` / `mime.ts`. (The one `switch` on kind — `src/core/push/hook.ts:15` — has no exhaustiveness/`never` guard and its caller casts `kind as any`, so widening the union does not break the build; the next steps add a `case "video"` there so an outbound video preview renders correctly instead of falling through to "New message".)

- [ ] Add the failing push-preview test. In `src/core/push/hook.test.ts`, change the import on line 2 to also import `extractPreview`:
  ```ts
  import { firePushForReply, extractPreview } from "./hook"
  ```
  and append this test at the end of the file:
  ```ts
  test("extractPreview labels a video attachment", () => {
    expect(extractPreview({ op: "reply", chat_id: "web", text: "", attachments: [{ kind: "video", file_id: "f1" }] } as any)).toBe("🎥 Video")
  })
  ```

- [ ] Run it and watch it FAIL:
  ```
  bun test src/core/push/hook.test.ts
  ```
  Expected FAIL — `Expected: "🎥 Video"  Received: "New message"` (no `case "video"` yet).

- [ ] Implement the `hook.ts` case. In `src/core/push/hook.ts`, inside the `extractPreview` switch (~line 15), add a `case "video"` immediately above `case "video_note"` so both share the label:
  ```ts
      case "video":
      case "video_note": return "🎥 Video"
  ```

- [ ] Run it and watch it PASS:
  ```
  bun test src/core/push/hook.test.ts
  ```
  Expected PASS.

- [ ] Commit:
  ```
  git add src/core/files/kinds.ts src/core/files/mime.ts src/channels/channel.ts src/core/push/hook.ts src/core/files/kinds.test.ts src/core/files/mime.test.ts src/core/push/hook.test.ts
  git commit -m "$(cat <<'EOF'
  feat(files): add "video" attachment kind and video MIME extensions

  Introduce a distinct "video" AttachmentKind (reserving "video_note" for
  Telegram round clips), map video/* → "video" in kindFromMime, and add
  mov/mkv/m4v to the MIME→extension table. Widen the channel InboundAttachment
  and OutboundAttachmentRef kind unions to carry it, and add a push-preview
  case so an outbound video shows "🎥 Video".

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 2: `FileStore.putStream` streaming ingest + `PayloadTooLargeError`

**Files:**
- Modify `src/core/files/store.ts` (add `PayloadTooLargeError` after the `FileMeta` interface ~line 35; add `putStream` immediately after `put()` ~line 94)
- Create test `src/core/files/store.test.ts`

- [ ] Write the failing test. Create `src/core/files/store.test.ts` with the COMPLETE contents:
  ```ts
  import { describe, expect, test } from "bun:test"
  import { mkdtempSync, readFileSync, existsSync, readdirSync } from "fs"
  import { join } from "path"
  import { tmpdir } from "os"
  import { FileStore, PayloadTooLargeError } from "./store"
  import { openDb, runMigrations } from "../storage/db"
  import { MIGRATIONS } from "../storage/migrations"

  function makeStore(): { store: FileStore; root: string } {
    const root = mkdtempSync(join(tmpdir(), "mux-filestore-"))
    const db = openDb(":memory:")
    runMigrations(db, MIGRATIONS)
    return { store: new FileStore(db, root), root }
  }

  function streamOf(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
    return new ReadableStream<Uint8Array>({
      start(controller) {
        for (const c of chunks) controller.enqueue(c)
        controller.close()
      },
    })
  }

  // Recursively list every regular file under `dir` (proves no orphan .part or
  // final file survives a failed putStream).
  function allFiles(dir: string): string[] {
    const out: string[] = []
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name)
      if (e.isDirectory()) out.push(...allFiles(p))
      else out.push(p)
    }
    return out
  }

  describe("FileStore.putStream", () => {
    test("happy path: concatenates chunks, stores observed size, leaves no .part", async () => {
      const { store, root } = makeStore()
      const stream = streamOf([Buffer.from("hello "), Buffer.from("world")])
      const { file_id, size } = await store.putStream(
        { kind: "video", mime: "video/mp4", name: "clip.mp4", session: "s1", device: "d1", origin: "web-upload", maxBytes: 1024 },
        stream,
      )
      expect(file_id).toMatch(/^[0-9a-f]{32}$/)
      expect(size).toBe(11)

      const meta = await store.get(file_id)
      expect(meta).toMatchObject({ kind: "video", mime: "video/mp4", name: "clip.mp4", size: 11 })
      expect(readFileSync(meta!.path).toString()).toBe("hello world")
      expect(existsSync(`${meta!.path}.part`)).toBe(false)
      expect(allFiles(root).length).toBe(1)
    })

    test("cap abort: total over maxBytes throws PayloadTooLargeError and leaves no file", async () => {
      const { store, root } = makeStore()
      const stream = streamOf([new Uint8Array(60), new Uint8Array(60)]) // 120 > 100
      let thrown: any
      try {
        await store.putStream({ kind: "video", mime: "video/mp4", origin: "web-upload", maxBytes: 100 }, stream)
      } catch (e) {
        thrown = e
      }
      expect(thrown).toBeInstanceOf(PayloadTooLargeError)
      expect(thrown.code).toBe("PAYLOAD_TOO_LARGE")
      expect(allFiles(root)).toEqual([])
    })

    test("insert-failure cleanup: unlinks the final file and rethrows", async () => {
      const root = mkdtempSync(join(tmpdir(), "mux-filestore-"))
      // A db stub whose INSERT throws — proves the post-rename cleanup path.
      const failingDb = { prepare: () => ({ run: () => { throw new Error("insert boom") } }) } as any
      const store = new FileStore(failingDb, root)
      let thrown: any
      try {
        await store.putStream(
          { kind: "video", mime: "video/mp4", origin: "web-upload", maxBytes: 1024 },
          streamOf([Buffer.from("data")]),
        )
      } catch (e) {
        thrown = e
      }
      expect(String(thrown?.message)).toContain("insert boom")
      expect(allFiles(root)).toEqual([])
    })
  })
  ```

- [ ] Run the test and watch it FAIL:
  ```
  bun test src/core/files/store.test.ts
  ```
  Expected FAIL — `TypeError: store.putStream is not a function` (the method and `PayloadTooLargeError` do not exist yet).

- [ ] Implement `PayloadTooLargeError`. In `src/core/files/store.ts`, immediately AFTER the `FileMeta` interface (ends at line 35) and BEFORE `export class FileStore` (line 37), insert:
  ```ts
  /**
   * Thrown by putStream when the incoming byte total exceeds the caller's
   * maxBytes. Distinguishable (instanceof / .code) so the web channel can map it
   * to HTTP 413 rather than a generic 500.
   */
  export class PayloadTooLargeError extends Error {
    readonly code = "PAYLOAD_TOO_LARGE" as const
    constructor(message = "payload too large") {
      super(message)
      this.name = "PayloadTooLargeError"
    }
  }
  ```

- [ ] Implement `putStream`. In `src/core/files/store.ts`, immediately AFTER the `put()` method's closing brace (line 94) and BEFORE `async get(...)` (line 96), insert the COMPLETE method:
  ```ts
    /**
     * Streaming variant of put(): consume `source` chunk-by-chunk into
     * <file_id>.part while tracking a running byte total, so a large upload never
     * buffers wholly in RAM. If the running total exceeds input.maxBytes, abort:
     * unlink the partial file and throw PayloadTooLargeError. On success: fsync,
     * rename to the final path, then INSERT the row with the OBSERVED byte total.
     * Mirrors put()'s two cleanup blocks (unlink part on write failure; unlink
     * final on INSERT failure).
     */
    async putStream(
      input: Omit<FileStorePutInput, "bytes"> & { maxBytes: number },
      source: ReadableStream<Uint8Array>,
    ): Promise<{ file_id: string; size: number }> {
      const file_id = randomBytes(16).toString("hex")
      const ext = extFromMime(input.mime)
      const shard = file_id.slice(0, 2)
      const shardDir = join(this.rootDir, shard)
      mkdirSync(shardDir, { recursive: true, mode: 0o700 })

      const finalPath = join(shardDir, `${file_id}.${ext}`)
      const partPath = `${finalPath}.part`

      // Stream chunks to <file_id>.part, enforcing the cap as bytes arrive so a
      // chunked/absent-length or lying client can't exceed maxBytes. fsync before
      // rename for the same durability guarantee as put(). Unlink the part file on
      // any write failure (including a cap abort).
      let total = 0
      try {
        const fd = openSync(partPath, "w", 0o600)
        try {
          const reader = source.getReader()
          try {
            while (true) {
              const { done, value } = await reader.read()
              if (done) break
              if (!value || value.byteLength === 0) continue
              total += value.byteLength
              if (total > input.maxBytes) throw new PayloadTooLargeError()
              const buf = Buffer.from(value.buffer, value.byteOffset, value.byteLength)
              writeSync(fd, buf, 0, buf.length)
            }
            fsyncSync(fd)
          } finally {
            reader.releaseLock()
          }
        } finally {
          closeSync(fd)
        }
        renameSync(partPath, finalPath)
      } catch (err) {
        try { unlinkSync(partPath) } catch { /* ignore — best-effort cleanup */ }
        throw err
      }

      // Same INSERT as put(), but with the observed total as size. If it fails
      // after rename, unlink the orphaned final file (no row → gc won't find it).
      try {
        this.db.prepare(`
          INSERT INTO attachments (file_id, kind, mime, size, name, path, origin, session, device, created_at, ref_count)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), 0)
        `).run(
          file_id,
          input.kind,
          input.mime ?? null,
          total,
          input.name ?? null,
          finalPath,
          input.origin,
          input.session ?? null,
          input.device ?? null,
        )
      } catch (err) {
        try { unlinkSync(finalPath) } catch { /* ignore — best-effort cleanup */ }
        throw err
      }

      return { file_id, size: total }
    }
  ```
  (No new imports: `Buffer`/`randomBytes`/`join`/`extFromMime` and every `fs` call are already imported at the top of `store.ts`.)

- [ ] Run the test and watch it PASS:
  ```
  bun test src/core/files/store.test.ts
  ```
  Expected PASS — happy path stores 11 bytes with no `.part`; cap abort throws `PayloadTooLargeError` and leaves the store empty; insert-failure rethrows "insert boom" and leaves the store empty.

- [ ] Verify types:
  ```
  bun run typecheck
  ```
  Expected: no new errors.

- [ ] Commit:
  ```
  git add src/core/files/store.ts src/core/files/store.test.ts
  git commit -m "$(cat <<'EOF'
  feat(files): add FileStore.putStream streaming ingest with byte cap

  putStream pipes a ReadableStream to <file_id>.part enforcing a running-total
  cap, fsyncs + renames on success, and INSERTs the row with the observed size.
  On overflow it unlinks the part file and throws a distinguishable
  PayloadTooLargeError; INSERT failure unlinks the final file. Mirrors put()'s
  durability + cleanup pattern so uploads never buffer wholly in RAM.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 3: Dual-mode `/upload` endpoint (streaming + legacy multipart)

**Files:**
- Modify `src/channels/web/index.ts` (add import after line 9; `VALID_KINDS` line 34; replace the `/upload` handler lines 1096-1161)
- Create test `src/channels/web/upload-routes.test.ts`

- [ ] Write the failing test. Create `src/channels/web/upload-routes.test.ts` with the COMPLETE contents:
  ```ts
  // HTTP tests for the dual-mode POST /upload handler. Boots a real WebChannel on
  // an ephemeral port with a real FileStore (in-memory sqlite + temp files root)
  // and drives it over fetch — mirroring update-routes.test.ts. Bearer auth
  // (native-client style) bypasses the same-origin CSRF guard so POSTs need no
  // Origin header.
  import { afterEach, describe, expect, test } from "bun:test"
  import { mkdtempSync, readdirSync } from "fs"
  import { join } from "path"
  import { tmpdir } from "os"
  import { WebChannel, type WebChannelOpts } from "./index"
  import { DeviceStore } from "./device-store"
  import { FileStore } from "../../core/files/store"
  import { openDb, runMigrations } from "../../core/storage/db"
  import { MIGRATIONS } from "../../core/storage/migrations"

  function makeChannel(): { channel: WebChannel; devicesFile: string; store: FileStore; filesRoot: string } {
    const dir = mkdtempSync(join(tmpdir(), "mux-upload-routes-"))
    const devicesFile = join(dir, "devices.json")
    const filesRoot = join(dir, "files")
    const db = openDb(":memory:")
    runMigrations(db, MIGRATIONS)
    const store = new FileStore(db, filesRoot)
    const opts: WebChannelOpts = {
      port: 0, // ephemeral; real port via channel.boundPort after start()
      devicesFile,
      publicUrl: "http://localhost",
      getSessionsSnapshot: () => [],
      getSessionLog: () => [],
      setMute: () => {},
      onSendFromWeb: () => {},
      fileStore: store,
      updateChecker: null,
    }
    return { channel: new WebChannel(opts), devicesFile, store, filesRoot }
  }

  function mintToken(devicesFile: string): string {
    return new DeviceStore(devicesFile).mint("test-device").token
  }

  function allFiles(dir: string): string[] {
    const out: string[] = []
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name)
      if (e.isDirectory()) out.push(...allFiles(p))
      else out.push(p)
    }
    return out
  }

  let channel: WebChannel | undefined
  const savedEnv: Record<string, string | undefined> = {}
  function setEnv(k: string, v: string | undefined): void {
    if (!(k in savedEnv)) savedEnv[k] = process.env[k]
    if (v === undefined) delete process.env[k]
    else process.env[k] = v
  }

  afterEach(async () => {
    for (const k of Object.keys(savedEnv)) {
      if (savedEnv[k] === undefined) delete process.env[k]
      else process.env[k] = savedEnv[k]
      delete savedEnv[k]
    }
    if (channel) {
      await channel.stop()
      channel = undefined
    }
  })

  function base(): string {
    return `http://127.0.0.1:${channel!.boundPort}`
  }

  describe("POST /upload — streaming path", () => {
    test("octet-stream body → stores file, infers video kind from mime, returns {file_id,size,mime,name}", async () => {
      const made = makeChannel()
      channel = made.channel
      await channel.start()
      const token = mintToken(made.devicesFile)

      const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8])
      const res = await fetch(`${base()}/upload`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${token}`,
          "content-type": "application/octet-stream",
          "x-mux-session": "sess-1",
          "x-mux-mime": "video/mp4",
          "x-mux-filename": encodeURIComponent("my clip.mp4"),
        },
        body: bytes,
      })
      expect(res.status).toBe(200)
      const body = (await res.json()) as Record<string, unknown>
      expect(body.file_id as string).toMatch(/^[0-9a-f]{32}$/)
      expect(body.size).toBe(8)
      expect(body.mime).toBe("video/mp4")
      expect(body.name).toBe("my clip.mp4")

      // stored under the new "video" kind and retrievable byte-for-byte
      const meta = await made.store.get(body.file_id as string)
      expect(meta?.kind).toBe("video")
      const dl = await fetch(`${base()}/files/${body.file_id}`, { headers: { authorization: `Bearer ${token}` } })
      expect(dl.status).toBe(200)
      expect(new Uint8Array(await dl.arrayBuffer())).toEqual(bytes)
    })

    test("missing X-Mux-Session → 400", async () => {
      const made = makeChannel()
      channel = made.channel
      await channel.start()
      const token = mintToken(made.devicesFile)

      const res = await fetch(`${base()}/upload`, {
        method: "POST",
        headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "x-mux-mime": "video/mp4" },
        body: new Uint8Array([1, 2, 3]),
      })
      expect(res.status).toBe(400)
    })

    test("Content-Length over cap → 413 up front", async () => {
      setEnv("MUX_WEB_UPLOAD_MAX_MB", "0.0001") // ~104 bytes
      const made = makeChannel()
      channel = made.channel
      await channel.start()
      const token = mintToken(made.devicesFile)

      const res = await fetch(`${base()}/upload`, {
        method: "POST",
        headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "x-mux-session": "s1", "x-mux-mime": "video/mp4" },
        body: new Uint8Array(500), // 500 > ~104
      })
      expect(res.status).toBe(413)
    })

    test("oversized body → 413 and leaves no stored file (putStream aborts)", async () => {
      setEnv("MUX_WEB_UPLOAD_MAX_MB", "0.0001") // ~104 bytes
      const made = makeChannel()
      channel = made.channel
      await channel.start()
      const token = mintToken(made.devicesFile)

      // A chunked (no Content-Length) body forces the authoritative in-stream cap;
      // if the runtime buffers it and sets Content-Length instead, the up-front
      // check still returns 413. Either way: 413 and nothing stored.
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new Uint8Array(80))
          controller.enqueue(new Uint8Array(80)) // 160 > ~104
          controller.close()
        },
      })
      const res = await fetch(`${base()}/upload`, {
        method: "POST",
        headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "x-mux-session": "s1", "x-mux-mime": "video/mp4" },
        body: stream,
        duplex: "half",
      } as any)
      expect(res.status).toBe(413)
      expect(allFiles(made.filesRoot)).toEqual([])
    })

    test("no auth → 401", async () => {
      const made = makeChannel()
      channel = made.channel
      await channel.start()

      const res = await fetch(`${base()}/upload`, {
        method: "POST",
        headers: { "content-type": "application/octet-stream", "x-mux-session": "s1", "x-mux-mime": "video/mp4" },
        body: new Uint8Array([1, 2, 3]),
      })
      expect(res.status).toBe(401)
    })
  })

  describe("POST /upload — legacy multipart path", () => {
    test("multipart/form-data still stores the file", async () => {
      const made = makeChannel()
      channel = made.channel
      await channel.start()
      const token = mintToken(made.devicesFile)

      const fd = new FormData()
      fd.append("file", new Blob([new Uint8Array([9, 8, 7])], { type: "image/png" }), "pic.png")
      fd.append("session", "sess-1")
      const res = await fetch(`${base()}/upload`, {
        method: "POST",
        headers: { authorization: `Bearer ${token}` },
        body: fd,
      })
      expect(res.status).toBe(200)
      const body = (await res.json()) as Record<string, unknown>
      expect(body.file_id as string).toMatch(/^[0-9a-f]{32}$/)
      expect(body.size).toBe(3)
      expect(body.mime).toBe("image/png")
      expect(body.name).toBe("pic.png")

      const meta = await made.store.get(body.file_id as string)
      expect(meta?.kind).toBe("photo")
    })

    test("multipart over its own smaller cap → 413", async () => {
      setEnv("MUX_WEB_UPLOAD_MULTIPART_MAX_MB", "0.0001") // ~104 bytes
      const made = makeChannel()
      channel = made.channel
      await channel.start()
      const token = mintToken(made.devicesFile)

      const fd = new FormData()
      fd.append("file", new Blob([new Uint8Array(500)], { type: "image/png" }), "big.png")
      fd.append("session", "sess-1")
      const res = await fetch(`${base()}/upload`, {
        method: "POST",
        headers: { authorization: `Bearer ${token}` },
        body: fd,
      })
      expect(res.status).toBe(413)
    })
  })
  ```

- [ ] Run the test and watch it FAIL:
  ```
  bun test src/channels/web/upload-routes.test.ts
  ```
  Expected FAIL — the streaming requests currently hit the legacy multipart-only handler: `await req.formData()` on an `application/octet-stream` body throws, returning **400 "bad multipart"** instead of 200/400/413 as asserted (e.g. the success test fails `expect(res.status).toBe(200)` → `Received: 400`).

- [ ] Add the `PayloadTooLargeError` import. In `src/channels/web/index.ts`, immediately AFTER line 9:
  ```ts
  import { kindFromMime, type AttachmentKind } from "../../core/files/kinds"
  ```
  add:
  ```ts
  import { PayloadTooLargeError } from "../../core/files/store"
  ```

- [ ] Add `"video"` to `VALID_KINDS`. Change line 34 from:
  ```ts
  const VALID_KINDS: AttachmentKind[] = ["photo", "document", "voice", "audio", "video_note"]
  ```
  to:
  ```ts
  const VALID_KINDS: AttachmentKind[] = ["photo", "document", "voice", "audio", "video", "video_note"]
  ```

- [ ] Replace the `/upload` handler. In `src/channels/web/index.ts`, replace the ENTIRE existing block (current lines 1096-1161, from `if (method === "POST" && path === "/upload") {` through its closing `}`) with the COMPLETE new block:
  ```ts
      if (method === "POST" && path === "/upload") {
        log.info("upload.start", {
          ip: clientIp(req),
          contentType: req.headers.get("content-type"),
          contentLength: req.headers.get("content-length"),
        })
        const authResult = this.requireAuth(req)
        if (!authResult.ok) {
          log.warn("upload.unauth", { ip: clientIp(req) })
          return new Response("unauthorized", { status: 401 })
        }
        if (!this.fileStore) {
          log.error("upload.no_store")
          return new Response("file store not mounted", { status: 500 })
        }

        // Streaming path gets the full cap (it never buffers). The legacy buffered
        // multipart path keeps a smaller in-RAM cap so an old/hostile client can't
        // OOM the broker with a huge multipart body.
        const MAX_UPLOAD_BYTES = Number(process.env.MUX_WEB_UPLOAD_MAX_MB ?? 500) * 1024 * 1024
        const MAX_MULTIPART_BYTES = Number(process.env.MUX_WEB_UPLOAD_MULTIPART_MAX_MB ?? 25) * 1024 * 1024
        const contentType = req.headers.get("content-type") ?? ""

        // ── Legacy buffered path: multipart/form-data (old app-store builds) ──
        if (contentType.includes("multipart/form-data")) {
          const contentLength = Number(req.headers.get("content-length") ?? 0)
          if (contentLength > MAX_MULTIPART_BYTES) {
            log.warn("upload.too_large_header", { contentLength, cap: MAX_MULTIPART_BYTES, device: authResult.device.name })
            return new Response("payload too large", { status: 413 })
          }

          let form: Awaited<ReturnType<Request["formData"]>>
          try {
            form = await req.formData()
          } catch (err: any) {
            log.warn("upload.bad_multipart", { err: err?.message ?? String(err), device: authResult.device.name })
            return new Response("bad multipart", { status: 400 })
          }

          const file = form.get("file")
          const session = form.get("session")
          const kindHint = form.get("kind")
          if (!(file instanceof Blob)) {
            log.warn("upload.no_file_field", { device: authResult.device.name })
            return new Response("file field required", { status: 400 })
          }
          if (typeof session !== "string" || session.length === 0) {
            log.warn("upload.no_session_field", { device: authResult.device.name })
            return new Response("session field required", { status: 400 })
          }
          if (file.size > MAX_MULTIPART_BYTES) {
            log.warn("upload.too_large_body", { size: file.size, cap: MAX_MULTIPART_BYTES, device: authResult.device.name })
            return new Response("payload too large", { status: 413 })
          }
          if (file.size === 0) {
            log.warn("upload.empty_file", { device: authResult.device.name })
            return new Response("empty file", { status: 400 })
          }

          const bytes = new Uint8Array(await file.arrayBuffer())
          const mime = file.type || undefined
          const name = (file as any).name as string | undefined
          const kind: AttachmentKind = (typeof kindHint === "string" && VALID_KINDS.includes(kindHint as AttachmentKind))
            ? (kindHint as AttachmentKind)
            : kindFromMime(mime)

          try {
            const { file_id, size } = await this.fileStore.put({
              kind, mime, name, session, origin: "web-upload",
              device: authResult.device.name, bytes,
            })
            log.info("upload.ok", { file_id, kind, mime, size, name, session, device: authResult.device.name, via: "multipart" })
            return this.json({ file_id, size, mime, name })
          } catch (err: any) {
            log.error("upload.store_failed", { err: err?.message ?? String(err), device: authResult.device.name, session, mime, size: bytes.length })
            return new Response("file store error", { status: 500 })
          }
        }

        // ── Streaming path: raw request body (updated clients) ───────────────
        const session = req.headers.get("x-mux-session") ?? ""
        if (session.length === 0) {
          log.warn("upload.no_session_header", { device: authResult.device.name })
          return new Response("session header required", { status: 400 })
        }
        const contentLength = Number(req.headers.get("content-length") ?? 0)
        if (contentLength > MAX_UPLOAD_BYTES) {
          log.warn("upload.too_large_header", { contentLength, cap: MAX_UPLOAD_BYTES, device: authResult.device.name })
          return new Response("payload too large", { status: 413 })
        }
        if (!req.body) {
          log.warn("upload.no_body", { device: authResult.device.name })
          return new Response("empty body", { status: 400 })
        }

        const mime = req.headers.get("x-mux-mime") || undefined
        const filenameHeader = req.headers.get("x-mux-filename")
        let name: string | undefined
        if (filenameHeader) {
          try { name = decodeURIComponent(filenameHeader) } catch { name = filenameHeader }
        }
        const kindHint = req.headers.get("x-mux-kind")
        const kind: AttachmentKind = (kindHint && VALID_KINDS.includes(kindHint as AttachmentKind))
          ? (kindHint as AttachmentKind)
          : kindFromMime(mime)

        try {
          const { file_id, size } = await this.fileStore.putStream(
            { kind, mime, name, session, origin: "web-upload", device: authResult.device.name, maxBytes: MAX_UPLOAD_BYTES },
            req.body,
          )
          log.info("upload.ok", { file_id, kind, mime, size, name, session, device: authResult.device.name, via: "stream" })
          return this.json({ file_id, size, mime, name })
        } catch (err: any) {
          if (err instanceof PayloadTooLargeError) {
            log.warn("upload.too_large_stream", { device: authResult.device.name, cap: MAX_UPLOAD_BYTES })
            return new Response("payload too large", { status: 413 })
          }
          log.error("upload.store_failed", { err: err?.message ?? String(err), device: authResult.device.name, session, mime, via: "stream" })
          return new Response("file store error", { status: 500 })
        }
      }
  ```

- [ ] Run the test and watch it PASS:
  ```
  bun test src/channels/web/upload-routes.test.ts
  ```
  Expected PASS — streaming success returns 200 + `{file_id,size,mime,name}` with `kind==="video"` and a byte-exact `/files/<id>` download; missing session → 400; header-cap and oversized-body → 413 (store empty); no auth → 401; legacy multipart still stores; multipart over its own cap → 413.

- [ ] Verify types:
  ```
  bun run typecheck
  ```
  Expected: no new errors.

- [ ] Commit:
  ```
  git add src/channels/web/index.ts src/channels/web/upload-routes.test.ts
  git commit -m "$(cat <<'EOF'
  feat(web): dual-mode /upload — stream raw body up to 500 MB

  Branch POST /upload on Content-Type: multipart/form-data keeps the buffered
  path (own 25 MB cap via MUX_WEB_UPLOAD_MULTIPART_MAX_MB); any other type streams
  the raw request body through FileStore.putStream with a 500 MB cap
  (MUX_WEB_UPLOAD_MAX_MB, default bumped 25 → 500). Streaming clients pass
  X-Mux-Session (required), X-Mux-Mime, X-Mux-Filename (percent-encoded) and an
  optional X-Mux-Kind; PayloadTooLargeError maps to 413. Add "video" to
  VALID_KINDS.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 4: Telegram inbound — map `m.video` to `"video"`

**Files:**
- Modify `src/channels/telegram/inbound.ts` (`pickRawAttachment`, add branch after line 47)
- Create test `src/channels/telegram/inbound.test.ts`

- [ ] Write the failing test. Create `src/channels/telegram/inbound.test.ts` with the COMPLETE contents:
  ```ts
  // Exercises normalizeTelegramInbound end-to-end with a mocked DownloadableApi
  // (getFile + fetchFile) and a fake FileStore that records the kind passed to
  // put(). This drives the real pickRawAttachment mapping. downloadAttachment
  // stages the bytes into /tmp and normalizeTelegramInbound cleans it up in its
  // finally block.
  import { describe, expect, test } from "bun:test"
  import { normalizeTelegramInbound } from "./inbound"

  function makeApi(bytes: Uint8Array) {
    return {
      getFile: async (_id: string) => ({ file_path: "videos/clip.mp4", file_size: bytes.length }),
      fetchFile: async (_fp: string) => Buffer.from(bytes),
    }
  }

  function fakeStore(onKind?: (k: string) => void) {
    return {
      put: async (input: any) => {
        onKind?.(input.kind)
        return { file_id: "0".repeat(32), size: (input.bytes as Uint8Array).length }
      },
    } as any
  }

  function ctxWith(message: any) {
    return {
      chat: { id: 42 },
      message: { message_id: 7, date: 1_700_000_000, from: { id: 99, username: "ada" }, ...message },
    }
  }

  describe("normalizeTelegramInbound attachment kinds", () => {
    test("m.video → kind 'video'", async () => {
      let kind = ""
      const ctx = ctxWith({ video: { file_id: "TG_VID", mime_type: "video/mp4", file_size: 123, file_name: "clip.mp4" } })
      const msg = await normalizeTelegramInbound({ ctx, api: makeApi(new Uint8Array([1, 2, 3])), fileStore: fakeStore((k) => (kind = k)) })
      expect(kind).toBe("video")
      expect(msg.attachments?.[0]).toMatchObject({ kind: "video", mime: "video/mp4", name: "clip.mp4" })
    })

    test("m.video_note still → kind 'video_note'", async () => {
      let kind = ""
      const ctx = ctxWith({ video_note: { file_id: "TG_VN", mime_type: "video/mp4", file_size: 55 } })
      const msg = await normalizeTelegramInbound({ ctx, api: makeApi(new Uint8Array([4, 5])), fileStore: fakeStore((k) => (kind = k)) })
      expect(kind).toBe("video_note")
      expect(msg.attachments?.[0]).toMatchObject({ kind: "video_note" })
    })
  })
  ```

- [ ] Run the test and watch it FAIL:
  ```
  bun test src/channels/telegram/inbound.test.ts
  ```
  Expected FAIL — the `m.video` case has no branch yet, so `pickRawAttachment` returns `null`, `attachments` stays `undefined`, and `kind` stays `""`: `expect(kind).toBe("video")` → `Received: ""` (and `msg.attachments?.[0]` is `undefined`). The `video_note` case already passes.

- [ ] Implement the branch. In `src/channels/telegram/inbound.ts`, inside `pickRawAttachment`, immediately AFTER the existing `m.video_note` block (line 45-47):
  ```ts
    if (m.video_note) {
      return { kind: "video_note", file_id: m.video_note.file_id, mime: m.video_note.mime_type, size: m.video_note.file_size }
    }
  ```
  insert:
  ```ts
    if (m.video) {
      return { kind: "video", file_id: m.video.file_id, mime: m.video.mime_type, size: m.video.file_size, name: m.video.file_name }
    }
  ```

- [ ] Run the test and watch it PASS:
  ```
  bun test src/channels/telegram/inbound.test.ts
  ```
  Expected PASS — `m.video` yields kind `"video"` carrying mime/name; `m.video_note` still yields `"video_note"`.

- [ ] Commit:
  ```
  git add src/channels/telegram/inbound.ts src/channels/telegram/inbound.test.ts
  git commit -m "$(cat <<'EOF'
  feat(telegram): map inbound m.video to the "video" kind

  pickRawAttachment now handles plain videos (m.video → kind "video", carrying
  mime + file_name), placed alongside the unchanged m.video_note branch so
  Telegram round clips stay "video_note".

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 5: WhatsApp inbound — map `video` to `"video"`

**Files:**
- Modify `src/channels/whatsapp/inbound.ts` (`mediaKind`, lines 24-31)
- Modify `src/channels/whatsapp/inbound.test.ts` (add a video case)

- [ ] Add the failing test. In `src/channels/whatsapp/inbound.test.ts`, add this test inside the `describe("normalizeWhatsAppInbound", ...)` block (e.g. after the audio `.ogg` test):
  ```ts
    test("video bare-string path → 'video' attachment (not document) with video/mp4 mime", async () => {
      let kind = ""
      let mime: string | undefined = "unset"
      const msg = await normalizeWhatsAppInbound(
        { id: "M7", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", video: "statics/media/clip.mp4" },
        deps({ putKindOut: (k) => (kind = k), putMimeOut: (m) => (mime = m) }),
      )
      expect(kind).toBe("video")
      expect(mime).toBe("video/mp4")
      expect(msg.attachments?.[0]).toMatchObject({ kind: "video", mime: "video/mp4" })
    })
  ```

- [ ] Run the test and watch it FAIL:
  ```
  bun test src/channels/whatsapp/inbound.test.ts
  ```
  Expected FAIL — `mediaKind("video", ...)` still hits the `document` fallback: `expect(kind).toBe("video")` → `Received: "document"`.

- [ ] Implement `mediaKind`. In `src/channels/whatsapp/inbound.ts`, replace the comment + function (lines 24-31):
  ```ts
  // WhatsApp lumps voice notes and audio under `audio`; a `.ogg` is a voice note
  // (parity with Telegram's `voice`). image→photo, document→document; video and
  // sticker fall back to document for v1 (tier B is text+image+document+voice).
  function mediaKind(field: MediaField, pathOrUrl: string): AttachmentKind {
    if (field === "image") return "photo"
    if (field === "audio") return pathOrUrl.toLowerCase().endsWith(".ogg") ? "voice" : "audio"
    return "document"
  }
  ```
  with:
  ```ts
  // WhatsApp lumps voice notes and audio under `audio`; a `.ogg` is a voice note
  // (parity with Telegram's `voice`). image→photo, video→video, document→document;
  // sticker falls back to document.
  function mediaKind(field: MediaField, pathOrUrl: string): AttachmentKind {
    if (field === "image") return "photo"
    if (field === "audio") return pathOrUrl.toLowerCase().endsWith(".ogg") ? "voice" : "audio"
    if (field === "video") return "video"
    return "document"
  }
  ```

- [ ] Run the test and watch it PASS:
  ```
  bun test src/channels/whatsapp/inbound.test.ts
  ```
  Expected PASS — WhatsApp `video` yields kind `"video"` with `video/mp4` mime (from the existing `.mp4 → video/mp4` entry in `EXT_MIME`); all prior cases still green.

- [ ] Verify the whole broker/inbound suite is green:
  ```
  bun test src/core/files src/channels
  ```
  Expected PASS — Tasks 1-5 all green.

- [ ] Commit:
  ```
  git add src/channels/whatsapp/inbound.ts src/channels/whatsapp/inbound.test.ts
  git commit -m "$(cat <<'EOF'
  feat(whatsapp): map inbound video to the "video" kind

  mediaKind now returns "video" for the video field (previously downgraded to
  "document"); the .mp4 → video/mp4 EXT_MIME entry supplies the mime. Comment
  updated to drop the stale "fall back to document" note.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Done criteria

- [ ] `bun test src/core/files src/channels` is fully green.
- [ ] `bun run typecheck` reports no new errors.
- [ ] `POST /upload` with `Content-Type: application/octet-stream` streams to disk (500 MB cap) and returns `{file_id,size,mime,name}`; `multipart/form-data` still works (25 MB cap); both 413 routes, the 400 missing-session route, and 401 auth are exercised.
- [ ] `kindFromMime("video/mp4") === "video"`; `extFromMime` resolves mov/mkv/m4v; `FileStore.putStream` cap-aborts cleanly with `PayloadTooLargeError`.
- [ ] Telegram `m.video` and WhatsApp `video` both normalize to the `"video"` kind; Telegram `video_note` is unchanged.
