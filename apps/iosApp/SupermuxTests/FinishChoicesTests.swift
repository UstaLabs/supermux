import XCTest
@testable import Supermux

final class FinishChoicesTests: XCTestCase {
    func testMergeCanAlwaysSkip() {
        XCTAssertTrue(canSkipTests(action: "merge", prRequiresGreen: false))
        XCTAssertTrue(canSkipTests(action: "merge", prRequiresGreen: true))
    }
    func testPrSkipsOnlyWhenNotRequiringGreen() {
        XCTAssertTrue(canSkipTests(action: "pr", prRequiresGreen: false))
        XCTAssertFalse(canSkipTests(action: "pr", prRequiresGreen: true))
    }
}
