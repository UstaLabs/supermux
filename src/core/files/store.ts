// src/core/files/store.ts
import { randomBytes } from "crypto"
import { closeSync, fsyncSync, mkdirSync, openSync, renameSync, statSync, unlinkSync, writeSync } from "fs"
import { join } from "path"
import type { Db } from "../storage/db"
import { makeLogger } from "../../shared/log"
import { extFromMime } from "./mime"
import type { AttachmentKind } from "./kinds"

const log = makeLogger("core/files/store")

/** Server-dictated chunk size for resumable uploads (echoed to clients in the
 *  init response so the server owns the knob). Default 5 MB. */
export function chunkBytes(): number {
  return Number(process.env.MUX_UPLOAD_CHUNK_MB ?? 5) * 1024 * 1024
}

// Re-export so existing `import { AttachmentKind } from "core/files/store"`
// call sites (e.g. channels/telegram/inbound.ts) keep working. New code
// should import from "./kinds" directly.
export type { AttachmentKind } from "./kinds"
export type AttachmentOrigin = "web-upload" | "telegram-dl" | "whatsapp-dl" | "session-outbound"

export interface FileStorePutInput {
  kind: AttachmentKind
  mime?: string
  name?: string
  session?: string
  device?: string
  origin: AttachmentOrigin
  bytes: Uint8Array | Buffer
}

export interface FileMeta {
  file_id: string
  kind: AttachmentKind
  path: string
  mime?: string
  name?: string
  size: number
}

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

/**
 * Thrown by putStream when the stream yields zero bytes. Distinguishable
 * (instanceof / .code) so the web channel can map it to HTTP 400 — parity with
 * the multipart path's `file.size === 0` guard — rather than storing an empty
 * file.
 */
export class EmptyUploadError extends Error {
  readonly code = "EMPTY_UPLOAD" as const
  constructor(message = "empty upload") {
    super(message)
    this.name = "EmptyUploadError"
  }
}

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

export class FileStore {
  constructor(private readonly db: Db, private readonly rootDir: string) {
    mkdirSync(rootDir, { recursive: true, mode: 0o700 })
  }

  async put(input: FileStorePutInput): Promise<{ file_id: string; size: number }> {
    const file_id = randomBytes(16).toString("hex")
    const ext = extFromMime(input.mime)
    const shard = file_id.slice(0, 2)
    const shardDir = join(this.rootDir, shard)
    mkdirSync(shardDir, { recursive: true, mode: 0o700 })

    const finalPath = join(shardDir, `${file_id}.${ext}`)
    const partPath = `${finalPath}.part`
    const bytes = input.bytes instanceof Buffer ? input.bytes : Buffer.from(input.bytes)

    // Stream to <file_id>.part, fsync, then rename — per spec §155. The fsync
    // ensures the data blocks are durable before the directory entry rename
    // exposes the final path. On any write failure, clean up the partial file.
    try {
      const fd = openSync(partPath, "w", 0o600)
      try {
        writeSync(fd, bytes, 0, bytes.length)
        fsyncSync(fd)
      } finally {
        closeSync(fd)
      }
      renameSync(partPath, finalPath)
    } catch (err) {
      try { unlinkSync(partPath) } catch { /* ignore — best-effort cleanup */ }
      throw err
    }

    // If INSERT fails after rename, the file on disk is orphaned (no row to
    // reference it, so gcOnce won't find it). Unlink the final file on insert
    // failure to avoid leaking.
    try {
      this.db.prepare(`
        INSERT INTO attachments (file_id, kind, mime, size, name, path, origin, session, device, created_at, ref_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), 0)
      `).run(
        file_id,
        input.kind,
        input.mime ?? null,
        bytes.length,
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

    return { file_id, size: bytes.length }
  }

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
            // Parity with the multipart path: never store a zero-byte upload.
            if (total === 0) throw new EmptyUploadError()
            fsyncSync(fd)
          } catch (err) {
            // On a cap/write/empty abort, explicitly cancel the inbound stream so
            // the client-side body is torn down rather than left dangling.
            try { await reader.cancel() } catch { /* ignore — best-effort */ }
            throw err
          }
        } finally {
          try { reader.releaseLock() } catch { /* ignore — best-effort (cancel may already have released) */ }
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

  // ── Resumable / chunked uploads ────────────────────────────────────────────
  // A large upload is init'd (createPending → empty <upload_id>.part + row),
  // filled chunk-by-chunk (appendChunk, offset-validated against the on-disk
  // size so it survives a broker restart), then finalized (finalizePending →
  // fsync + rename to <upload_id>.<ext> + attachments row). The upload_id IS the
  // eventual file_id. gcPendingOnce reaps abandoned partials past their TTL.

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

  /** Current stored byte count for an in-flight upload (the resume offset), or
   *  null if the upload_id is unknown. Reads the filesystem size as the truth. */
  async pendingOffset(upload_id: string): Promise<number | null> {
    const row = this.db.prepare(
      "SELECT path FROM pending_uploads WHERE upload_id = ?",
    ).get(upload_id) as { path: string } | undefined
    if (!row) return null
    try { return statSync(row.path).size } catch { return null }
  }

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

  async get(file_id: string): Promise<FileMeta | null> {
    const row = this.db.prepare("SELECT file_id, kind, mime, name, path, size FROM attachments WHERE file_id = ?").get(file_id) as any
    if (!row) return null
    return {
      file_id: row.file_id,
      kind: row.kind,
      path: row.path,
      mime: row.mime ?? undefined,
      name: row.name ?? undefined,
      size: row.size,
    }
  }

  /**
   * Look up a web-upload's metadata, but only if the row was originally
   * uploaded by the given device. Returns null in three cases:
   *   1. unknown file_id
   *   2. row exists but its origin is not 'web-upload' (e.g. telegram-dl,
   *      session-outbound — devices must not be able to attach those)
   *   3. row exists but its device column doesn't match
   *
   * Used by the web channel to validate `args.attachments[]` on inbound WS
   * `send` frames — keeps the device → file_id binding enforced at the
   * channel boundary instead of leaking sqlite into channel code.
   */
  async resolveOwnedWebUpload(file_id: string, device: string): Promise<FileMeta | null> {
    const row = this.db.prepare(
      "SELECT file_id, kind, mime, name, path, size, origin, device FROM attachments WHERE file_id = ?",
    ).get(file_id) as any
    if (!row) return null
    if (row.origin !== "web-upload") return null
    if (row.device !== device) return null
    return {
      file_id: row.file_id,
      kind: row.kind,
      path: row.path,
      mime: row.mime ?? undefined,
      name: row.name ?? undefined,
      size: row.size,
    }
  }

  async release(file_id: string): Promise<boolean> {
    const info = this.db.prepare("UPDATE attachments SET ref_count = MAX(ref_count - 1, 0) WHERE file_id = ?").run(file_id)
    return info.changes > 0
  }

  async bumpRef(file_id: string): Promise<boolean> {
    const info = this.db.prepare("UPDATE attachments SET ref_count = ref_count + 1 WHERE file_id = ?").run(file_id)
    return info.changes > 0
  }

  async gcOnce(opts: { graceHours: number }): Promise<number> {
    const rows = this.db.prepare(`
      SELECT file_id, path FROM attachments
      WHERE ref_count = 0
        AND created_at < datetime('now', ?)
    `).all(`-${opts.graceHours} hours`) as Array<{ file_id: string; path: string }>

    for (const r of rows) {
      let unlinkOk = true
      try {
        unlinkSync(r.path)
      } catch (e: any) {
        if (e?.code === "ENOENT") {
          // File already gone — safe to drop the row.
          log.warn("filestore_gc_unlink_enoent", { file_id: r.file_id, path: r.path })
        } else {
          // EBUSY/EACCES/EIO — leave the row in place so we'll retry next sweep.
          log.warn("filestore_gc_unlink_failed", { file_id: r.file_id, path: r.path, err: e?.message ?? String(e) })
          unlinkOk = false
        }
      }
      if (unlinkOk) {
        this.db.prepare("DELETE FROM attachments WHERE file_id = ?").run(r.file_id)
      }
    }
    return rows.length
  }
}
