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

/// Compact per-row host badge: identity dot + short host name. Rendered only in multi-host mode,
/// dimmed when its host is offline.
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

/// The `All · <host…> · +` filter chip row (spec §5). Each host chip carries its color dot + a live
/// session count; the trailing `+` chip opens the add-host flow. `selected` is a recordId or nil (All).
struct HostFilterChips: View {
    let hosts: [HostView]
    let selected: String?
    /// Live session count for a host recordId (shown as a trailing number when > 0).
    var count: (String) -> Int
    var onSelect: (String?) -> Void
    var onAddHost: () -> Void

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
                }
                chip(label: "Add", systemImage: "plus", dot: nil, selected: false, dimmed: false, action: onAddHost)
                    .accessibilityIdentifier("host_chip_add")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .accessibilityIdentifier("host_filter_chips")
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
