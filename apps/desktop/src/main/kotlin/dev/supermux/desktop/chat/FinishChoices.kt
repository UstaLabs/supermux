// Ported verbatim from apps/android/src/main/kotlin/dev/supermux/android/chat/FinishChoices.kt —
// keep in sync (spec 2026-07-10, M4b). PURE: no Compose, no state — a unit-testable policy helper.
package dev.supermux.desktop.chat

/** Whether "Skip tests" may be offered. Hidden only on the PR path when the repo
 *  requires green tests for a PR (skipping would silently defeat that policy). */
fun canSkipTests(action: String, prRequiresGreen: Boolean): Boolean =
    !(action == "pr" && prRequiresGreen)
