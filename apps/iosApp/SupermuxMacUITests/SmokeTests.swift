import XCTest

/// Critical-path smoke: app launches, auto-pairs from env, reaches the workspace,
/// and shows the sessions sidebar. Requires SM_PAIR_TOKEN/SM_PAIR_BASE in the
/// runner env (see the xcodebuild invocation) pointing at a reachable broker.
final class SmokeTests: XCTestCase {
    func testLaunchPairAndShowSessions() {
        let app = XCUIApplication()
        let env = ProcessInfo.processInfo.environment
        app.launchEnvironment["SM_PAIR_TOKEN"] = env["SM_PAIR_TOKEN"] ?? ""
        app.launchEnvironment["SM_PAIR_BASE"] = env["SM_PAIR_BASE"] ?? ""
        app.launch()

        // Paired + synced: the workspace window exists and is not the pairing screen.
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 15))
        // The New Session affordance is an SF-symbol-only button (SessionsRailView,
        // collapsed sidebar) or a composite icon+text button (SessionsListView,
        // expanded sidebar) depending on persisted sidebar state — neither has a
        // plain "New Session" text label, so both sites carry an explicit
        // accessibilityIdentifier("new-session") to match on regardless of which
        // sidebar layout renders.
        let newSession = app.buttons["new-session"].firstMatch
        XCTAssertTrue(newSession.waitForExistence(timeout: 30),
                      "workspace did not appear — pairing or WS sync failed")
    }
}
