package dev.supermux.editor.compose

import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Up/down with a real geometry: by VISUAL row (wrapped rows too), keeping the goal x. */
class VerticalMotionTest {
    @Test fun upAndDownKeepTheGoalColumnAcrossAShortLine() = withMeasure { m ->
        val text = "abcdefgh\nab\nabcdefgh"
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(6)))
        view.geometry = m.geometry({ view.state })
        DefaultCommands.cursorDown.run(view)
        assertEquals(11, view.state.selection.main.head, "clamped to the short line's end")
        DefaultCommands.cursorDown.run(view)
        assertEquals(12 + 6, view.state.selection.main.head, "back at the goal column")
        DefaultCommands.cursorUp.run(view)
        DefaultCommands.cursorUp.run(view)
        assertEquals(6, view.state.selection.main.head)
    }

    @Test fun downWalksTheRowsOfAWrappedLine() = withMeasure { m ->
        val long = "word ".repeat(30).trim()
        val view = EditorView(EditorState.create("$long\nend", EditorSelection.cursor(2)))
        val g = m.geometry({ view.state }, wrapWidthPx = (20 * m.layouts().charWidthPx).toInt())
        view.geometry = g
        val rows = g.lineLayout(0).lineCount
        repeat(rows - 1) { DefaultCommands.cursorDown.run(view) }
        assertEquals(0, view.state.doc.lineIndexAt(view.state.selection.main.head), "still inside the wrapped line")
        val lastRowStart = g.lineLayout(0).getLineStart(rows - 1)
        assertEquals(lastRowStart + 2, view.state.selection.main.head)
        DefaultCommands.cursorDown.run(view)
        assertEquals(long.length + 1 + 2, view.state.selection.main.head, "onto the next line, same x")
    }

    @Test fun multipleCursorsMoveTogether() = withMeasure { m ->
        val view = EditorView(EditorState.create("abc\nabc\nabc", EditorSelection.create(listOf(
            dev.supermux.editor.core.SelectionRange(1), dev.supermux.editor.core.SelectionRange(6),
        ))))
        view.geometry = m.geometry({ view.state })
        DefaultCommands.cursorDown.run(view)
        assertEquals(listOf(5, 10), view.state.selection.ranges.map { it.head })
    }
}
