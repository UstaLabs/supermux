# Resumable / chunked uploads + real progress across all clients

**Date:** 2026-07-05
**Status:** Approved (design) — awaiting spec review before planning
**Scope:** broker (TS), shared KMP (`BrokerApi`), web PWA, iOS, Android

## Summary

Today attachment uploads are a single HTTP `POST /upload` per file. Consequences,
confirmed by audit on `mux/supermux-13` (post-v0.7.0):

- **No real progress bar anywhere.** Web/Android show an indeterminate spinner;
  iOS shows nothing during upload.
- **Not resumable.** A dropped connection at 99 % restarts from zero.
- **Client RAM is the real ceiling, not the 500 MB server cap.** The shared
  `BrokerApi.upload(bytes: ByteArray)` buffers the whole file. iOS is worst:
  `Data` → base64 `String` across the SKIE bridge → Kotlin decode ≈ 2.3× the
  file size transiently. Android reads the whole file with `readBytes()`.
- **Silent failure on native.** iOS `try?` → nil → the message sends *without*
  the attachment, no error. Android `runCatching{}.getOrNull()` → the chip just
  disappears. Only web surfaces failures.

This design adds a chunked, resumable upload path that fixes all four in one
stroke: chunking bounds client RAM to one chunk, yields a genuine determinate
progress bar, and makes a dropped connection resume from the server's offset.

### Goals

1. Determinate upload progress on **all** clients (web, iOS, Android).
2. Bounded client memory — never hold more than one chunk regardless of file size.
3. Resume an interrupted upload **within the app session** (network blip / failed
   chunk) without restarting from zero.
4. Surface upload failures on native (progress + "Failed — tap to retry"); never
   silently send a message minus its attachment.

### Non-goals (explicit YAGNI)

- **Resume across app restart / kill.** The wire protocol supports it (via `HEAD`),
  but the client work — persisting the picked file's bytes to app storage because
  a picked URI is usually not re-readable after restart, plus persisting
  `{upload_id, offset}` — is disproportionate for a marginal case. Deferred; the
  protocol leaves the door open.
- **Parallel chunk uploads.** Chunks go sequentially (in order). Keeps offset
  validation trivial and RAM bounded. The bottleneck is the phone's uplink, not
  request concurrency.
- **Multiple attachments reaching the agent.** Still only the first attachment is
  seen by the model — unchanged, separate issue.

## Current state (reference)

- `POST /upload` — two modes: legacy `multipart/form-data` (≤ 25 MB, buffered) for
  old app-store builds, and raw `application/octet-stream` + `X-Mux-*` headers
  (≤ 500 MB, streamed to `<file_id>.part` → fsync → rename) for updated clients.
- `FileStore.putStream()` streams the body to `.part`, enforces the cap mid-stream,
  fsyncs, renames to final, inserts the `attachments` row.
- `attachments` table: `file_id, kind, mime, size, name, path, origin, session,
  device, created_at, ref_count`. GC = `gcOnce({graceHours})` deletes rows with
  `ref_count = 0` older than the grace window; scheduled from `main.ts` at 24 h.
- Clients all call shared `BrokerApi.upload(session, bytes, filename, mime, kind)`
  (iOS via `uploadBase64`). Web calls its own `useUploader` (`fetch`).

## Design overview

Chunked upload is **purely additive** — three new endpoints alongside the
untouched `POST /upload`. Small files keep the fast single-POST path; only files
larger than the chunk size use the resumable protocol. Old clients are unaffected.

```
small file (≤ chunk):   POST /upload            (existing raw-stream path, +progress)
large file (> chunk):    POST /upload/init       → { upload_id, offset:0, chunk_size }
                         PATCH /upload/<id>  ×N  (Upload-Offset header + raw chunk body)
                         HEAD  /upload/<id>       (resume probe → server offset)
                         (last PATCH finalizes → { file_id, size, mime, name })
```

## Server design

### Protocol endpoints

All three require the same auth as `POST /upload` (`requireAuth`) and record the
authenticated `device` so the existing `resolveOwnedWebUpload` device↔file_id
binding still holds after finalize.

**`POST /upload/init`** — JSON body `{ session, mime, name, kind?, total_size }`.
- Reject `total_size > MAX_UPLOAD_BYTES` → `413`.
- Reject missing/empty `session` → `400`.
- Create an empty `<upload_id>.part` and a `pending_uploads` row (see schema).
- Respond `200 { upload_id, offset: 0, chunk_size }`. `chunk_size` is
  server-dictated (`MUX_UPLOAD_CHUNK_MB`, default 5) so the server owns the knob.

**`PATCH /upload/<upload_id>`** — header `Upload-Offset: N`, body = raw next chunk.
- `404` if `upload_id` unknown (GC'd or never existed).
- If `N !== received` (current `.part` size) → `409` with header
  `Upload-Offset: <received>` so the client re-syncs and resumes. (tus semantics —
  handles a lost ack where the client thinks it's behind.)
- If `received + chunkLen > total_size` → `400` (overflow) and abort.
- Append the chunk, update `received`.
- If `received < total_size` → `200 { offset: received }`.
- If `received === total_size` → **finalize inline**: fsync → rename `.part` to
  `<file_id>.<ext>` → insert the `attachments` row (`origin:"web-upload"`, the
  recorded `device`, `ref_count:0`) → delete the `pending_uploads` row → respond
  `200 { file_id, size, mime, name }`. The client detects finalize by `file_id`
  being present.

**`HEAD /upload/<upload_id>`** — the resume probe.
- `404` if unknown; else `200` with header `Upload-Offset: <received>`.

Sequential, single-writer per `upload_id`: chunk `k+1` is sent only after `k`'s
ack. No locking needed beyond the offset check.

### FileStore additions

New methods, mirroring the existing `.part` durability discipline (fsync before
rename, best-effort unlink on failure):

- `createPending({ session, mime, name, kind, total_size, device, origin }) →
  { upload_id, chunk_size }` — mkdir shard, `openSync(<upload_id>.part, "w")`,
  insert `pending_uploads` row.
- `appendChunk(upload_id, offset, chunk) → { received, done }` — validate
  `offset === received` (else throw `OffsetConflictError` carrying `received`),
  enforce running total ≤ `total_size` and ≤ cap, `writeSync` append, update row.
- `finalizePending(upload_id) → { file_id, size, mime, name }` — fsync, rename to
  final, insert `attachments`, delete pending row. (Reuses the same INSERT as
  `put`/`putStream`.)
- `pendingOffset(upload_id) → number | null` — for `HEAD`.
- `gcPendingOnce({ ttlHours }) → number` — delete `.part` + row where
  `created_at < now - ttl`. Scheduled next to the existing `gcOnce` in `main.ts`.

Typed errors (`OffsetConflictError`, `UploadNotFoundError`, plus the existing
`PayloadTooLargeError`/`EmptyUploadError`) so the web channel maps them to
409/404/413/400 rather than a generic 500 — same pattern as today.

### `pending_uploads` table (migration `023_pending_uploads.sql`)

```sql
CREATE TABLE pending_uploads (
  upload_id  TEXT PRIMARY KEY,
  session    TEXT NOT NULL,
  kind       TEXT NOT NULL,
  mime       TEXT,
  name       TEXT,
  total_size INTEGER NOT NULL,
  received   INTEGER NOT NULL DEFAULT 0,
  path       TEXT NOT NULL,          -- the <upload_id>.part path
  origin     TEXT NOT NULL,
  device     TEXT,
  created_at TEXT NOT NULL
);
```

Separate from `attachments` so an abandoned partial never masquerades as a real
attachment and gets its own TTL sweep. On finalize the row moves into
`attachments`.

### Config

- `MUX_UPLOAD_CHUNK_MB` (default **5**) — chunk size, echoed to clients in `init`.
- `MUX_UPLOAD_PENDING_TTL_HOURS` (default **24**) — abandoned-pending GC window.
- `MUX_WEB_UPLOAD_MAX_MB` (default 500) — unchanged hard cap, now enforced at
  `init` and per `PATCH`.

## Client design

### Shared KMP (`BrokerApi`) — the one upload brain

Add `uploadResumable(session, source: ChunkSource, filename, mime, kind?,
onProgress: (sent: Long, total: Long) -> Unit): UploadResponse`.

- `ChunkSource` is an `expect class` with a **synchronous** `read(offset, len):
  ByteArray` and a `size: Long`. Synchronous on purpose — it avoids capturing a
  Swift closure across a SKIE suspension point (the K/N GC-pinning trap the
  terminal predictive-echo work hit). The read runs on `Dispatchers.IO` inside the
  suspend upload; it's a plain function call, not a suspending callback.
  - iOS `actual`: wraps `NSFileHandle` (seek + read) over the picked file's URL.
  - Android `actual`: re-openable `ContentResolver` stream (skip to offset, read).
- Logic: if `source.size ≤ chunk_size` → existing single `POST /upload` but with
  Ktor `onUpload { sent, total }` wired to `onProgress`. Else: `init` → loop
  `read(offset, chunk_size)` → `PATCH` → advance offset, emit progress → until a
  response carries `file_id`. On a per-chunk `IOException`: `HEAD` to re-sync the
  server offset, then retry with capped exponential backoff (e.g. 4 tries); after
  exhaustion, throw so the caller marks the attachment failed.

Web is TypeScript and cannot share this Kotlin. It reimplements the same small
loop; a **parity test suite** locks the web ↔ Kotlin chunk-splitting and progress
semantics byte-for-byte (mirroring the 23 `PredictiveEcho` parity tests).

### Web (`useUploader` / `uploads` store / `PromptInputAttachments.vue`)

- Single-POST path: swap `fetch` → **`XMLHttpRequest`** for `upload.onprogress`
  (fetch cannot report upload progress). Determinate bar for small files.
- Chunked path: `File.slice(offset, offset+chunk)` → `Blob` → XHR `PATCH`. The
  browser streams the Blob from disk, so RAM stays bounded; per-chunk
  `upload.onprogress` feeds overall progress `(offset + chunkSent)/total`.
- `uploads` store: extend the `uploading` state with `progress: number` (0..1).
  Add a `retry(id)` that resumes via `HEAD`.
- UI: replace the indeterminate `Loader2` overlay with a determinate ring/bar;
  keep the existing failed chip + toast + retry (already good on web).

### iOS (`ComposerModel` / `ChatPane` / composer chip)

- **Source, not eager Data:** `PendingAttachment` carries `.data(Data)` for small
  items (images stay JPEG `Data`) or `.fileURL(URL)` for videos/large files. The
  Photos picker loads a **video** via `loadFileRepresentation`/`Movie` transferable
  to a temp URL instead of `loadTransferable(type: Data.self)` — the current path
  that pulls the whole video into `Data`. Camera video and the Files picker already
  hand back URLs.
- **Upload UI (new):** the composer chip gains a determinate progress ring and a
  failed state. `ComposerModel` tracks per-attachment `{progress, failed}`.
- **No silent drop:** `sendMessage` awaits all uploads; on any failure it does
  **not** send — it keeps the draft + a "Failed — tap to retry" chip (matches web).
- Bridges to `uploadResumable` via a `ChunkSource`: `.fileURL` items build the
  `NSFileHandle`-backed source (large videos never enter RAM whole); `.data` items
  build an in-memory source over the `Data` (images are small — ≤ chunk — so they
  take the single-POST branch regardless).

### Android (`ChatPanel` / `AppViewModel`)

- **Uri, not eager `readBytes()`:** keep the picked `Uri` in `PendingAttachment`
  and slice it via a re-openable `ContentResolver` stream, so a large video never
  loads whole into the heap.
- Extend `PendingAttachment(fileId, name, uploading)` with `progress: Float` and
  `failed: Boolean`; the chip already has a `CircularProgressIndicator` — make it
  determinate and add the failed/retry affordance.
- `AppViewModel.upload` currently swallows errors (`runCatching{}.getOrNull()`).
  Change it to report progress + surface failure to the chip; the send stays
  blocked (Send already gates on `!uploading`) and a failed attachment shows an
  error instead of vanishing.
- Preserve Android's existing **eager-at-pick** upload timing; only the transport
  (chunked), progress, and error handling change.

## Unified error model

On any attachment upload failure, on every client: **do not send the message.**
Keep the draft and the attachment, show a per-attachment failed state with retry.
Retry resumes from the server offset (`HEAD`) — it does not restart. This matches
web's existing `submit()`-throws behavior and replaces the native silent-drop.

## Backward compatibility

Old app-store builds keep using `POST /upload` (raw-stream or multipart),
untouched. New clients use chunked **only** for files larger than `chunk_size`;
smaller files still single-POST. The chunked endpoints are additive — nothing that
exists today changes behavior.

## Testing strategy

- **Broker:** `init`/`PATCH`/`HEAD`/finalize happy path; offset-mismatch → 409 +
  correct `Upload-Offset`; overflow → 400; over-cap at init → 413; unknown id →
  404; resume (patch → simulated drop → HEAD → continue → finalize); finalized
  file passes `resolveOwnedWebUpload` for its device; `gcPendingOnce` reaps an
  aged partial. Exercised through the web-channel handler, not just FileStore.
- **FileStore:** `createPending`/`appendChunk`/`finalizePending`/`pendingOffset`/
  `gcPendingOnce` units, including the durability/cleanup branches.
- **Shared KMP:** `uploadResumable` with a mock HTTP engine — chunk math, progress
  callbacks, small-file single-POST branch, resume-on-IOException via `HEAD`,
  finalize detection. Web↔Kotlin parity suite.
- **Web:** `useUploader` chunked + single-POST progress (mock XHR), store progress,
  retry.
- **iOS/Android:** unit-test the source-slicing + "send blocks on failure" logic
  where feasible; **feel-test** on sim/emulator including a simulated mid-upload
  network drop → auto-resume, since static review has historically missed device-only
  SwiftUI/Compose behavior here.

## Phased build order (each phase green before the next)

1. **Broker + FileStore** — table, endpoints, GC, typed errors, tests. Verifiable
   standalone via `bun test` / curl; no client change.
2. **Shared KMP** — `uploadResumable` + `ChunkSource` expect/actual + progress +
   resume + parity tests.
3. **Web** — XHR progress (single-POST) + chunked client + determinate UI + retry.
   Deployable restart-free (web-app-only rebuild).
4. **iOS** — source/URL sourcing + progress/failed UI + chunked via KMP + error
   surfacing.
5. **Android** — Uri slicing + progress/failed UI + chunked via KMP + error
   surfacing.

## Future extensions (not in this spec)

- Resume across app restart (persist bytes + `{upload_id, offset}`) — protocol is
  already `HEAD`-ready.
- Parallel chunks, if uplink ever stops being the bottleneck (it won't on a phone).
