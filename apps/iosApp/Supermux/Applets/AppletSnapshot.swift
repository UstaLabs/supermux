//
//  AppletSnapshot.swift
//  Supermux
//
//  #117 W1: what the applet widgets, the Control Center control and Spotlight know about applets: the snapshot the
//  app writes into the App Group (`IosAppletWidgets.kt`; the JSON is `:shared`'s `WidgetHost`), one file per host,
//  host-<key>.json, plus `active` (the host picker's host) and the icons it names. Widgets are snapshots, never live
//  views: the app rewrites this whenever it sees its applets change.
//
//  Compiled into BOTH the app and the widget extension (see project.yml).
//

import Foundation
import SwiftUI
import UIKit

struct AppletSummary: Codable, Hashable {
    var value: String?
    var label: String?
    var lines: [String]?
}

struct WidgetApplet: Codable, Hashable {
    var name: String
    var title: String
    var needs: Bool?
    var pin: Int?
    var summary: AppletSummary?
    var iconFile: String?
    /// #165: its widget document (AppletWidgetDoc.swift); nil: the widget draws [summary] as before.
    var widget: JSONValue?
    /// Its picture page, relative to the host; the app draws its pictures from it.
    var widgetHtml: String?
    var accent: String?
    /// Actions to confirm in the applet rather than run from the widget.
    var confirm: [String]?
    /// Pictures the app has drawn, by "<size>/<part>/<light|dark>" → file name.
    var pictures: [String: String]?
    /// Why the last widget tap failed (written by the widget's intent; gone with the next snapshot).
    var widgetError: String?

    init(name: String, title: String, needs: Bool? = nil, pin: Int? = nil, summary: AppletSummary? = nil, iconFile: String? = nil,
         widget: JSONValue? = nil, widgetHtml: String? = nil, accent: String? = nil, confirm: [String]? = nil, pictures: [String: String]? = nil) {
        self.name = name; self.title = title; self.needs = needs; self.pin = pin; self.summary = summary; self.iconFile = iconFile
        self.widget = widget; self.widgetHtml = widgetHtml; self.accent = accent; self.confirm = confirm; self.pictures = pictures
    }

    var doc: WidgetDoc? { WidgetDoc.parse(widget) }
}

struct WidgetHost: Codable {
    var hostId: String
    var at: Int64
    var applets: [WidgetApplet]

    /// The grid's applets: the pinned ones by their place, as the phone's sidebar (the first 8).
    var pinned: [WidgetApplet] {
        applets.filter { $0.pin != nil }.sorted { ($0.pin ?? 0) < ($1.pin ?? 0) }.prefix(8).map { $0 }
    }
}

enum AppletSnapshot {
    static let appGroup = "group.dev.supermux.app"
    /// Where a control or Spotlight leaves the applet to open, for the app to take when it comes forward.
    static let pendingKey = "applets.pendingOpen"

    static var dir: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?.appendingPathComponent("applet-widgets")
    }

    static func hosts() -> [WidgetHost] {
        guard let dir, let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return [] }
        return files
            .filter { $0.lastPathComponent.hasPrefix("host-") && $0.pathExtension == "json" }
            .compactMap { try? JSONDecoder().decode(WidgetHost.self, from: Data(contentsOf: $0)) }
    }

    /// The host the grid shows: the app's active one, else the one seen last.
    static func active() -> WidgetHost? {
        let all = hosts()
        if let dir, let id = try? String(contentsOf: dir.appendingPathComponent("active"), encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines),
           let host = all.first(where: { $0.hostId == id }) {
            return host
        }
        return all.max { $0.at < $1.at }
    }

    /// Every applet of every host, for the pickers (one-applet widget, control, Spotlight).
    static func allApplets() -> [(host: String, applet: WidgetApplet)] {
        hosts().flatMap { h in h.applets.map { (h.hostId, $0) } }
    }

    static func applet(host: String, name: String) -> WidgetApplet? {
        hosts().first { $0.hostId == host }?.applets.first { $0.name == name }
    }

    static func icon(_ a: WidgetApplet) -> UIImage? {
        guard let dir, let file = a.iconFile, !file.isEmpty else { return nil }
        return UIImage(contentsOfFile: dir.appendingPathComponent(file).path)
    }

    /// The address every way in opens: the app shows the applet full screen (`AppletCover`); [at] is where in it.
    static func uri(host: String, name: String, at: String? = nil) -> String {
        let base = "supermux://applet/\(host)/\(name)"
        guard let at, at.hasPrefix("/") || at.hasPrefix("#"), !at.hasPrefix("//") else { return base }
        return base + "?at=" + (at.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~/"))) ?? "")
    }

    static func url(host: String, name: String, at: String? = nil) -> URL? { URL(string: uri(host: host, name: name, at: at)) }

    /// A picture the app drew for a widget, or nil before it exists.
    static func picture(_ file: String?) -> UIImage? {
        guard let dir, let file, !file.isEmpty else { return nil }
        return UIImage(contentsOfFile: dir.appendingPathComponent(file).path)
    }

    /// #165: changes one applet's entry in [host]'s snapshot file, keeping every other field as the app wrote it.
    static func update(host: String, applet: String, _ change: (inout [String: Any]) -> Void) {
        guard let dir else { return }
        let path = dir.appendingPathComponent("host-\(fnv64(host)).json")
        guard let data = try? Data(contentsOf: path),
              var root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              var list = root["applets"] as? [[String: Any]],
              let i = list.firstIndex(where: { $0["name"] as? String == applet }) else { return }
        var entry = list[i]
        change(&entry)
        list[i] = entry
        root["applets"] = list
        if let out = try? JSONSerialization.data(withJSONObject: root) { try? out.write(to: path, options: .atomic) }
    }

    /// An action's answer: the applet's new widget, shown at once.
    static func putWidget(host: String, applet: String, doc: JSONValue) {
        guard let data = try? JSONEncoder().encode(doc), let obj = try? JSONSerialization.jsonObject(with: data) else { return }
        update(host: host, applet: applet) { $0["widget"] = obj }
    }
}

/// An applet's icon: its own image, else the app's lettered tile (the project tiles' colour: hsl of the title's
/// Java-style hash, 45 %, 42 %), in the iOS icon shape.
struct AppletIconView: View {
    let applet: WidgetApplet
    let size: CGFloat

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.225, style: .continuous)
        if let image = AppletSnapshot.icon(applet) {
            Image(uiImage: image).resizable().scaledToFill().frame(width: size, height: size).clipShape(shape)
        } else {
            ZStack {
                shape.fill(Self.tileColor(applet.title))
                Text(String(applet.title.first ?? "?").uppercased())
                    .font(.system(size: size * 0.42, weight: .bold))
                    .foregroundStyle(.white)
            }
            .frame(width: size, height: size)
        }
    }

    static func tileColor(_ title: String) -> Color {
        var h: Int32 = 0
        for u in title.utf16 { h = h &* 31 &+ Int32(u) }
        let hue = Double((UInt32(bitPattern: h) >> 1) % 360)
        // hsl(hue, 45%, 42%) as hue/saturation/brightness.
        let l = 0.42, s = 0.45
        let v = l + s * min(l, 1 - l)
        let sv = v == 0 ? 0 : 2 * (1 - l / v)
        return Color(hue: hue / 360, saturation: sv, brightness: v)
    }
}
