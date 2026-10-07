// PURE finish-policy helpers: no Compose, no state — the ONE source of truth Android's FinishSheet
// and desktop's FinishDialog both use (spec 2026-07-10, M4b). The finish-dot derivations live here
// too so SessionDetail and FinishButton share one definition the tests exercise directly.
package dev.supermux.chat

import dev.supermux.proto.FinishJobDto

/** Whether "Skip tests" may be offered. Hidden only on the PR path when the repo
 *  requires green tests for a PR (skipping would silently defeat that policy). */
fun canSkipTests(action: String, prRequiresGreen: Boolean): Boolean =
    !(action == "pr" && prRequiresGreen)

/** Whether the header's unacked dot should show: a terminal (non-running) finish result the user
 *  hasn't opened/acked yet (Android SessionShellDetail parity). [acked] is whether THIS job's
 *  startedAt has been acked (see [HostStore.isFinishAcked]). Pure + shared by SessionDetail
 *  and its test. */
fun isFinishUnacked(job: FinishJobDto?, acked: Boolean): Boolean =
    job != null && job.status != "running" && !acked

/** Whether the unacked dot should render in the ERROR color (a failed finish) vs the primary color
 *  (a successful one). Pure so FinishButton and its test agree. */
fun finishDotIsError(job: FinishJobDto?): Boolean = job?.status == "failed"
