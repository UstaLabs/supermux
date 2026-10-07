// Canonical UI test identifiers — the SAME strings on every client.
//
// Why one vocabulary: the merge-gate journeys (pair → open a session → converse
// → review a diff) are the same user story on web, Android, iOS and desktop. If
// each client names its elements differently, every journey has to be written
// four times and a Maestro flow cannot run unchanged on both an Android emulator
// and an iOS simulator. Naming them identically is what makes one flow portable.
//
// This file is the source of truth. The mirrors are:
//   apps/shared/src/commonMain/kotlin/dev/supermux/ui/TestIds.kt   (Android + Compose Desktop)
//   apps/iosApp/Supermux/DesignSystem/TestIds.swift                (iOS + macOS)
// tests/test-ids-parity.test.ts fails if the three ever drift apart, so adding an
// id here without adding it there is a red build, not a silent divergence.
//
// Conventions:
//   - kebab-case, lowercase.
//   - A per-row identity uses a `:<id>` suffix (`session-row:abc123`,
//     `chat-message:outbound:m17`). Compose is now the only client renderer, and
//     a Compose node carries a single `testTag` and no sibling attributes — on
//     Compose-for-Web that tag becomes the DOM element's `id`, so a journey
//     selects a row with a prefix selector (`[id^="chat-message:outbound:"]`)
//     rather than a tag + data-attribute pair.
//   - Only elements a JOURNEY touches belong here. Platform-specific affordances
//     (rail_new, add_host_scan, vnc_surface, …) stay local to their client.
//
// Workspaces change which list the home screen shows, and the journeys have to
// know both shapes:
//   - workspaces OFF → `session-list` with `session-row:<sessionId>` rows (the
//     ids below).
//   - workspaces ON  → the sidebar renders `workspaces_list` with
//     `workspace_row_<workspaceId>` rows (snake_case, local to `:ui`'s
//     `SessionsRail`/`WorkspacesList` — NOT canonical, because only the web and
//     desktop shells have a workspace sidebar at all). `tests/ui/compose-dom.ts`
//     `waitReady()` accepts either, and `scripts/test-broker.sh` seeds the
//     workspaces-ON shape.
export const TEST_IDS = {
  /** The scrollable list of sessions on the home/list screen. */
  sessionList: "session-list",
  /** One row in that list. Native appends `:<sessionId>`; web pairs it with data-session-id. */
  sessionRow: "session-row",
  /** The chat screen for a single session, once opened. */
  chatView: "chat-view",
  /** The text field a user types a prompt into. */
  composerInput: "composer-input",
  /** The button that sends what is in the composer. */
  composerSend: "composer-send",
  /** A single rendered message row in the transcript — a PREFIX; see {@link chatMessageId}. */
  chatMessage: "chat-message",
  /** The affordance that starts a new session. */
  newSession: "new-session",
} as const

export type TestId = (typeof TEST_IDS)[keyof typeof TEST_IDS]

/** Native per-row tag for a session (`session-row:<id>`). */
export function sessionRowId(sessionId: string): string {
  return `${TEST_IDS.sessionRow}:${sessionId}`
}

/**
 * Per-row tag for one transcript message (`chat-message:<direction>:<messageId>`).
 *
 * `direction` is the broker's own word for who spoke — `inbound` is the user's
 * message travelling towards the agent, `outbound` is the agent's reply — so a
 * journey can wait for `[id^="chat-message:outbound:"]` without knowing the
 * message id the broker will mint.
 */
export function chatMessageId(direction: string, messageId: string): string {
  return `${TEST_IDS.chatMessage}:${direction}:${messageId}`
}
