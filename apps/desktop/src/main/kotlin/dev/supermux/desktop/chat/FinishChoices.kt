// Ported from apps/android/src/main/kotlin/dev/supermux/android/chat/FinishChoices.kt (canSkipTests)
// — keep in sync (spec 2026-07-10, M4b). PURE: no Compose, no state — unit-testable policy helpers.
// The finish-dot derivations live here too so SessionDetail and FinishButton share ONE source of
// truth the tests exercise directly (rather than a test-local copy).
package dev.supermux.desktop.chat

import dev.supermux.proto.FinishJobDto

/** Whether "Skip tests" may be offered. Hidden only on the PR path when the repo
 *  requires green tests for a PR (skipping would silently defeat that policy). */
fun canSkipTests(action: String, prRequiresGreen: Boolean): Boolean =
    !(action == "pr" && prRequiresGreen)

/** Whether the header's unacked dot should show: a terminal (non-running) finish result the user
 *  hasn't opened/acked yet (Android SessionShellDetail parity). [acked] is whether THIS job's
 *  startedAt has been acked (see [DesktopAppState.isFinishAcked]). Pure + shared by SessionDetail
 *  and its test. */
fun isFinishUnacked(job: FinishJobDto?, acked: Boolean): Boolean =
    job != null && job.status != "running" && !acked

/** Whether the unacked dot should render in the ERROR color (a failed finish) vs the primary color
 *  (a successful one). Pure so FinishButton and its test agree. */
fun finishDotIsError(job: FinishJobDto?): Boolean = job?.status == "failed"
