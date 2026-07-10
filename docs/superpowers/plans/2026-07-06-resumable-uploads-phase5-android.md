# Resumable Uploads — Phase 5: Android Implementation Plan

> Executed with superpowers:executing-plans. Concise plan (mostly wiring on top of the tested Phase-2 KMP client); verification is the Gradle `:android` compile + an emulator feel-test.

**Goal:** Android composer uploads big videos in chunks with a determinate progress bar and never silently drops a failed attachment — fixing all three audit findings (no progress, whole-file-in-RAM, silent-drop) by wiring the Phase-2 `uploadResumable` behind a `ContentResolver`-backed `ChunkSource`.

**Architecture:** A new `ContentResolverChunkSource` reads the picked `content://` Uri in position-absolute slices (fresh seekable fd per read) — no `readBytes()` of the whole file. `ChatPanel.stageFromUri` builds it, calls the threaded `onUpload` (now resumable + progress) and updates a per-attachment `progress`/`failed` state tracked by a stable id. The chip shows a determinate `CircularProgressIndicator` and a "Failed — tap to retry" state; send is blocked while any attachment is uploading or failed (kills the silent drop).

## Files
- **Create** `apps/android/.../chat/ContentResolverChunkSource.kt` — `ChunkSource` over a Uri.
- **Modify** `apps/android/.../AppViewModel.kt` — `uploadResumable(sessionId, source, name, mime, kind, onProgress): String?`.
- **Modify** `apps/android/.../chat/ChatPanel.kt` — `PendingAttachment(id, fileId, name, uploading, progress, failed, source)`; `stageFromUri` builds the source + reports progress + marks failed (not remove); `onUpload` param type change; chip UI (determinate progress + failed/retry); `canSend`/`doSend` block on uploading|failed.
- **Modify** `apps/android/.../chat/ChatScreen.kt`, `apps/android/.../workspace/SessionWorkspaceDetail.kt` — thread the new `onUpload` signature.
- **Modify** `apps/android/.../session/SessionKeepAlive.kt` — both call sites → `vm.uploadResumable(...)`.

## New `onUpload` signature
`suspend (source: ChunkSource, name: String, mime: String, kind: String?, onProgress: (Long, Long) -> Unit) -> String?`

## Tasks
1. `ContentResolverChunkSource` + `AppViewModel.uploadResumable`.
2. `ChatPanel`: `PendingAttachment` fields + stable-id updates + `stageFromUri` rewrite + chip UI + send-gating + retry.
3. Thread the signature through `ChatScreen`, `SessionWorkspaceDetail`, `SessionKeepAlive`.
4. Verify: `./gradlew :android:compileDebugKotlin` (from `apps/`) green; then emulator feel-test — pick a large video, watch the % bar advance, and confirm a killed-network mid-upload surfaces "Failed — tap to retry" rather than sending without the file.

## Done criteria
- `:android` compiles.
- Chip shows determinate progress; failure surfaces retry and blocks send; large video never loads whole into RAM.
- Uses the shared tested `uploadResumable` (no Android-specific upload logic beyond the Uri source + UI).
