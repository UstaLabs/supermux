package dev.supermux.editor.plugins.diff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The inline diff in a composed Editor: the folded runs' expanders and the deleted lines' widget. */
@OptIn(ExperimentalTestApi::class)
class InlineDiffUiTest {
    private val base = (1..100).joinToString("\n") { "line $it" }
    private val working = base.split('\n').toMutableList().also { it[49] = "line 50 changed"; it.removeAt(60) }.joinToString("\n")

    private fun ComposeUiTest.show(config: DiffConfig = DiffConfig()): EditorView {
        val view = EditorView(EditorState.create(working, extensions = inlineDiff(base, config)))
        setContent { Box(Modifier.size(700.dp, 900.dp)) { InlineDiffEditor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        return view
    }

    @Test fun theDeletedLinesShowAboveTheirPlaceWithTheOldText() = runComposeUiTest {
        val v = show(DiffConfig(context = 3))
        onAllNodesWithContentDescription("1 deleted line").assertCountEquals(2)
        onNodeWithText("line 50").assertExists()   // the old line 50, in the change's widget
        onNodeWithText("line 61").assertExists()   // the deleted line
        assertEquals(2, Diff.model(v.state)!!.hunks.size)
    }

    @Test fun anExpanderRevealsTheLinesItFolds() = runComposeUiTest {
        val v = show(DiffConfig(context = 3, expandStep = 20))
        val first = Diff.model(v.state)!!.collapsed.first()
        assertEquals(0 until 46, first.bFrom until first.bTo)
        onAllNodesWithTag("diff-collapsed").assertCountEquals(Diff.model(v.state)!!.collapsed.size)
        onNodeWithContentDescription("Show 20 more lines above").performClick()
        waitForIdle()
        assertEquals(0 until 26, Diff.model(v.state)!!.collapsed.first().let { it.bFrom until it.bTo })
        onNodeWithContentDescription("Show all 26 lines").performClick()
        waitForIdle()
        assertTrue(Diff.model(v.state)!!.collapsed.none { it.bFrom == 0 }, "${Diff.model(v.state)!!.collapsed}")
        assertTrue(!v.focused, "an expander never takes the editor's focus")
    }

    @Test fun readOnlyWalkthroughDropsTyping() = runComposeUiTest {
        val v = show(DiffConfig(editable = false, collapseUnchanged = false))
        assertTrue(v.readOnly)
        v.typeText("x", "input.type")
        assertEquals(working, v.state.doc.toString())
    }
}
