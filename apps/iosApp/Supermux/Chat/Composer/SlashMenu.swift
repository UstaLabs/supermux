// apps/iosApp/Supermux/Chat/Composer/SlashMenu.swift
import SwiftUI
import Shared

/// The `/command` autocomplete dropdown, shared by both composers. Stateless: the screen
/// passes the current matches + apply action. `showsActionGlyph` adds the bolt marker for
/// control commands (chat shows it; the launcher's preview commands are insert-only).
struct SlashMenu: View {
    let matches: [SlashCommand]
    var showsActionGlyph: Bool = false
    let onApply: (SlashCommand) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(matches, id: \.id) { cmd in
                SlashMenuRow(
                    title: cmd.sigil + cmd.name,
                    subtitle: cmd.family,
                    showsBolt: showsActionGlyph && cmd.action != nil
                ) { onApply(cmd) }
            }
        }
        .padding(6)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.hairline, lineWidth: 1))
        .shadow(color: .black.opacity(0.10), radius: 10, y: 3)
    }
}

private struct SlashMenuRow: View {
    let title: String
    let subtitle: String
    let showsBolt: Bool
    let action: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(title)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Theme.teal)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if showsBolt {
                    Image(systemName: "bolt.fill")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 8)
            .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .background(
                hovered ? Theme.teal.opacity(0.08) : Color.clear,
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
        }
        .buttonStyle(.plain)
        #if os(macOS)
        .onHover { hovered = $0 }
        #endif
    }
}
