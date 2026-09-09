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
                if normalized == "codex" {
                    ZStack {
                        RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
                            .fill(Color(red: 0.075, green: 0.075, blue: 0.07))
                            .overlay {
                                RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
                                    .strokeBorder(.white.opacity(0.16), lineWidth: 0.75)
                            }
                        Image(assetName)
                            .renderingMode(.template)
                            .resizable()
                            .scaledToFit()
                            .foregroundStyle(.white)
                            .padding(size * 0.17)
                    }
                    .padding(size * 0.02)
                } else if normalized == "cursor" {
                    Image(assetName)
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(.primary)
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
