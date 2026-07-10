import SwiftUI

/// supermux design tokens — light, airy, teal-accented; iOS 26 Liquid Glass.
enum Theme {
    static let teal = Color(red: 14 / 255, green: 156 / 255, blue: 132 / 255)
    static let hairline = Color.primary.opacity(0.08)
    static let cardCorner: CGFloat = 14
    static let barCorner: CGFloat = 22
    /// Subtle fill marking the user's own turns (no border, not a heavy bubble).
    static let userTint = teal.opacity(0.10)
    /// Terminal surface colors (dark, match the web `--cmux-terminal` / its foreground).
    static let terminalBackground = Color(red: 0.07, green: 0.07, blue: 0.09)
    static let terminalForeground = Color(red: 0.90, green: 0.90, blue: 0.92)
}

extension View {
    /// Liquid Glass surface for floating chrome (composer, clusters, pills).
    func glassSurface(cornerRadius: CGFloat = Theme.barCorner) -> some View {
        glassEffect(.regular, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    /// The composer card. iOS: Liquid Glass. macOS: glass renders near-invisible against the
    /// flat window background (no floating-chrome compositing like iOS), so the card gets a
    /// real material + hairline + soft shadow — visible structure in both light and dark.
    @ViewBuilder func composerSurface(cornerRadius: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        #if os(macOS)
        background(.regularMaterial, in: shape)
            .overlay(shape.strokeBorder(Theme.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(0.10), radius: 10, y: 2)
        #else
        glassEffect(.regular, in: shape)
        #endif
    }

    /// A filled card. iOS: `smSecondaryBackground` (visibly gray on white). macOS maps that
    /// token to `controlBackgroundColor` — white-on-white — so the Mac gets the same
    /// material + hairline + shadow treatment as `composerSurface` instead.
    @ViewBuilder func smCardSurface(cornerRadius: CGFloat) -> some View {
        #if os(macOS)
        composerSurface(cornerRadius: cornerRadius)
        #else
        background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        #endif
    }

    /// Agent reply: plain full-bleed text (no card). Kept as a no-op wrapper so call
    /// sites read intentionally and we can reintroduce treatment later if needed.
    func transcriptBody() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
    }

    /// User turn: subtle teal tint, continuous corner, no border.
    func userMessageSurface() -> some View {
        padding(.horizontal, 12).padding(.vertical, 9)
            .background(Theme.userTint, in: RoundedRectangle(cornerRadius: Theme.cardCorner, style: .continuous))
    }

    /// Compat alias — legacy call sites (InfoPages.swift, ArchivedMessageRow) still use this.
    func transcriptCard() -> some View {
        let shape = RoundedRectangle(cornerRadius: Theme.cardCorner, style: .continuous)
        return padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(Color.smSecondaryBackground, in: shape)
            .overlay(shape.strokeBorder(Theme.hairline, lineWidth: 1))
    }
}
