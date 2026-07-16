import SwiftUI

/// Brand marks for OpenCode's built-in authentication providers. Known providers use
/// their real mark; unknown or user-installed providers get a neutral service glyph so
/// we never invent a fake brand logo.
struct ProviderLogo: View {
    let provider: String
    var size: CGFloat = 24

    private var normalized: String { provider.lowercased() }

    private var assetName: String? {
        switch normalized {
        case "anthropic": return "claude"
        case "openai": return "codex"
        case "opencode", "opencode-go": return "opencode"
        case "azure": return "providerAzure"
        case "github", "github-copilot": return "providerGithubCopilot"
        case "gitlab": return "gitlab"
        case "cloudflare", "cloudflare-workers-ai", "cloudflare-ai-gateway": return "providerCloudflare"
        case "digitalocean": return "providerDigitalOcean"
        case "snowflake", "snowflake-cortex": return "providerSnowflake"
        case "poe": return "providerPoe"
        default: return nil
        }
    }

    private var usesTemplate: Bool {
        ["anthropic", "openai", "github", "github-copilot"].contains(normalized)
    }

    var body: some View {
        ZStack {
            if let assetName {
                Image(assetName)
                    .renderingMode(usesTemplate ? .template : .original)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.primary)
            } else {
                RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
                    .fill(Color.smSecondaryBackground)
                    .overlay {
                        Image(systemName: "sparkles")
                            .font(.system(size: size * 0.46, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
            }
        }
        .padding(size * 0.06)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
