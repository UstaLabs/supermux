# Resumable Uploads — Phase 4: iOS Implementation Plan

> Executed with superpowers:executing-plans. iOS = blind Swift on this Linux host → **the verification gate is a remote-Mac build + Simulator feel-test** (route: `mux:ios-simulator-on-remote-mac`; paid account 57L7J9XA89). Per `mux:ios-development`: "builds ≠ works" — do not claim done off a green build; drive the upload in the Simulator (pick a >5 MB video → watch the ring → confirm a killed-network attempt shows a failed/retry chip), mirroring the Android emulator pass.

**Goal:** iOS composer uploads big videos in chunks with a determinate progress ring and never silently drops a failed attachment — the same three audit fixes proven on Android, on top of the shared `uploadResumable`.

**Architecture:** Videos are staged as a **file URL** (not `Data`) and uploaded via `uploadResumable` behind `NSFileHandleChunkSource` (slice reads, bounded RAM — kills the old `Data`→base64→`ByteArray` ~2.3× blow-up). Images/audio stay on the existing `Data` single-POST path (small, fast). Per-attachment `uploading/progress/failed` state drives the `AttachmentTray` chip; send is blocked while any attachment is uploading or failed.

## Status
- ✅ **Shared core written + committed:** `apps/shared/src/appleMain/kotlin/dev/supermux/net/NSFileHandleChunkSource.kt` (implements the commonMain `ChunkSource` via `NSFileHandle` seek+read; `NSData`→`ByteArray` via `memcpy`). Compiles only for Apple targets → validated by the Mac build below.
- ⏳ **Swift + build + Simulator verify:** the remote-Mac loop (this doc).

## Files
- **Created** `apps/shared/src/appleMain/.../NSFileHandleChunkSource.kt` — done.
- **Modify** `apps/iosApp/.../Broker/BrokerSession.swift` — add `uploadResumable`.
- **Modify** `apps/iosApp/.../Chat/Composer/PendingAttachment.swift` — optional `data`/`fileURL` + `uploading/progress/failed`.
- **Modify** `apps/iosApp/.../Chat/Composer/ComposerModel.swift` — stage videos as a temp file URL (Photos `loadFileRepresentation` / camera URL), images/audio unchanged.
- **Modify** `apps/iosApp/.../Chat/Composer/AttachmentTray.swift` — determinate ring + failed/retry chip.
- **Modify** `apps/iosApp/.../Chat/ChatPane.swift` + `apps/iosApp/.../Sessions/NewSessionView.swift` — shared upload loop: per-attachment `uploadResumable`/`upload`, progress, failed → don't send, retry.

## Key Swift

**BrokerSession** (mirror of `upload`):
```swift
/// Resumable/chunked upload from a ChunkSource (bounded RAM), with progress.
func uploadResumable(_ sessionId: String, source: ChunkSource, filename: String, mime: String,
                     kind: String? = nil, onProgress: @escaping (Int64, Int64) -> Void) async -> String? {
    (try? await api.uploadResumable(session: sessionId, source: source, filename: filename,
        mime: mime, kind: kind, onProgress: { sent, total in onProgress(sent.int64Value, total.int64Value) }))?.file_id
}
```
(SKIE renders the Kotlin `(Long,Long)->Unit` progress param as a Swift closure taking `KotlinLong`s — confirm the exact bridged signature on the Mac and adjust the `int64Value` unwrap.)

**PendingAttachment** — keep both inits so existing image/audio call sites are untouched:
```swift
struct PendingAttachment: Identifiable {
    let id = UUID()
    var data: Data? = nil
    var fileURL: URL? = nil
    let filename: String
    let mime: String
    var uploading = false
    var progress: Double = 0
    var failed = false
    init(data: Data, filename: String, mime: String) { self.data = data; self.filename = filename; self.mime = mime }
    init(fileURL: URL, filename: String, mime: String) { self.fileURL = fileURL; self.filename = filename; self.mime = mime }
}
```

**ComposerModel** — video staging switches to a stable temp URL (copy out of the system's transient URL before it's reclaimed):
- `loadPhotos`: for a movie item, `loadTransferable(type: MovieTransfer.self)` (a `Transferable` that copies to a temp URL) or `item.loadFileRepresentation` → copy to `FileManager.default.temporaryDirectory` → `PendingAttachment(fileURL:)`. Images stay `PendingAttachment(data:)`.
- `addCameraVideo(_ url:)`: copy `url` to temp → `PendingAttachment(fileURL:)` (drop the current `Data(contentsOf:)`).
- `handleFiles`, `addCameraImage`, paste, audio: unchanged (`Data`).
- `consume()` unchanged; the screen keeps the temp URLs and deletes them after a successful send.

**Upload loop** (shared by `ChatPane.sendMessage` and `NewSessionView.spawn`; today it silently drops failures). New behavior:
```swift
// don't consume() until all uploads succeed; drive progress/failed on composer.pending by id.
for idx in composer.pending.indices {
    let p = composer.pending[idx]
    composer.pending[idx].uploading = true
    let kind = p.mime.hasPrefix("audio") ? "voice" : nil
    let id: String?
    if let url = p.fileURL {
        id = await broker.uploadResumable(session.id, source: NSFileHandleChunkSource(path: url.path),
            filename: p.filename, mime: p.mime, kind: kind) { sent, total in
                Task { @MainActor in composer.setProgress(p.id, total > 0 ? Double(sent)/Double(total) : 0) } }
    } else {
        id = await broker.upload(session.id, data: p.data!, filename: p.filename, mime: p.mime, kind: kind)
    }
    if let id { ids.append(id); composer.pending[idx].uploading = false; composer.pending[idx].progress = 1 }
    else { composer.pending[idx].uploading = false; composer.pending[idx].failed = true; return } // block send, keep chips
}
// all succeeded → consume() + send + delete temp files
```
Add `ComposerModel.setProgress(_ id: UUID, _ p: Double)` (find by id, set `progress`) and a `canSubmit` that is false while any pending is `uploading || failed`. Retry = re-run the loop for the failed attachment (tap the chip).

**AttachmentTray** chip — determinate ring + failed/retry:
```swift
if p.failed {
    // red tint, tappable to retry
    HStack { Image(systemName: "exclamationmark.circle.fill"); Text("\(p.filename) · Retry") }
        .foregroundStyle(.red).onTapGesture { onRetry(p) }
} else if p.uploading {
    ProgressView(value: p.progress).progressViewStyle(.circular).controlSize(.small)
    Text(p.filename)…
} else { /* existing chip + xmark remove */ }
```
(Add an `onRetry` closure to `AttachmentTray`, wired to the screen's per-attachment upload.)

## Build + verify (remote Mac — the gate)
1. Sync the WHOLE `apps/` to the Mac (per the build rule), build the KMP framework (`embedAndSign…`, `--no-daemon` for keychain over SSH) + the app for the Simulator (`ios-simulator-on-remote-mac`).
2. Fix compile errors (esp. the SKIE-bridged progress closure signature, and `ChunkSource`/`NSFileHandleChunkSource` visibility in the framework).
3. Boot the Simulator, drive it: pick a >5 MB video → **watch the ring advance**; simulate an upload failure (point at a broker without Phase-1, or airplane-mode) → **confirm the failed/retry chip** (not a silent send). Screenshot each. Real-device pass for gesture/feel is a follow-up.
4. Only then mark done — with the screenshot as evidence.

## Done criteria
- KMP framework + iOS app build green on the Mac.
- Simulator: determinate ring during upload; failure shows retry + blocks send; large video never loads whole into RAM.
- Uses the shared `uploadResumable` (no iOS-specific upload logic beyond the file-URL source + UI).
