# iOS Composer Reuse — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract one shared `@Observable ComposerModel` + stateless shared subviews so the chat composer (`ChatPane`) and the new-session launcher (`NewSessionView`) share all composer logic and bug fixes; unify behavior so the launcher gains on-device dictation and photo/file/camera import.

**Architecture:** A `ComposerModel` (state + logic: draft, attachments, mic/dictation pipeline, slash parsing) is injected with a small `ComposerContext` (the 3 IO closures that differ per screen). Each screen keeps its own shell/layout, surrounding controls, and terminal action (chat *sends*; launcher *spawns then sends*) and calls `model.consume()`. Four stateless shared subviews render the common UI pieces.

**Tech Stack:** Native SwiftUI (iOS 26 target), Swift 5, Observation framework (`@Observable`), XCTest, XcodeGen, KMP `Shared` framework for DTOs (`SlashCommand`, `SessionInfo`, …).

**Spec:** `docs/superpowers/specs/2026-06-24-ios-composer-reuse-design.md`

---

## Execution environment & build

- **Edits + git** happen in this worktree on Linux (`apps/iosApp/...`).
- **Build + tests** require a Mac (XcodeGen + xcodebuild + iOS Simulator). This Linux host cannot run them. Use the `mux:ios-simulator-on-remote-mac` skill to rsync the worktree to the remote Mac and build/test there. Resolve the Mac at the start of Task 3 (first time a build is needed).
- The `.xcodeproj` is **gitignored and generated**; new files under `Supermux/` and `SupermuxTests/` are auto-globbed by `project.yml`. After adding/removing files, regenerate: `cd apps/iosApp && xcodegen generate`.
- **Canonical test command** (run on the Mac, from `apps/iosApp`):
  ```bash
  xcodegen generate
  xcodebuild test -scheme Supermux \
    -destination 'platform=iOS Simulator,name=iPhone 16,OS=26.0' \
    -only-testing:SupermuxTests
  ```
  Adjust the simulator to one installed (`xcrun simctl list devices available`); if no iOS 26 runtime, `xcodebuild -downloadPlatform iOS` first. Expected after Task 3: `** TEST SUCCEEDED **`.
- **Build-only** smoke for the app target: `xcodebuild build -scheme Supermux -destination 'platform=iOS Simulator,name=iPhone 16,OS=26.0'`.
- Because remote-Mac builds are slow, each task verifies with **one** build/test run at its end (not per assertion). Commit after each green task.

## File structure

**New (`apps/iosApp/Supermux/Chat/Composer/`):**
- `PendingAttachment.swift` — the staged-attachment value type (moved out of `ChatView.swift`).
- `ComposerContext.swift` — the 3 per-screen IO closures.
- `ComposerModel.swift` — the shared brain (`@Observable @MainActor`).
- `AttachmentTray.swift` — horizontal strip of attachment chips.
- `SlashMenu.swift` — the `/command` autocomplete dropdown.
- `MicButton.swift` — mic / dictation entry button.
- `AttachMenu.swift` — the `+` Photos/Files/Camera menu.

**New (`apps/iosApp/SupermuxTests/`):**
- `ComposerModelTests.swift` — unit tests for the model's pure logic.

**Modified:**
- `apps/iosApp/Supermux/Chat/ChatView.swift` — delete the `PendingAttachment` declaration (now its own file).
- `apps/iosApp/Supermux/Chat/ChatPane.swift` — consume `ComposerModel` + subviews; keep the pill shell, pills, persistence, transcript, banner.
- `apps/iosApp/Supermux/Sessions/NewSessionView.swift` — consume `ComposerModel` + subviews; gain dictation + pickers; keep the launcher card + pickers + `spawn()`.

> **Refactor tasks (5, 6) note:** Do NOT reprint the whole 600-line files. Read the current file, apply the described transformation, and preserve every existing comment, lifecycle hook, and the transcript's `.equatable()` gate. The plan gives the exact new/changed code; you splice it in.

---

## Task 1: Move `PendingAttachment` into its own file

**Files:**
- Create: `apps/iosApp/Supermux/Chat/Composer/PendingAttachment.swift`
- Modify: `apps/iosApp/Supermux/Chat/ChatView.swift:5-12` (delete the struct)

- [ ] **Step 1: Create the new file**

```swift
// apps/iosApp/Supermux/Chat/Composer/PendingAttachment.swift
import Foundation

/// A photo, file, or audio clip staged in the composer, awaiting upload on send/spawn.
/// Shared by the chat composer (`ChatPane`) and the new-session launcher (`NewSessionView`)
/// via `ComposerModel`.
struct PendingAttachment: Identifiable {
    let id = UUID()
    let data: Data
    let filename: String
    let mime: String
}
```

- [ ] **Step 2: Delete the old declaration from `ChatView.swift`**

Remove lines 5–12 of `apps/iosApp/Supermux/Chat/ChatView.swift` (the `/// A photo (or audio) staged…` doc comment through the closing `}` of `struct PendingAttachment`). Leave the `import` lines and `ChatView` untouched.

- [ ] **Step 3: Regenerate + build**

Run (on the Mac): `cd apps/iosApp && xcodegen generate && xcodebuild build -scheme Supermux -destination 'platform=iOS Simulator,name=iPhone 16,OS=26.0'`
Expected: `** BUILD SUCCEEDED **` (no other file referenced the declaration's location; the type is unchanged).

- [ ] **Step 4: Commit**

```bash
git add apps/iosApp/Supermux/Chat/Composer/PendingAttachment.swift apps/iosApp/Supermux/Chat/ChatView.swift
git commit -m "refactor(ios): move PendingAttachment to its own composer file"
```

---

## Task 2: Add `ComposerContext`

**Files:**
- Create: `apps/iosApp/Supermux/Chat/Composer/ComposerContext.swift`

- [ ] **Step 1: Create the file**

```swift
// apps/iosApp/Supermux/Chat/Composer/ComposerContext.swift
import Foundation

/// Per-screen IO injected into `ComposerModel`. Only the things that genuinely differ
/// between the chat composer and the new-session launcher live here; everything else is
/// shared logic on the model. All three closures capture at most `broker` + a session id,
/// so the chat screen rebuilds this (via `ComposerModel.reconfigure`) whenever the session
/// switches; the launcher builds it once.
struct ComposerContext {
    /// Project/technical-term glossary fed to on-device dictation as contextual bias.
    /// Session-less (`broker.fetchGlossary`), identical on both screens.
    var glossary: () async -> [String] = { [] }

    /// Agent cleanup of an on-device dictation draft. Chat passes the session-bound
    /// `transcribeDraft(sessionId:)`. The launcher has no session pre-spawn → passes `nil`,
    /// and the raw on-device transcript is used as-is.
    var cleanupTranscript: ((String) async throws -> String)? = nil

    /// Whisper transcription of a recorded clip — the fallback when on-device recognition is
    /// unavailable. Chat passes the session-bound `transcribeAudio(sessionId:)`. When `nil`
    /// (launcher), a recorded clip is instead staged as a voice attachment.
    var audioFallbackTranscribe: ((Data, String) async throws -> String)? = nil
}
```

- [ ] **Step 2: Regenerate + build**

Run: `cd apps/iosApp && xcodegen generate && xcodebuild build -scheme Supermux -destination 'platform=iOS Simulator,name=iPhone 16,OS=26.0'`
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
git add apps/iosApp/Supermux/Chat/Composer/ComposerContext.swift
git commit -m "feat(ios): add ComposerContext (per-screen composer IO)"
```

---

## Task 3: Add `ComposerModel` (TDD)

The model lifts the composer logic out of `ChatPane` verbatim, with three adaptations: (a) the mic audio-fallback branch is context-driven (whisper vs stage-as-attachment), (b) the dictation cleanup is context-driven (agent cleanup vs raw text), (c) control slash commands and status/refocus are exposed as observable signals the screen reacts to (instead of mutating view state directly).

**Files:**
- Create: `apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift`
- Test: `apps/iosApp/SupermuxTests/ComposerModelTests.swift`

- [ ] **Step 1: Write the failing tests**

```swift
// apps/iosApp/SupermuxTests/ComposerModelTests.swift
import XCTest
import Shared
@testable import Supermux

/// Unit tests for `ComposerModel`'s pure logic (draft, slash parsing, consume). The mic /
/// dictation pipeline touches hardware (AVAudioEngine / SFSpeechRecognizer) and is covered by
/// manual smoke, not here. `ComposerModel` is `@MainActor`, so these run on the main actor.
@MainActor
final class ComposerModelTests: XCTestCase {

    private func model(draft: String = "") -> ComposerModel {
        ComposerModel(context: ComposerContext(), initialDraft: draft)
    }

    /// Build a minimal `SlashCommand` (full positional init — avoids SKIE default-arg overloads).
    private func cmd(_ name: String, family: String = "fam",
                     insertText: String? = nil, action: ControlAction? = nil) -> SlashCommand {
        SlashCommand(id: name, family: family, name: name, sigil: "/",
                     description: nil, insertText: insertText, action: action)
    }

    func testCanSubmitFalseWhenEmpty() {
        XCTAssertFalse(model().canSubmit)
    }
    func testCanSubmitFalseWhenWhitespaceOnly() {
        XCTAssertFalse(model(draft: "   \n").canSubmit)
    }
    func testCanSubmitTrueWithDraft() {
        XCTAssertTrue(model(draft: "hello").canSubmit)
    }
    func testHasContent() {
        XCTAssertFalse(model().hasContent)
        XCTAssertTrue(model(draft: "x").hasContent)
    }
    func testConsumeReturnsAndClears() {
        let m = model(draft: "build the thing")
        let out = m.consume()
        XCTAssertEqual(out.text, "build the thing")
        XCTAssertTrue(out.attachments.isEmpty)
        XCTAssertEqual(m.draft, "")
        XCTAssertTrue(m.pending.isEmpty)
    }
    func testAppendToDraftSpaceJoins() {
        let m = model(draft: "hello")
        m.appendToDraft("world")
        XCTAssertEqual(m.draft, "hello world")
    }
    func testAppendToDraftFromEmpty() {
        let m = model()
        m.appendToDraft("hi")
        XCTAssertEqual(m.draft, "hi")
    }
    func testAppendToDraftBumpsRefocusToken() {
        let m = model()
        let before = m.refocusToken
        m.appendToDraft("hi")
        XCTAssertEqual(m.refocusToken, before + 1)
    }
    func testSlashQueryAtEndOfDraft() {
        XCTAssertEqual(model(draft: "do this /he").slashQuery, "he")
    }
    func testSlashQueryNilForMidWordSlash() {
        XCTAssertNil(model(draft: "path/to/file").slashQuery)
    }
    func testSlashQueryEmptyForBareSlash() {
        XCTAssertEqual(model(draft: "/").slashQuery, "")
    }
    func testSlashMatchesFiltersByQuery() {
        let m = model(draft: "/he")
        let matches = m.slashMatches(in: [cmd("help"), cmd("model"), cmd("hello")])
        XCTAssertEqual(matches.map(\.name), ["help", "hello"])
    }
    func testApplyInsertCommandReplacesToken() {
        let m = model(draft: "go /he")
        m.applyCommand(cmd("help", insertText: "/help "))
        XCTAssertEqual(m.draft, "go /help ")
    }
    func testApplyControlCommandClearsTokenAndSignals() {
        let m = model(draft: "stop it /sto")
        m.applyCommand(cmd("stop", action: ControlAction(kind: "stop", muted: nil)))
        XCTAssertEqual(m.draft, "stop it ")          // token removed, leading space kept
        XCTAssertEqual(m.controlCommandToHandle?.name, "stop")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run the canonical test command (Mac). Expected: **compile failure** — `cannot find 'ComposerModel' in scope` (the type doesn't exist yet). That is the red state.

- [ ] **Step 3: Write the model**

```swift
// apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift
import SwiftUI
import Shared
import PhotosUI
import UniformTypeIdentifiers

/// The composer's shared brain: draft + staged attachments + the mic/dictation pipeline +
/// slash-command parsing. Lifted out of `ChatPane` so the new-session launcher shares the
/// exact same logic (and bug fixes). Per-screen IO is injected via `ComposerContext`; the
/// screen owns its own layout + terminal action (send vs spawn) and calls `consume()`.
///
/// View-reaction signals (`controlCommandToHandle`, `status`, `refocusToken`) are exposed as
/// observable state instead of being pushed via closures, so the screen reacts with full
/// access to its own `@State`/`@FocusState` without the model capturing view state.
@Observable
@MainActor
final class ComposerModel {
    // MARK: - Composer state
    var draft: String
    var pending: [PendingAttachment] = []
    let recorder = AudioRecorder()
    let dictation = SpeechDictation()
    var transcribing = false
    var micDenied = false
    // True while dictation.start() is in flight (notably the first-run on-device model
    // download). Blocks a second tap from starting a concurrent mic session — two installTap
    // on one audio bus is a hard crash (the first-voice crash on a fresh install).
    var micStarting = false
    var glossary: [String] = []

    // MARK: - View-reaction signals
    /// A *control* slash command (`action != nil`) was applied; the screen runs the actual
    /// model/rename/mute/stop/kill action and resets this to nil. Launcher ignores it.
    var controlCommandToHandle: SlashCommand?
    /// Transient status ("Didn't catch that" / "Transcription failed"); the screen surfaces it
    /// (chat → its banner) and resets to nil. Launcher may ignore it.
    var status: String?
    /// Bumped whenever text is appended to the draft (dictation result); the screen observes it
    /// to re-focus the composer.
    var refocusToken = 0

    private var context: ComposerContext

    init(context: ComposerContext, initialDraft: String = "") {
        self.context = context
        self.draft = initialDraft
    }

    /// Re-point session-bound IO and reset per-session state. Chat calls this on session switch
    /// (the transcribe closures capture the session id, so they must be rebuilt). Cancels any
    /// in-flight mic session and drops staged attachments.
    func reconfigure(context: ComposerContext, draft: String) {
        cancelMic()
        self.context = context
        self.draft = draft
        self.pending = []
    }

    // MARK: - Derived
    var canSubmit: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !pending.isEmpty }
    var hasContent: Bool { !draft.isEmpty || !pending.isEmpty }
    var isBusy: Bool { dictation.isListening || recorder.isRecording || transcribing || micStarting }

    // MARK: - Lifecycle
    func loadGlossary() async { glossary = await context.glossary() }
    func cancelMic() { dictation.cancel(); recorder.cancel() }

    /// Current draft + staged attachments, clearing both. The screen does the send/spawn.
    func consume() -> (text: String, attachments: [PendingAttachment]) {
        let out = (draft, pending)
        draft = ""
        pending = []
        return out
    }

    // MARK: - Attachments
    func removeAttachment(_ p: PendingAttachment) { pending.removeAll { $0.id == p.id } }

    /// Load picked photos into `pending`. The screen clears its `PhotosPickerItem` selection
    /// after awaiting this.
    func loadPhotos(_ items: [PhotosPickerItem]) async {
        for (i, item) in items.enumerated() {
            if let data = try? await item.loadTransferable(type: Data.self) {
                pending.append(PendingAttachment(data: data,
                                                 filename: "image-\(pending.count + i + 1).jpg",
                                                 mime: "image/jpeg"))
            }
        }
    }
    func handleFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                pending.append(PendingAttachment(data: data, filename: url.lastPathComponent, mime: mime))
            }
        }
    }
    func addCameraImage(_ img: UIImage) {
        if let data = img.jpegData(compressionQuality: 0.85) {
            pending.append(PendingAttachment(data: data, filename: "photo-\(pending.count + 1).jpg", mime: "image/jpeg"))
        }
    }

    // MARK: - Mic / dictation
    // Mic → on-device STT first (real-time → cleanup → draft text), falling back to audio
    // recording. The fallback's terminal action is context-driven: chat transcribes the clip
    // (whisper) into text; the launcher (no session) stages it as a voice attachment.
    func toggleMic() async {
        if recorder.isRecording {
            guard let (data, name) = recorder.stop() else { status = "Didn't catch that"; return }
            if let whisper = context.audioFallbackTranscribe {
                await runTranscription { try await whisper(data, name) }
            } else {
                pending.append(PendingAttachment(data: data, filename: name, mime: "audio/mp4"))
            }
            return
        }
        if dictation.isListening {
            let (text, _) = await dictation.stop()
            guard !text.isEmpty else { status = "Didn't catch that"; return }
            if let cleanup = context.cleanupTranscript {
                await runTranscription(rawFallback: text) { try await cleanup(text) }
            } else {
                appendToDraft(text)   // launcher: raw on-device text, no agent cleanup
            }
            return
        }
        guard !micStarting else { return }
        micStarting = true
        defer { micStarting = false }
        switch await dictation.start(contextualStrings: glossary) {
        case .started: break
        case .denied: micDenied = true
        case .unavailable, .downloading, .failed:
            if case .denied = await recorder.start() { micDenied = true }
        }
    }

    /// Run a transcribe call with the "transcribing" state, then append the cleaned text. On
    /// any failure keep `rawFallback` (the on-device draft) so dictation isn't lost; only
    /// surface the failure status when there's no text to keep at all.
    private func runTranscription(rawFallback: String? = nil,
                                  _ call: @escaping () async throws -> String) async {
        transcribing = true
        defer { transcribing = false }
        let result: String
        do {
            result = try await call()
        } catch {
            if let raw = rawFallback?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
                appendToDraft(raw)
            } else {
                status = "Transcription failed"
            }
            return
        }
        let cleaned = result.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty {
            if let raw = rawFallback?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
                appendToDraft(raw)
            }
            return
        }
        appendToDraft(cleaned)
    }

    /// Append text to the draft, space-joining onto any existing draft, and request focus.
    func appendToDraft(_ text: String) {
        let existing = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        draft = existing.isEmpty ? text : existing + " " + text
        refocusToken += 1
    }

    // MARK: - Slash commands
    /// Active `/command` token at the end of the draft (cursor assumed at the end), starting at
    /// the beginning or after whitespace — mirrors web `activeSlashToken`.
    var slashQuery: String? {
        guard let r = draft.range(of: #"(?:^|\s)(/[^\s]*)$"#, options: .regularExpression) else { return nil }
        let token = draft[r].drop(while: { $0 == " " || $0 == "\n" || $0 == "\t" })
        return String(token.dropFirst()).lowercased()
    }
    /// Matches for the current `slashQuery` against the screen-provided command list. The list
    /// is passed in (not captured) so the screen's `@Observable`/`@State` source stays tracked
    /// and the menu re-renders when commands load.
    func slashMatches(in commands: [SlashCommand]) -> [SlashCommand] {
        guard let q = slashQuery else { return [] }
        return Array(commands
            .filter { q.isEmpty || $0.name.lowercased().contains(q) || $0.family.lowercased().contains(q) }
            .prefix(8))
    }
    /// Apply a tapped command: insert its text, or (control command) clear the token and signal
    /// the screen via `controlCommandToHandle`.
    func applyCommand(_ cmd: SlashCommand) {
        if cmd.action != nil {
            clearSlashToken()
            controlCommandToHandle = cmd
            return
        }
        replaceSlashToken(with: (cmd.insertText.flatMap { $0.isEmpty ? nil : $0 }) ?? (cmd.sigil + cmd.name + " "))
    }
    func replaceSlashToken(with insert: String) {
        if let r = draft.range(of: #"(?:^|\s)/[^\s]*$"#, options: .regularExpression) {
            let lead = draft[r].prefix(while: { $0 == " " || $0 == "\n" || $0 == "\t" })
            let prefixEnd = draft.index(r.lowerBound, offsetBy: lead.count)
            draft = String(draft[draft.startIndex..<prefixEnd]) + insert
        } else {
            draft = insert
        }
    }
    func clearSlashToken() { replaceSlashToken(with: "") }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run the canonical test command (Mac). Expected: `** TEST SUCCEEDED **`, all `ComposerModelTests` green (and the existing `ComposerKeyboardTests` still green).

- [ ] **Step 5: Commit**

```bash
git add apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift apps/iosApp/SupermuxTests/ComposerModelTests.swift
git commit -m "feat(ios): add ComposerModel shared composer engine + tests"
```

---

## Task 4: Add the stateless shared subviews

**Files:**
- Create: `apps/iosApp/Supermux/Chat/Composer/AttachmentTray.swift`
- Create: `apps/iosApp/Supermux/Chat/Composer/SlashMenu.swift`
- Create: `apps/iosApp/Supermux/Chat/Composer/MicButton.swift`
- Create: `apps/iosApp/Supermux/Chat/Composer/AttachMenu.swift`

- [ ] **Step 1: Create `AttachmentTray.swift`**

```swift
// apps/iosApp/Supermux/Chat/Composer/AttachmentTray.swift
import SwiftUI

/// Horizontal strip of staged-attachment chips, shared by both composers. Stateless: the
/// screen owns the `pending` array (on `ComposerModel`) and the remove action.
struct AttachmentTray: View {
    let pending: [PendingAttachment]
    let onRemove: (PendingAttachment) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) { ForEach(pending) { chip($0) } }
        }
    }

    private func chip(_ p: PendingAttachment) -> some View {
        HStack(spacing: 5) {
            Image(systemName: p.mime.hasPrefix("audio") ? "waveform" : "photo").font(.caption2)
            Text(p.filename).font(.caption2).lineLimit(1)
            Button { onRemove(p) } label: {
                Image(systemName: "xmark.circle.fill").font(.caption2)
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .background(Color(.tertiarySystemFill), in: Capsule())
        .foregroundStyle(.secondary)
    }
}
```

- [ ] **Step 2: Create `SlashMenu.swift`**

```swift
// apps/iosApp/Supermux/Chat/Composer/SlashMenu.swift
import SwiftUI
import Shared

/// The `/command` autocomplete dropdown, shared by both composers. Stateless: the screen
/// passes the current matches + apply action. `showsActionGlyph` adds the bolt marker for
/// control commands (chat shows it; the launcher's preview commands are insert-only).
struct SlashMenu: View {
    let matches: [SlashCommand]
    var showsActionGlyph: Bool = false
    let onApply: (SlashCommand) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(matches, id: \.id) { cmd in
                Button { onApply(cmd) } label: {
                    HStack(spacing: 8) {
                        Text(cmd.sigil + cmd.name).font(.callout.weight(.semibold)).foregroundStyle(Theme.teal)
                        Text(cmd.family).font(.caption2).foregroundStyle(.tertiary)
                        Spacer(minLength: 0)
                        if showsActionGlyph && cmd.action != nil {
                            Image(systemName: "bolt.fill").font(.caption2).foregroundStyle(.tertiary)
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 9).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if cmd.id != matches.last?.id { Divider() }
            }
        }
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.hairline, lineWidth: 1))
    }
}
```

- [ ] **Step 3: Create `MicButton.swift`**

```swift
// apps/iosApp/Supermux/Chat/Composer/MicButton.swift
import SwiftUI

/// Mic / on-device-dictation entry button, shared by both composers. Shows the `micStarting`
/// spinner (first-run model download) and disables during transcription.
struct MicButton: View {
    let model: ComposerModel

    var body: some View {
        Button {
            Task { await model.toggleMic() }
        } label: {
            Group {
                if model.micStarting {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "mic").font(.body.weight(.medium)).foregroundStyle(.secondary)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .disabled(model.transcribing || model.micStarting)
    }
}
```

- [ ] **Step 4: Create `AttachMenu.swift`**

```swift
// apps/iosApp/Supermux/Chat/Composer/AttachMenu.swift
import SwiftUI

/// The "+" attachment menu (Photos / Files / Camera), shared by both composers. Stateless: it
/// flips the screen-owned picker-presentation bindings; the screen wires the actual
/// `.photosPicker` / `.fileImporter` / `.fullScreenCover` modifiers.
struct AttachMenu: View {
    @Binding var showPhotos: Bool
    @Binding var showFiles: Bool
    @Binding var showCamera: Bool

    var body: some View {
        Menu {
            Button { showPhotos = true } label: { Label("Photos", systemImage: "photo") }
            Button { showFiles = true } label: { Label("Files", systemImage: "folder") }
            Button { showCamera = true } label: { Label("Camera", systemImage: "camera") }
        } label: {
            Image(systemName: "plus")
                .font(.body.weight(.medium))
                .foregroundStyle(.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
    }
}
```

- [ ] **Step 5: Regenerate + build**

Run: `cd apps/iosApp && xcodegen generate && xcodebuild build -scheme Supermux -destination 'platform=iOS Simulator,name=iPhone 16,OS=26.0'`
Expected: `** BUILD SUCCEEDED **`. (The subviews aren't referenced yet; this confirms they compile against `Theme`, `PendingAttachment`, `SlashCommand`, `ComposerModel`.)

- [ ] **Step 6: Commit**

```bash
git add apps/iosApp/Supermux/Chat/Composer/AttachmentTray.swift apps/iosApp/Supermux/Chat/Composer/SlashMenu.swift apps/iosApp/Supermux/Chat/Composer/MicButton.swift apps/iosApp/Supermux/Chat/Composer/AttachMenu.swift
git commit -m "feat(ios): add shared composer subviews (tray, slash menu, mic, attach)"
```

---

## Task 5: Refactor `ChatPane` onto `ComposerModel`

Replace `ChatPane`'s composer `@State` and private logic with the shared `ComposerModel` + subviews, **preserving** the expanding glass-pill shell, the model/reasoning pills, per-session draft persistence, the banner, the transcript and its `.equatable()` gate, sheets, and all existing comments/lifecycle.

**Files:**
- Modify: `apps/iosApp/Supermux/Chat/ChatPane.swift`

- [ ] **Step 1: Add an explicit `init` that builds the model, and swap composer state**

Delete these `@State`/helpers now owned by the model: `draft`, `pending`, `recorder`, `dictation`, `transcribing`, `micDenied`, `micStarting`, `glossary`, plus the methods `loadGlossary`, `toggleMic`, `runTranscription`, `appendToDraft`, `loadPhotos`, `handleFiles`, `addCameraImage`, `attachmentChip`, `slashQuery`, `slashMatches`, `applyCommand`, `replaceSlashToken`, `clearSlashToken`, `micButton`, `attachMenu`, and the `canSend` computed. Keep `photoItems`, `showPhotos`, `showFiles`, `showCamera`, `composing`, `modelSheet`, `reasoningSheet`, `reasoning`, and the bindings.

Add the model + static builders (static avoids capturing view `@State` at init):

```swift
@State private var composer: ComposerModel

init(broker: BrokerSession, session: SessionInfo,
     showRename: Binding<Bool>, renameText: Binding<String>,
     showKillConfirm: Binding<Bool>, banner: Binding<String?>) {
    self.broker = broker
    self.session = session
    self._showRename = showRename
    self._renameText = renameText
    self._showKillConfirm = showKillConfirm
    self._banner = banner
    self._composer = State(initialValue: ComposerModel(
        context: Self.makeContext(broker: broker, session: session),
        initialDraft: Self.loadDraft(session: session)
    ))
}

private static func makeContext(broker: BrokerSession, session: SessionInfo) -> ComposerContext {
    ComposerContext(
        glossary: { (try? await broker.fetchGlossary()) ?? [] },
        cleanupTranscript: { try await broker.transcribeDraft(sessionId: session.id, draft: $0) },
        audioFallbackTranscribe: { try await broker.transcribeAudio(sessionId: session.id, data: $0, filename: $1) }
    )
}
private static func loadDraft(session: SessionInfo) -> String {
    UserDefaults.standard.string(forKey: "cmux:draft:\(session.id)")
        ?? ProcessInfo.processInfo.environment["SM_DRAFT"] ?? ""
}
```

- [ ] **Step 2: Repoint `loadPane`, persistence, and lifecycle to the model**

```swift
private var draftKey: String { "cmux:draft:\(session.id)" }

private func loadPane() {
    Task { reasoning = await broker.reasoning(session.id) }
    Task { await composer.loadGlossary() }
}
```

Change the body modifiers:
- `.onAppear { loadPane() }` — unchanged.
- `.onDisappear { composer.cancelMic() }`
- `.onChange(of: session.id) { _, _ in composer.reconfigure(context: Self.makeContext(broker: broker, session: session), draft: Self.loadDraft(session: session)); loadPane() }`
- `.onChange(of: composer.draft) { _, new in UserDefaults.standard.set(new, forKey: draftKey) }`

Add the three view-reaction observers (anywhere in the body modifier chain):
```swift
.onChange(of: composer.refocusToken) { _, _ in composing = true }
.onChange(of: composer.status) { _, s in
    guard let s else { return }
    showBanner(s)
    composer.status = nil
}
.onChange(of: composer.controlCommandToHandle) { _, cmd in
    guard let cmd else { return }
    handleControlCommand(cmd)
    composer.controlCommandToHandle = nil
}
```

Add `showBanner` (it previously lived inline) and the control-command handler (the switch lifted from the old `applyCommand`):
```swift
private func showBanner(_ text: String) {
    banner = text
    Task { try? await Task.sleep(nanoseconds: 4_000_000_000); banner = nil }
}
private func handleControlCommand(_ cmd: SlashCommand) {
    switch cmd.action?.kind {
    case "model": modelSheet = true
    case "rename": renameText = session.name; showRename = true
    case "mute": broker.toggleMute(session)
    case "stop": broker.interrupt(session.id)
    case "kill": showKillConfirm = true
    default: break   // spawn needs navigation we don't have from chat
    }
}
```

> `composer.controlCommandToHandle` is `SlashCommand?`; `.onChange(of:)` requires `Equatable`. `SlashCommand` is a KMP `data class` and is `Equatable` in Swift. If the compiler disagrees, observe `composer.controlCommandToHandle?.id` instead and stash the command in a local `@State`.

- [ ] **Step 3: Rewire the composer UI to model + subviews**

`composerExpanded` now reads the model:
```swift
private var composerExpanded: Bool {
    composing || composer.hasContent || composer.isBusy
}
```

In `composerField`:
- Recording/dictation block: read `composer.dictation` / `composer.recorder`:
  - `if composer.dictation.isListening || composer.recorder.isRecording { ... }`
  - live transcript: `if composer.dictation.isListening && !composer.dictation.transcript.isEmpty { ScrollView { Text(composer.dictation.transcript)... } }`
  - `RecordingBar(elapsed: composer.dictation.isListening ? composer.dictation.elapsed : composer.recorder.elapsed, onStop: { Task { await composer.toggleMic() } }, onCancel: { composer.cancelMic() })`
- Attachment chips → `AttachmentTray(pending: composer.pending, onRemove: { composer.removeAttachment($0) })` (replaces the inline `pending` `ScrollView`/`attachmentChip`).
- `if transcribing` / `if micStarting` → `if composer.transcribing` / `if composer.micStarting`.
- The `TextField`:
  ```swift
  TextField("Message \(session.name)…", text: $composer.draft, axis: .vertical)
      .lineLimit(composerExpanded ? (1...12) : (1...1))
      .focused($composing)
      .composerHardwareKeyboardSubmit(canSubmit: composer.canSubmit) { sendMessage() }
  ```
- Collapsed/expanded `attachMenu` → `AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera)`.
- Collapsed/expanded `micButton` → `MicButton(model: composer)`.
- Send button `disabled(!canSend)` / background → `composer.canSubmit`.
- Slash menu (in `dock`): `if !composer.slashMatches(in: broker.commands[session.id] ?? []).isEmpty { SlashMenu(matches: composer.slashMatches(in: broker.commands[session.id] ?? []), showsActionGlyph: true) { composer.applyCommand($0) } }`. (Reading `broker.commands` here in `body` keeps Observation tracking; bind it to a local `let cmds = broker.commands[session.id] ?? []` at the top of `dock` to avoid computing twice.)

In `dock`'s modifiers, repoint the pickers:
```swift
.onChange(of: photoItems) { _, items in
    guard !items.isEmpty else { return }
    Task { await composer.loadPhotos(items); photoItems = [] }
}
.photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .images)
.fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { composer.handleFiles($0) }
.fullScreenCover(isPresented: $showCamera) { CameraPicker { composer.addCameraImage($0) } }
```

`sendMessage` consumes the model:
```swift
private func sendMessage() {
    let (text, toUpload) = composer.consume()
    Task {
        var ids: [String] = []
        for p in toUpload {
            let kind = p.mime.hasPrefix("audio") ? "voice" : nil
            if let id = await broker.upload(session.id, data: p.data, filename: p.filename, mime: p.mime, kind: kind) {
                ids.append(id)
            }
        }
        broker.send(session.id, text, attachments: ids.isEmpty ? nil : ids)
    }
}
```

Keep `SM_FOCUS`/`SM_DRAFT` debug hooks (focus still drives `composing`; draft now hydrates via `loadDraft`). Keep `pill`, `bannerView`, `transcribingBar`, `preparingBar`, `modelPillLabel`, the sheets, and `SessionTranscript` **unchanged**.

- [ ] **Step 4: Verify the typing-performance gate is intact**

Confirm `SessionTranscript` still reads **neither** `composer` nor `draft` (grep the struct). It must stay `Equatable` keyed on `session.id` so keystrokes don't rebuild it.

Run: `grep -n "composer\|draft" apps/iosApp/Supermux/Chat/ChatPane.swift` — every hit must be in `ChatPane` (above `SessionTranscript`), none inside `struct SessionTranscript`.

- [ ] **Step 5: Build + run tests + manual smoke**

Run the canonical test command + a build. Expected: `** TEST SUCCEEDED **`, `** BUILD SUCCEEDED **`. Then on the simulator/device smoke-test chat: type (fast, in a long chat — no lag/blank), `/` slash menu + apply, attach a photo/file, dictate (mic → text), send with and without an attachment, switch sessions (draft persists per session), background banner still shows.

- [ ] **Step 6: Commit**

```bash
git add apps/iosApp/Supermux/Chat/ChatPane.swift
git commit -m "refactor(ios): ChatPane uses shared ComposerModel + subviews"
```

---

## Task 6: Refactor `NewSessionView` onto `ComposerModel` (gains dictation + pickers)

Replace the launcher's local composer state + duplicated chip/slash code with `ComposerModel` + the shared subviews, and add the photo/file/camera pickers. **Keep** the launcher card, the project/agent/model/worktree pickers, and `spawn()`.

**Files:**
- Modify: `apps/iosApp/Supermux/Sessions/NewSessionView.swift`

- [ ] **Step 1: Swap composer state for the model**

Delete `@State` `draft`, `pending`, `recorder`, `micDenied`, and the methods `attachmentChip`, `slashQuery`, `slashMatches`, `applyCommand`, `replaceSlashToken`, `clearSlashToken`, `micButton`. Keep `projects`, `workdir`, `agent`, `model`, `models`, `projectSearch`, `launcherCommands`, `spawning`, the worktree state, and `composing`.

Add the model + picker bindings:
```swift
@State private var composer = ComposerModel(context: ComposerContext(
    glossary: { [] },                 // set in .task once broker is in scope (see Step 2)
    cleanupTranscript: nil,           // no session pre-spawn → raw on-device text
    audioFallbackTranscribe: nil      // no session → record-and-attach voice memo
))
@State private var showPhotos = false
@State private var showFiles = false
@State private var showCamera = false
@State private var photoItems: [PhotosPickerItem] = []
```

Add the imports the pickers need at the top of the file: `import PhotosUI` and `import UniformTypeIdentifiers`.

- [ ] **Step 2: Point the model's glossary at the broker on appear**

`ComposerContext`'s `glossary` needs `broker`, which isn't available in the property initializer. Set it in the existing `.task`:
```swift
.task {
    composer.reconfigure(context: ComposerContext(
        glossary: { (try? await broker.fetchGlossary()) ?? [] },
        cleanupTranscript: nil,
        audioFallbackTranscribe: nil
    ), draft: composer.draft)
    await composer.loadGlossary()
    projects = await broker.projects()
    // ...existing SM_WORKDIR / workdir defaulting stays...
}
```
(Glossary biases on-device dictation; `cleanupTranscript`/`audioFallbackTranscribe` stay `nil` so dictation yields raw on-device text and an unavailable-STT fallback stages a voice attachment — today's launcher behavior.)

- [ ] **Step 3: Rewire `composeCard` to model + subviews**

```swift
private var composeCard: some View {
    let cmds = launcherCommands
    return VStack(alignment: .leading, spacing: 12) {
        if !composer.pending.isEmpty {
            AttachmentTray(pending: composer.pending, onRemove: { composer.removeAttachment($0) })
        }
        if composer.dictation.isListening || composer.recorder.isRecording {
            if composer.dictation.isListening && !composer.dictation.transcript.isEmpty {
                ScrollView { Text(composer.dictation.transcript).font(.callout).frame(maxWidth: .infinity, alignment: .leading) }
                    .frame(maxHeight: 100)
            }
            RecordingBar(elapsed: composer.dictation.isListening ? composer.dictation.elapsed : composer.recorder.elapsed,
                         onStop: { Task { await composer.toggleMic() } },
                         onCancel: { composer.cancelMic() })
        }
        TextField("What should the agent do?", text: $composer.draft, axis: .vertical)
            .lineLimit(3...8).focused($composing)
            .composerHardwareKeyboardSubmit(canSubmit: canSpawn && !spawning) { spawn() }
        if !composer.slashMatches(in: cmds).isEmpty {
            SlashMenu(matches: composer.slashMatches(in: cmds), showsActionGlyph: false) { composer.applyCommand($0) }
        }
        HStack(spacing: 16) {
            // ...existing agent Menu + model Menu unchanged...
            AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera)
            MicButton(model: composer)
            Spacer()
            Button(action: spawn) {
                if spawning { ProgressView().tint(.white).frame(width: 40, height: 40) }
                else {
                    Image(systemName: "arrow.up").font(.headline.weight(.bold)).foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(canSpawn ? Theme.teal : Color.gray.opacity(0.5), in: Circle())
                }
            }
            .disabled(!canSpawn || spawning)
        }
    }
    .padding(16)
    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
}
```
(The old `if recorder.isRecording { RecordingBar(... append voice memo ...) }` block and the old `if !recorder.isRecording { micButton }` are replaced by the dictation-aware block + `MicButton` above. `canSpawn` is unchanged: `!workdir.isEmpty`. Control slash commands stay no-ops on the launcher — the model sets `controlCommandToHandle`, which the launcher never observes, so nothing happens beyond the token clearing.)

- [ ] **Step 4: Attach the pickers + refocus to the screen**

Add to the outer `ScrollView`'s modifier chain (next to the existing `.sheet`/`.alert`s):
```swift
.onChange(of: photoItems) { _, items in
    guard !items.isEmpty else { return }
    Task { await composer.loadPhotos(items); photoItems = [] }
}
.photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .images)
.fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { composer.handleFiles($0) }
.fullScreenCover(isPresented: $showCamera) { CameraPicker { composer.addCameraImage($0) } }
.onChange(of: composer.refocusToken) { _, _ in composing = true }
.alert("Microphone access needed", isPresented: $composer.micDenied) {
    Button("OK", role: .cancel) {}
} message: {
    Text("Enable microphone access for supermux in Settings to record voice messages.")
}
```
Remove the old `.alert(... isPresented: $micDenied ...)` (now `$composer.micDenied`) and the now-unused `micDenied` state.

- [ ] **Step 5: Update `spawn()` to consume the model**

```swift
private func spawn() {
    spawning = true
    let (raw, toUpload) = composer.consume()
    let firstMsg = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    let eligible = repoInfo?.eligible == true
    let wantsWorktree = eligible ? useWorktree : false
    let base = (eligible && useWorktree && !baseBranch.isEmpty) ? baseBranch : nil
    Task {
        let id = await broker.spawn(workdir: workdir, agent: agent, name: nil, model: model,
                                    worktree: wantsWorktree, baseBranch: base)
        if let id, !id.isEmpty {
            var ids: [String] = []
            for p in toUpload {
                let kind = p.mime.hasPrefix("audio") ? "voice" : nil
                if let fid = await broker.upload(id, data: p.data, filename: p.filename, mime: p.mime, kind: kind) {
                    ids.append(fid)
                }
            }
            if !firstMsg.isEmpty || !ids.isEmpty {
                broker.send(id, firstMsg, attachments: ids.isEmpty ? nil : ids)
            }
            onSpawned(id)
        }
        spawning = false
    }
}
```

- [ ] **Step 6: Build + run tests + manual smoke**

Run the canonical test command + build. Expected: green. Then smoke-test the launcher: type + `/` slash menu (insert), attach a photo/file/camera image, tap mic → dictation appends text to the field (on a dictation-capable simulator/device; otherwise it records and stages a voice attachment), pick agent/model/worktree, spawn — the new session opens with the first message + attachments sent.

- [ ] **Step 7: Commit**

```bash
git add apps/iosApp/Supermux/Sessions/NewSessionView.swift
git commit -m "refactor(ios): NewSessionView uses shared ComposerModel; gains dictation + pickers"
```

---

## Task 7: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full regenerate + clean test**

```bash
cd apps/iosApp && xcodegen generate
xcodebuild test -scheme Supermux \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=26.0' \
  -only-testing:SupermuxTests
```
Expected: `** TEST SUCCEEDED **` (all `ComposerModelTests` + `ComposerKeyboardTests` + the rest).

- [ ] **Step 2: Duplication gone**

```bash
grep -rn "func attachmentChip\|func slashMatches\|func replaceSlashToken\|func runTranscription" apps/iosApp/Supermux
```
Expected: each appears **once** (in `ComposerModel.swift`), not in `ChatPane.swift` or `NewSessionView.swift`.

- [ ] **Step 3: Performance gate intact**

```bash
awk '/struct SessionTranscript/,0' apps/iosApp/Supermux/Chat/ChatPane.swift | grep -n "composer\|\.draft"
```
Expected: no output (the transcript reads neither — the `.equatable()` typing optimization holds).

- [ ] **Step 4: Manual smoke matrix (simulator or device)**

Chat: fast typing in a long chat (no lag/blank), slash apply, attach photo/file/camera, dictate→text, send ±attachment, session switch keeps per-session draft, background/git banner shows.
Launcher: slash insert, attach photo/file/camera, dictate→text (or record→voice-attachment when STT unavailable), spawn sends first message + attachments.

- [ ] **Step 5: Confirm clean tree**

```bash
git status   # clean; all work committed across Tasks 1–6
```

---

## Self-review (author checklist — completed)

**Spec coverage:** ComposerModel (§4.1) → Task 3; ComposerContext (§4.2) → Task 2; shared subviews (§4.3) → Task 4; ChatPane refactor (§4.4) → Task 5; NewSessionView refactor + dictation/pickers (§4.5) → Task 6; session-less cleanup (§5.1) → context `nil` closures (Tasks 2/6); perf gate (§5.2) → Tasks 5.4 & 7.3; draft persistence (§5.3) → Task 5.2; expand/collapse shell-only (§5.4) → Task 5.3 (`composerExpanded`); slash actions (§5.5) → `controlCommandToHandle` (Tasks 3/5.2); PendingAttachment relocation (§5.6) → Task 1; testing (§6) → Task 3 + smoke; XcodeGen (§9) → Execution-environment + per-task regenerate. All covered.

**Placeholders:** none — every code step has full code; commands have expected output. (The "set glossary in .task" is real code in Task 6.2, not a TODO.)

**Type consistency:** `ComposerModel(context:initialDraft:)`, `reconfigure(context:draft:)`, `consume() -> (text:attachments:)`, `slashMatches(in:)`, `applyCommand(_:)`, `controlCommandToHandle`/`status`/`refocusToken`, `canSubmit`/`hasContent`/`isBusy` — used identically in the model (Task 3), ChatPane (Task 5), NewSessionView (Task 6), and tests (Task 3). Subview initializers (`AttachmentTray(pending:onRemove:)`, `SlashMenu(matches:showsActionGlyph:onApply:)`, `MicButton(model:)`, `AttachMenu(showPhotos:showFiles:showCamera:)`) match their call sites.
