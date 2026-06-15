import SwiftUI

/// supermux design tokens — mirrors the PWA's light, airy, teal-accented look.
enum Theme {
    /// Primary accent (the PWA's teal — Chat pill / send button).
    static let teal = Color(red: 14 / 255, green: 156 / 255, blue: 132 / 255)
    static let hairline = Color.primary.opacity(0.08)
    static let cardCorner: CGFloat = 14
    static let barCorner: CGFloat = 20
}

extension View {
    /// Floating translucent "Liquid Glass" surface for chrome (top bar, composer,
    /// pane bar). Uses `.ultraThinMaterial` as a safe baseline on the iOS 26 SDK;
    /// upgrade to `.glassEffect` once confirmed building.
    func glassSurface(cornerRadius: CGFloat = Theme.barCorner) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return background(.ultraThinMaterial, in: shape)
            .overlay(shape.strokeBorder(.white.opacity(0.15), lineWidth: 0.5))
    }

    /// Outlined transcript card (agent replies) — hairline border, rounded.
    func transcriptCard() -> some View {
        let shape = RoundedRectangle(cornerRadius: Theme.cardCorner, style: .continuous)
        return padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(Color(.secondarySystemBackground), in: shape)
            .overlay(shape.strokeBorder(Theme.hairline, lineWidth: 1))
    }
}
