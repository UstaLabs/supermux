import SwiftUI
import Shared

/// The quick read-only pages reachable from the list header ⋮ menu.
enum InfoSheet: String, Identifiable, CaseIterable {
    case archived, usage, proxies, displays, devices
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
    var systemImage: String {
        switch self {
        case .archived: return "archivebox"
        case .usage: return "chart.bar"
        case .proxies: return "network"
        case .displays: return "display"
        case .devices: return "ipad.and.iphone"
        }
    }
    @ViewBuilder func view(broker: BrokerSession) -> some View {
        switch self {
        case .archived: ArchivedView(broker: broker)
        case .usage: UsageView(broker: broker)
        case .proxies: ProxiesView(broker: broker)
        case .displays: DisplaysView(broker: broker)
        case .devices: DevicesView(broker: broker)
        }
    }
}

private struct Loadable<Content: View>: View {
    let title: String
    let loading: Bool
    let isEmpty: Bool
    @ViewBuilder let content: () -> Content
    var body: some View {
        Group {
            if loading {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if isEmpty {
                ContentUnavailableView("Nothing here", systemImage: "tray")
            } else {
                content()
            }
        }
        .navigationTitle(title)
    }
}

struct DevicesView: View {
    let broker: BrokerSession
    @State private var items: [DeviceDto] = []
    @State private var loading = true
    var body: some View {
        Loadable(title: "Devices", loading: loading, isEmpty: items.isEmpty) {
            List(items, id: \.name) { d in
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.name).font(.subheadline.weight(.medium))
                    if let seen = d.last_seen_at {
                        Text("last seen \(seen)").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .task { items = (try? await broker.api.devices()) ?? []; loading = false }
    }
}

struct ProxiesView: View {
    let broker: BrokerSession
    @State private var items: [ProxyDto] = []
    @State private var loading = true
    var body: some View {
        Loadable(title: "Proxies", loading: loading, isEmpty: items.isEmpty) {
            List(items, id: \.domain) { p in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.domain).font(.subheadline.weight(.medium)).lineLimit(1)
                        Text("\(p.sessionName) · :\(p.port)").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if p.isPublic {
                        Text("public").font(.caption2.weight(.semibold))
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(Theme.teal.opacity(0.16), in: Capsule())
                            .foregroundStyle(Theme.teal)
                    }
                }
            }
        }
        .task { items = (try? await broker.api.proxies()) ?? []; loading = false }
    }
}

struct DisplaysView: View {
    let broker: BrokerSession
    @State private var items: [DisplayStream] = []
    @State private var loading = true
    var body: some View {
        Loadable(title: "Displays", loading: loading, isEmpty: items.isEmpty) {
            List(items, id: \.id) { d in
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.sessionName.isEmpty ? d.id : d.sessionName).font(.subheadline.weight(.medium))
                    Text("\(d.provider) · \(d.transport) · \(d.status)")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .task { items = (try? await broker.api.listDisplays()) ?? []; loading = false }
    }
}

struct UsageView: View {
    let broker: BrokerSession
    @State private var raw = ""
    @State private var loading = true
    var body: some View {
        Loadable(title: "Usage", loading: loading, isEmpty: raw.isEmpty) {
            ScrollView {
                Text(raw).font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading).padding()
                    .textSelection(.enabled)
            }
        }
        .task { raw = (try? await broker.api.usageRaw()) ?? ""; loading = false }
    }
}

struct ArchivedView: View {
    let broker: BrokerSession
    @State private var items: [ArchivedDto] = []
    @State private var loading = true
    var body: some View {
        Loadable(title: "Archived", loading: loading, isEmpty: items.isEmpty) {
            List(items, id: \.id) { a in
                HStack(spacing: 11) {
                    AgentLogo(agent: a.agent, size: 30)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.name).font(.subheadline.weight(.medium)).lineLimit(1)
                        Text(formatWorkdir(workdir: a.workdir, home: inferHomeDir(workdir: a.workdir)))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Button("Resume") {
                        broker.resume(a.id)
                        items.removeAll { $0.id == a.id }
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.teal).controlSize(.small)
                }
            }
        }
        .task { items = await broker.archived(); loading = false }
    }
}
