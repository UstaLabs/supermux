package dev.supermux.android.workspace

/** Minimum available width (dp) for the multi-pane workspace; below this we use the phone UI. */
const val WORKSPACE_MIN_WIDTH_DP = 600

/** True when width warrants the workspace. 600 = Medium+; catches the unfolded Galaxy Z Fold 7. */
fun isWorkspaceWidth(widthDp: Int): Boolean = widthDp >= WORKSPACE_MIN_WIDTH_DP
