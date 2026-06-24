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
        VStack(spacing: 0) {
            ForEach(matches, id: \.id) { cmd in
                Button { onApply(cmd) } label: {
                    HStack(spacing: 8) {
                        Text(cmd.sigil + cmd.name).font(.callout.weight(.semibold)).foregroundStyle(Theme.teal)
                        Text(cmd.family).font(.caption2).foregroundStyle(.tertiary)
                        Spacer(minLength: 0)
                        if showsActionGlyph && cmd.action != nil {
                            Image(systemName: "bolt.fill").font(.caption2).foregroundStyle(.tertiary)
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 9).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if cmd.id != matches.last?.id { Divider() }
            }
        }
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.hairline, lineWidth: 1))
    }
}
