# Windows/Linux Desktop Client — Milestone 4d (Chat Composer Uploads) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add attachments to the desktop CHAT composer. `DesktopComposer` is text-only today; this adds an Attach button (java.awt.FileDialog), staged-attachment chips with per-file upload progress/retry, drag-and-drop, and send-with-file_ids — reusing M4a's `FileChunkSource` + `DesktopAppState.uploadResumable`. The chat composer uploads against the LIVE session immediately (unlike the launcher which stages pre-spawn).

**Architecture:** The M4a foundations are proven: `upload/FileChunkSource.kt` + `DesktopAppState.uploadResumable(session, source, name, mime, kind?, onProgress): String?`. This task wires them into the chat composer: an "Attach" affordance (FileDialog multi-select → FileChunkSource per file), a staged-chip row showing name + upload progress (0→100%) + a retry-on-failure state, and on send, the collected file_ids go into `app.sendMessage(session.id, text, fileIds)` (which already accepts `attachments: List<String>`). Ports the staging/progress/retry UX from Android's `chat/ChatPanel.kt` composer, minus the Android media-picker specifics (desktop = one FileDialog + drag-drop).

**Tech Stack:** Compose Desktop, `java.awt.FileDialog` (multi-select), `Modifier.dragAndDropTarget` (external file drop), shared `uploadResumable`/`ChunkSource`, `DesktopAppState.uploadResumable`/`sendMessage`.

---

## Ground rules

All prior-milestone rules hold (standard gradle invocation with /home/ahmet/.cache logs + TMPDIR; Xvfb :77 + `SKIKO_RENDER_API=SOFTWARE`; paired config; xwd+Pillow; NO xdotool — env hooks; never restart the broker; snake_case tests; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch ONLY apps/desktop/src, NEVER build). Suite baseline at M4d start: desktop 341 / shared jvmTest 292 / android compile green.

- Reuse M4a's `FileChunkSource` + `DesktopAppState.uploadResumable` + `StagedUpload` (from LauncherStore/session) — do NOT reinvent. Check if StagedUpload's shape fits the chat case or if a chat-specific staged-chip model is cleaner (the chat case uploads immediately vs the launcher's pre-spawn staging — the chip needs a live upload STATE: pending/uploading(pct)/done(file_id)/failed).
- Upload timing: the chat composer uploads a chip IMMEDIATELY on stage (against the live session), so send just gathers the already-uploaded file_ids. (Android's chat composer does this — verify; the launcher's post-spawn staging is the other model. Match Android's chat behavior.)
- Send gating: send is disabled while any chip is still uploading OR failed (Android blocks the send on incomplete uploads — the "any upload failure BLOCKS the send" rule from the video-attachment work; mirror it). A failed chip shows "tap to retry".
- Desktop.browse is NOT relevant here. FileDialog is modal on the EDT (fine — Compose Desktop Main == EDT, established in M4a).
- Only the FIRST attachment reaches the agent (broker limitation, per domain memory) — but stage/upload/send all attachments (the broker stores them); don't special-case.

---

### Task 1: The staged-upload chip model + composer attach (TDD where pure)

**Files:** Modify `apps/desktop/.../chat/DesktopComposer.kt` (+ a `ComposerAttachment` state model if cleaner) + test.

- [x] A `ComposerAttachment(id, name, mime, source: FileChunkSource, state: UploadState)` where `UploadState = Uploading(pct) | Done(fileId) | Failed`. A `mutableStateListOf<ComposerAttachment>` in the composer. Attach button → `java.awt.FileDialog(LOAD, multi-select)` → one ComposerAttachment per file (mime via Files.probeContentType, kind guess). Each chip kicks off `app.uploadResumable(session.id, source, name, mime, kind) { sent, total -> pct }` on stage, updating its state; on file_id → Done; on null → Failed. Extract the pure bits (mime/kind guess, the send-gating predicate `canSend(text, attachments)` = text-or-attachments non-empty AND no chip Uploading/Failed) + test them.
- [x] Chip row UI: name + a progress indicator (determinate % while Uploading) + an × remove + a retry affordance on Failed. Send button gated on `canSend`; a "Sending…" indicator stays (existing). Compose the chip row above the text field.

### Task 2: Drag-and-drop + send wiring

**Files:** Modify `apps/desktop/.../chat/DesktopComposer.kt` + `ChatPanel.kt` (the send path).

- [x] `Modifier.dragAndDropTarget` on the composer accepting external file drops (java.awt file-list flavor) → same ComposerAttachment staging path as the FileDialog. (If dragAndDropTarget's external-file support is finicky under Compose Desktop, document + gate it; the FileDialog is the must-ship, drag-drop the nice-to-have.)
- [x] Send: onSend gathers `attachments.map { (it.state as Done).fileId }` (send is gated so all are Done), calls `app.sendMessage(session.id, text, fileIds)`, clears the text AND the chips. Wire through ChatPanel's DesktopComposer usage (currently `onSend: (String) -> Unit` → extend to carry attachments, or the composer owns the attachment state and calls app.sendMessage directly — pick the cleaner given ChatPanel's structure; check how the composer currently sends).
- [x] UI tests via seams (faked uploadResumable): staging a file shows a chip with progress → done; send gated while uploading; a failed upload shows retry + blocks send; retry re-uploads; send gathers file_ids + clears; remove-chip works.

### Task 3: Live verification + report

- [x] `SM_CHAT_ATTACH=<session-name>|<file-path>|<text>` hook (documented, off by default): stage the file into the named session's composer + upload it + send with the text. Drives the real chat-upload→send path headlessly.
- [x] Live checklist (m4dv-*.png, see report below): (1) PASS — a throwaway session's composer showed the Attach-staged chip transition Uploading(%) → Done live; (2) PASS — round-tripped TWICE against the real broker/agent (a 20 MiB and a 150 MiB attachment, plus a small text file with a real message) — the agent downloaded each file, computed its SHA-256/read its contents, and replied referencing it; broker `GET /files/<id>` byte-matched every upload; (3) PASS — a 150 MiB attachment's mid-upload frames (43%/73%) were caught live with the Send button visibly disabled (grey arrow), corroborating the existing `assertIsNotEnabled` unit test; (4) SUBSTITUTED — a genuine broker-side upload failure wasn't cheaply inducible live (no broker error injection hook, and `MUX_WEB_UPLOAD_MAX_MB` is too large to hit with a throwaway file); the Failed→Retry→blocks-send path is unit-test-covered (`failedUpload_showsRetry_blocksSend_retryReRunsToDone`, plus the new `externalAttach_failedUpload_neverSends_butStillConsumes`); (5) CODE-VERIFIED, MANUAL-ONLY — the AWT drag gesture can't be driven headless under Xvfb (no file manager); `dragAndDropTarget` wiring + the shared `stageFiles` funnel are unit-tested (`stageFiles_batchStagesAllValidFiles_andUploadsEach…`, `composerFilesFromDragData_*`). Throwaway session + temp files cleaned up; ui-state.json reset. Suites green (desktop 366 / shared jvmTest 292 / android compile, all `--rerun-tasks`). Plan ticked, `docs(desktop): M4d plan executed`, report incl. what M4e-g inherit.

## Self-review notes
Spec coverage: chat uploads complete the composer parity (text-only → attachments). Reuses M4a's proven FileChunkSource + upload wrapper (no new upload plumbing). The send-gating-on-incomplete-uploads rule is the load-bearing correctness bit (the "any upload failure blocks the send" lesson from the video work). Drag-drop is nice-to-have behind the FileDialog must-ship.
