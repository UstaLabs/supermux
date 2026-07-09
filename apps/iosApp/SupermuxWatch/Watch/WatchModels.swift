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
    var mute: Bool?
    // Watch session-list enrichment (GET /sessions; see watch-session-row.ts):
    var phase: String?
    var tool: String?
    var waiting: Bool?      // idle but background tasks still open (turn will resume)
    var bgOpen: Int?        // open background-task count
    var lastText: String?
    var lastTs: String?
    var lastFrom: String?
    var unread: Bool?
    var git: GitLite?

    enum CodingKeys: String, CodingKey {
        case id, name, agent, status, connected, mute, phase, tool, waiting, bgOpen, lastText, lastTs, lastFrom, unread, git
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.name = try c.decode(String.self, forKey: .name)
        // GET /sessions (SessionSnapshot) has an optional id; fall back to name.
        self.id = (try? c.decode(String.self, forKey: .id)) ?? name
        self.agent = try? c.decode(String.self, forKey: .agent)
        self.status = try? c.decode(String.self, forKey: .status)
        self.connected = try? c.decode(Bool.self, forKey: .connected)
        self.mute = try? c.decode(Bool.self, forKey: .mute)
        self.phase = try? c.decode(String.self, forKey: .phase)
        self.tool = try? c.decode(String.self, forKey: .tool)
        self.waiting = try? c.decode(Bool.self, forKey: .waiting)
        self.bgOpen = try? c.decode(Int.self, forKey: .bgOpen)
        self.lastText = try? c.decode(String.self, forKey: .lastText)
        self.lastTs = try? c.decode(String.self, forKey: .lastTs)
        self.lastFrom = try? c.decode(String.self, forKey: .lastFrom)
        self.unread = try? c.decode(Bool.self, forKey: .unread)
        self.git = try? c.decode(GitLite.self, forKey: .git)
    }
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
