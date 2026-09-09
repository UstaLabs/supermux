import Foundation

/// Canonical UI test identifiers — mirrors `src/shared/test-ids.ts`.
///
/// The merge-gate journeys are the same user story on every client, so the
/// elements they touch carry the same names everywhere. That is what lets one
/// Maestro flow run unchanged against an Android emulator and an iOS simulator,
/// and what lets an XCUITest query the same names a Playwright spec uses.
///
/// `tests/test-ids-parity.test.ts` fails if this file and its TS/Kotlin siblings
/// drift, so adding an id in one place without the others is a red build.
///
/// Apply with `.accessibilityIdentifier(TestIds.composerInput)`.
///
/// Only journey-critical elements live here. Platform-specific affordances
/// (add_host_scan, host_chip_all, mac_host_pairing_qr, …) stay local.
enum TestIds {
    /// The scrollable list of sessions on the home/list screen.
    static let sessionList = "session-list"

    /// One row in that list — see `sessionRow(_:)` for the per-session form.
    static let sessionRow = "session-row"

    /// The chat screen for a single session, once opened.
    static let chatView = "chat-view"

    /// The text field a user types a prompt into.
    static let composerInput = "composer-input"

    /// The button that sends what is in the composer.
    static let composerSubmit = "composer-submit"

    /// A single rendered message bubble in the transcript.
    static let chatMessage = "chat-message"

    /// The affordance that starts a new session.
    static let newSession = "new-session"

    /// Per-row identifier for one session (`session-row:<id>`).
    static func sessionRow(_ sessionId: String) -> String {
        "\(sessionRow):\(sessionId)"
    }
}
