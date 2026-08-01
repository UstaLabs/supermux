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
//   - A per-row identity uses a `:<id>` suffix on native (`session-row:abc123`)
//     and a sibling `data-session-id` attribute on the web, because DOM elements
//     can carry attributes and Compose/SwiftUI nodes cannot.
//   - Only elements a JOURNEY touches belong here. Platform-specific affordances
//     (rail_new, add_host_scan, vnc_surface, …) stay local to their client.
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
  composerSubmit: "composer-submit",
  /** A single rendered message bubble in the transcript. */
  chatMessage: "chat-message",
  /** The affordance that starts a new session. */
  newSession: "new-session",
} as const

export type TestId = (typeof TEST_IDS)[keyof typeof TEST_IDS]

/** Native per-row tag for a session (`session-row:<id>`). */
export function sessionRowId(sessionId: string): string {
  return `${TEST_IDS.sessionRow}:${sessionId}`
}
