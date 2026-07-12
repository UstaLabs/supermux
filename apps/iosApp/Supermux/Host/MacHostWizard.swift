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
    let hostName: String

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
        startHost: @escaping StartHost,
        existingToken: @escaping () -> String?,
        prepare: @escaping Prepare,
        persistLocalPair: @escaping PersistLocalPair,
        installKeepAlive: @escaping (Int) -> Bool,
        stopHost: @escaping () -> Void = {},
        restartHost: @escaping () async -> Void = {}
    ) {
        self.hostName = hostName
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
        let bootstrap = MacHostBootstrap()
        let machine = Host.current().localizedName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = machine.flatMap { $0.isEmpty ? nil : "This computer (\($0))" } ?? "This computer"
        let coordinator = MacHostCoordinator(
            hostName: name,
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
                if let id = sidecar.hostId,
                   let token = HostStore.shared.list().first(where: { $0.hostId == id })?.token,
                   !token.isEmpty {
                    return token
                }
                if let base = BrokerConfig.baseURL,
                   base == sidecar.localBaseURL {
                    return BrokerConfig.token
                }
                return nil
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
            restartHost: { await sidecar.start() }
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
        persistLocalPair(claim.localToken, endpoint.baseURL, endpoint.hostId, hostName)
        state = .ready(payloadJSON: claim.payloadJSON)
    }

    @discardableResult
    func finish(keepAlive: Bool) -> Bool {
        guard case .ready = state, let endpoint else { return false }
        guard keepAlive else { return true }
        // Free the port before launchd starts its supervised broker, then reconnect by adoption.
        stopHost()
        let installed = installKeepAlive(endpoint.port)
        Task { await restartHost() }
        return installed
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

struct MacHostWizard: View {
    @ObservedObject var coordinator: MacHostCoordinator
    let onContinue: () -> Void
    var onConnectManually: () -> Void = {}
    @State private var keepAlive = true
    @State private var keepAliveError = false

    var body: some View {
        VStack(spacing: 22) {
            Image(nsImage: NSApplication.shared.applicationIconImage)
                .resizable()
                .frame(width: 72, height: 72)
            Text("This Mac is your Supermux host")
                .font(.largeTitle.bold())
            Text("Your agents run here. Scan the pairing code with Supermux on your phone while both devices are on the same network.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 520)

            content

            Text("Connections are encrypted in transit. Relay traffic is not end-to-end encrypted yet.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(48)
        .frame(minWidth: 720, minHeight: 700)
        .task {
            if MacHostPolicy.shouldAutostart(), coordinator.state == .idle { await coordinator.start() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .idle, .preparing:
            ProgressView("Preparing the local host…")
                .controlSize(.large)
        case let .failed(message):
            VStack(spacing: 14) {
                Text(message).foregroundStyle(.red)
                HStack {
                    Button("Connect to existing host", action: onConnectManually)
                    Button("Retry") { Task { await coordinator.start() } }
                        .buttonStyle(.borderedProminent)
                }
            }
        case let .ready(payload):
            VStack(spacing: 18) {
                if let qr = MacHostQRCode.image(for: payload) {
                    Image(nsImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 260, height: 260)
                        .accessibilityIdentifier("mac_host_pairing_qr")
                        .accessibilityLabel("Phone pairing QR code")
                }
                Text(coordinator.hostName)
                    .font(.headline)
                Toggle("Keep this Mac available after I sign in", isOn: $keepAlive)
                    .toggleStyle(.checkbox)
                if keepAliveError {
                    Text("The login helper couldn't be installed. You can continue; hosting will work while Supermux is open.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
                Button("Open Supermux") {
                    let installed = coordinator.finish(keepAlive: keepAlive)
                    keepAliveError = keepAlive && !installed
                    if installed || !keepAlive { onContinue() }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
        }
    }
}
#endif
