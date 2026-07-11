import XCTest
import SwiftUI
import Shared
@testable import Supermux

/// Verifies the multi-host fleet-view logic reaches Swift correctly through the KMP `Shared`
/// framework (the ALGORITHM itself is canonically tested on the JVM in `:shared:jvmTest`
/// `FleetModelTest`). Here we assert the Swift↔Kotlin bridge for `FleetModelKt` + `HostView`, and
/// that the SwiftUI `hostDotColor` conversion of the shared packed-ARGB palette is well-formed —
/// so badge colors are guaranteed to match Android's (same shared source).
final class FleetModelTests: XCTestCase {

    func testHostColorIndexIsDeterministicAndInRange() {
        let palette: Int32 = 6   // HOST_PALETTE_SIZE
        for i in 0..<50 {
            let seed = "record-\(i)"
            let a = FleetModelKt.hostColorIndex(seed: seed, paletteSize: palette)
            let b = FleetModelKt.hostColorIndex(seed: seed, paletteSize: palette)
            XCTAssertEqual(a, b, "same seed must yield the same slot")
            XCTAssertTrue(a >= 0 && a < palette, "slot \(a) out of palette range")
        }
    }

    func testShortLabelAndLastSeenBridge() {
        XCTAssertEqual(FleetModelKt.hostShortLabel(displayName: "This host"), "This")
        XCTAssertEqual(FleetModelKt.hostShortLabel(displayName: "MacBook"), "MacBook")
        XCTAssertEqual(FleetModelKt.formatLastSeen(nowMs: 1_000_000, lastSeenAt: 0), "")
        XCTAssertEqual(FleetModelKt.formatLastSeen(nowMs: 1_000_000, lastSeenAt: 1_000_000 - 5_000), "just now")
        XCTAssertEqual(FleetModelKt.formatLastSeen(nowMs: 1_000_000_000, lastSeenAt: 1_000_000_000 - 5 * 60_000), "5m ago")
    }

    func testHostViewDerivationsPreferHostId() {
        // Same durable hostId, different recordId → same color slot (survives a re-pair / backfill).
        let a = HostView(recordId: "rA", hostId: "habc", displayName: "MacBook", online: true, lastSeenAt: 0)
        let b = HostView(recordId: "rB", hostId: "habc", displayName: "MacBook", online: false, lastSeenAt: 0)
        XCTAssertEqual(a.colorIndex, b.colorIndex)
        XCTAssertEqual(a.shortLabel, "MacBook")
    }

    func testFilterSessionsBridgeRuns() {
        // Bridge smoke (empty input avoids constructing a full SessionInfo — the real cases are
        // covered by the JVM test); proves the KMP `filterSessions` is callable from Swift.
        XCTAssertEqual(FleetModelKt.filterSessions(sessions: [], sessionHost: [:], filter: nil).count, 0)
    }

    func testDotColorIsThemeVariedAcrossPalette() {
        for i in 0..<6 {   // HOST_PALETTE_SIZE
            let idx = Int32(i)
            // dark/light differ per slot, and each is stable — the shared packed-ARGB converts to a
            // valid opaque SwiftUI Color.
            XCTAssertNotEqual(hostDotColor(idx, dark: true), hostDotColor(idx, dark: false))
            XCTAssertEqual(hostDotColor(idx, dark: true), hostDotColor(idx, dark: true))
        }
    }
}
