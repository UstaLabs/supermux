#if os(macOS)
import AppKit
import Combine
import CoreImage
import Shared
import SwiftUI

struct MacHostEndpoint: Equatable {
    let baseURL: String
    let pairingURL: String
    let hostId: String
    let port: Int

    init(baseURL: String, pairingURL: String? = nil, hostId: String, port: Int) {
        self.baseURL = baseURL
        self.pairingURL = pairingURL ?? baseURL
        self.hostId = hostId
        self.port = port
    }
}

@MainActor
final class MacHostCoordinator: ObservableObject {
    enum State: Equatable {
        case idle
        case preparing
        case ready(payloadJSON: String)
        case failed(String)
    }

    typealias StartHost = () async -> MacHostEndpoint?
    typealias Prepare = (MacHostEndpoint, String, String?) async -> MacHostPreparedClaim?
    typealias PersistLocalPair = (String, String, String, String) -> Void

    @Published private(set) var state: State = .idle
    @Published private(set) var claimExpiresAt: Date?
    @Published private(set) var refreshingClaim = false
    let hostName: String

    private let requiresRelay: Bool
    private let startHost: StartHost
    private let existingToken: () -> String?
    private let prepare: Prepare
    private let persistLocalPair: PersistLocalPair
    private let installKeepAlive: (Int) -> Bool
    private let stopHost: () -> Void
    private let restartHost: () async -> Void
    private var endpoint: MacHostEndpoint?
    private var sidecar: MacBrokerSidecar?
    private var terminationObserver: NSObjectProtocol?

    init(
        hostName: String,
        requiresRelay: Bool = false,
        startHost: @escaping StartHost,
        existingToken: @escaping () -> String?,
        prepare: @escaping Prepare,
        persistLocalPair: @escaping PersistLocalPair,
        installKeepAlive: @escaping (Int) -> Bool,
        stopHost: @escaping () -> Void = {},
        restartHost: @escaping () async -> Void = {}
    ) {
        self.hostName = hostName
        self.requiresRelay = requiresRelay
        self.startHost = startHost
        self.existingToken = existingToken
        self.prepare = prepare
        self.persistLocalPair = persistLocalPair
        self.installKeepAlive = installKeepAlive
        self.stopHost = stopHost
        self.restartHost = restartHost
    }

    static func live() -> MacHostCoordinator {
        let sidecar = MacBrokerSidecar()
        let relayEnabled = ProcessInfo.processInfo.environment["SM_HOST_RELAY_DISABLED"] != "1"
        let bootstrap = MacHostBootstrap(
            relayAttempts: relayEnabled ? 60 : 1,
            relayPollDelay: relayEnabled ? 250_000_000 : 0
        )
        let name = MacBrokerSidecar.localHostDisplayName()
        let coordinator = MacHostCoordinator(
            hostName: name,
            requiresRelay: relayEnabled,
            startHost: {
                await sidecar.start()
                guard let id = sidecar.hostId,
                      sidecar.phase == .online || sidecar.phase == .adopted else { return nil }
                return MacHostEndpoint(
                    baseURL: sidecar.localBaseURL,
                    pairingURL: MacHostNetwork.directURL(port: sidecar.effectivePort),
                    hostId: id,
                    port: sidecar.effectivePort
                )
            },
            existingToken: {
                guard MacHostPolicy.shouldPersist() else { return nil }
                let fleetToken = sidecar.hostId.flatMap { id in
                    HostStore.shared.list().first(where: { $0.hostId == id })?.token
                }
                return MacHostPolicy.preferredLocalToken(
                    localBaseURL: sidecar.localBaseURL,
                    currentBaseURL: BrokerConfig.baseURL,
                    currentToken: BrokerConfig.token,
                    fleetToken: fleetToken
                )
            },
            prepare: { endpoint, hostName, token in
                await bootstrap.prepare(
                    localBaseURL: endpoint.baseURL,
                    pairingDirectURL: endpoint.pairingURL,
                    hostId: endpoint.hostId,
                    hostName: hostName,
                    existingToken: token
                )
            },
            persistLocalPair: { token, url, hostId, displayName in
                guard MacHostPolicy.shouldPersist() else { return }
                if !BrokerConfig.isPaired {
                    BrokerConfig.pair(PairToken(baseURL: url, token: token))
                }
                _ = HostStore.shared.addOrUpdate(
                    displayName: displayName,
                    token: token,
                    relayUrl: nil,
                    directUrl: url,
                    hostId: hostId,
                    platform: "macOS",
                    version: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
                )
            },
            installKeepAlive: { port in
                guard let binaries = try? MacHostResources.prepareBundled() else { return false }
                return MacHostKeepAlive.install(
                    brokerURL: binaries.broker,
                    port: port,
                    binDirectory: binaries.binDirectory,
                    stateDirectory: MacBrokerSidecar.hostStateDirectory()
                )
            },
            stopHost: { sidecar.stop() },
            restartHost: { await sidecar.adoptKeepAliveHost() }
        )
        coordinator.sidecar = sidecar
        coordinator.terminationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak coordinator] _ in
            MainActor.assumeIsolated { coordinator?.applicationWillTerminate() }
        }
        return coordinator
    }

    func start() async {
        if case .preparing = state { return }
        state = .preparing
        guard let endpoint = await startHost() else {
            state = .failed("Couldn't start the local Supermux host.")
            return
        }
        self.endpoint = endpoint
        guard let claim = await prepare(endpoint, hostName, existingToken()) else {
            state = .failed("The local host is already configured. Pair this Mac with it, then retry.")
            return
        }
        guard !requiresRelay || claim.relayURL != nil else {
            state = .failed("Couldn't bring the Supermux relay online. Check your connection, then retry.")
            return
        }
        persistLocalPair(claim.localToken, endpoint.baseURL, endpoint.hostId, hostName)
        claimExpiresAt = claim.expiresAt
        state = .ready(payloadJSON: claim.payloadJSON)
    }

    /// Mint a new one-time claim without restarting the host or disturbing the local pairing.
    /// The connectivity screen calls this on entry and whenever the visible claim expires.
    @discardableResult
    func refreshPairingClaim() async -> Bool {
        guard !refreshingClaim, let endpoint, let token = existingToken(), !token.isEmpty else {
            return false
        }
        refreshingClaim = true
        defer { refreshingClaim = false }
        guard let claim = await prepare(endpoint, hostName, token),
              !requiresRelay || claim.relayURL != nil else { return false }
        claimExpiresAt = claim.expiresAt
        state = .ready(payloadJSON: claim.payloadJSON)
        return true
    }

    @discardableResult
    func finish(keepAlive: Bool) -> Bool {
        guard case .ready = state, let endpoint else { return false }
        guard keepAlive else { return true }
        // Bootstrap launchd before stopping the managed child. If installation fails, the
        // in-app host remains alive and the user can continue without keep-alive. launchd may
        // briefly fail to bind while the child owns the port; KeepAlive retries after stop.
        let installed = installKeepAlive(endpoint.port)
        guard installed else { return false }
        stopHost()
        Task { await restartHost() }
        return true
    }

    func applicationWillTerminate() {
        // MacBrokerSidecar.stop() terminates only Ownership.managed. After a successful launchd
        // handoff the restarted sidecar is external/adopted, so the login host stays alive.
        stopHost()
    }
}

enum MacHostQRCode {
    static func image(for text: String) -> NSImage? {
        guard let data = text.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 9, y: 9)) else {
            return nil
        }
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cg = context.createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    }
}

enum MacPairingMonitor {
    static func newlyPairedDevice(baseline: Set<String>, current: [String]) -> String? {
        current.first { !baseline.contains($0) }
    }
}

enum MacOnboardingStep: Int, CaseIterable {
    case welcome
    case agents
    case gitHosting
    case connectivity
    case done

    var title: String {
        switch self {
        case .welcome: return "Welcome"
        case .agents: return "Agents"
        case .gitHosting: return "Git Hosting"
        case .connectivity: return "Connectivity"
        case .done: return "Done"
        }
    }

    func canAdvance(hasBroker: Bool, agentsReady: Bool, hostReady: Bool) -> Bool {
        switch self {
        case .welcome, .gitHosting, .done: return hasBroker
        case .agents: return agentsReady
        case .connectivity: return hostReady
        }
    }
}

struct MacHostWizard: View {
    @ObservedObject var coordinator: MacHostCoordinator
    let onContinue: () -> Void
    var onConnectManually: () -> Void = {}
    @State private var step = MacOnboardingStep.welcome
    @State private var broker: BrokerSession?
    @State private var agentsReady = false
    @State private var keepAlive = true
    @State private var keepAliveError = false
    @State private var finishing = false
    @State private var finishError: String?
    @State private var pairedDeviceName: String?
    @State private var pairingError: String?
    @State private var pairingGeneration = 0

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            footer
        }
        .frame(minWidth: 900, minHeight: 700)
        .task {
            if MacHostPolicy.shouldAutostart(), coordinator.state == .idle { await coordinator.start() }
            prepareBrokerIfReady()
        }
        .task(id: "\(step.rawValue)-\(pairingGeneration)") {
            guard step == .connectivity else { return }
            await monitorPairing()
        }
        .onChange(of: coordinator.state) { _, _ in prepareBrokerIfReady() }
        .onDisappear { broker?.stop() }
    }

    private var header: some View {
        HStack(spacing: 14) {
            Image("supermuxLogo")
                .resizable()
                .renderingMode(.template)
                .scaledToFit()
                .foregroundStyle(.primary)
                .frame(width: 42, height: 42)
            VStack(alignment: .leading, spacing: 2) {
                Text("Set up Supermux").font(.headline)
                Text("Step \(step.rawValue + 1) of \(MacOnboardingStep.allCases.count) — \(step.title)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            ProgressView(value: Double(step.rawValue + 1), total: Double(MacOnboardingStep.allCases.count))
                .tint(Theme.teal)
                .frame(width: 190)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 18)
    }

    @ViewBuilder
    private var content: some View {
        switch step {
        case .welcome:
            VStack(spacing: 22) {
                Image("supermuxLogo")
                    .resizable()
                    .renderingMode(.template)
                    .scaledToFit()
                    .foregroundStyle(.primary)
                    .frame(width: 108, height: 108)
                Text("Welcome to Supermux")
                    .font(.largeTitle.bold())
                Text("Run multiple coding agents on this Mac and stay connected from your phone or browser.")
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 600)
                hostPreparationStatus
            }
            .padding(48)

        case .agents:
            if let broker {
                AgentSettingsView(
                    broker: broker,
                    showsNavigationTitle: false,
                    onReadinessChanged: { agentsReady = $0 }
                )
            } else {
                ProgressView("Connecting to the local host…")
                    .controlSize(.large)
            }

        case .gitHosting:
            if let broker {
                GitHostingSettingsView(broker: broker)
            } else {
                ProgressView("Connecting to the local host…")
                    .controlSize(.large)
            }

        case .connectivity:
            ScrollView {
                VStack(spacing: 18) {
                    Text("Connect your other devices")
                        .font(.largeTitle.bold())
                    Text("Scan this code with Supermux on your iPhone, iPad, or Android device. The built-in relay keeps it reachable away from your local network.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 600)
                    if let pairedDeviceName {
                        VStack(spacing: 14) {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 72))
                                .foregroundStyle(Theme.teal)
                            Text("\(pairedDeviceName) paired")
                                .font(.title2.bold())
                            Text("The device is connected and ready to use.")
                                .foregroundStyle(.secondary)
                            Button("Pair another device") { pairingGeneration += 1 }
                                .buttonStyle(.bordered)
                        }
                        .frame(height: 300)
                    } else if case let .ready(payload) = coordinator.state,
                              let qr = MacHostQRCode.image(for: payload) {
                        VStack(spacing: 10) {
                            Image(nsImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .frame(width: 260, height: 260)
                                .accessibilityIdentifier("mac_host_pairing_qr")
                                .accessibilityLabel("Device pairing QR code")
                            Button(coordinator.refreshingClaim ? "Refreshing…" : "Refresh code") {
                                pairingGeneration += 1
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(Theme.teal)
                            .disabled(coordinator.refreshingClaim)
                        }
                    } else {
                        ProgressView("Creating a fresh pairing code…")
                            .controlSize(.large)
                            .frame(height: 300)
                    }
                    if let pairingError {
                        Text(pairingError)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                    Text(coordinator.hostName).font(.headline)
                    Toggle("Keep this Mac available after I sign in", isOn: $keepAlive)
                        .toggleStyle(.checkbox)
                    Text("Connections are encrypted in transit. Relay traffic is not end-to-end encrypted yet.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(38)
                .frame(maxWidth: .infinity)
            }

        case .done:
            VStack(spacing: 20) {
                Image(systemName: "party.popper.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(Theme.teal)
                Text("You’re all set!")
                    .font(.largeTitle.bold())
                Text("Your Mac host and coding agent are ready. Open Supermux to start your first session.")
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 600)
                if keepAliveError {
                    VStack(spacing: 10) {
                        Text("The login helper couldn't be installed. Hosting can still work while Supermux is open.")
                            .font(.caption)
                            .foregroundStyle(.orange)
                        Button("Continue without login helper") {
                            keepAlive = false
                            advance()
                        }
                    }
                }
                if let finishError {
                    Text(finishError).font(.caption).foregroundStyle(.red)
                }
            }
            .padding(48)
        }
    }

    @ViewBuilder
    private var hostPreparationStatus: some View {
        switch coordinator.state {
        case .idle, .preparing:
            HStack(spacing: 10) {
                ProgressView().controlSize(.small).tint(Theme.teal)
                Text("Preparing the local host…").foregroundStyle(.secondary)
            }
        case .ready:
            Label("Local host ready", systemImage: "checkmark.circle.fill")
                .foregroundStyle(Theme.teal)
        case let .failed(message):
            VStack(spacing: 12) {
                Text(message).foregroundStyle(.red)
                HStack {
                    Button("Connect to existing host", action: onConnectManually)
                    Button("Retry") { Task { await coordinator.start() } }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.teal)
                }
            }
        }
    }

    private var footer: some View {
        HStack {
            if step != .welcome && step != .done {
                Button("Back") { moveBack() }
            }
            Spacer()
            Button(step == .done ? "Open Supermux" : "Continue") { advance() }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .controlSize(.large)
                .disabled(!canAdvance || finishing)
            if step == .welcome {
                Spacer()
            }
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 18)
    }

    private var canAdvance: Bool {
        step.canAdvance(
            hasBroker: broker != nil,
            agentsReady: agentsReady,
            hostReady: coordinator.state.isReady
        )
    }

    private func prepareBrokerIfReady() {
        guard broker == nil, coordinator.state.isReady,
              let base = BrokerConfig.baseURL, let token = BrokerConfig.token else { return }
        let session = BrokerSession(baseURL: base, token: token)
        // Keep one control connection alive for the whole wizard. Moving between steps must
        // not create and tear down host sessions or leave a cancelled REST request looking
        // like a disconnect when the user navigates back.
        session.start()
        broker = session
    }

    private func advance() {
        finishError = nil
        switch step {
        case .welcome: step = .agents
        case .agents: step = .gitHosting
        case .gitHosting: step = .connectivity
        case .connectivity: step = .done
        case .done: Task { await completeSetup() }
        }
    }

    private func moveBack() {
        guard let previous = MacOnboardingStep(rawValue: step.rawValue - 1) else { return }
        step = previous
    }

    @MainActor
    private func monitorPairing() async {
        guard let broker else { return }
        pairedDeviceName = nil
        pairingError = nil

        let before = (try? await broker.api.devices()) ?? []
        let baseline = Set(before.map(\.name))
        guard !Task.isCancelled, step == .connectivity else { return }

        if !coordinator.refreshingClaim, !(await coordinator.refreshPairingClaim()) {
            pairingError = "Couldn't refresh the pairing code. Try again."
        }

        while !Task.isCancelled, step == .connectivity {
            if let devices = try? await broker.api.devices(),
               let added = MacPairingMonitor.newlyPairedDevice(
                   baseline: baseline,
                   current: devices.map(\.name)
               ) {
                pairedDeviceName = added
                pairingError = nil
                return
            }

            if let expiry = coordinator.claimExpiresAt,
               Date() >= expiry.addingTimeInterval(-5),
               !coordinator.refreshingClaim,
               !(await coordinator.refreshPairingClaim()) {
                pairingError = "The pairing code expired and couldn't be refreshed. Try again."
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    private func completeSetup() async {
        guard let broker else { return }
        finishing = true
        defer { finishing = false }

        await broker.saveConfig(onboarded: true)
        guard (await broker.config())?.onboarded == true else {
            finishError = "Couldn't save setup. Check the host connection and try again."
            return
        }

        let installed = coordinator.finish(keepAlive: keepAlive)
        keepAliveError = keepAlive && !installed
        if installed || !keepAlive { onContinue() }
    }
}

private extension MacHostCoordinator.State {
    var isReady: Bool {
        if case .ready = self { return true }
        return false
    }
}
#endif
