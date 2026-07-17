import SwiftUI

/// Per-agent brand mark from the agent's own artwork. The mark is intentionally not
/// placed inside another generic square: several brands already define their own shape,
/// and double-framing made the settings rows look like mismatched app icons.
struct AgentLogo: View {
    let agent: String?
    var size: CGFloat = 34

    private var normalized: String { agent?.lowercased() ?? "" }

    private var assetName: String? {
        switch normalized {
        case "claude": return "claude"
        case "codex": return "codex"
        case "cursor": return "cursor"
        case "opencode": return "opencode"
        case "grok": return "grok"
        default: return nil
        }
    }

    var body: some View {
        ZStack {
            if let assetName {
                if normalized == "codex" || normalized == "cursor" {
                    Image(assetName)
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(.primary)
                        // The Codex vector reaches every edge of its viewBox. Give its antialiased
                        // outline enough breathing room at toolbar/tab sizes so it never clips.
                        .padding(normalized == "codex" ? size * 0.04 : 0)
                } else {
                    Image(assetName)
                        .resizable()
                        .scaledToFit()
                }
            } else {
                Image(systemName: "cube.transparent")
                    .font(.system(size: size * 0.52))
                    .foregroundStyle(Theme.teal)
            }
        }
        .padding(size * 0.06)
        .frame(width: size, height: size)
    }
}
