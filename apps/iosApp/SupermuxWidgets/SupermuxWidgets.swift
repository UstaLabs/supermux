//
//  SupermuxWidgets.swift
//  SupermuxWidgets
//
//  #117 W1: supermux's widgets. "Applets" is the pinned grid (as the phone's sidebar), "Applet" one applet with the
//  summary it gives ("31 · open · 2 waiting for testing"), and a Control Center / Lock Screen control opens one.
//  Every tap opens supermux straight into the applet, full screen, signed in as usual. They draw from the App Group
//  snapshot the app writes (AppletSnapshot.swift); iOS refreshes them on its own budget, and the app asks for a
//  redraw whenever its applets change.
//

import AppIntents
import SwiftUI
import WidgetKit

@main
struct SupermuxWidgetsBundle: WidgetBundle {
    var body: some Widget {
        AppletsWidget()
        AppletWidget()
        OpenAppletControl()
    }
}

// MARK: - "Applets": the pinned grid

struct AppletsEntry: TimelineEntry {
    let date: Date
    let host: WidgetHost?
}

struct AppletsProvider: TimelineProvider {
    func placeholder(in context: Context) -> AppletsEntry { AppletsEntry(date: .now, host: AppletSnapshot.active()) }

    func getSnapshot(in context: Context, completion: @escaping (AppletsEntry) -> Void) {
        completion(AppletsEntry(date: .now, host: AppletSnapshot.active()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<AppletsEntry>) -> Void) {
        // The app redraws on change; this half-hourly pass catches a snapshot written while no redraw was possible.
        completion(Timeline(entries: [AppletsEntry(date: .now, host: AppletSnapshot.active())], policy: .after(.now.addingTimeInterval(1800))))
    }
}

struct AppletsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "applets", provider: AppletsProvider()) { entry in
            AppletsWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Applets")
        .description("Your pinned applets. Tap one to open it.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct AppletsWidgetView: View {
    let entry: AppletsEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        let pins = entry.host?.pinned ?? []
        let icon: CGFloat = family == .systemLarge ? 56 : 44
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 5) {
                Image(systemName: "square.grid.2x2.fill").font(.system(size: 10, weight: .semibold))
                Text("Applets").font(.system(size: 12, weight: .semibold))
            }
            .foregroundStyle(.secondary)
            if pins.isEmpty {
                Spacer(minLength: 0)
                Text(entry.host == nil ? "Open supermux to see your applets." : "Pin applets in supermux to see them here.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .multilineTextAlignment(.center)
                Spacer(minLength: 0)
            } else {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: family == .systemLarge ? 18 : 8) {
                    ForEach(pins, id: \.name) { a in
                        if let host = entry.host, let url = AppletSnapshot.url(host: host.hostId, name: a.name) {
                            Link(destination: url) { AppletCell(applet: a, size: icon) }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        }
    }
}

struct AppletCell: View {
    let applet: WidgetApplet
    let size: CGFloat

    var body: some View {
        VStack(spacing: 4) {
            ZStack(alignment: .topTrailing) {
                AppletIconView(applet: applet, size: size)
                if applet.needs == true {
                    Circle().fill(Color(red: 1, green: 0.23, blue: 0.19))
                        .frame(width: 11, height: 11)
                        .overlay(Circle().stroke(.black.opacity(0.25), lineWidth: 2))
                        .offset(x: 3, y: -3)
                        .accessibilityLabel(Text("Needs you"))
                }
            }
            Text(applet.title).font(.system(size: 10.5)).lineLimit(1)
        }
    }
}

// MARK: - "Applet": one applet and its summary

struct AppletEntry: TimelineEntry {
    let date: Date
    let host: String?
    let applet: WidgetApplet?
    /// Chosen, but gone from the host's applets.
    let gone: Bool
}

struct AppletProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> AppletEntry {
        AppletEntry(date: .now, host: nil, applet: WidgetApplet(name: "issues", title: "Issues", summary: AppletSummary(value: "31", label: "open", lines: ["2 waiting for testing"])), gone: false)
    }

    func snapshot(for configuration: SelectAppletIntent, in context: Context) async -> AppletEntry { entry(configuration) }

    func timeline(for configuration: SelectAppletIntent, in context: Context) async -> Timeline<AppletEntry> {
        Timeline(entries: [entry(configuration)], policy: .after(.now.addingTimeInterval(1800)))
    }

    private func entry(_ configuration: SelectAppletIntent) -> AppletEntry {
        guard let chosen = configuration.applet else {
            // Not chosen yet: the first pinned applet, so a fresh widget isn't empty.
            let host = AppletSnapshot.active()
            return AppletEntry(date: .now, host: host?.hostId, applet: host?.pinned.first, gone: false)
        }
        let a = AppletSnapshot.applet(host: chosen.host, name: chosen.name)
        return AppletEntry(date: .now, host: chosen.host, applet: a, gone: a == nil)
    }
}

struct AppletWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "applet", intent: SelectAppletIntent.self, provider: AppletProvider()) { entry in
            AppletWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Applet")
        .description("One applet and what it has to say, like how many are open.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct AppletWidgetView: View {
    let entry: AppletEntry

    var body: some View {
        if let a = entry.applet, let host = entry.host {
            content(a).widgetURL(AppletSnapshot.url(host: host, name: a.name))
        } else {
            Text(entry.gone ? "This applet is gone. Choose another." : "Touch and hold to choose an applet.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    @ViewBuilder private func content(_ a: WidgetApplet) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                AppletIconView(applet: a, size: 20)
                Text(a.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                if a.needs == true {
                    Circle().fill(Color(red: 1, green: 0.23, blue: 0.19)).frame(width: 8, height: 8)
                        .accessibilityLabel(Text("Needs you"))
                }
            }
            if let s = a.summary {
                if let value = s.value {
                    Text(value).font(.system(size: 38, weight: .bold)).minimumScaleFactor(0.5).lineLimit(1).padding(.top, 8)
                }
                if let label = s.label {
                    Text(label).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(1)
                }
                ForEach(s.lines ?? [], id: \.self) { line in
                    Text(line).font(.system(size: 11.5)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            } else {
                // No summary: the applet itself, big, to tap.
                Spacer(minLength: 0)
                HStack { Spacer(); AppletIconView(applet: a, size: 56); Spacer() }
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Control Center / Lock Screen

struct OpenAppletControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(kind: "dev.supermux.app.widgets.applet-control", intent: OpenAppletIntent.self) { configuration in
            ControlWidgetButton(action: configuration) {
                Label(configuration.applet?.title ?? String(localized: "Applet"), systemImage: "square.grid.2x2")
            }
        }
        .displayName("Open applet")
        .description("Opens an applet in supermux, full screen.")
    }
}
