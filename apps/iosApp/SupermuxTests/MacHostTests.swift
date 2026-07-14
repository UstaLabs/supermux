import XCTest
import Shared
@testable import Supermux

#if os(macOS)
final class MacHostPolicyTests: XCTestCase {
    func testOnboardingStepsMatchWebSetupOrder() {
        XCTAssertEqual(MacOnboardingStep.allCases.map(\.title), ["Welcome", "Agents", "Connectivity", "Done"])
    }

    func testOnboardingRequiresAuthenticatedAgentOrInstalledOpenCode() {
        XCTAssertFalse(AgentSettingsView.canProceed(with: [
            AgentInstallStatus(kind: "codex", installed: true, authed: false),
            AgentInstallStatus(kind: "opencode", installed: false, authed: false),
        ]))
        XCTAssertTrue(AgentSettingsView.canProceed(with: [
            AgentInstallStatus(kind: "codex", installed: true, authed: true),
        ]))
        XCTAssertTrue(AgentSettingsView.canProceed(with: [
            AgentInstallStatus(kind: "opencode", installed: true, authed: false),
        ]))
    }

    func testHealthySupermuxHostIsAdopted() {
        XCTAssertEqual(
            MacHostPolicy.decision(for: .supermuxHost(hostId: "abcdefghijklmnopqrstuvwxyz")),
            .adoptExternal
        )
    }

    func testLegacySupermuxHostRequiresUpgrade() {
        XCTAssertEqual(MacHostPolicy.decision(for: .legacySupermux), .upgradeRequired)
    }

    func testForeignProcessUsesManagedAlternatePort() {
        XCTAssertEqual(MacHostPolicy.decision(for: .foreignProcess), .spawnManagedAlternate)
        XCTAssertEqual(MacHostPolicy.alternatePort(persisted: nil, defaultPort: 9898), 9911)
        XCTAssertEqual(MacHostPolicy.alternatePort(persisted: 9922, defaultPort: 9898), 9922)
        XCTAssertEqual(MacHostPolicy.alternatePort(persisted: 9898, defaultPort: 9898), 9911)
    }

    func testFreePortSpawnsManagedBroker() {
        XCTAssertEqual(MacHostPolicy.decision(for: .portFree), .spawnManaged)
    }

    func testOnlyManagedOwnershipMayTerminateBroker() {
        XCTAssertTrue(MacHostPolicy.mayTerminate(.managed))
        XCTAssertFalse(MacHostPolicy.mayTerminate(.external))
        XCTAssertFalse(MacHostPolicy.mayTerminate(.none))
    }

    func testEphemeralSmokeModeNeverPersistsPairingState() {
        XCTAssertFalse(MacHostPolicy.shouldPersist(environment: ["SM_HOST_EPHEMERAL": "1"]))
        XCTAssertTrue(MacHostPolicy.shouldPersist(environment: [:]))
    }

    func testXCTestHostNeverAutostartsRealBroker() {
        XCTAssertFalse(MacHostPolicy.shouldAutostart(environment: ["XCTestConfigurationFilePath": "/tmp/tests.xctestconfiguration"]))
        XCTAssertFalse(MacHostPolicy.shouldAutostart(environment: ["XCTestBundlePath": "/tmp/SupermuxMacTests.xctest"]))
        XCTAssertTrue(MacHostPolicy.shouldAutostart(environment: [:]))
    }
}

@MainActor
final class MacBrokerSidecarTests: XCTestCase {
    private final class FakeProcess: MacHostProcess {
        var isRunning = true
        var terminateCount = 0

        func terminate() {
            terminateCount += 1
            isRunning = false
        }
    }

    func testHealthyBrokerIsAdoptedWithoutSpawning() async {
        var spawnCount = 0
        let sidecar = MacBrokerSidecar(
            probe: { _ in .supermuxHost(hostId: "host-adopted") },
            spawn: { _ in spawnCount += 1; return FakeProcess() },
            acquireManagerLock: { true }
        )

        await sidecar.start()

        XCTAssertEqual(sidecar.phase, .adopted)
        XCTAssertEqual(sidecar.ownership, .external)
        XCTAssertEqual(sidecar.hostId, "host-adopted")
        XCTAssertEqual(spawnCount, 0)
    }

    func testFreePortSpawnsManagedBrokerAndStopsOwnedChild() async {
        let process = FakeProcess()
        var probes = [MacHostProbeResult.portFree, .supermuxHost(hostId: "host-managed")]
        var spawnedPort: Int?
        let sidecar = MacBrokerSidecar(
            probe: { _ in probes.removeFirst() },
            spawn: { port in spawnedPort = port; return process },
            acquireManagerLock: { true },
            healthPollDelay: 0
        )

        await sidecar.start()
        sidecar.stop()

        XCTAssertEqual(spawnedPort, 9898)
        XCTAssertEqual(sidecar.ownership, .none)
        XCTAssertEqual(sidecar.phase, .stopped)
        XCTAssertEqual(process.terminateCount, 1)
    }

    func testForeignPortSpawnsOnPersistedAlternate() async {
        var probedPorts: [Int] = []
        var spawnedPort: Int?
        let sidecar = MacBrokerSidecar(
            probe: { port in
                probedPorts.append(port)
                return port == 9898 ? .foreignProcess : .supermuxHost(hostId: "host-alt")
            },
            spawn: { port in spawnedPort = port; return FakeProcess() },
            acquireManagerLock: { true },
            loadAlternatePort: { 9922 },
            healthPollDelay: 0
        )

        await sidecar.start()

        XCTAssertEqual(probedPorts, [9898, 9922])
        XCTAssertEqual(spawnedPort, 9922)
        XCTAssertEqual(sidecar.effectivePort, 9922)
        XCTAssertEqual(sidecar.phase, .online)
    }

    func testLosingManagerLockNeverSpawnsDuplicate() async {
        var spawnCount = 0
        let sidecar = MacBrokerSidecar(
            probe: { _ in .portFree },
            spawn: { _ in spawnCount += 1; return FakeProcess() },
            acquireManagerLock: { false },
            healthAttempts: 1,
            healthPollDelay: 0
        )

        await sidecar.start()

        XCTAssertEqual(spawnCount, 0)
        XCTAssertEqual(sidecar.phase, .failed)
        XCTAssertEqual(sidecar.ownership, .none)
    }

    func testStoppingAdoptedBrokerDoesNotTerminateAnything() async {
        let process = FakeProcess()
        let sidecar = MacBrokerSidecar(
            probe: { _ in .supermuxHost(hostId: "external") },
            spawn: { _ in process },
            acquireManagerLock: { true }
        )

        await sidecar.start()
        sidecar.stop()

        XCTAssertEqual(process.terminateCount, 0)
        XCTAssertEqual(sidecar.phase, .stopped)
    }

    func testKeepAliveHandoffOnlyAdoptsAndNeverSpawns() async {
        var probes = [MacHostProbeResult.portFree, .supermuxHost(hostId: "launchd-host")]
        var spawnCount = 0
        let sidecar = MacBrokerSidecar(
            probe: { _ in probes.removeFirst() },
            spawn: { _ in spawnCount += 1; return FakeProcess() },
            acquireManagerLock: { true },
            healthAttempts: 2,
            healthPollDelay: 0
        )

        sidecar.stop()
        await sidecar.adoptKeepAliveHost()

        XCTAssertEqual(spawnCount, 0)
        XCTAssertEqual(sidecar.hostId, "launchd-host")
        XCTAssertEqual(sidecar.ownership, .external)
        XCTAssertEqual(sidecar.phase, .adopted)
    }

    func testPackagedBrokerPathIncludesHelpersAndCommonAgentLocations() {
        let path = MacBrokerSidecar.childPath(
            bundledBinDirectory: URL(fileURLWithPath: "/tmp/mux-bin"),
            existing: "/usr/bin:/bin",
            home: URL(fileURLWithPath: "/Users/test")
        )

        XCTAssertEqual(
            path.split(separator: ":").map(String.init),
            [
                "/tmp/mux-bin",
                "/Users/test/.local/bin",
                "/Users/test/.bun/bin",
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/usr/bin",
                "/bin",
                "/usr/sbin",
                "/sbin",
            ]
        )
    }

    func testBrokerEnvironmentAlwaysSetsValidLocalWebPair() {
        let environment = MacBrokerSidecar.brokerEnvironment(
            port: 9911,
            base: ["EXISTING": "kept"],
            path: "/tmp/mux-bin:/usr/bin",
            hostName: "Ahmet’s MacBook Air"
        )

        XCTAssertEqual(environment["MUX_WEB_PORT"], "9911")
        XCTAssertEqual(environment["MUX_WEB_PUBLIC_URL"], "http://127.0.0.1:9911")
        XCTAssertEqual(environment["MUX_RELAY_DOMAIN"], "relay.supermux.dev")
        XCTAssertEqual(environment["PATH"], "/tmp/mux-bin:/usr/bin")
        XCTAssertEqual(environment["MUX_HOST_NAME"], "Ahmet’s MacBook Air")
        XCTAssertEqual(environment["EXISTING"], "kept")
    }
}

final class MacHostBootstrapTests: XCTestCase {
    private func response(
        _ url: URL,
        status: Int = 200,
        headers: [String: String] = [:],
        body: String
    ) -> (Data, HTTPURLResponse) {
        (
            Data(body.utf8),
            HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: headers)!
        )
    }

    func testExistingLocalTokenIsReusedToMintPhoneClaim() async throws {
        var requests: [URLRequest] = []
        let bootstrap = MacHostBootstrap(relayAttempts: 1) { request in
            requests.append(request)
            return self.response(request.url!, body: #"{"claimSecret":"phone-secret"}"#)
        }

        let result = await bootstrap.prepare(
            localBaseURL: "http://127.0.0.1:9898",
            hostId: "abcdefghijklmnopqrstuvwxyz",
            hostName: "This computer",
            existingToken: "local-token"
        )

        XCTAssertEqual(result?.localToken, "local-token")
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].url?.path, "/pair/mint-claim")
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Authorization"), "Bearer local-token")
        XCTAssertEqual(requests[1].url?.path, "/me")
    }

    func testFreshBrokerBootstrapsTokenFromCookieBeforeMintingClaim() async {
        var paths: [String] = []
        let bootstrap = MacHostBootstrap(relayAttempts: 1) { request in
            paths.append(request.url!.path)
            if request.url!.path == "/pair/claim" {
                return self.response(
                    request.url!,
                    headers: ["Set-Cookie": "cmux_token=fresh-token; Path=/; HttpOnly"],
                    body: #"{"paired":true}"#
                )
            }
            return self.response(request.url!, body: #"{"claimSecret":"fresh-secret"}"#)
        }

        let result = await bootstrap.prepare(
            localBaseURL: "http://127.0.0.1:9898",
            hostId: "abcdefghijklmnopqrstuvwxyz",
            hostName: "My Mac",
            existingToken: nil
        )

        XCTAssertEqual(paths, ["/pair/claim", "/pair/mint-claim", "/me"])
        XCTAssertEqual(result?.localToken, "fresh-token")
    }

    func testPairingPayloadMatchesSharedContract() async throws {
        let bootstrap = MacHostBootstrap(relayAttempts: 1) { request in
            if request.url!.path == "/me" {
                return self.response(request.url!, body: #"{"paired":true,"relayUrl":"https://h-abcdefghijklmnopqrstuvwxyz.relay.supermux.dev"}"#)
            }
            return self.response(
                request.url!,
                body: #"{"claimSecret":"phone-secret","expiresAt":"2026-07-14T01:02:03.000Z"}"#
            )
        }

        let result = await bootstrap.prepare(
            localBaseURL: "http://127.0.0.1:9911",
            pairingDirectURL: "http://192.168.1.101:9911",
            hostId: "abcdefghijklmnopqrstuvwxyz",
            hostName: "Studio Mac",
            existingToken: "token"
        )
        let payload = try XCTUnwrap(result?.payloadJSON.data(using: .utf8))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: payload) as? [String: Any])

        XCTAssertEqual(json["v"] as? Int, 1)
        XCTAssertEqual(json["action"] as? String, "pair")
        XCTAssertEqual(json["hostId"] as? String, "abcdefghijklmnopqrstuvwxyz")
        XCTAssertEqual(json["name"] as? String, "Studio Mac")
        XCTAssertEqual(json["directUrl"] as? String, "http://192.168.1.101:9911")
        XCTAssertEqual(json["claimSecret"] as? String, "phone-secret")
        XCTAssertEqual(json["relayUrl"] as? String, "https://h-abcdefghijklmnopqrstuvwxyz.relay.supermux.dev")
        XCTAssertEqual(
            try XCTUnwrap(result?.expiresAt).timeIntervalSince1970,
            1_783_990_923,
            accuracy: 0.001
        )
    }

    func testWaitsForRelayBeforePublishingPairingPayload() async throws {
        var relayChecks = 0
        let bootstrap = MacHostBootstrap(relayAttempts: 3, relayPollDelay: 0) { request in
            if request.url!.path == "/me" {
                relayChecks += 1
                let body = relayChecks < 3
                    ? #"{"paired":true}"#
                    : #"{"paired":true,"relayUrl":"https://h-abcdefghijklmnopqrstuvwxyz.relay.supermux.dev"}"#
                return self.response(request.url!, body: body)
            }
            return self.response(request.url!, body: #"{"claimSecret":"phone-secret"}"#)
        }

        let result = await bootstrap.prepare(
            localBaseURL: "http://127.0.0.1:9898",
            pairingDirectURL: "http://192.168.1.101:9898",
            hostId: "abcdefghijklmnopqrstuvwxyz",
            hostName: "Studio Mac",
            existingToken: "token"
        )

        XCTAssertEqual(relayChecks, 3)
        XCTAssertEqual(result?.relayURL, "https://h-abcdefghijklmnopqrstuvwxyz.relay.supermux.dev")
    }

    func testBootstrapFailureReturnsNilWithoutPartialResult() async {
        let bootstrap = MacHostBootstrap(relayAttempts: 1) { request in
            self.response(request.url!, status: 403, body: #"{"error":"already set up"}"#)
        }

        let result = await bootstrap.prepare(
            localBaseURL: "http://127.0.0.1:9898",
            hostId: "abcdefghijklmnopqrstuvwxyz",
            hostName: "My Mac",
            existingToken: nil
        )

        XCTAssertNil(result)
    }
}

final class MacHostNetworkTests: XCTestCase {
    func testPhoneURLPrefersPrivateIPv4OverLoopbackAndIPv6() {
        XCTAssertEqual(
            MacHostNetwork.directURL(
                port: 9898,
                addresses: ["::1", "127.0.0.1", "fe80::1234", "192.168.1.101", "100.64.1.2"]
            ),
            "http://192.168.1.101:9898"
        )
    }

    func testPhoneURLFallsBackToNonLoopbackIPv4ThenLoopback() {
        XCTAssertEqual(
            MacHostNetwork.directURL(port: 9911, addresses: ["203.0.113.8"]),
            "http://203.0.113.8:9911"
        )
        XCTAssertEqual(
            MacHostNetwork.directURL(port: 9911, addresses: ["::1", "127.0.0.1"]),
            "http://127.0.0.1:9911"
        )
    }
}

final class MacHostKeepAliveTests: XCTestCase {
    func testLaunchAgentPlistKeepsBundledBrokerAliveAndEscapesValues() throws {
        let plist = MacHostKeepAlive.plist(
            brokerPath: "/Applications/Supermux & Tools/supermux-broker",
            port: 9911,
            binDirectory: "/Applications/Supermux & Tools",
            stateDirectory: "/Users/a&b/.mux/state",
            hostName: "Ahmet & Mac"
        )

        let data = try XCTUnwrap(plist.data(using: .utf8))
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        )
        let environment = try XCTUnwrap(object["EnvironmentVariables"] as? [String: String])

        XCTAssertEqual(object["Label"] as? String, "dev.supermux.host")
        XCTAssertEqual(object["RunAtLoad"] as? Bool, true)
        XCTAssertEqual(object["KeepAlive"] as? Bool, true)
        XCTAssertEqual(object["ProgramArguments"] as? [String], ["/Applications/Supermux & Tools/supermux-broker"])
        XCTAssertEqual(environment["MUX_WEB_PORT"], "9911")
        XCTAssertEqual(environment["MUX_WEB_PUBLIC_URL"], "http://127.0.0.1:9911")
        XCTAssertEqual(environment["MUX_STATE_DIR"], "/Users/a&b/.mux/state")
        XCTAssertEqual(environment["MUX_RELAY_DOMAIN"], "relay.supermux.dev")
        XCTAssertEqual(environment["MUX_HOST_NAME"], "Ahmet & Mac")
        XCTAssertTrue(plist.contains("/Applications/Supermux &amp; Tools/supermux-broker"))
        XCTAssertTrue(plist.contains("/Users/a&amp;b/.mux/state"))
    }

    func testLaunchAgentPathIsStableAndScopedToCurrentUser() {
        let path = MacHostKeepAlive.plistURL(home: URL(fileURLWithPath: "/Users/test"))
        XCTAssertEqual(path.path, "/Users/test/Library/LaunchAgents/dev.supermux.host.plist")
    }
}

final class MacHostResourcesTests: XCTestCase {
    func testBundledHelpersAreMaterializedExecutableIntoMuxBin() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("mac-host-resources-\(UUID().uuidString)")
        let resources = root.appendingPathComponent("resources")
        let state = root.appendingPathComponent("state")
        try FileManager.default.createDirectory(at: resources, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        for name in ["supermux-broker", "frpc", "tmux"] {
            try "\(name)-bytes".write(
                to: resources.appendingPathComponent(name),
                atomically: true,
                encoding: .utf8
            )
        }

        let binaries = try MacHostResources.prepare(
            resourceDirectory: resources,
            stateDirectory: state
        )

        XCTAssertEqual(try String(contentsOf: binaries.broker, encoding: .utf8), "supermux-broker-bytes")
        XCTAssertEqual(try String(contentsOf: binaries.frpc!, encoding: .utf8), "frpc-bytes")
        XCTAssertEqual(try String(contentsOf: binaries.tmux!, encoding: .utf8), "tmux-bytes")
        XCTAssertTrue(FileManager.default.isExecutableFile(atPath: binaries.broker.path))
        XCTAssertTrue(FileManager.default.isExecutableFile(atPath: binaries.frpc!.path))
        XCTAssertTrue(FileManager.default.isExecutableFile(atPath: binaries.tmux!.path))
        XCTAssertEqual(binaries.binDirectory, state.appendingPathComponent("bin"))
    }

    func testMissingBundledBrokerFailsLoudly() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("mac-host-resources-\(UUID().uuidString)")
        let resources = root.appendingPathComponent("resources")
        try FileManager.default.createDirectory(at: resources, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        XCTAssertThrowsError(
            try MacHostResources.prepare(resourceDirectory: resources, stateDirectory: root.appendingPathComponent("state"))
        )
    }

    func testSameSizeAppUpdateRematerializesBroker() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("mac-host-resources-\(UUID().uuidString)")
        let resources = root.appendingPathComponent("resources")
        let state = root.appendingPathComponent("state")
        try FileManager.default.createDirectory(at: resources, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = resources.appendingPathComponent("supermux-broker")
        try "aaaa".write(to: source, atomically: true, encoding: .utf8)
        _ = try MacHostResources.prepare(resourceDirectory: resources, stateDirectory: state)

        try "bbbb".write(to: source, atomically: true, encoding: .utf8)
        let updated = try MacHostResources.prepare(resourceDirectory: resources, stateDirectory: state)

        XCTAssertEqual(try String(contentsOf: updated.broker, encoding: .utf8), "bbbb")
    }
}

@MainActor
final class MacHostCoordinatorTests: XCTestCase {
    func testSuccessfulPreparationSelfPairsAndPublishesPhoneQRPayload() async {
        var persisted: (String, String, String)?
        let coordinator = MacHostCoordinator(
            hostName: "My Mac",
            startHost: {
                MacHostEndpoint(baseURL: "http://127.0.0.1:9898", hostId: "abcdefghijklmnopqrstuvwxyz", port: 9898)
            },
            existingToken: { nil },
            prepare: { _, _, _ in
                MacHostPreparedClaim(localToken: "local-token", payloadJSON: #"{"action":"pair"}"#, relayURL: nil)
            },
            persistLocalPair: { token, url, hostId, _ in persisted = (token, url, hostId) },
            installKeepAlive: { _ in true }
        )

        await coordinator.start()

        XCTAssertEqual(coordinator.state, .ready(payloadJSON: #"{"action":"pair"}"#))
        XCTAssertEqual(persisted?.0, "local-token")
        XCTAssertEqual(persisted?.1, "http://127.0.0.1:9898")
        XCTAssertEqual(persisted?.2, "abcdefghijklmnopqrstuvwxyz")
    }

    func testFailureCanRetryToReady() async {
        var attempts = 0
        let coordinator = MacHostCoordinator(
            hostName: "My Mac",
            startHost: {
                attempts += 1
                return attempts == 1 ? nil : MacHostEndpoint(
                    baseURL: "http://127.0.0.1:9898",
                    hostId: "abcdefghijklmnopqrstuvwxyz",
                    port: 9898
                )
            },
            existingToken: { "token" },
            prepare: { _, _, _ in MacHostPreparedClaim(localToken: "token", payloadJSON: "payload", relayURL: nil) },
            persistLocalPair: { _, _, _, _ in },
            installKeepAlive: { _ in true }
        )

        await coordinator.start()
        XCTAssertEqual(coordinator.state, .failed("Couldn't start the local Supermux host."))

        await coordinator.start()
        XCTAssertEqual(coordinator.state, .ready(payloadJSON: "payload"))
    }

    func testPairingClaimCanRefreshWithoutRestartingHost() async {
        var starts = 0
        var preparations = 0
        let coordinator = MacHostCoordinator(
            hostName: "My Mac",
            startHost: {
                starts += 1
                return MacHostEndpoint(
                    baseURL: "http://127.0.0.1:9898",
                    hostId: "abcdefghijklmnopqrstuvwxyz",
                    port: 9898
                )
            },
            existingToken: { "token" },
            prepare: { _, _, _ in
                preparations += 1
                return MacHostPreparedClaim(
                    localToken: "token",
                    payloadJSON: "payload-\(preparations)",
                    relayURL: nil
                )
            },
            persistLocalPair: { _, _, _, _ in },
            installKeepAlive: { _ in true }
        )

        await coordinator.start()
        let refreshed = await coordinator.refreshPairingClaim()
        XCTAssertTrue(refreshed)

        XCTAssertEqual(starts, 1)
        XCTAssertEqual(preparations, 2)
        XCTAssertEqual(coordinator.state, .ready(payloadJSON: "payload-2"))
    }

    func testPairingMonitorFindsNewDeviceInBrokerOrder() {
        XCTAssertEqual(
            MacPairingMonitor.newlyPairedDevice(
                baseline: ["This computer", "iPhone"],
                current: ["This computer", "Pixel", "iPhone"]
            ),
            "Pixel"
        )
        XCTAssertNil(
            MacPairingMonitor.newlyPairedDevice(
                baseline: ["This computer"],
                current: ["This computer"]
            )
        )
    }

    func testRequiredRelayNeverPublishesLocalOnlyQRCode() async {
        let coordinator = MacHostCoordinator(
            hostName: "My Mac",
            requiresRelay: true,
            startHost: {
                MacHostEndpoint(baseURL: "http://127.0.0.1:9898", hostId: "abcdefghijklmnopqrstuvwxyz", port: 9898)
            },
            existingToken: { "token" },
            prepare: { _, _, _ in
                MacHostPreparedClaim(localToken: "token", payloadJSON: "local-only", relayURL: nil)
            },
            persistLocalPair: { _, _, _, _ in },
            installKeepAlive: { _ in true }
        )

        await coordinator.start()

        XCTAssertEqual(
            coordinator.state,
            .failed("Couldn't bring the Supermux relay online. Check your connection, then retry.")
        )
    }

    func testFinishInstallsKeepAliveOnlyWhenSelected() async {
        var installedPorts: [Int] = []
        var stopCount = 0
        let coordinator = MacHostCoordinator(
            hostName: "My Mac",
            startHost: {
                MacHostEndpoint(baseURL: "http://127.0.0.1:9911", hostId: "abcdefghijklmnopqrstuvwxyz", port: 9911)
            },
            existingToken: { "token" },
            prepare: { _, _, _ in MacHostPreparedClaim(localToken: "token", payloadJSON: "payload", relayURL: nil) },
            persistLocalPair: { _, _, _, _ in },
            installKeepAlive: { port in installedPorts.append(port); return true },
            stopHost: { stopCount += 1 },
            restartHost: {}
        )

        await coordinator.start()
        XCTAssertTrue(coordinator.finish(keepAlive: false))
        XCTAssertEqual(installedPorts, [])
        XCTAssertEqual(stopCount, 0)
        XCTAssertTrue(coordinator.finish(keepAlive: true))
        XCTAssertEqual(installedPorts, [9911])
        XCTAssertEqual(stopCount, 1)
    }

    func testFailedKeepAliveInstallLeavesInAppHostRunning() async {
        var stopCount = 0
        var restartCount = 0
        let coordinator = MacHostCoordinator(
            hostName: "My Mac",
            startHost: {
                MacHostEndpoint(baseURL: "http://127.0.0.1:9898", hostId: "abcdefghijklmnopqrstuvwxyz", port: 9898)
            },
            existingToken: { "token" },
            prepare: { _, _, _ in MacHostPreparedClaim(localToken: "token", payloadJSON: "payload", relayURL: nil) },
            persistLocalPair: { _, _, _, _ in },
            installKeepAlive: { _ in false },
            stopHost: { stopCount += 1 },
            restartHost: { restartCount += 1 }
        )

        await coordinator.start()

        XCTAssertFalse(coordinator.finish(keepAlive: true))
        XCTAssertEqual(stopCount, 0)
        XCTAssertEqual(restartCount, 0)
    }

    func testApplicationTerminationStopsOnlyThroughSidecarOwnershipPolicy() {
        var stopCount = 0
        let coordinator = MacHostCoordinator(
            hostName: "My Mac",
            startHost: { nil },
            existingToken: { nil },
            prepare: { _, _, _ in nil },
            persistLocalPair: { _, _, _, _ in },
            installKeepAlive: { _ in true },
            stopHost: { stopCount += 1 },
            restartHost: {}
        )

        coordinator.applicationWillTerminate()

        XCTAssertEqual(stopCount, 1)
    }

    func testQRCodeGeneratorProducesBitmap() throws {
        let image = try XCTUnwrap(MacHostQRCode.image(for: "pairing payload"))
        XCTAssertGreaterThan(image.size.width, 100)
        XCTAssertEqual(image.size.width, image.size.height)
    }
}
#endif
