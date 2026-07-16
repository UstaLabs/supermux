import SwiftUI
import Shared
#if os(iOS)
import UIKit
#endif

/// First-launch pairing. On the Simulator (no camera) the paste path is primary;
/// QR scanning is validated on a physical device. Both consume the same one-time
/// claim payload produced by the desktop host; legacy token URLs remain accepted.
struct PairingView: View {
    var onPaired: (PairToken) -> Void
    @State private var fleet: Fleet
    @State private var input = ""
    @State private var error: String?
    @State private var busy = false
    #if os(iOS)
    @State private var showScanner = false
    #endif

    init(onPaired: @escaping (PairToken) -> Void) {
        self.onPaired = onPaired
        _fleet = State(initialValue: Fleet())
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color.smBackground, Theme.teal.opacity(0.06)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            VStack(spacing: 20) {
                Spacer()
                Image(systemName: "cube.transparent")
                    .font(.system(size: 52)).foregroundStyle(Theme.teal)
                VStack(spacing: 6) {
                    Text("Connect to your broker").font(.title2.weight(.bold))
                    Text("Scan the QR shown by the Supermux desktop app, or paste its pairing payload here.")
                        .font(.callout).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, 28)

                VStack(spacing: 12) {
                    Button {
                        #if os(iOS)
                        error = nil
                        showScanner = true
                        #endif
                    } label: {
                        Label("Scan QR code", systemImage: "qrcode.viewfinder")
                            .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 13)
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.teal)
                    .disabled(busy)

                    TextField("Paste pairing payload…", text: $input, axis: .vertical)
                        .lineLimit(1...3)
                        .padding(14)
                        .glassSurface(cornerRadius: 14)
                        .autocorrectionDisabled()
                        .smNoAutocapitalization()
                        .onChange(of: input) { _, _ in error = nil }

                    Button(action: pair) {
                        Text("Pair").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 13)
                    }
                    .foregroundStyle(.white)
                    .background(input.trimmed.isEmpty ? Color.gray.opacity(0.4) : Theme.teal,
                                in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(busy || input.trimmed.isEmpty)
                }
                .padding(.horizontal, 24)

                if busy { ProgressView() }
                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                        .multilineTextAlignment(.center).padding(.horizontal, 28)
                }
                Spacer(); Spacer()
            }
        }
        #if os(iOS)
        .smFullScreenCover(isPresented: $showScanner) {
            ZStack(alignment: .topTrailing) {
                QRScannerView { decoded in
                    showScanner = false
                    claim(decoded)
                }
                .ignoresSafeArea()
                Button { showScanner = false } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title).foregroundStyle(.white).padding()
                }
            }
        }
        #endif
    }

    private func pair() {
        error = nil
        // The current desktop QR is a structured, one-time claim. Check it before the legacy
        // URL/token parser so a surviving broker URL can never turn the whole JSON payload into
        // a bearer token (the common "URL remained, Keychain was reset" recovery case).
        if PairingPayload.companion.parse(raw: input) != nil {
            claim(input)
            return
        }
        if let p = PairToken.parse(input, fallbackBaseURL: BrokerConfig.baseURL) {
            BrokerConfig.pair(p)
            onPaired(p)
        } else {
            claim(input)
        }
    }

    private func claim(_ raw: String) {
        guard !busy else { return }
        busy = true
        let deviceName: String
        #if os(iOS)
        deviceName = UIDevice.current.name
        #else
        deviceName = Host.current().localizedName ?? "Mac"
        #endif
        Task {
            let result = await fleet.claim(raw: raw, deviceName: deviceName)
            busy = false
            switch result {
            case .added(let host):
                guard let base = [host.relayUrl, host.directUrl]
                    .compactMap({ $0 })
                    .first(where: { !$0.isEmpty }) else {
                    error = "The host did not return a usable address."
                    return
                }
                let pair = PairToken(baseURL: base, token: host.token)
                fleet.stop()
                BrokerConfig.pair(pair)
                onPaired(pair)
            case .needsClaim:
                error = "That host needs a fresh pairing QR from its desktop app."
            case .error(let message):
                error = message
            }
        }
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
