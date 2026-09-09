import SwiftUI
import Shared

/// Host badge visuals for the merged fleet list (spec §5): a stable per-host color dot, a compact
/// per-row badge, and the `All · <host…> · +` filter chip row. The color slot, compact label, and
/// the OKLCH dot palette all come from the SHARED `FleetModel` (KMP commonMain) via `FleetModelKt`
/// — the exact same algorithm + values Android's Compose `HostBadge` uses, so a host's dot is the
/// same color on both platforms. This file is only the SwiftUI rendering.

/// The fixed dot color for a host color slot, theme-aware — resolved from the shared `hostDotArgb`.
func hostDotColor(_ colorIndex: Int32, dark: Bool) -> Color {
    let argb = UInt32(bitPattern: FleetModelKt.hostDotArgb(colorIndex: colorIndex, dark: dark))
    return Color(.sRGB,
                 red: Double((argb >> 16) & 0xFF) / 255.0,
                 green: Double((argb >> 8) & 0xFF) / 255.0,
                 blue: Double(argb & 0xFF) / 255.0,
                 opacity: 1)
}

/// A small filled dot in the host's identity color.
struct HostDot: View {
    let colorIndex: Int32
    var size: CGFloat = 8
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Circle()
            .fill(hostDotColor(colorIndex, dark: scheme == .dark))
            .frame(width: size, height: size)
    }
}

/// Compact per-row host badge: identity dot + short host name. Multi-host + "All" filter only
/// (hidden when a specific host pill is selected); dimmed when its host is offline.
struct HostBadge: View {
    let host: HostView

    var body: some View {
        HStack(spacing: 4) {
            HostDot(colorIndex: host.colorIndex, size: 7)
            Text(host.shortLabel)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.secondary)
        }
        .opacity(host.online ? 1 : 0.55)
        .accessibilityIdentifier("host_badge_\(host.recordId)")
    }
}

/// Explicit host scope for pages whose data and actions belong to one broker (Usage, Devices,
/// Settings, and similar). Keeping this visible prevents an action from silently targeting the
/// last host used somewhere else in the app.
struct HostScopePicker: View {
    let hosts: [HostView]
    let selected: String?
    var onSelect: (String) -> Void

    private var selectedHost: HostView? {
        hosts.first { $0.recordId == selected } ?? hosts.first
    }

    var body: some View {
        if hosts.count >= 2, let host = selectedHost {
            HStack(spacing: 10) {
                Text("Host")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 8)
                Menu {
                    ForEach(hosts, id: \.recordId) { candidate in
                        Button { onSelect(candidate.recordId) } label: {
                            if candidate.recordId == selected {
                                Label(candidate.displayLabel, systemImage: "checkmark")
                            } else {
                                Text(candidate.displayLabel)
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        HostDot(colorIndex: host.colorIndex, size: 9)
                        Text(host.displayLabel).lineLimit(1)
                        if !host.online {
                            Text("Offline").foregroundStyle(.secondary)
                        }
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline.weight(.medium))
                }
                .accessibilityIdentifier("host_scope_picker")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .background(.bar)
            .overlay(alignment: .bottom) { Divider() }
        }
    }
}

/// The `All · <host…> · +` filter chip row (spec §5). Each host chip carries its color dot + a live
/// session count; the trailing `+` chip opens the add-host flow. `selected` is a recordId or nil
/// (All). Long-pressing a host chip exposes its destructive forget action, matching Android.
struct HostFilterChips: View {
    let hosts: [HostView]
    let selected: String?
    /// Live session count for a host recordId (shown as a trailing number when > 0).
    var count: (String) -> Int
    var onSelect: (String?) -> Void
    var onAddHost: () -> Void
    var onForgetHost: (String) -> Void

    @State private var forgetTarget: HostView?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(label: "All", dot: nil, selected: selected == nil, dimmed: false) { onSelect(nil) }
                    .accessibilityIdentifier("host_chip_all")
                ForEach(hosts, id: \.recordId) { h in
                    let n = count(h.recordId)
                    chip(label: n > 0 ? "\(h.shortLabel)  \(n)" : h.shortLabel,
                         dot: h.colorIndex,
                         selected: selected == h.recordId,
                         dimmed: !h.online) { onSelect(h.recordId) }
                        .accessibilityIdentifier("host_chip_\(h.recordId)")
                        .accessibilityHint("Touch and hold for host actions")
                        .accessibilityAction(named: Text("Forget host")) {
                            forgetTarget = h
                        }
                        .contextMenu {
                            Button(role: .destructive) { forgetTarget = h } label: {
                                Label("Forget Host…", systemImage: "trash")
                            }
                        }
                }
                chip(label: "Add", systemImage: "plus", dot: nil, selected: false, dimmed: false, action: onAddHost)
                    .accessibilityIdentifier("host_chip_add")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .accessibilityIdentifier("host_filter_chips")
        .confirmationDialog(
            "Forget \u{201C}\(forgetTarget?.displayLabel ?? "")\u{201D}?",
            isPresented: Binding(
                get: { forgetTarget != nil },
                set: { if !$0 { forgetTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Forget host", role: .destructive) {
                if let target = forgetTarget { onForgetHost(target.recordId) }
                forgetTarget = nil
            }
            Button("Cancel", role: .cancel) { forgetTarget = nil }
        } message: {
            Text("Removes this host and its sessions from this device. You\u{2019}ll need a new pairing link to add it again.")
        }
    }

    @ViewBuilder
    private func chip(label: String, systemImage: String? = nil, dot: Int32?, selected: Bool,
                      dimmed: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let systemImage { Image(systemName: systemImage).font(.caption2.weight(.semibold)) }
                if let dot { HostDot(colorIndex: dot, size: 9) }
                Text(label)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .foregroundStyle(dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Capsule().fill(selected ? Theme.teal.opacity(0.22) : Color.smSecondaryBackground)
            )
            .overlay(
                Capsule().strokeBorder(selected ? Theme.teal : .clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}
