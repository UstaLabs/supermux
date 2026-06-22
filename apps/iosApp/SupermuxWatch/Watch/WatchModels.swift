import Foundation

// Pure-Swift mirrors of the broker's wire DTOs (the watch can't use the KMP
// Shared.framework because SKIE doesn't support the watch device arch, arm64_32).
// Codable ignores unknown JSON keys, so we declare only the fields the watch uses.

struct SessionInfo: Decodable, Identifiable {
    let id: String
    let name: String
    var agent: String?
    var status: String?
    var connected: Bool?
}

struct Attachment: Decodable, Identifiable {
    let file_id: String
    var kind: String?
    var mime: String?
    var name: String?
    var id: String { file_id }
}

struct LogEntry: Decodable, Identifiable {
    let id: String
    let ts: String
    let direction: String
    var text: String?
    var attachments: [Attachment]?
}
