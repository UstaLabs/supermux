import XCTest
import Shared
@testable import Supermux

/// Regression test for the `BrokerSession` teardown leak: `start()`'s frames collector
/// strongly rebinds `self` while iterating a hot SharedFlow that never completes, so
/// without `stop()` cancelling it the session (and its endlessly-reconnecting socket)
/// out-lives every owner — closed macOS session windows and unpaired/re-paired RootViews
/// kept live WS loops for the whole process lifetime, and `deinit` was unreachable.
///
/// The test drives the REAL code path: `start()` against an unreachable loopback broker
/// (the run loop enters its retry/backoff cycle; the frames collector parks on the empty
/// flow), then `stop()`, then asserts the instance actually deallocates — the exact thing
/// that was impossible before the fix. SKIE propagates the Swift `Task` cancellation into
/// the Kotlin coroutines (unwinding `delay()` / ending the flow collection), which is the
/// mechanism `stop()` (and macOS `wakeKick()`) relies on.
@MainActor
final class BrokerSessionTeardownTests: XCTestCase {

    func testStopReleasesSession() async throws {
        weak var leaked: BrokerSession?

        var broker: BrokerSession? = BrokerSession(baseURL: "http://127.0.0.1:9", token: "test-token")
        leaked = broker
        broker?.start()
        // Let the spawned tasks actually start (dial fails fast on the dead port and the
        // run loop parks in its backoff delay; the frames collector parks on the flow).
        try await Task.sleep(nanoseconds: 300_000_000)

        broker?.stop()
        broker = nil

        // Cancellation has to unwind through SKIE into the Kotlin coroutines before the
        // tasks exit and drop their strong refs — poll briefly instead of asserting
        // instantly. 5s is far beyond the expected few main-loop turns.
        for _ in 0..<50 {
            if leaked == nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertNil(leaked, """
            BrokerSession must deallocate after stop(): the frames collector / run loop \
            must not retain it once cancelled (closed session windows and unpaired roots \
            would otherwise keep live reconnecting sockets forever).
            """)
    }
}
