package dev.supermux.android.chat

/** Whether "Skip tests" may be offered. Hidden only on the PR path when the repo
 *  requires green tests for a PR (skipping would silently defeat that policy). */
fun canSkipTests(action: String, prRequiresGreen: Boolean): Boolean =
    !(action == "pr" && prRequiresGreen)
