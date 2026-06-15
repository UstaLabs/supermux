import SwiftUI

/// First-launch pairing. On the Simulator (no camera) the paste path is primary;
/// QR scanning is validated on a physical device. Both yield the same token.
struct PairingView: View {
    var onPaired: (PairToken) -> Void
    @State private var input = ""
    @State private var error: String?

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(.systemBackground), Theme.teal.opacity(0.06)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            VStack(spacing: 20) {
                Spacer()
                Image(systemName: "cube.transparent")
                    .font(.system(size: 52)).foregroundStyle(Theme.teal)
                VStack(spacing: 6) {
                    Text("Connect to your broker").font(.title2.weight(.bold))
                    Text("Run \(Text("`bun run pair <name>`").monospaced()) on your broker, then scan the QR or paste the link here.")
                        .font(.callout).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, 28)

                VStack(spacing: 12) {
                    Button { } label: {
                        Label("Scan QR code", systemImage: "qrcode.viewfinder")
                            .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 13)
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.teal)

                    TextField("Paste pairing link…", text: $input, axis: .vertical)
                        .lineLimit(1...3)
                        .padding(14)
                        .glassSurface(cornerRadius: 14)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: input) { _, _ in error = nil }

                    Button(action: pair) {
                        Text("Pair").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 13)
                    }
                    .foregroundStyle(.white)
                    .background(input.trimmed.isEmpty ? Color.gray.opacity(0.4) : Theme.teal,
                                in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(input.trimmed.isEmpty)
                }
                .padding(.horizontal, 24)

                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                        .multilineTextAlignment(.center).padding(.horizontal, 28)
                }
                Spacer(); Spacer()
            }
        }
    }

    private func pair() {
        guard let p = PairToken.parse(input, fallbackBaseURL: BrokerConfig.baseURL) else {
            error = "Couldn't read a token from that — paste the full pairing link (it contains ?t=…)."
            return
        }
        BrokerConfig.pair(p)
        onPaired(p)
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Temporary post-pair placeholder until the live shell (sessions list/chat) lands.
struct ConnectedPlaceholder: View {
    var onUnpair: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.seal.fill").font(.system(size: 48)).foregroundStyle(Theme.teal)
            Text("Paired ✓").font(.title.bold())
            Text(BrokerConfig.baseURL ?? "—").font(.footnote).foregroundStyle(.secondary)
            Button("Unpair", role: .destructive, action: onUnpair).padding(.top, 8)
        }
    }
}
