// apps/iosApp/Supermux/Chat/Composer/PendingAttachment.swift
import Foundation

/// A photo, file, or audio clip staged in the composer, awaiting upload on send/spawn.
/// Backed by either in-memory `data` (images/audio — small, single-POST) or a local
/// `fileURL` (videos/large files — streamed in chunks via NSFileHandleChunkSource so a
/// big video never loads whole into RAM). The `uploading`/`progress`/`failed` fields drive
/// the AttachmentTray chip during the send-time upload; `uploadedFileId` lets a retry skip
/// already-finished uploads. Shared by the chat composer and the new-session launcher.
struct PendingAttachment: Identifiable {
    let id = UUID()
    var data: Data? = nil
    var fileURL: URL? = nil
    let filename: String
    let mime: String
    var uploading = false
    var progress: Double = 0
    var failed = false
    var uploadedFileId: String? = nil

    init(data: Data, filename: String, mime: String) {
        self.data = data; self.filename = filename; self.mime = mime
    }
    init(fileURL: URL, filename: String, mime: String) {
        self.fileURL = fileURL; self.filename = filename; self.mime = mime
    }
}
