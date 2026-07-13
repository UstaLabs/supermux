#if os(macOS)
import Combine
import Darwin
import Foundation

protocol MacHostProcess: AnyObject {
    var isRunning: Bool { get }
    func terminate()
}

extension Process: MacHostProcess {}

@MainActor
final class MacBrokerSidecar: ObservableObject {
    enum Phase: Equatable {
        case idle
        case starting
        case online
        case adopted
        case needsUpgrade
        case failed
        case stopped
    }

    typealias Probe = (Int) async -> MacHostProbeResult
    typealias Spawn = (Int) throws -> MacHostProcess

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var ownership: MacHostOwnership = .none
    @Published private(set) var hostId: String?
    @Published private(set) var effectivePort: Int

    let defaultPort: Int
    private let probe: Probe
    private let spawn: Spawn
    private let acquireManagerLock: () -> Bool
    private let loadAlternatePort: () -> Int?
    private let saveAlternatePort: (Int) -> Void
    private let healthAttempts: Int
    private let healthPollDelay: UInt64
    private var managedProcess: MacHostProcess?

    var localBaseURL: String { "http://127.0.0.1:\(effectivePort)" }

    init(
        defaultPort: Int = 9898,
        probe: @escaping Probe = MacBrokerSidecar.probeLocal,
        spawn: @escaping Spawn = MacBrokerSidecar.spawnBundledBroker,
        acquireManagerLock: @escaping () -> Bool = MacHostManagerLock.shared.acquire,
        loadAlternatePort: @escaping () -> Int? = MacBrokerSidecar.loadPersistedAlternatePort,
        saveAlternatePort: @escaping (Int) -> Void = MacBrokerSidecar.savePersistedAlternatePort,
        healthAttempts: Int = 60,
        healthPollDelay: UInt64 = 500_000_000
    ) {
        self.defaultPort = defaultPort
        self.effectivePort = defaultPort
        self.probe = probe
        self.spawn = spawn
        self.acquireManagerLock = acquireManagerLock
        self.loadAlternatePort = loadAlternatePort
        self.saveAlternatePort = saveAlternatePort
        self.healthAttempts = healthAttempts
        self.healthPollDelay = healthPollDelay
    }

    func start() async {
        guard phase == .idle || phase == .stopped || phase == .failed else { return }
        phase = .starting
        ownership = .none
        hostId = nil
        effectivePort = defaultPort

        let initial = await probe(defaultPort)
        switch MacHostPolicy.decision(for: initial) {
        case .adoptExternal:
            guard case let .supermuxHost(id) = initial else {
                phase = .failed
                return
            }
            ownership = .external
            hostId = id
            phase = .adopted

        case .upgradeRequired:
            phase = .needsUpgrade

        case .spawnManaged:
            guard acquireManagerLock() else {
                await adoptAfterLockLoss(port: defaultPort)
                return
            }
            await spawnManaged(port: defaultPort)

        case .spawnManagedAlternate:
            guard acquireManagerLock() else {
                await adoptAfterLockLoss(port: defaultPort)
                return
            }
            let port = MacHostPolicy.alternatePort(
                persisted: loadAlternatePort(),
                defaultPort: defaultPort
            )
            effectivePort = port
            saveAlternatePort(port)
            await spawnManaged(port: port)
        }
    }

    func stop() {
        if MacHostPolicy.mayTerminate(ownership), let managedProcess, managedProcess.isRunning {
            managedProcess.terminate()
        }
        managedProcess = nil
        ownership = .none
        hostId = nil
        phase = .stopped
    }

    /// Complete the managed-process → launchd handoff without ever spawning a second broker.
    /// `MacHostKeepAlive.install` returns once launchd accepted the job, not once `/host` is
    /// listening, so calling `start()` here races launchd and can reclaim the port itself.
    func adoptKeepAliveHost() async {
        guard phase == .stopped || phase == .failed else { return }
        phase = .starting
        ownership = .none
        hostId = nil
        if let id = await pollForHost(port: effectivePort) {
            ownership = .external
            hostId = id
            phase = .adopted
        } else {
            phase = .failed
        }
    }

    private func spawnManaged(port: Int) async {
        effectivePort = port
        do {
            managedProcess = try spawn(port)
            ownership = .managed
        } catch {
            ownership = .none
            phase = .failed
            return
        }

        if let id = await pollForHost(port: port) {
            hostId = id
            phase = .online
        } else {
            if let managedProcess, managedProcess.isRunning { managedProcess.terminate() }
            self.managedProcess = nil
            ownership = .none
            phase = .failed
        }
    }

    private func adoptAfterLockLoss(port: Int) async {
        effectivePort = port
        if let id = await pollForHost(port: port) {
            ownership = .external
            hostId = id
            phase = .adopted
        } else {
            ownership = .none
            phase = .failed
        }
    }

    private func pollForHost(port: Int) async -> String? {
        for attempt in 0..<max(1, healthAttempts) {
            if case let .supermuxHost(id) = await probe(port) { return id }
            if attempt + 1 < healthAttempts, healthPollDelay > 0 {
                try? await Task.sleep(nanoseconds: healthPollDelay)
            }
        }
        return nil
    }

    nonisolated static func probeLocal(port: Int) async -> MacHostProbeResult {
        guard let url = URL(string: "http://127.0.0.1:\(port)/host") else { return .portFree }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .foreignProcess }
            guard http.statusCode == 200 else { return .foreignProcess }
            let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            if let id = body?["hostId"] as? String, !id.isEmpty {
                return .supermuxHost(hostId: id)
            }
            return .legacySupermux
        } catch let error as URLError where error.code == .cannotConnectToHost || error.code == .networkConnectionLost {
            return .portFree
        } catch {
            return .foreignProcess
        }
    }

    nonisolated static func spawnBundledBroker(port: Int) throws -> MacHostProcess {
        let process = Process()
        let environment = ProcessInfo.processInfo.environment
        let stateDir = hostStateDirectory(environment: environment)
        try FileManager.default.createDirectory(at: stateDir, withIntermediateDirectories: true)
        var bundledBinDirectory: URL?

        if let override = environment["SM_HOST_BROKER_PATH"], !override.isEmpty {
            process.executableURL = URL(fileURLWithPath: override)
        } else if let bundled = try? MacHostResources.prepareBundled(stateDirectory: stateDir) {
            process.executableURL = bundled.broker
            bundledBinDirectory = bundled.binDirectory
        } else if let repo = findRepositoryRoot() {
            process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
            process.arguments = ["bun", repo.appendingPathComponent("src/main.ts").path]
            process.currentDirectoryURL = repo
        } else {
            throw MacHostSidecarError.brokerUnavailable
        }

        let path = childPath(
            bundledBinDirectory: bundledBinDirectory,
            existing: environment["PATH"],
            home: FileManager.default.homeDirectoryForCurrentUser
        )
        process.environment = brokerEnvironment(port: port, base: environment, path: path)

        let logURL = stateDir.appendingPathComponent("native-host.log")
        if !FileManager.default.fileExists(atPath: logURL.path) {
            FileManager.default.createFile(atPath: logURL.path, contents: nil)
        }
        if let log = try? FileHandle(forWritingTo: logURL) {
            _ = try? log.seekToEnd()
            process.standardOutput = log
            process.standardError = log
        }
        try process.run()
        return process
    }

    nonisolated static func hostStateDirectory(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> URL {
        if let explicit = environment["MUX_STATE_DIR"], !explicit.isEmpty {
            return URL(fileURLWithPath: explicit, isDirectory: true)
        }
        if let muxHome = environment["MUX_HOME"], !muxHome.isEmpty {
            return URL(fileURLWithPath: muxHome, isDirectory: true).appendingPathComponent("state")
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".mux/state", isDirectory: true)
    }

    nonisolated static func childPath(
        bundledBinDirectory: URL?,
        existing: String?,
        home: URL
    ) -> String {
        var candidates: [String] = []
        if let bundledBinDirectory { candidates.append(bundledBinDirectory.path) }
        candidates.append(home.appendingPathComponent(".local/bin").path)
        candidates.append(home.appendingPathComponent(".bun/bin").path)
        candidates.append(contentsOf: ["/opt/homebrew/bin", "/usr/local/bin"])
        if let existing {
            candidates.append(contentsOf: existing.split(separator: ":").map(String.init))
        }
        candidates.append(contentsOf: ["/usr/bin", "/bin", "/usr/sbin", "/sbin"])

        var seen = Set<String>()
        return candidates
            .filter { !$0.isEmpty && seen.insert($0).inserted }
            .joined(separator: ":")
    }

    nonisolated static func brokerEnvironment(
        port: Int,
        base: [String: String],
        path: String
    ) -> [String: String] {
        var environment = base
        environment["MUX_WEB_PORT"] = String(port)
        environment["MUX_WEB_PUBLIC_URL"] = "http://127.0.0.1:\(port)"
        if environment["SM_HOST_RELAY_DISABLED"] != "1" {
            environment["MUX_RELAY_DOMAIN"] = environment["MUX_RELAY_DOMAIN"] ?? "relay.supermux.dev"
        }
        environment["PATH"] = path
        return environment
    }

    nonisolated private static func findRepositoryRoot() -> URL? {
        var candidate = URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true)
        for _ in 0..<8 {
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent("src/main.ts").path),
               FileManager.default.fileExists(atPath: candidate.appendingPathComponent("package.json").path) {
                return candidate
            }
            candidate.deleteLastPathComponent()
        }
        return nil
    }

    nonisolated private static func alternatePortFile() -> URL {
        hostStateDirectory().appendingPathComponent("native-host-port")
    }

    nonisolated static func loadPersistedAlternatePort() -> Int? {
        guard let text = try? String(contentsOf: alternatePortFile(), encoding: .utf8) else { return nil }
        return Int(text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    nonisolated static func savePersistedAlternatePort(_ port: Int) {
        let file = alternatePortFile()
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? String(port).write(to: file, atomically: true, encoding: .utf8)
    }
}

private enum MacHostSidecarError: Error {
    case brokerUnavailable
}

private final class MacHostManagerLock {
    static let shared = MacHostManagerLock()
    private var descriptor: Int32 = -1

    func acquire() -> Bool {
        if descriptor >= 0 { return true }
        let stateDir = MacBrokerSidecar.hostStateDirectory()
        try? FileManager.default.createDirectory(at: stateDir, withIntermediateDirectories: true)
        let path = stateDir.appendingPathComponent("native-host.lock").path
        let fd = Darwin.open(path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { return false }
        guard Darwin.lockf(fd, F_TLOCK, 0) == 0 else {
            Darwin.close(fd)
            return false
        }
        descriptor = fd
        return true
    }

    deinit {
        if descriptor >= 0 {
            _ = Darwin.lockf(descriptor, F_ULOCK, 0)
            Darwin.close(descriptor)
        }
    }
}
#endif
