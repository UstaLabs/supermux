// #167: sharing from another app straight to an agent. Compiled into the app AND the share extension: the targets the
// app writes into the App Group, the queued shares (the extension queues, either side sends), the host's address and
// key from the shared keychain, the send itself (uploads, then one idempotent POST /sessions/<id>/send), and the
// INSendMessageIntent donation that puts the agent in the share sheet's top row.
import Foundation
import Intents
import UIKit
import UniformTypeIdentifiers

/// One chat the share sheet offers (written by the app, ShareTargets in :shared, as share/targets.json).
struct ShareTargetRow: Codable, Hashable, Identifiable {
    let hostId: String
    let hostName: String
    let sessionId: String
    let name: String
    let isAgent: Bool
    var isDefault: Bool? = false
    var avatar: String? = nil
    var preview: String? = nil
    var lastAt: Int64? = 0
    var id: String { "\(hostId)/\(sessionId)" }
}

struct ShareFile: Codable, Hashable {
    /// Relative to the App Group's share/ folder.
    var path: String
    var name: String
    var mime: String
    var size: Int64
    var fileId: String? = nil
}

/// A share waiting to go out. The broker accepts one clientMessageId once, so a retry never posts twice.
struct ShareJobRow: Codable {
    var clientMessageId: String
    var hostId: String
    var sessionId: String
    var targetName: String
    var message: String
    var texts: [String]
    var files: [ShareFile]
    var createdAt: Double
    var attempts: Int = 0

    /// The message the agent reads: the user's words, then each shared text or link (as Android's ShareJob.text()).
    var text: String {
        var seen = Set<String>()
        return ([message] + texts).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
            .joined(separator: "\n\n")
    }

    static func newId() -> String {
        let alphabet = Array("abcdefghijklmnopqrstuvwxyz0123456789")
        return "web-share-\(Int64(Date().timeIntervalSince1970 * 1000))-" + String((0..<10).map { _ in alphabet.randomElement()! })
    }
}

enum ShareStore {
    static let appGroup = "group.dev.supermux.app"

    /// share/ in the group container; nil in a build without the App Group.
    static var root: URL? {
        guard let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return nil }
        let dir = base.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func read<T: Decodable>(_ name: String, _ type: T.Type) -> T? {
        guard let url = root?.appendingPathComponent(name), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private static func write<T: Encodable>(_ name: String, _ value: T) {
        guard let url = root?.appendingPathComponent(name), let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    /// Agents first (the default, then the most recent), then the other sessions — the app already ordered them;
    /// a share made since moves its chat up.
    static func targets() -> [ShareTargetRow] {
        let all = read("targets.json", [ShareTargetRow].self) ?? []
        let recent = read("recent.json", [String: Int64].self) ?? [:]
        return all.map { t in
            var t = t
            if let r = recent[t.id], r > (t.lastAt ?? 0) { t.lastAt = r }
            return t
        }.sorted { a, b in
            if a.isAgent != b.isAgent { return a.isAgent }
            if (a.isDefault ?? false) != (b.isDefault ?? false) { return a.isDefault ?? false }
            if (a.lastAt ?? 0) != (b.lastAt ?? 0) { return (a.lastAt ?? 0) > (b.lastAt ?? 0) }
            return a.name.lowercased() < b.name.lowercased()
        }
    }

    static func recordSent(_ job: ShareJobRow) {
        var recent = read("recent.json", [String: Int64].self) ?? [:]
        recent["\(job.hostId)/\(job.sessionId)"] = Int64(Date().timeIntervalSince1970 * 1000)
        write("recent.json", recent)
    }

    // The outbox is read and written under a file coordinator: the app may drain it while the extension adds to it.
    private static func coordinated<T>(_ body: (URL) -> T) -> T? {
        guard let url = root?.appendingPathComponent("outbox.json") else { return nil }
        var result: T?
        var err: NSError?
        NSFileCoordinator().coordinate(writingItemAt: url, options: [], error: &err) { u in result = body(u) }
        return result
    }

    static func outbox() -> [ShareJobRow] {
        coordinated { u in (try? Data(contentsOf: u)).flatMap { try? JSONDecoder().decode([ShareJobRow].self, from: $0) } ?? [] } ?? []
    }

    static func put(_ job: ShareJobRow) {
        _ = coordinated { u in
            var jobs = (try? Data(contentsOf: u)).flatMap { try? JSONDecoder().decode([ShareJobRow].self, from: $0) } ?? []
            jobs.removeAll { $0.clientMessageId == job.clientMessageId }
            jobs.append(job)
            if let data = try? JSONEncoder().encode(jobs) { try? data.write(to: u, options: .atomic) }
        }
    }

    static func remove(_ job: ShareJobRow) {
        _ = coordinated { u in
            var jobs = (try? Data(contentsOf: u)).flatMap { try? JSONDecoder().decode([ShareJobRow].self, from: $0) } ?? []
            jobs.removeAll { $0.clientMessageId == job.clientMessageId }
            if let data = try? JSONEncoder().encode(jobs) { try? data.write(to: u, options: .atomic) }
        }
        if let dir = root?.appendingPathComponent(job.clientMessageId, isDirectory: true) { try? FileManager.default.removeItem(at: dir) }
    }
}

/// The paired host's address and this device's key, kept by the app in the keychain group it shares with its extensions
/// (WidgetCredentials in :shared; the widgets read the same item).
enum ShareHostAccess {
    static let service = "dev.supermux.widgets"
    static let group = "57L7J9XA89.dev.supermux.app.push"

    static func credentials(host: String) -> (url: String, token: String)? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: host,
            kSecAttrAccessGroup as String: group,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data,
              let v = try? JSONDecoder().decode([String: String].self, from: data),
              let url = v["url"], let token = v["token"] else { return nil }
        return (url.hasPrefix("ws") ? "http" + url.dropFirst(2) : url, token)
    }
}

enum ShareSend {
    enum Outcome {
        case sent
        /// Offline or the host didn't answer: keep it (with the uploads that made it) and try again later.
        case retry(ShareJobRow, String)
        /// The host refused it for good (the chat is gone, a file it won't take).
        case failed(String)
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    private static func errorText(_ data: Data, _ status: Int) -> String {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? String ?? "HTTP \(status)"
    }

    private static func isRetryable(_ status: Int) -> Bool { status >= 500 || status == 408 || status == 429 }

    static func send(_ job: ShareJobRow) async -> Outcome {
        var job = job
        job.attempts += 1
        guard let c = ShareHostAccess.credentials(host: job.hostId), let root = ShareStore.root else {
            return .failed(String(localized: "Open supermux once, then share again."))
        }
        let base = c.url.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        do {
            for i in job.files.indices where job.files[i].fileId == nil {
                let f = job.files[i]
                let fileURL = root.appendingPathComponent(f.path)
                guard FileManager.default.fileExists(atPath: fileURL.path), let url = URL(string: "\(base)/upload") else {
                    return .failed(String(format: String(localized: "%@ is no longer there"), f.name))
                }
                var req = URLRequest(url: url, timeoutInterval: 600)
                req.httpMethod = "POST"
                req.setValue("Bearer \(c.token)", forHTTPHeaderField: "Authorization")
                req.setValue(job.sessionId, forHTTPHeaderField: "X-Mux-Session")
                req.setValue(f.mime, forHTTPHeaderField: "X-Mux-Mime")
                req.setValue(f.name.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? "file", forHTTPHeaderField: "X-Mux-Filename")
                req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                let (data, resp) = try await session.upload(for: req, fromFile: fileURL)
                let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
                guard (200..<300).contains(status),
                      let id = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["file_id"] as? String else {
                    return isRetryable(status) ? .retry(job, errorText(data, status)) : .failed(errorText(data, status))
                }
                job.files[i].fileId = id
            }
            let enc = job.sessionId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/?#"))) ?? job.sessionId
            guard let url = URL(string: "\(base)/sessions/\(enc)/send") else { return .failed("bad address") }
            var req = URLRequest(url: url, timeoutInterval: 30)
            req.httpMethod = "POST"
            req.setValue("Bearer \(c.token)", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: [
                "text": job.text,
                "attachments": job.files.compactMap { $0.fileId },
                "client_message_id": job.clientMessageId,
            ])
            let (data, resp) = try await session.data(for: req)
            let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if (200..<300).contains(status) { return .sent }
            return isRetryable(status) ? .retry(job, errorText(data, status)) : .failed(errorText(data, status))
        } catch {
            return .retry(job, error.localizedDescription)
        }
    }

    /// Sends what is still queued (the app, when it comes to the foreground). Returns how many went out.
    @discardableResult
    static func drain() async -> Int {
        var sent = 0
        for job in ShareStore.outbox() {
            switch await send(job) {
            case .sent:
                ShareStore.recordSent(job)
                ShareStore.remove(job)
                ShareDonation.donate(hostId: job.hostId, sessionId: job.sessionId, name: job.targetName)
                sent += 1
            case .retry(let next, _):
                if next.attempts >= 20 { ShareStore.remove(next) } else { ShareStore.put(next) }
            case .failed:
                ShareStore.remove(job)
            }
        }
        return sent
    }
}

/// Puts an agent in the share sheet's suggestions row: an outgoing INSendMessageIntent to it as a conversation.
enum ShareDonation {
    static func donate(hostId: String, sessionId: String, name: String) {
        let key = "\(hostId)/\(sessionId)"
        let image = INImage(imageData: avatarPNG(name))
        let person = INPerson(
            personHandle: INPersonHandle(value: key, type: .unknown),
            nameComponents: nil, displayName: name, image: image, contactIdentifier: nil, customIdentifier: key
        )
        let intent = INSendMessageIntent(
            recipients: [person], outgoingMessageType: .outgoingMessageText, content: nil,
            speakableGroupName: INSpeakableString(spokenPhrase: name), conversationIdentifier: key,
            serviceName: nil, sender: nil, attachments: nil
        )
        intent.setImage(image, forParameterNamed: \.speakableGroupName)
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .outgoing
        interaction.donate(completion: nil)
    }

    /// The agent's initials on its colour (the same hue rule as Android's Direct Share icons).
    static func avatarPNG(_ name: String) -> Data {
        let size = CGSize(width: 120, height: 120)
        let initials = name.split(separator: " ").filter { $0.first?.isUppercase == true }.prefix(2).map { String($0.prefix(1)) }.joined()
        var h = Int32(0)
        for u in name.unicodeScalars { h = h &* 31 &+ Int32(bitPattern: u.value) }
        let hue = CGFloat(Int(h) & 0xffff).truncatingRemainder(dividingBy: 360) / 360
        return UIGraphicsImageRenderer(size: size).pngData { ctx in
            UIColor(hue: hue, saturation: 0.45, brightness: 0.62, alpha: 1).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let text = (initials.isEmpty ? String(name.prefix(1)).uppercased() : initials) as NSString
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 44, weight: .bold), .foregroundColor: UIColor.white]
            let s = text.size(withAttributes: attrs)
            text.draw(at: CGPoint(x: (size.width - s.width) / 2, y: (size.height - s.height) / 2), withAttributes: attrs)
        }
    }
}
