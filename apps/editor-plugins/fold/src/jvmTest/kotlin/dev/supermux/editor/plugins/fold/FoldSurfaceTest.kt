package dev.supermux.editor.plugins.fold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Folding in a composed Editor: the gutter's arrows are the surface's marker nodes, and they toggle. */
@OptIn(ExperimentalTestApi::class)
class FoldSurfaceTest {
    @Test fun theGutterArrowsFoldAndUnfoldThroughTheSurface() = runComposeUiTest {
        val text = "fun a() {\n    one\n    two\n}\n\nfun b() {\n    three\n}\n"
        val view = EditorView(EditorState.create(text, extensions = fold()))
        setContent { Box(Modifier.size(400.dp, 300.dp)) { Editor(view, Modifier.fillMaxSize()) } }
        waitForIdle()
        val arrows = onAllNodesWithContentDescription("Fold", useUnmergedTree = true)
        assertEquals(2, arrows.fetchSemanticsNodes().size, "a fold arrow per foldable line")
        arrows[0].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(1, Fold.folded(view.state).size)
        val unfold = onAllNodesWithContentDescription("Unfold", useUnmergedTree = true)
        assertEquals(1, unfold.fetchSemanticsNodes().size)
        unfold[0].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(0, Fold.folded(view.state).size)
        assertEquals(text, view.state.doc.toString())
    }
}
