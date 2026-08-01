package dev.supermux.ui

/**
 * Canonical UI test identifiers — mirrors `src/shared/test-ids.ts`.
 *
 * The merge-gate journeys are the same user story on every client, so the
 * elements they touch carry the same names everywhere. That is what lets one
 * Maestro flow run unchanged against an Android emulator and an iOS simulator.
 *
 * `tests/test-ids-parity.test.ts` fails if this file and its TS/Swift siblings
 * drift, so adding an id in one place without the others is a red build.
 *
 * Android note: MainActivity sets `testTagsAsResourceId = true`, which is what
 * publishes these tags to uiautomator/Maestro as resource ids. Without it they
 * are visible only to Compose UI tests.
 *
 * Only journey-critical elements live here. Platform-specific affordances
 * (rail_new, add_host_scan, vnc_surface, …) stay local to their client.
 */
object TestIds {
    /** The scrollable list of sessions on the home/list screen. */
    const val SESSION_LIST = "session-list"

    /** One row in that list — see [sessionRow] for the per-session form. */
    const val SESSION_ROW = "session-row"

    /** The chat screen for a single session, once opened. */
    const val CHAT_VIEW = "chat-view"

    /** The text field a user types a prompt into. */
    const val COMPOSER_INPUT = "composer-input"

    /** The button that sends what is in the composer. */
    const val COMPOSER_SUBMIT = "composer-submit"

    /** A single rendered message bubble in the transcript. */
    const val CHAT_MESSAGE = "chat-message"

    /** The affordance that starts a new session. */
    const val NEW_SESSION = "new-session"

    /** Per-row tag for one session (`session-row:<id>`). */
    fun sessionRow(sessionId: String): String = "$SESSION_ROW:$sessionId"
}
