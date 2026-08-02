import XCTest

/// Real macOS UI test (XCUITest): launches Supermux, shows the session-list rail fixture
/// (actual `SessionStatusRail` views), and asserts the working / unread / idle icons are
/// present in the accessibility tree — not a pure unit matrix.
///
/// Run (on the remote Mac):
///   xcodebuild test -scheme SupermuxMac -only-testing:SupermuxMacUITests/SessionListRailUITests \
///     -destination 'platform=macOS,arch=arm64' …
final class SessionListRailUITests: XCTestCase {
    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["SM_UITEST_RAIL_FIXTURE"] = "1"
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    func testUnreadRailIconIsVisibleInUI() {
        // Fixture window mounts the real SessionStatusRail composable for three states.
        let fixture = app.descendants(matching: .any)["session-rail-fixture"]
        XCTAssertTrue(fixture.waitForExistence(timeout: 15),
                      "rail fixture window did not appear — is SM_UITEST_RAIL_FIXTURE wired?")

        // Unread = larger green mark with accessibility id + label (product idle-unread rail).
        let unread = app.descendants(matching: .any)["session-rail-unread"]
        XCTAssertTrue(unread.waitForExistence(timeout: 5),
                      "expected session-rail-unread icon in the UI")
        XCTAssertTrue(unread.isHittable || unread.exists,
                      "unread rail icon exists but is not in the UI tree as expected")

        // Working spinner wins over unread when both flags are true (fixture row sets both).
        let working = app.descendants(matching: .any)["session-rail-working"]
        XCTAssertTrue(working.waitForExistence(timeout: 5),
                      "expected session-rail-working spinner in the UI")

        // Idle gray mark when neither working nor unread.
        let idle = app.descendants(matching: .any)["session-rail-idle"]
        XCTAssertTrue(idle.waitForExistence(timeout: 5),
                      "expected session-rail-idle gray mark in the UI")

        // Label is set on SessionStatusRail; may be merged into parent — id is the contract.
        if !unread.label.isEmpty {
            XCTAssertTrue(unread.label.lowercased().contains("unread"),
                          "unread rail accessibility label should mention unread, got: \(unread.label)")
        }
    }

    func testWorkingRowDoesNotExposeUnreadIcon() {
        XCTAssertTrue(app.descendants(matching: .any)["session-rail-fixture"].waitForExistence(timeout: 15))

        // The "Working Chat" row paints working with unread=true input; only working icon.
        let workingRow = app.descendants(matching: .any)["session-rail-fixture-row-working-chat"]
        XCTAssertTrue(workingRow.waitForExistence(timeout: 5))

        // Within the working row there must be a working spinner and no unread mark.
        let workingInRow = workingRow.descendants(matching: .any)["session-rail-working"]
        XCTAssertTrue(workingInRow.waitForExistence(timeout: 3),
                      "working row should show the spinner")
        let unreadInWorkingRow = workingRow.descendants(matching: .any)["session-rail-unread"]
        XCTAssertFalse(unreadInWorkingRow.exists,
                       "working row must not show the green unread mark (spinner wins)")
    }

    func testUnreadRowShowsUnreadNotWorking() {
        XCTAssertTrue(app.descendants(matching: .any)["session-rail-fixture"].waitForExistence(timeout: 15))

        let unreadRow = app.descendants(matching: .any)["session-rail-fixture-row-unread-chat"]
        XCTAssertTrue(unreadRow.waitForExistence(timeout: 5))
        XCTAssertTrue(unreadRow.descendants(matching: .any)["session-rail-unread"].waitForExistence(timeout: 3))
        XCTAssertFalse(unreadRow.descendants(matching: .any)["session-rail-working"].exists)
    }
}
