// apps/iosApp/Supermux/Chat/Composer/ComposerContext.swift
import Foundation

/// Per-screen IO injected into `ComposerModel`. Only the things that genuinely differ
/// between the chat composer and the new-session launcher live here; everything else is
/// shared logic on the model. All three closures capture at most `broker` + a session id,
/// so the chat screen rebuilds this (via `ComposerModel.reconfigure`) whenever the session
/// switches; the launcher builds it once.
struct ComposerContext {
    /// Project/technical-term glossary: used as on-device contextual bias when
    /// `ComposerModel.enableOnDeviceSTT` is on, and always available to the broker cleanup
    /// prompt. Session-less (`broker.fetchGlossary`), identical on both screens.
    var glossary: () async -> [String] = { [] }

    /// Agent cleanup of an on-device dictation draft (only when on-device STT is enabled).
    /// Both screens wire this to `transcribeDraft(sessionId:)` — chat with its session id,
    /// the launcher with `nil` (id-less `/transcribe`). `nil` means "use the raw transcript".
    var cleanupTranscript: ((String) async throws -> String)? = nil

    /// Broker STT of a recorded clip (codex-realtime / whisper via multipart `/transcribe`).
    /// This is the default mic path when on-device STT is off. Both screens wire this to
    /// `transcribeAudio(sessionId:)` (launcher passes `nil`). When `nil` here, a recorded
    /// clip is instead staged as a voice attachment.
    var audioFallbackTranscribe: ((Data, String) async throws -> String)? = nil
}
