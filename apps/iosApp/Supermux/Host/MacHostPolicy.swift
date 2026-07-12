#if os(macOS)
import Foundation

enum MacHostProbeResult: Equatable {
    case supermuxHost(hostId: String)
    case legacySupermux
    case foreignProcess
    case portFree
}

enum MacHostDecision: Equatable {
    case adoptExternal
    case upgradeRequired
    case spawnManaged
    case spawnManagedAlternate
}

enum MacHostOwnership: Equatable {
    case none
    case managed
    case external
}

enum MacHostPolicy {
    static let defaultAlternatePort = 9911

    static func decision(for result: MacHostProbeResult) -> MacHostDecision {
        switch result {
        case .supermuxHost:
            return .adoptExternal
        case .legacySupermux:
            return .upgradeRequired
        case .foreignProcess:
            return .spawnManagedAlternate
        case .portFree:
            return .spawnManaged
        }
    }

    static func alternatePort(persisted: Int?, defaultPort: Int) -> Int {
        guard let persisted, persisted > 0, persisted <= 65_535, persisted != defaultPort else {
            return defaultAlternatePort
        }
        return persisted
    }

    static func mayTerminate(_ ownership: MacHostOwnership) -> Bool {
        ownership == .managed
    }

    static func shouldPersist(environment: [String: String] = ProcessInfo.processInfo.environment) -> Bool {
        environment["SM_HOST_EPHEMERAL"] != "1"
    }

    static func shouldAutostart(environment: [String: String] = ProcessInfo.processInfo.environment) -> Bool {
        environment["XCTestConfigurationFilePath"] == nil && environment["XCTestBundlePath"] == nil
    }
}
#endif
