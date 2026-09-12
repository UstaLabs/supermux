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
 *
 * Workspaces change which list the home screen shows, and the journeys have to
 * know both shapes:
 *  - workspaces OFF → [SESSION_LIST] with [sessionRow] rows.
 *  - workspaces ON  → the sidebar renders `workspaces_list` with
 *    `workspace_row_<workspaceId>` rows (snake_case, local to `SessionsRail` —
 *    not canonical, because only the web and desktop shells have a workspace
 *    sidebar). `tests/ui/compose-dom.ts` accepts either.
 *
 * On Compose-for-Web a `testTag` surfaces as the DOM element's `id`, so these
 * same strings are what the Playwright journeys select on.
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
    const val COMPOSER_SEND = "composer-send"

    /** One transcript message row — a PREFIX; see [chatMessage]. */
    const val CHAT_MESSAGE = "chat-message"

    /** The affordance that starts a new session. */
    const val NEW_SESSION = "new-session"

    /** Per-row tag for one session (`session-row:<id>`). */
    fun sessionRow(sessionId: String): String = "$SESSION_ROW:$sessionId"

    /**
     * Per-row tag for one transcript message (`chat-message:<direction>:<messageId>`).
     *
     * [direction] is the broker's own word for who spoke — `inbound` is the user's message
     * travelling towards the agent, `outbound` is the agent's reply — so a journey can wait for
     * `chat-message:outbound:*` without knowing the id the broker will mint.
     */
    fun chatMessage(direction: String, messageId: String): String =
        "$CHAT_MESSAGE:$direction:$messageId"
}
