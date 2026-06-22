import SwiftUI
import Shared

@main
struct SupermuxWatchApp: App {
    @State private var provisioning = WatchProvisioning()
    @State private var broker: WatchBrokerSession?
    @State private var path: [String] = []
    @State private var didAutoOpen = false

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $path) {
                Group {
                    if let broker {
                        SessionsListView(broker: broker)
                    } else {
                        NotConnectedView()
                    }
                }
            }
            .onAppear {
                provisioning.start()
                rebuildBroker()
            }
            .onChange(of: credsKey) { rebuildBroker() }
            .onChange(of: broker?.synced ?? false) { _, synced in
                if synced { maybeAutoOpen() }
            }
        }
    }

    /// Identity of the current credentials, so we only rebuild the client when they
    /// actually change (not on every observable tick).
    private var credsKey: String {
        guard let c = provisioning.creds else { return "" }
        return c.baseURL + "|" + c.token
    }

    private func rebuildBroker() {
        guard let c = provisioning.creds else { broker = nil; return }
        if broker?.baseURL != c.baseURL {
            let session = WatchBrokerSession(baseURL: c.baseURL, token: c.token)
            session.start()
            broker = session
        }
    }

    /// Headless-test hook (mirrors the iOS app's SM_OPEN_SESSION): once synced, if
    /// SM_OPEN_SESSION names a live session, push straight into its detail view.
    private func maybeAutoOpen() {
        guard !didAutoOpen, let broker,
              let name = ProcessInfo.processInfo.environment["SM_OPEN_SESSION"], !name.isEmpty,
              let match = broker.sessions.first(where: { $0.name == name }) else { return }
        didAutoOpen = true
        path = [match.id]
    }
}

/// Shown until the iPhone hands over credentials via WatchConnectivity.
private struct NotConnectedView: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "iphone.radiowaves.left.and.right").font(.title3)
            Text("Open Supermux on your iPhone to connect your watch.")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
