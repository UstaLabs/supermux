import SwiftUI
// The iOS app links ONE Kotlin framework, SupermuxKit, which re-exports :shared; linking Shared as
// well would embed the :shared klib twice — two Kotlin runtimes, two copies of every object.
import SupermuxKit

@main
struct SupermuxApp: App {
    // UIKit AppDelegate (push/APNs) adapted into the SwiftUI lifecycle. The delegate
    // requests notification authorization + registers for remote notifications on
    // launch (if paired), and orchestrates relay/broker push registration.
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate
    // Drives `IosAppState.foreground`, which the shared shell reads to suppress viewing presence
    // (and so keep pushes coming) while the app is in the background.
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Crash guard FIRST, before any networking: ktor-darwin surfaces WS/connection
        // errors on an unhandled coroutine → SIGABRT (the "app cannot be open" launch
        // crash when the broker restarts / a connection error hits at startup). Install
        // a Kotlin/Native hook that logs-and-continues so the reconnect loop recovers
        // instead of the process aborting.
        IosClientKt.installIosCrashGuard()
        // Debug/testing convenience: auto-pair from launch env
        // (inject via SIMCTL_CHILD_SM_PAIR_TOKEN / SIMCTL_CHILD_SM_PAIR_BASE).
        let env = ProcessInfo.processInfo.environment
        if let t = env["SM_PAIR_TOKEN"], let b = env["SM_PAIR_BASE"], !t.isEmpty, !b.isEmpty {
            BrokerConfig.pair(PairToken(baseURL: b, token: t))
        }
        // No `HostStore.migrateFromLegacyIfNeeded()` here: the Kotlin root owns the ONE
        // `PairedHostStore` in the process (`IosHostStores`) and runs the single-host → PairedHost[0]
        // migration itself at construction, AFTER the env seed above (which writes the LEGACY pairing,
        // exactly what Kotlin's migration then folds in). Doing it here as well would build a SECOND
        // store over the same Keychain items — two in-memory copies of the fleet, each overwriting
        // the other's saves.
        // Start the WatchConnectivity channel so a paired Apple Watch gets the broker
        // credentials (pushed on activation + whenever they change below).
        PhoneWatchProvisioner.shared.activate()
    }

    var body: some Scene {
        // The Compose shell: the MAIN scene, whose content is the shared `SupermuxApp` root inside
        // a navigation controller (see ComposeRootView). Deliberately absent, compared with the
        // SwiftUI shell this replaced:
        //  - `.preferredColorScheme` — Compose owns appearance now, reading `appearance:mode` from
        //    the shared settings store. Leaving it would let SwiftUI force a scheme the Compose
        //    theme disagrees with, and the two would fight on every change.
        //  - the pairing gate — `MainViewController` runs the shared intro/pairing flow itself, so
        //    Swift no longer decides what "paired" means.
        WindowGroup(id: SceneWindows.mainGroupId) {
            ComposeRootView()
                // Compose draws to the very edges and pads for the safe areas itself, through
                // `WindowInsets.safeDrawing` in the shared shell.
                .ignoresSafeArea()
                // How Kotlin opens the extra windows below (`SceneWindows`).
                .modifier(ExtraWindowOpener())
                .onOpenURL { url in
                    // Handing over the raw string rather than parsing here: `PairUrl.parse` is
                    // shared code and already handles both `supermux://pair?...` and a pasted
                    // https link, with the stored base URL as the fallback.
                    IosAppState.shared.setOpenedUrl(url: url.absoluteString)
                }
                .onChange(of: scenePhase) { _, phase in
                    // `!= .background`, deliberately not `== .active`. This drives viewing
                    // presence (spec §11): while it is false the broker treats the user as away
                    // and keeps sending pushes for the chat on screen. Android's equivalent is
                    // ON_START/ON_STOP — VISIBILITY, which does not end when a system overlay
                    // steals focus — and iOS's `.inactive` is exactly that set of moments:
                    // Notification Center or Control Center pulled down, an incoming call banner,
                    // the app switcher on its way up, plus the instant of every transition. Using
                    // `.active` would report "not looking" while the user is plainly reading the
                    // chat, and the broker would push a notification for a message on screen.
                    IosAppState.shared.setForeground(value: phase != .background)
                }
        }

        // iPad: a pane moved out of the main window ("Move to New Window") into a window of its
        // own. The value is the window's claim — which views it shows — so iPadOS restores the
        // window with it. See `IosWindows.kt`. An iPhone never opens one (one window per app).
        WindowGroup(id: SceneWindows.groupId, for: String.self) { $claim in
            ExtraWindowView(claim: $claim)
                .ignoresSafeArea()
        }
        // Pair links and other URLs belong to the main window, never to an extra one.
        .handlesExternalEvents(matching: [])
    }
}

