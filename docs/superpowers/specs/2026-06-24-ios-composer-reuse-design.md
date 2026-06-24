# iOS Composer Reuse — Design

- **Date:** 2026-06-24
- **Status:** Approved (approach #1, "unify behavior")
- **Area:** `apps/iosApp` (native SwiftUI)
- **Goal:** One shared composer engine behind both the chat composer and the new-session launcher, so updates and bug fixes land in both screens.

## 1. Motivation

The app has **two separate composers** that have drifted into copy-paste duplication:

- **Chat** — `Supermux/Chat/ChatPane.swift`. Expanding glass pill; on-device dictation + transcription (with audio/whisper fallback); photo/file/camera import; model & reasoning pills; per-session draft persistence; slash-command autocomplete; hardware-keyboard submit.
- **New session** — `Supermux/Sessions/NewSessionView.swift`. A static launcher card; *plain* mic (record → attach as a voice memo, no dictation); slash autocomplete; agent / model / worktree / project pickers. It **hand-copies** the attachment-chip view and the entire slash-command block from chat.

Today a composer bug fix (e.g. the double-mic-tap crash, transcription fallback, slash parsing) has to be applied in two places, and the launcher silently lacks chat's nicer affordances.

## 2. Decision

Adopt **shared engine, separate skins** with **behavior unification**:

- Extract one **`@Observable ComposerModel`** (state + logic) plus a small set of **stateless shared subviews**.
- Each screen keeps its **own shell/layout**, its **own surrounding controls**, and its **own terminal action** (chat *sends* to a live session; the launcher *spawns* then sends).
- **Unify behavior:** the launcher gains on-device dictation and photo/file/camera import via the shared engine. Where dictation needs a session it can't have pre-spawn, it degrades gracefully (see §5.1).

Rejected alternatives:
- *Focused controllers composed per screen* — the pieces (dictation writes the draft; attachments + mic drive expand/send) are interdependent, so each screen would re-thread bindings between them. More seams than one cohesive model buys, given both screens now want nearly the same feature set.
- *Minimal leaf extraction (chip + slash helpers only)* — leaves the bug-prone mic/picker **orchestration** duplicated, so it under-delivers on the unify-behavior goal.

## 3. Current shared/isolated building blocks (reused as-is)

- `RecordingBar` — `Supermux/Chat/AudioRecorder.swift:91` (already a shared view).
- `AudioRecorder` — `@Observable @MainActor final class` (already isolated).
- `SpeechDictation` — `@Observable @MainActor final class` (already isolated).
- `composerEnterAction` / `composerHardwareKeyboardSubmit` — `Supermux/Chat/ComposerKeyboard.swift` (already shared).
- `PendingAttachment` — currently declared in `Supermux/Chat/ChatView.swift:7`; **relocate** into the composer folder (it is shared model, mis-homed).
- `SlashCommand`, `SessionInfo`, `ModelInfo`, `RepoInfo`, `ReasoningResponse` — from the KMP `Shared` framework.

## 4. Architecture

New files under **`Supermux/Chat/Composer/`** (kept next to the existing composer primitives; both Chat and Sessions import them):

### 4.1 `ComposerModel`  (`ComposerModel.swift`)

`@Observable @MainActor final class ComposerModel` — owns the composer's state and logic. Nesting `AudioRecorder`/`SpeechDictation` (both `@Observable`) works via Observation's nested tracking.

**State**
- `var draft: String`
- `var pending: [PendingAttachment]`
- `let recorder = AudioRecorder()`
- `let dictation = SpeechDictation()`
- `var transcribing, micDenied, micStarting: Bool`
- `var glossary: [String]`

**Derived**
- `var canSubmit: Bool` — non-empty trimmed draft **or** a staged attachment.
- `var isBusy: Bool` — recording/dictating/transcribing/micStarting.
- `var hasContent: Bool` — `!draft.isEmpty || !pending.isEmpty`.

**Logic (moved verbatim from `ChatPane`)**
- Slash: `slashQuery`, `slashMatches(in:)`, `applyCommand(_:)`, `replaceSlashToken(with:)`, `clearSlashToken()`, `appendToDraft(_:)`.
- Mic pipeline: `toggleMic()`, `runTranscription(rawFallback:_:)`.
- Attachments: `loadPhotos(_:)`, `handleFiles(_:)`, `addCameraImage(_:)`, `removeAttachment(_:)`.
- `consume() -> (text: String, attachments: [PendingAttachment])` — returns current draft+pending and clears both. Used by each screen's send/spawn.
- `loadGlossary()` — best-effort glossary hydration.

### 4.2 `ComposerContext`  (injected per screen)

A value type holding the per-screen dependencies the model needs:

```
struct ComposerContext {
    var glossary: () async -> [String]                                  // shared: broker.fetchGlossary
    var cleanupTranscript: ((String) async throws -> String)?            // chat: transcribeDraft(sessionId:); launcher: nil
    var audioFallbackTranscribe: ((Data, String) async throws -> String)?// chat: transcribeAudio(sessionId:); launcher: nil
    var commands: () -> [SlashCommand]                                   // chat: broker.commands[id]; launcher: previewCommands
    var onControlCommand: (SlashCommand) -> Void                         // chat: model/rename/mute/stop/kill; launcher: no-op
}
```

The mic-fallback branch lives **inside** `toggleMic()`, keyed on whether the context provides `audioFallbackTranscribe`:
- **provided (chat):** on-device dictation → cleanup; else record → whisper → text.
- **nil (launcher):** on-device dictation → raw text; if on-device unavailable, record → **attach as a voice memo `PendingAttachment`** (today's launcher behavior; server transcribes after spawn).

`cleanupTranscript == nil` (launcher) means: use the raw on-device transcript (glossary-biased), skip agent cleanup.

### 4.3 Stateless shared subviews

- `AttachmentTray` — horizontal scroll of chips (extracted from the identical `attachmentChip` in both files). Takes `pending` + `onRemove`.
- `SlashMenu` — the dropdown (extracted from the near-identical `slashMenu`). Takes `matches` + `onApply`. Chat's variant shows the `bolt.fill` action glyph; expose it via a `showsActionGlyph` flag (chat true, launcher false).
- `MicButton` — mic glyph + `micStarting` spinner; takes the model.
- `AttachMenu` — the `+` menu (Photos / Files / Camera) driving the screen's picker bindings.
- `RecordingBar` — already shared; reused.

### 4.4 `ChatPane` refactor

- Replace composer `@State` with `@State private var composer = ComposerModel(...)` (or inject the context on `.task`).
- **Keep:** expanding glass-pill shell, model/reasoning pills + sheets, per-session draft persistence (`onChange(of: composer.draft)`), banner, transcript + `.equatable()` gate, sheets/alerts.
- `composerExpanded = composing || composer.hasContent || composer.isBusy`.
- `sendMessage()` → `let (text, atts) = composer.consume()` then upload + `broker.send`.
- Context: session-bound `cleanupTranscript`/`audioFallbackTranscribe`, `commands = broker.commands[id]`, `onControlCommand` wires the existing control-action switch (model/rename/mute/stop/kill).

### 4.5 `NewSessionView` refactor

- Replace the local `draft`/`pending`/`recorder`/`micDenied` + the duplicated `attachmentChip` and slash block with `@State private var composer = ComposerModel(...)` + the shared subviews.
- **Keep:** launcher card shell, project / agent / model / worktree pickers, `spawn()`.
- **Gains:** on-device dictation; photo/file/camera import (via `AttachMenu` + the picker modifiers).
- `spawn()` → `let (text, atts) = composer.consume()`, spawn, upload to the new id, send (existing sequencing).
- Context: `cleanupTranscript = nil`, `audioFallbackTranscribe = nil` (→ record-and-attach fallback), `commands = launcherCommands`, `onControlCommand = { _ in }` (insert-only).

## 5. Key decisions / tricky bits

### 5.1 Session-less dictation cleanup (launcher)
Transcription is `POST /sessions/{id}/transcribe` — **session-bound** for both agent text-cleanup (`transcribeDraft`) and the whisper fallback (`transcribeAudio`). The launcher has no session pre-spawn. **v1:** launcher dictation = on-device raw text (glossary-biased); when on-device STT is unavailable, fall back to record-and-attach-voice-memo (server transcribes post-spawn). No behavior is lost vs today. **Future option (out of scope):** add a session-less `/transcribe` endpoint so the launcher can get agent cleanup pre-spawn.

### 5.2 Typing performance must not regress
Chat's fast typing relies on `SessionTranscript` being `.equatable()` (keyed on `session.id`) so keystrokes don't rebuild the transcript. Moving `draft` into an `@Observable ComposerModel` preserves this: mutating `composer.draft` invalidates only views that *read* `draft`; `SessionTranscript` reads neither `composer` nor `draft` and stays gated. **Verification:** confirm no accidental `composer`/`draft` read leaks into `SessionTranscript`, and smoke-test typing in a long chat.

### 5.3 Draft persistence stays a chat concern
Per-session `UserDefaults` draft persistence remains wired in `ChatPane` via `onChange(of: composer.draft)`, with an `initialDraft` passed into the model for hydration. The launcher keeps no persistence (matches today).

### 5.4 Expand/collapse is shell-only
The expanding-pill morph is chat-shell behavior. The model exposes `hasContent`/`isBusy`; the chat shell decides to animate. The launcher card is always expanded.

### 5.5 Slash parsing shared, actions per-screen
Parsing + menu are shared. `applyCommand` inserts text for insert commands; for control commands it calls `context.onControlCommand` (chat runs the switch; launcher no-ops and just clears the token).

### 5.6 `PendingAttachment` relocates
Move from `ChatView.swift` into `Supermux/Chat/Composer/` so the shared model owns its own types.

## 6. Testing

The current suite only unit-tests `composerEnterAction` (`ComposerKeyboardTests`). Extracting logic into `ComposerModel` is a **testability win** — new `ComposerModelTests` (no UI, `@MainActor`):

- Slash: `slashQuery` extraction at end-of-draft; `replaceSlashToken` keeps the leading whitespace; `clearSlashToken`.
- `appendToDraft` space-joins onto an existing draft and trims.
- `canSubmit` / `hasContent` truth table (empty, draft-only, attachment-only).
- `consume()` returns the current text+attachments and clears both.
- `runTranscription` fallback: keeps `rawFallback` text on a thrown cleanup; surfaces the failure banner path only when there is no text to keep.
- Mic-fallback branch selection: with `audioFallbackTranscribe == nil` the unavailable path stages a voice `PendingAttachment`; with it provided, it routes to whisper.

Keep `ComposerKeyboardTests`. Then **build the app** and run the suite (Linux iOS toolchain per the `ios-dev-on-linux` skill, or a remote Mac).

## 7. Risks & mitigations

- **Regressing the polished chat composer.** Mitigation: the move is mechanical (logic lifted, not rewritten); shells stay intact; new model tests + existing composer tests; build + manual smoke of typing, dictation, attachments, slash, send.
- **Observation subtlety** (nested `@Observable`, perf gate). Mitigation: §5.2 verification step; keep `draft` reads out of `SessionTranscript`.

## 8. Out of scope (now)

- Session-less `/transcribe` broker endpoint for pre-spawn agent cleanup (§5.1 future).
- Any redesign of either shell's visual layout.
- Web composer (this is iOS-only).

## 9. File-by-file change list

**New** (`Supermux/Chat/Composer/`): `ComposerModel.swift`, `ComposerContext.swift`, `AttachmentTray.swift`, `SlashMenu.swift`, `MicButton.swift`, `AttachMenu.swift`, `PendingAttachment.swift` (moved).
**New** (`SupermuxTests/`): `ComposerModelTests.swift`.
**Edit:** `Supermux/Chat/ChatPane.swift` (consume `ComposerModel` + shared subviews), `Supermux/Sessions/NewSessionView.swift` (consume `ComposerModel` + shared subviews; gains dictation + pickers), `Supermux/Chat/ChatView.swift` (drop the moved `PendingAttachment`).
**Xcode project:** the project is **XcodeGen** (`project.yml`) with directory-globbed sources (`sources: - path: Supermux`, `createIntermediateGroups: true`) and a gitignored `.xcodeproj`. New files under `Supermux/` and `SupermuxTests/` are picked up automatically — no `project.yml` edit and no `.pbxproj` surgery; just re-run `xcodegen generate` before building.
