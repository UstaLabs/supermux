import SwiftUI

/// Horizontal, unlimited tab strip for the editor pane. Parity with the PWA `EditorTabs.vue`
/// and Android `EditorTabs`, but per the user's overrides: NO tab cap, the ACTIVE tab reads by
/// a distinct background+foreground COLOUR (not a dot), and the amber dot is reserved for the
/// DIRTY state only.
struct EditorTabsView: View {
    let state: EditorState
    let onSelect: (String) -> Void
    let onClose: (String) -> Void

    /// Dirty indicator — amber ~ #FBBF24 (matches Android `0xFFFBBF24` / web `bg-amber-400`).
    private static let amber = Color(red: 0.98, green: 0.75, blue: 0.14)

    var body: some View {
        if !state.tabs.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(state.tabs) { tab in
                        chip(tab)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
            }
        }
    }

    private func basename(_ path: String) -> String {
        path.split(separator: "/").last.map(String.init) ?? path
    }

    /// The directory leading up to the file, shown as a faint hint when the path is nested.
    private func dirHint(_ path: String) -> String? {
        let parts = path.split(separator: "/")
        guard parts.count > 1 else { return nil }
        return parts.dropLast().joined(separator: "/")
    }

    @ViewBuilder
    private func chip(_ tab: EditorState.Tab) -> some View {
        let active = tab.path == state.activeTabPath
        HStack(spacing: 6) {
            // The tappable label area = select. Decorative subviews are folded into one
            // VoiceOver element carrying the name, dirty state, and the select action.
            HStack(spacing: 6) {
                if tab.isDirty {
                    Circle().fill(Self.amber).frame(width: 6, height: 6)
                }
                Text(basename(tab.path))
                    .font(.subheadline)
                    .lineLimit(1)
                if let hint = dirHint(tab.path) {
                    Text(hint)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                        .truncationMode(.head)
                        .frame(maxWidth: 90)
                }
            }
            .padding(.leading, 10)
            .frame(height: 34)
            .contentShape(Rectangle())
            .onTapGesture {
                SMHaptics.selection()
                onSelect(tab.path)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(basename(tab.path))\(tab.isDirty ? ", unsaved changes" : "")")
            .accessibilityAddTraits(active ? [.isButton, .isSelected] : .isButton)
            .accessibilityHint("Double tap to open")

            // Close stays its own VoiceOver element / 44pt target.
            Button {
                SMHaptics.selection()
                onClose(tab.path)
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)        // 44pt hit area
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close \(basename(tab.path))")
        }
        .foregroundStyle(active ? .primary : .secondary)
        .background(
            active ? Color.smSecondaryBackground : .clear,
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
    }
}
