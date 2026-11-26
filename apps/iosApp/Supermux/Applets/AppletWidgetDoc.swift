//
//  AppletWidgetDoc.swift
//  Supermux
//
//  #165: an applet's widget document as the broker sends it (GET /applets → widgetDoc, copied into the snapshot by the
//  app): native blocks laid out for every size. Mirrors `:shared`'s AppletWidgetDoc.kt and, like it, reads block by
//  block, so a block type a newer broker adds is skipped instead of breaking the widget. Also the widget's own way to
//  its host for a button: the address and key the app leaves in the shared keychain group.
//
//  Compiled into BOTH the app and the widget extension (see project.yml).
//

import Foundation
import Security

/// Any JSON value, kept as it came (the snapshot's `widget`, an action's args).
enum JSONValue: Codable, Hashable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .object(let o): try c.encode(o)
        case .array(let a): try c.encode(a)
        case .null: try c.encodeNil()
        }
    }

    subscript(_ k: String) -> JSONValue? { if case .object(let o) = self { return o[k] }; return nil }
    var str: String? {
        switch self {
        case .string(let s): return s.isEmpty ? nil : s
        case .number(let n): return n == n.rounded() && abs(n) < 1e15 ? String(Int64(n)) : String(n)
        default: return nil
        }
    }
    var num: Double? { if case .number(let n) = self { return n }; return nil }
    var bool: Bool { if case .bool(let b) = self { return b }; return false }
    var arr: [JSONValue] { if case .array(let a) = self { return a }; return [] }
    var json: String { (try? String(data: JSONEncoder().encode(self), encoding: .utf8)) ?? "{}" }
}

enum WidgetSizeKey: String, CaseIterable { case small, medium, large, xlarge, wide, tall, slim }

struct WidgetButtonRef: Hashable {
    var title: String
    var action: String
    var args: JSONValue?
    var fill: Bool
}

struct WidgetRow: Hashable {
    var title: String
    var sub: String?
    var value: String?
    var color: String?
    var href: String?
    var button: WidgetButtonRef?
}

struct WidgetToggle: Hashable {
    var title: String
    var on: Bool
    var sub: String?
    var action: String
    var args: JSONValue?
}

indirect enum WidgetBlock: Hashable {
    case header(title: String?, sub: String?)
    case stat(value: String, label: String?, trend: String?, tone: String?, size: String?)
    case stats([(value: String, label: String?, tone: String?)])
    case progress(value: Double, label: String?, right: String?)
    case ring(value: Double, text: String?, sub: String?)
    case chart(line: Bool, points: [Double], labels: [String], label: String?)
    case list(title: String?, rows: [WidgetRow])
    case toggles(grid: Bool, items: [WidgetToggle])
    case buttons([WidgetButtonRef])
    case picture(part: String, height: Double, label: String, caption: String?, alt: WidgetBlock?)
    case text(String)
    case columns([[WidgetBlock]], weights: [Double])
    case gap(String?)
    case grow

    static func == (a: WidgetBlock, b: WidgetBlock) -> Bool { String(describing: a) == String(describing: b) }
    func hash(into h: inout Hasher) { h.combine(String(describing: self)) }
}

struct WidgetDoc {
    var href: String?
    var fullSnapshot: Bool
    var label: String?
    var layouts: [WidgetSizeKey: [WidgetBlock]]
    var circular: (text: String, ring: Double?, label: String?, action: WidgetButtonRef?)
    var rectangular: (lines: [String], button: WidgetButtonRef?)
    var inline: String
    var timeline: [(at: Date, layouts: [WidgetSizeKey: [WidgetBlock]])]

    /// The blocks [size] shows at [date] (a timeline entry that has begun overrides it).
    func layout(_ size: WidgetSizeKey, at date: Date = .now) -> [WidgetBlock] {
        timeline.last(where: { $0.at <= date && $0.layouts[size] != nil })?.layouts[size] ?? layouts[size] ?? []
    }

    /// The times ahead at which the widget changes on its own (its timeline's entries).
    func changes(after date: Date) -> [Date] { timeline.map(\.at).filter { $0 > date } }

    /// Each picture [size] shows, with the share of the widget's inner width it gets.
    func pictureSlots(_ size: WidgetSizeKey) -> [(part: String, height: Double, caption: Bool, share: Double)] {
        var out: [(String, Double, Bool, Double)] = []
        func walk(_ bs: [WidgetBlock], _ share: Double) {
            for b in bs {
                switch b {
                case .picture(let part, let height, _, let caption, _): if !out.contains(where: { $0.0 == part }) { out.append((part, height, caption != nil, share)) }
                case .columns(let cols, let w): let t = w.reduce(0, +); for (i, c) in cols.enumerated() { walk(c, share * w[i] / (t > 0 ? t : 1)) }
                default: break
                }
            }
        }
        walk(layouts[size] ?? [], 1)
        for t in timeline { walk(t.layouts[size] ?? [], 1) }
        return out
    }

    static func parse(_ v: JSONValue?) -> WidgetDoc? {
        guard let v, let l = v["layouts"] else { return nil }
        let layouts = sizes(l)
        if layouts.isEmpty { return nil }
        let c = l["circular"], r = l["rectangular"]
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return WidgetDoc(
            href: v["href"]?.str,
            fullSnapshot: v["snapshot"]?.str == "full",
            label: v["label"]?.str,
            layouts: layouts,
            circular: (c?["text"]?.str ?? "", c?["ring"]?.num, c?["label"]?.str, c?["action"].flatMap { a in a["action"]?.str.map { WidgetButtonRef(title: "", action: $0, args: a["args"], fill: false) } }),
            rectangular: ((r?["lines"]?.arr ?? []).compactMap(\.str), button(r?["button"])),
            inline: l["inline"]?.str ?? "",
            timeline: (v["timeline"]?.arr ?? []).compactMap { t in
                guard let s = t["at"]?.str, let at = iso.date(from: s) ?? ISO8601DateFormatter().date(from: s), let ls = t["layouts"] else { return nil }
                let sz = sizes(ls)
                return sz.isEmpty ? nil : (at, sz)
            }.sorted { $0.0 < $1.0 }
        )
    }

    private static func sizes(_ l: JSONValue) -> [WidgetSizeKey: [WidgetBlock]] {
        var out: [WidgetSizeKey: [WidgetBlock]] = [:]
        for k in WidgetSizeKey.allCases { if case .array(let a) = l[k.rawValue] { out[k] = a.compactMap(block) } }
        return out
    }

    private static func frac(_ v: JSONValue?) -> Double { min(1, max(0, v?.num ?? 0)) }

    private static func button(_ v: JSONValue?) -> WidgetButtonRef? {
        guard let v, let title = v["title"]?.str, let action = v["action"]?.str else { return nil }
        return WidgetButtonRef(title: title, action: action, args: v["args"], fill: v["style"]?.str == "fill")
    }

    private static func block(_ b: JSONValue) -> WidgetBlock? {
        switch b["type"]?.str {
        case "header": return .header(title: b["title"]?.str, sub: b["sub"]?.str)
        case "stat": return b["value"]?.str.map { .stat(value: $0, label: b["label"]?.str, trend: b["trend"]?.str, tone: b["tone"]?.str, size: b["size"]?.str) }
        case "stats": return .stats((b["items"]?.arr ?? []).compactMap { i in i["value"]?.str.map { ($0, i["label"]?.str, i["tone"]?.str) } })
        case "progress": return .progress(value: frac(b["value"]), label: b["label"]?.str, right: b["right"]?.str)
        case "ring": return .ring(value: frac(b["value"]), text: b["text"]?.str, sub: b["sub"]?.str)
        case "chart":
            let pts = (b["points"]?.arr ?? []).compactMap(\.num)
            return pts.count < 2 ? nil : .chart(line: b["kind"]?.str == "line", points: pts, labels: (b["labels"]?.arr ?? []).compactMap(\.str), label: b["label"]?.str)
        case "list":
            return .list(title: b["title"]?.str, rows: (b["rows"]?.arr ?? []).compactMap { r in
                r["title"]?.str.map { WidgetRow(title: $0, sub: r["sub"]?.str, value: r["value"]?.str, color: r["color"]?.str, href: r["href"]?.str, button: button(r["button"])) }
            })
        case "toggles":
            return .toggles(grid: b["grid"]?.bool ?? false, items: (b["items"]?.arr ?? []).compactMap { t in
                guard let title = t["title"]?.str, let action = t["action"]?.str else { return nil }
                return WidgetToggle(title: title, on: t["on"]?.bool ?? false, sub: t["sub"]?.str, action: action, args: t["args"])
            })
        case "buttons": return .buttons((b["items"]?.arr ?? []).compactMap(button))
        case "html":
            guard let part = b["part"]?.str, let label = b["label"]?.str else { return nil }
            return .picture(part: part, height: b["height"]?.num ?? 60, label: label, caption: b["caption"]?.str, alt: b["alt"].flatMap(block))
        case "text": return b["text"]?.str.map { .text($0) }
        case "columns":
            let cols = (b["cols"]?.arr ?? []).map { $0.arr.compactMap(block) }
            if cols.isEmpty { return nil }
            let w = (b["w"]?.arr ?? []).compactMap(\.num)
            return .columns(cols, weights: w.count == cols.count ? w : cols.map { _ in 1 })
        case "gap": return .gap(b["size"]?.str)
        case "grow": return .grow
        default: return nil
        }
    }
}

/// A picture's file beside the snapshot (`widgetPictureFile` in AppletWidgetDoc.kt: FNV-1a 64 of the same string).
func widgetPictureFile(host: String, applet: String, size: WidgetSizeKey, part: String, dark: Bool) -> String {
    "pic-\(fnv64("\(host)/\(applet)/\(size.rawValue)/\(part)/\(dark ? "dark" : "light")")).png"
}

func fnv64(_ s: String) -> String {
    var h: UInt64 = 0xcbf29ce484222325
    for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
    let hex = String(h, radix: 16)
    return String(repeating: "0", count: 16 - hex.count) + hex
}

/// The paired host's address and key for a widget's own call (the app keeps them in the shared keychain group).
enum WidgetHostAccess {
    static let service = "dev.supermux.widgets"
    static let group = "57L7J9XA89.dev.supermux.app.push"

    static func credentials(host: String) -> (url: String, token: String)? {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: host,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        q[kSecAttrAccessGroup as String] = group
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data,
              let v = try? JSONDecoder().decode([String: String].self, from: data),
              let url = v["url"], let token = v["token"] else { return nil }
        return (url, token)
    }

    enum Failure: Error, LocalizedError {
        case notPaired, refused(String)
        var errorDescription: String? {
            switch self {
            case .notPaired: return String(localized: "Open supermux to see your applets.")
            case .refused(let why): return why
            }
        }
    }

    /// POST /applets/<name>/widget/actions/<action>: the applet's new widget document, written into the snapshot.
    static func run(host: String, applet: String, action: String, argsJSON: String) async throws {
        guard let c = credentials(host: host) else { throw Failure.notPaired }
        let enc = { (s: String) in s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/?#"))) ?? s }
        guard let url = URL(string: "\(c.url.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/applets/\(enc(applet))/widget/actions/\(enc(action))") else { throw Failure.notPaired }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.httpMethod = "POST"
        req.setValue("Bearer \(c.token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = "{\"args\":\(argsJSON.isEmpty ? "{}" : argsJSON)}".data(using: .utf8)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let body = (try? JSONDecoder().decode(JSONValue.self, from: data))
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw Failure.refused(body?["error"]?.str ?? "HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)")
        }
        if let doc = body?["widget"], doc != .null { AppletSnapshot.putWidget(host: host, applet: applet, doc: doc) }
    }
}
