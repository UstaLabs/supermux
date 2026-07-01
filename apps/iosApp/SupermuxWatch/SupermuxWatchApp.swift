import Combine
import SwiftUI
import UserNotifications
import WatchKit

@main
struct SupermuxWatchApp: App {
    @WKApplicationDelegateAdaptor(WatchAppDelegate.self) private var appDelegate
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
            .onReceive(WatchPushRouter.shared.$pendingSessionId) { id in
                // A tapped (mirrored) notification → open that session. Resolves once
                // the session loads (navigationDestination reads broker.sessions).
                guard let id else { return }
                path = [id]
                WatchPushRouter.shared.pendingSessionId = nil
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

/// Routes a tapped (mirrored) notification to the right session on the watch. The
/// iPhone's NSE stamps `sm_session_id` onto the notification before it mirrors over,
/// so the watch reads it on tap — no on-device decryption needed (the key is on the phone).
@MainActor final class WatchPushRouter: ObservableObject {
    static let shared = WatchPushRouter()
    @Published var pendingSessionId: String?
    private init() {}
}

/// Catches notification taps on the watch and records the target session id for the app.
final class WatchAppDelegate: NSObject, WKApplicationDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// User tapped a notification → open the session it was about.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let id = info["sm_session_id"] as? String, !id.isEmpty else { return }
        await MainActor.run { WatchPushRouter.shared.pendingSessionId = id }
    }
}
