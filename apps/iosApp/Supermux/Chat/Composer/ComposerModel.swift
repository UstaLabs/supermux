// apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift
import SwiftUI
import Shared
import PhotosUI
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import CoreTransferable

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

    /// Swap only the injected IO, leaving draft/attachments/mic untouched. The launcher binds
    /// its glossary closure to the broker via this in `.task`, without resetting state (unlike
    /// `reconfigure`, which is the session-switch reset).
    func setContext(_ context: ComposerContext) { self.context = context }

    // MARK: - Derived
    // Trims newlines too (the original chat `canSend` used `.whitespaces`, leaving a
    // newline-only draft "sendable" — a latent quirk; this aligns with the launcher's
    // `.whitespacesAndNewlines` send-trim so both screens treat whitespace-only as empty).
    // Blocked while any attachment is still uploading or has failed — never send a message
    // minus its attachment (the old silent drop). The user retries or removes the failed chip.
    var canSubmit: Bool {
        let noBlocking = !pending.contains { $0.uploading || $0.failed }
        return noBlocking && (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !pending.isEmpty)
    }
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

    // MARK: - Per-attachment upload state (driven by the screen's send-time upload loop)
    private func mutate(_ id: UUID, _ f: (inout PendingAttachment) -> Void) {
        if let i = pending.firstIndex(where: { $0.id == id }) { f(&pending[i]) }
    }
    func markUploading(_ id: UUID) { mutate(id) { $0.uploading = true; $0.failed = false } }
    func setProgress(_ id: UUID, _ p: Double) { mutate(id) { $0.progress = max(0, min(1, p)) } }
    func markUploaded(_ id: UUID, _ fileId: String) { mutate(id) { $0.uploading = false; $0.progress = 1; $0.uploadedFileId = fileId } }
    func markFailed(_ id: UUID) { mutate(id) { $0.uploading = false; $0.failed = true } }

    /// Derive the upload MIME + filename for a picked photo-library item from the content types
    /// it advertises. A movie item (any `UTType` conforming to `.movie` — e.g. `public.movie`,
    /// `com.apple.quicktime-movie`, `public.mpeg-4`) keeps its real video type + extension, so a
    /// picked video uploads as `video/*` instead of the old hardcoded `image/jpeg`. Everything
    /// else is staged as a JPEG still (Photos hands JPEG `Data` back for images — the design
    /// keeps images as image/jpeg). Pure + `static` (takes the item's `supportedContentTypes`, no
    /// `PhotosPickerItem`) so it's unit-testable without a live photo library — the same split as
    /// `addPastedImage`.
    static func attachmentMeta(for contentTypes: [UTType], number: Int) -> (filename: String, mime: String) {
        if let movie = contentTypes.first(where: { $0.conforms(to: .movie) }) {
            let ext = movie.preferredFilenameExtension ?? "mov"
            let mime = movie.preferredMIMEType ?? "video/quicktime"
            return (filename: "video-\(number).\(ext)", mime: mime)
        }
        return (filename: "image-\(number).jpg", mime: "image/jpeg")
    }

    /// Load picked photos into `pending`. The screen clears its `PhotosPickerItem` selection
    /// after awaiting this.
    func loadPhotos(_ items: [PhotosPickerItem]) async {
        for item in items {
            let meta = Self.attachmentMeta(for: item.supportedContentTypes, number: pending.count + 1)
            // Videos stage as a temp file URL (streamed in chunks — bounded RAM); images as JPEG Data.
            if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
                if let movie = try? await item.loadTransferable(type: MovieFile.self) {
                    pending.append(PendingAttachment(fileURL: movie.url, filename: meta.filename, mime: meta.mime))
                }
            } else if let data = try? await item.loadTransferable(type: Data.self) {
                pending.append(PendingAttachment(data: data, filename: meta.filename, mime: meta.mime))
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
    func addCameraImage(_ img: PlatformImage) {
        if let data = img.smJpegData(quality: 0.85) {
            pending.append(PendingAttachment(data: data, filename: "photo-\(pending.count + 1).jpg", mime: "image/jpeg"))
        }
    }

    /// Stage a movie recorded by the camera (a temp file URL from `UIImagePickerController`).
    /// Reads the clip into `Data` (Phase 1: the shared upload takes bytes; true streaming is a
    /// separate KMP change) and labels it with the file's real video MIME + extension.
    func addCameraVideo(_ url: URL) {
        let ext = url.pathExtension.isEmpty ? "mov" : url.pathExtension
        let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "video/quicktime"
        // Copy the picker's transient URL to a stable temp file we own, then stream from it.
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + ext)
        try? FileManager.default.removeItem(at: dest)
        guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { return }
        pending.append(PendingAttachment(fileURL: dest,
                                         filename: "video-\(pending.count + 1).\(ext)",
                                         mime: mime))
    }

    // MARK: - Paste
    /// Stage whatever is pasteable on the system clipboard as attachment(s), mirroring the web
    /// composer's paste handler (`PromptInputTextarea.handlePaste`): images first — the common
    /// "copied a screenshot / photo" case — otherwise any copied file data (e.g. a PDF) via item
    /// providers. Plain text and URLs are skipped here so the text field keeps pasting those as
    /// text; they never reach this path. Reuses the same `pending` staging as the +-menu pickers.
    #if canImport(UIKit)
    func pasteClipboard(_ pasteboard: UIPasteboard = .general) async {
        if let images = pasteboard.images, !images.isEmpty {
            for image in images { addPastedImage(image) }
            return
        }
        for provider in pasteboard.itemProviders {
            await addPastedFile(provider)
        }
    }
    #else
    /// Mac analog: NSPasteboard has no item-provider API — stage the pasteboard image if there
    /// is one, otherwise read copied file URLs (Finder copies) and stage their data.
    func pasteClipboard() async {
        if let image = SMPasteboard.image {
            addPastedImage(image)
            return
        }
        // File URLs ONLY (both belts): a plain web URL here would turn `Data(contentsOf:)`
        // into a synchronous network fetch on the main actor.
        guard let urls = NSPasteboard.general.readObjects(
            forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] else { return }
        for url in urls where url.isFileURL {
            guard let data = try? Data(contentsOf: url) else { continue }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            pending.append(PendingAttachment(data: data, filename: url.lastPathComponent, mime: mime))
        }
    }
    #endif

    /// Stage a single pasted image as a JPEG attachment. Split out (and non-private) so the
    /// staging/filename logic is unit-testable without a live `UIPasteboard`.
    func addPastedImage(_ image: PlatformImage) {
        guard let data = image.smJpegData(quality: 0.9) else { return }
        pending.append(PendingAttachment(data: data,
                                         filename: "pasted-\(pending.count + 1).jpg",
                                         mime: "image/jpeg"))
    }

    /// Stage a pasted non-image file (e.g. a copied PDF) by loading the first non-text, non-URL
    /// content type the provider advertises. Text/URL identifiers are skipped so they keep
    /// pasting as text; file-URL references (Files-app copies) fall through to the +-menu picker.
    private func addPastedFile(_ provider: NSItemProvider) async {
        guard let typeId = provider.registeredTypeIdentifiers.first(where: {
            guard let type = UTType($0) else { return false }
            return !type.conforms(to: .text) && !type.conforms(to: .url)
        }), let utType = UTType(typeId) else { return }
        let data: Data? = await withCheckedContinuation { continuation in
            provider.loadDataRepresentation(forTypeIdentifier: typeId) { data, _ in
                continuation.resume(returning: data)
            }
        }
        guard let data else { return }
        let ext = utType.preferredFilenameExtension ?? "dat"
        let mime = utType.preferredMIMEType ?? "application/octet-stream"
        pending.append(PendingAttachment(data: data,
                                         filename: "pasted-\(pending.count + 1).\(ext)",
                                         mime: mime))
    }

    // MARK: - Mic / dictation
    // Prefer broker STT (codex-realtime / whisper via multipart /transcribe) — same path as web +
    // desktop. On-device Speech recognition is kept behind [enableOnDeviceSTT] for offline /
    // no-engine environments; when off, mic always records audio and POSTs it.
    // The recording path's terminal action is context-driven: chat transcribes the clip into
    // text; the launcher (no session) stages it as a voice attachment when no transcribe hook
    // is wired.
    /// When true, try Apple on-device speech first and only fall back to audio upload.
    /// Off by default so clients hit the broker's pluggable STT engine (voiceSttEngine).
    static let enableOnDeviceSTT = false

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

        if !Self.enableOnDeviceSTT {
            if case .denied = await recorder.start() { micDenied = true }
            return
        }

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

/// A `Transferable` that materializes a picked video as a temp file URL (copied out of the
/// system's transient location) instead of loading it into `Data` — so a large video streams
/// from disk in chunks. Used by `loadPhotos` for movie items.
struct MovieFile: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "." + ext)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return MovieFile(url: dest)
        }
    }
}
