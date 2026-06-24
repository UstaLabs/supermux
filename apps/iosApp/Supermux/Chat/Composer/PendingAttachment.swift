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
