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
