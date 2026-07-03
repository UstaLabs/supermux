# Allow video uploads across all clients — Phase 1 (single-POST streaming) — Design (2026-07-02)

- **Date:** 2026-07-02
- **Status:** Approved (confirmed with user: full scope incl. camera recording + inline players; 500 MB cap; Approach 1 raw-body streaming; two-phase split with resumable/chunked deferred to Phase 2)
- **Area:** broker (`src/core/files`, `src/channels/web`, `src/channels/telegram`, `src/channels/whatsapp`), `src/web-app` (Vue), `apps/iosApp` (SwiftUI), `apps/android` (Compose), `apps/shared` (KMP)
- **Goal:** Every client should let the user **pick or record a video** (not just photos), upload it up to **500 MB**, and have it play back. Inbound video from Telegram/WhatsApp should reach the agent too. The broker should **stream uploads to disk** instead of buffering the whole file in RAM.

## Context

The transport/storage layer is already essentially media-agnostic — the real restrictions live in the client pickers, the size cap, and a couple of inbound gaps.

**Already works, do not rebuild:**
- `FileStore` stores arbitrary MIME with random (not content-hashed) `file_id`s, via a `<file_id>.part` → `fsync` → `rename` durability dance (`src/core/files/store.ts:42-94`).
- The web channel serves `/files/<id>` with HTTP `Range`/206 support, so HTML5 `<video>` streaming already works (per the exploration of `src/channels/web/index.ts:1215-1235` + `serveFile`).
- `download_attachment` is kind-agnostic (`src/core/session-manager/download.ts:21-36`).
- The web app already ships a `<video controls playsinline>` renderer: `src/web-app/src/components/attachments/AttachmentVideo.vue`, wired for `kind==="video_note"` in `AttachmentList.vue:10-15`.
- Telegram **outbound** already sends videos via `sendVideo` (extension-based, `src/channels/telegram/bot-api.ts:25,57-58`).
- Shared KMP `Attachment.kind` is a free-form `String?` (`apps/shared/.../proto/Frames.kt:70-76`) — no schema change needed to carry a new kind over the wire.

**The actual gates (what this spec changes):**
1. `kindFromMime` maps `video/* → "video_note"` (`src/core/files/kinds.ts:8`), conflating generic video with Telegram's round-selfie-clip type.
2. Upload path buffers the whole file in RAM: `await req.formData()` then `new Uint8Array(await file.arrayBuffer())` (`src/channels/web/index.ts:1117,1143`), and the cap defaults to 25 MB (`:1108`).
3. Client pickers hard-filter to images: web camera `accept="image/*"` (`PromptInputActionAddCamera.vue:29-30`), iOS `matching: .images` (`ChatPane.swift:214`, `NewSessionView.swift:181`), Android `PickVisualMedia.ImageOnly` (`ChatScreen.kt:1122-1124`).
4. iOS mislabels **every** picked item as `image/jpeg` / `image-N.jpg` (`apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift:90-98`) — a picked video would upload with the wrong MIME/extension.
5. Telegram inbound ignores `m.video` (`src/channels/telegram/inbound.ts:34-56` handles voice/audio/video_note/document/photo, not plain video).
6. WhatsApp downgrades `video → "document"` (`src/channels/whatsapp/inbound.ts:27-31`).
7. Camera capture is photo-only on all three platforms (web `capture` with `image/*`; iOS `UIImagePickerController` no `mediaTypes`, `ChatMessages.swift:163-165`; Android `TakePicture()`, `ChatScreen.kt:283`).
8. Native inline playback is absent — video falls to a downloadable file row (iOS QuickLook, `ChatMessages.swift:142`) or a system-viewer chip (Android, `Timeline.kt:687-738`). Android's timeline also has a latent kind mismatch: it checks `att.kind == "video"` (`Timeline.kt:690-691`) while the broker emits `video_note` today — works only via the `mime.startsWith("video/")` fallback.

## Decisions

1. **Introduce a distinct `"video"` attachment kind.** Reserve `"video_note"` for Telegram round-selfie-clips only. Generic picked/recorded/film video (and Telegram `m.video`, WhatsApp `video`) becomes `"video"`. Renderers accept **both** `video` and `video_note` so nothing regresses.
2. **Size cap: 500 MB default** via `MUX_WEB_UPLOAD_MAX_MB` (env-overridable). Client-side `max-file-size` bumped to match, with a user-visible "too big" message.
3. **Approach 1 — raw-body streaming upload, dual-mode `/upload`.** Streaming clients send the raw file bytes as the request body with metadata in headers; the server pipes `req.body` into a new `FileStore.putStream`. The existing `multipart/form-data` path stays so **not-yet-updated app-store builds keep working**.
4. **Legacy multipart path keeps a conservative in-RAM cap** (its own smaller limit), independent of the 500 MB streaming cap — so an old or hostile client can't OOM the broker with a huge buffered multipart body. Only the streaming path (which never buffers) gets the full 500 MB.
5. **Camera gains a distinct "Record video" action** on each platform rather than overloading the existing photo-capture control.
6. **Inline native video playback is in scope** (iOS `VideoPlayer`, Android `ExoPlayer`/media3) — the user chose the full-polish option. Web already has `<video>`.
7. **The model does not "see" video frames** — video reaches agents as a file-path reference, which is the existing behavior for non-images (`src/core/agents/codex/adapter.ts:161-162`; cursor/opencode already path-only). No adapter change; documented as a known limitation.
8. **Phase 2 (resumable/chunked upload) is out of scope here** and gets its own spec. Approach 1's raw-body request is deliberately the same shape a Phase-2 chunk will take.

## Shape

```
// src/core/files/kinds.ts
type AttachmentKind = "photo" | "document" | "voice" | "audio" | "video" | "video_note"   // + "video"
kindFromMime: video/* → "video"                                                            // was "video_note"

// src/channels/web/index.ts
VALID_KINDS = [..., "video"]                                                               // + "video"

// src/channels/channel.ts — both unions gain "video"
OutboundAttachmentRef.kind: "... | video | video_note"
InboundAttachment.kind:     "... | video | video_note"

// src/core/files/store.ts — new streaming ingest, mirrors put()
FileStore.putStream(
  input: Omit<FileStorePutInput, "bytes"> & { maxBytes: number },
  source: ReadableStream<Uint8Array>,
): Promise<{ file_id: string; size: number }>
// writes chunks to <file_id>.part, aborts+unlinks if running total > maxBytes (→ typed
// PayloadTooLarge error), fsync, rename, INSERT row with the OBSERVED byte total.

// Streaming upload request contract (updated clients):
POST /upload
  Content-Type: application/octet-stream        // ← selects the streaming path
  Content-Length: <bytes>                        // checked up front for a fast 413
  X-Mux-Session:  <session id>                   // required
  X-Mux-Mime:     <real mime, e.g. video/mp4>    // real type (octet-stream body hides it)
  X-Mux-Filename: <RFC3986 percent-encoded name> // optional, header-safe
  X-Mux-Kind:     <kind>                          // optional; validated vs VALID_KINDS else inferred from X-Mux-Mime
  <body> = raw file bytes
// multipart/form-data on the same URL → unchanged legacy buffered path.
```

## Broker — attachment kind (`src/core/files`)

- `kinds.ts:2` — add `"video"` to the `AttachmentKind` union.
- `kinds.ts:8` — change `video/* → "video"` (was `"video_note"`).
- `mime.ts` — add video extensions so files land with sane names: `"video/quicktime": "mov"`, `"video/x-matroska": "mkv"`, `"video/x-m4v": "m4v"` (keep existing `video/mp4→mp4`, `video/webm→webm`).
- `src/channels/channel.ts:10,27` — add `"video"` to `OutboundAttachmentRef.kind` and `InboundAttachment.kind`.
- Any other explicit kind unions surfaced by a repo-wide grep for `"video_note"` / `video_note` at implementation time (e.g. `src/core/session-manager/messages.ts`, web `stores/messages.ts:6` if it hard-codes the union — several are already free-form `string`).

## Broker — streaming upload (`src/core/files/store.ts`, `src/channels/web/index.ts`)

- **`FileStore.putStream(input, source)`** — new method mirroring `put()` (`store.ts:42-94`): same shard dir + `<file_id>.part` open, but consume `source` chunk-by-chunk (write each chunk, keep a running byte total). If total exceeds `input.maxBytes`, stop, `unlinkSync` the part file, and throw a distinguishable `PayloadTooLarge` error. On success: `fsync`, `rename` to final, then the same `INSERT` as `put()` but with the observed total as `size`. Reuse `put()`'s two `try/catch` cleanup blocks (unlink part on write failure, unlink final on INSERT failure). `input.mime`/`name`/`kind`/`origin`/`session`/`device` carry through unchanged.
- **`/upload` handler (`index.ts:1096`)** — branch at the top on `Content-Type`:
  - `multipart/form-data` → the existing buffered path, unchanged, but capped by `MAX_MULTIPART_BYTES` (Decision 4).
  - otherwise → **streaming path**: `requireAuth` (unchanged); require `X-Mux-Session`; up-front `Content-Length > MAX_UPLOAD_BYTES → 413`; resolve `mime = X-Mux-Mime`, `name = decodeURIComponent(X-Mux-Filename)`, `kind = X-Mux-Kind if in VALID_KINDS else kindFromMime(mime)`; call `fileStore.putStream({ kind, mime, name, session, origin:"web-upload", device, maxBytes: MAX_UPLOAD_BYTES }, req.body)`; on `PayloadTooLarge` → 413; on success return the same `{ file_id, size, mime, name }` JSON as today.
- **Caps** (`index.ts:1108`): `MAX_UPLOAD_BYTES = (MUX_WEB_UPLOAD_MAX_MB ?? 500) * 1MB` (default 25 → 500). Add `MAX_MULTIPART_BYTES = (MUX_WEB_UPLOAD_MULTIPART_MAX_MB ?? 25) * 1MB` for the legacy buffered path.
- Auth, session/device binding, `origin:"web-upload"`, and `resolveOwnedWebUpload` (`store.ts:121`) validation on the inbound WS `send` frame are all unchanged.

## Inbound — Telegram (`src/channels/telegram/inbound.ts`)

- Add an `m.video` branch to `pickRawAttachment` (`:34-56`), placed alongside `m.video_note`:
  ```
  if (m.video) return { kind: "video", file_id: m.video.file_id, mime: m.video.mime_type, size: m.video.file_size, name: m.video.file_name }
  ```
  Keep the existing `m.video_note` branch as-is (round clips stay `video_note`). Eager-download + `FileStore.put` flow below is unchanged.

## Inbound — WhatsApp (`src/channels/whatsapp/inbound.ts`)

- `mediaKind` (`:27-31`) — add `if (field === "video") return "video"` before the `document` fallback; update the stale comment ("video … fall back to document for v1"). `MEDIA_FIELDS` already lists `video` (`:16`); `EXT_MIME` already has `.mp4 → video/mp4` (`:46`) — optionally add `.mov`/`.webm` for parity.

## Web (`src/web-app`)

- **Record-video action:** new `PromptInputActionAddRecordVideo.vue` mirroring `PromptInputActionAddCamera.vue` but `accept="video/*"` + `capture="environment"`; wire it into the attach menus (`ChatView.vue:660-668`, `SessionLauncherView.vue:361-367`). Library "Files"/drag-drop already accept video (no `accept` filter → `matchesAccept` returns true, `context.ts:41-66`) — verify, no change expected.
- **Size:** bump `:max-file-size` 25 MB → 500 MB (`ChatView.vue:637`, `SessionLauncherView.vue:335`) and surface the existing over-limit rejection as a visible message.
- **Uploader → streaming:** change `useUploader.ts` to POST the raw file body with `Content-Type: application/octet-stream` + `X-Mux-*` headers (instead of `FormData`); percent-encode the filename. `useComposerSubmit.ts:57-110` continues to send the WS `send` frame with the returned `file_id`(s); kind still inferred server-side.
- **Composer chip:** add a video branch to `PromptInputAttachments.vue:11-13` (currently only `isImage`/`isAudio`) — a thumbnail (`<video>` first-frame or an icon) so a staged video isn't a generic file icon.
- **Playback:** already handled — extend `AttachmentList.vue:13` to map **both** `video` and `video_note` → `AttachmentVideo.vue`.

## iOS (`apps/iosApp`)

- **Picker:** `matching: .images` → `matching: .any(of: [.images, .videos])` at `ChatPane.swift:214` and `NewSessionView.swift:181`. `.fileImporter([.item])` already accepts video.
- **Fix the mislabel bug:** `ComposerModel.loadPhotos` (`ComposerModel.swift:90-98`) must stop hardcoding `image/jpeg`/`image-N.jpg`. Detect each `PhotosPickerItem`'s real type via its `supportedContentTypes` / `UTType` (image vs movie) and derive MIME + extension accordingly (`image/jpeg`+`.jpg` for images; `video/quicktime`+`.mov` or the item's actual type for videos). Upload call sites (`ChatPane.swift:339-340`, `NewSessionView.swift:362-363`) set `kind = mime.hasPrefix("audio") ? "voice" : nil`; leave `nil` for video so the server infers `"video"`.
- **Camera video:** add movie capture — a distinct "Record video" entry in `AttachMenu.swift:22-24` driving `UIImagePickerController` with `mediaTypes = [UTType.movie.identifier]` (extend `CameraPicker`, `ChatMessages.swift:163-165`), or the SwiftUI equivalent.
- **Inline playback:** render `video`/`video_note` (or `mime` `video/*`) with an inline `AVKit.VideoPlayer` in the message list instead of only the QuickLook file row (`ChatMessages.swift:47,142`). QuickLook remains the fallback.
- Upload transport is the shared KMP `BrokerApi` (below).

## Android (`apps/android`)

- **Picker:** `PickVisualMedia.ImageOnly` → `PickVisualMedia.ImageAndVideo` (`ChatScreen.kt:1122-1124`). `filePickerLauncher.launch("*/*")` already accepts video.
- **Camera video:** add an `ActivityResultContracts.CaptureVideo()` launcher beside `TakePicture()` (`:283`) and a distinct "Record video" menu item in the attach dropdown (`:1114-1150`).
- **Clipboard/drag:** widen the image-only filters (`:255`, `:263`, `:1029` `getType(uri)?.startsWith("image/")`) to also accept `video/`. `stageFromUri` (`:228-247`) is already generic.
- **Kind alignment + inline playback:** `Timeline.kt:690-691` already checks `att.kind == "video"` (now correct with the new kind) — keep the `mime.startsWith("video/")` fallback. Add inline playback via media3/`ExoPlayer` for `video`/`video_note`, falling back to the current system-viewer chip.
- Upload transport is the shared KMP `BrokerApi` (below).

## Shared KMP (`apps/shared`)

- `BrokerApi.upload` / `uploadBase64` (`apps/shared/.../net/BrokerApi.kt:1306-1333`) — switch from multipart to the streaming raw-body request: raw bytes as the body, `Content-Type: application/octet-stream`, `X-Mux-Session/Mime/Filename/Kind` headers (percent-encode filename). Signature (`session, bytes, filename, mime, kind?`) is unchanged, so both iOS (`BrokerSession.swift:192-194`) and Android call sites are unaffected. `Attachment.kind` (`Frames.kt:72`) stays free-form `String?`.

## Agent adapters (no change)

- codex (`src/core/agents/codex/adapter.ts:161-162`) already routes non-images to a `[Attached file: <path>]` text reference; video takes that path. cursor/opencode are path-only already. No change; the model does not receive video frames (Decision 7). Pre-existing "only the first attachment reaches the agent" limitation (`src/main.ts:2895-2899`, `src/channels/web/inbound-handler.ts:59-65`) is untouched.

## Error handling

- **Too large:** fast 413 from the `Content-Length` check; authoritative 413 from `putStream` aborting mid-stream (handles chunked/absent length and lying clients), with the `.part` file unlinked. Clients show "Video too large (max 500 MB)".
- **Aborted/broken upload:** `putStream` cleans up the `.part` file in a `try/catch`, exactly like `put()`.
- **Missing/invalid headers:** 400 for missing `X-Mux-Session`; unknown `X-Mux-Kind` silently falls back to `kindFromMime`; missing `X-Mux-Mime` → `document` kind + `.bin` extension (degraded but functional).
- **Unknown video MIME:** stored fine; extension falls back to `.bin` (`mime.ts:24`) if not in the extension map.
- Inbound download failures (Telegram/WhatsApp) already drop the attachment and let the message flow (`inbound.ts` `catch` blocks) — unchanged.

## Backward compatibility

- Old app-store builds keep uploading via `multipart/form-data` (legacy path retained, capped by `MAX_MULTIPART_BYTES`). The web app is served fresh each load, so it uses the streaming path immediately.
- The new `"video"` kind is additive; `video_note` still renders. Wire-level `kind` is a free-form string in the shared KMP layer, so old clients that only understand `video_note` still display inbound video via the `mime` `video/*` fallback already present on both platforms.

## Testing

- **Broker unit (`bun test`):** `putStream` happy path; `putStream` cap-abort mid-stream leaves no `.part` and throws `PayloadTooLarge`; `putStream` INSERT-failure cleanup; `/upload` streaming path (headers → correct kind/mime/size), legacy multipart path still works, both 413 routes, 400 on missing session, auth rejection; `kindFromMime` `video/* → "video"`; `extFromMime` for `.mov/.mkv/.m4v`.
- **Inbound unit:** Telegram `m.video → "video"` (and `m.video_note` still `video_note`); WhatsApp `video → "video"`.
- **Web:** typecheck/build `cd src/web-app && bun run build`; component test for the record-video action's `accept` and the composer video chip if there's an existing pattern to mirror.
- **iOS:** build via the remote-Mac simulator recipe; `ComposerModelTests` updated to assert a picked movie yields a `video/*` MIME + video extension (guards the mislabel-bug fix).
- **Android:** `:android:compileDebugKotlin` (or module build) + unit test for the widened clipboard/drag MIME check if coverage exists.
- **Manual, per client:** pick a video from library → uploads + plays inline; record a video from camera → uploads + plays; send a >500 MB file → clean "too large" message; send a Telegram video and a WhatsApp video to the bot → agent receives the file. Confirm an image still uploads/renders on every client (no regression).

## Out of scope

- **Phase 2 — resumable/chunked upload** (init/chunk/finalize + resume + progress, upload-session tracking, rewriting the upload client in all four codebases). Its own spec; Approach 1's raw-body request is the stepping stone.
- Server-side transcoding, compression, or thumbnail generation.
- Sending video frames to the model (agents get a path reference only).
- The pre-existing "only the first attachment reaches the agent" limitation.
- Telegram outbound `sendVideoNote` semantics for the `video_note` kind (outbound already uses `sendVideo`).

## Open questions

None outstanding — scope (full, incl. camera recording + inline players), the 500 MB cap, Approach 1 streaming with a retained legacy multipart path, and the Phase-1/Phase-2 split were all confirmed with the user before writing this spec.
