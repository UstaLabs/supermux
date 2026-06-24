import XCTest
@testable import Supermux

/// Unit tests for `RoutingTransport`'s phone-first failover decision.
final class RoutingTransportTests: XCTestCase {

    /// Records calls + the reported route across actor hops (test-only).
    final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private var _calls: [String] = []
        private var _route: BrokerRoute?
        func add(_ s: String) { lock.lock(); _calls.append(s); lock.unlock() }
        func setRoute(_ r: BrokerRoute) { lock.lock(); _route = r; lock.unlock() }
        var calls: [String] { lock.lock(); defer { lock.unlock() }; return _calls }
        var route: BrokerRoute? { lock.lock(); defer { lock.unlock() }; return _route }
    }

    /// Configurable fake transport.
    struct FakeTransport: BrokerTransport {
        let name: String
        let recorder: Recorder
        var result: (Data, Int)? = (Data("ok".utf8), 200)
        var error: Error?
        func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
            recorder.add(name)
            if let error { throw error }
            return result!
        }
    }

    struct FixedReachability: RelayReachability { let isReachable: Bool }

    private func makeRouter(reachable: Bool, direct: FakeTransport, relay: FakeTransport,
                            recorder: Recorder) -> RoutingTransport {
        RoutingTransport(direct: direct, relay: relay,
                         reachability: FixedReachability(isReachable: reachable),
                         onRoute: { recorder.setRoute($0) })
    }

    func testPhoneReachableUsesRelayNotDirect() async throws {
        let rec = Recorder()
        let router = makeRouter(reachable: true,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: FakeTransport(name: "relay", recorder: rec),
                                recorder: rec)
        _ = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(rec.calls, ["relay"])
        XCTAssertEqual(rec.route, .phone)
    }

    func testRelayTransportFailureFallsBackToDirect() async throws {
        let rec = Recorder()
        let relay = FakeTransport(name: "relay", recorder: rec, error: RelayError(reason: "unreachable"))
        let router = makeRouter(reachable: true,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: relay, recorder: rec)
        let (_, status) = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(rec.calls, ["relay", "direct"])
        XCTAssertEqual(rec.route, .direct)
        XCTAssertEqual(status, 200)
    }

    func testBrokerErrorStatusIsReturnedWithoutFallback() async throws {
        let rec = Recorder()
        let relay = FakeTransport(name: "relay", recorder: rec, result: (Data(), 500))
        let router = makeRouter(reachable: true,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: relay, recorder: rec)
        let (_, status) = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(status, 500)
        XCTAssertEqual(rec.calls, ["relay"])   // NO fallback on a real broker error
        XCTAssertEqual(rec.route, .phone)
    }

    func testPhoneUnreachableUsesDirectDirectly() async throws {
        let rec = Recorder()
        let router = makeRouter(reachable: false,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: FakeTransport(name: "relay", recorder: rec),
                                recorder: rec)
        _ = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(rec.calls, ["direct"])  // relay never attempted
        XCTAssertEqual(rec.route, .direct)
    }

    func testBothFailReportsOfflineAndThrows() async {
        let rec = Recorder()
        let relay = FakeTransport(name: "relay", recorder: rec, error: RelayError(reason: "x"))
        let direct = FakeTransport(name: "direct", recorder: rec, error: URLError(.notConnectedToInternet))
        let router = makeRouter(reachable: true, direct: direct, relay: relay, recorder: rec)
        do {
            _ = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
            XCTFail("expected throw")
        } catch {
            XCTAssertEqual(rec.calls, ["relay", "direct"])
            XCTAssertEqual(rec.route, .offline)
        }
    }
}
