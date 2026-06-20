import XCTest
@testable import Supermux

final class ResizableSplitTests: XCTestCase {
    func testRatioFromDrag() {
        XCTAssertEqual(SplitMath.pct(at: 400, total: 1000, min: 20, max: 80), 40, accuracy: 0.001)
        XCTAssertEqual(SplitMath.pct(at: 50,  total: 1000, min: 20, max: 80), 20, accuracy: 0.001)  // below min
        XCTAssertEqual(SplitMath.pct(at: 950, total: 1000, min: 20, max: 80), 80, accuracy: 0.001)  // above max
    }
    func testGuardsZeroTotal() {
        XCTAssertEqual(SplitMath.pct(at: 100, total: 0, min: 20, max: 80), 20, accuracy: 0.001)
    }
    func testWidthForPct() {
        XCTAssertEqual(SplitMath.width(pct: 25, total: 800), 200, accuracy: 0.001)
        XCTAssertEqual(SplitMath.width(pct: 0,  total: 800), 0,   accuracy: 0.001)
        XCTAssertEqual(SplitMath.width(pct: 50, total: 0), 0, accuracy: 0.001)
    }
}
