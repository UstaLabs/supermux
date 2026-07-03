package dev.supermux.android.workspace

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreakpointTest {
    @Test fun phoneIsNotWorkspace()             { assertFalse(isWorkspaceWidth(411)) }
    @Test fun foldCoverIsNotWorkspace()         { assertFalse(isWorkspaceWidth(408)) }
    @Test fun boundaryIsWorkspace()             { assertTrue(isWorkspaceWidth(600)) }
    @Test fun foldMediumUnfoldedIsWorkspace()   { assertTrue(isWorkspaceWidth(795)) }
    @Test fun foldExpandedUnfoldedIsWorkspace() { assertTrue(isWorkspaceWidth(984)) }
}
