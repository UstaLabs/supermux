import SwiftUI

/// Shared “modern menu” primitives used by Mac popovers and compact choosers
/// (worktree, model/reasoning, project picker). Stock `List`/`Form` chrome looks
/// dated inside a popover and often mis-sizes on macOS.

// MARK: - Section label

struct MenuSectionLabel: View {
    let title: String
    var trailing: AnyView? = nil

    init(_ title: String, trailing: AnyView? = nil) {
        self.title = title
        self.trailing = trailing
    }

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.secondary)
                .tracking(0.6)
            Spacer(minLength: 0)
            if let trailing { trailing }
        }
        .padding(.horizontal, 4)
    }
}

// MARK: - Search field

struct MenuSearchField: View {
    @Binding var text: String
    var placeholder: String = "Search…"
    var focused: FocusState<Bool>.Binding

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(focused.wrappedValue ? Theme.teal : Color.primary.opacity(0.4))
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(.subheadline)
                .autocorrectionDisabled()
                .smNoAutocapitalization()
                .focused(focused)
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 34)
        .background(
            Color.smTertiaryBackground.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 9, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .strokeBorder(
                    focused.wrappedValue ? Theme.teal.opacity(0.6) : Theme.hairline,
                    lineWidth: focused.wrappedValue ? 1.5 : 1
                )
        }
    }
}

// MARK: - Option row

/// Hoverable selectable row used in compact choosers.
struct MenuOptionRow: View {
    let title: String
    var subtitle: String? = nil
    var systemImage: String? = nil
    var monospaced: Bool = false
    var selected: Bool = false
    var destructive: Bool = false
    var emphasized: Bool = false
    let action: () -> Void

    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(iconColor)
                        .frame(width: 16)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(titleFont)
                        .foregroundStyle(titleColor)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Theme.teal)
                }
            }
            .padding(.horizontal, 10).padding(.vertical, subtitle == nil ? 7 : 8)
            .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .background(rowBackground, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay {
                if selected || emphasized {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(Theme.teal.opacity(emphasized && !selected ? 0.28 : 0.18), lineWidth: 1)
                }
            }
        }
        .buttonStyle(.plain)
        #if os(macOS)
        .onHover { hovered = $0 }
        #endif
    }

    private var titleFont: Font {
        if monospaced {
            return .system(.caption, design: .monospaced).weight(selected ? .semibold : .regular)
        }
        return .subheadline.weight(selected || emphasized ? .semibold : .medium)
    }

    private var titleColor: Color {
        if destructive { return .red }
        if selected || emphasized { return Theme.teal }
        return .primary
    }

    private var iconColor: Color {
        if destructive { return .red.opacity(0.85) }
        if selected || emphasized { return Theme.teal }
        return Color.primary.opacity(0.4)
    }

    private var rowBackground: Color {
        if selected { return Theme.teal.opacity(0.12) }
        if emphasized { return Theme.teal.opacity(0.06) }
        if hovered { return Color.primary.opacity(0.055) }
        return .clear
    }
}

// MARK: - Outline pill button (secondary actions next to send)

struct OutlinePillButton: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(enabled ? Theme.teal : Color.secondary.opacity(0.45))
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(
                    enabled ? Theme.teal.opacity(0.10) : Color.primary.opacity(0.04),
                    in: Capsule()
                )
                .overlay(
                    Capsule().strokeBorder(
                        enabled ? Theme.teal.opacity(0.35) : Theme.hairline,
                        lineWidth: 1
                    )
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.7)
    }
}

// MARK: - Send circle

struct SendCircleButton: View {
    var enabled: Bool
    var spinning: Bool = false
    var size: CGFloat = 36
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if spinning {
                    ProgressView().tint(.white).frame(width: size, height: size)
                } else {
                    Image(systemName: "arrow.up")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: size, height: size)
                        .background(enabled ? Theme.teal : Color.gray.opacity(0.45), in: Circle())
                        .shadow(color: enabled ? Theme.teal.opacity(0.35) : .clear, radius: 6, y: 2)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled || spinning)
    }
}

// MARK: - Soft filter pill (model / reasoning / agent chips)

struct SoftFilterPill: View {
    let text: String
    var systemImage: String? = nil
    var active: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 10, weight: .semibold))
                }
                Text(text)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .bold))
                    .opacity(0.5)
            }
            .foregroundStyle(active ? Theme.teal : Color.secondary)
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(
                active ? Theme.teal.opacity(0.10) : Color.smTertiaryFill,
                in: Capsule()
            )
            .overlay(
                Capsule().strokeBorder(
                    active ? Theme.teal.opacity(0.28) : Theme.hairline,
                    lineWidth: 1
                )
            )
        }
        .buttonStyle(.plain)
    }
}
