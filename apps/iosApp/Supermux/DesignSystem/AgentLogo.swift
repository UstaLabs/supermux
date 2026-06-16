import SwiftUI

/// Per-agent brand logo (asset-catalog SVGs), on a rounded tile. Falls back to a
/// tinted glyph for unknown agents.
struct AgentLogo: View {
    let agent: String?
    var size: CGFloat = 34

    private var assetName: String? {
        switch agent?.lowercased() {
        case "claude": return "claude"
        case "codex": return "codex"
        case "cursor": return "cursor"
        case "opencode": return "opencode"
        default: return nil
        }
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 8, style: .continuous)
        shape
            .fill(Color(.secondarySystemBackground))
            .overlay(shape.strokeBorder(Theme.hairline, lineWidth: 1))
            .frame(width: size, height: size)
            .overlay {
                if let assetName {
                    Image(assetName).resizable().scaledToFit().padding(size * 0.2)
                } else {
                    Image(systemName: "cube.transparent")
                        .font(.system(size: size * 0.46)).foregroundStyle(Theme.teal)
                }
            }
    }
}
