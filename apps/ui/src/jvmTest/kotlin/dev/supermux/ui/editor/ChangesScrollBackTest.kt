package dev.supermux.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.BlobText
import dev.supermux.net.DiffFile
import dev.supermux.net.RepoDiff
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Changes pane's list disposes a file scrolled out of view. Scrolling back must bring it back
 * as it left: not re-read, not "Loading…", not sized to the unfolded file before its (background)
 * diff, so the list moves exactly as far as the wheel says and the text under the reader stays put.
 */
@OptIn(ExperimentalTestApi::class)
class ChangesScrollBackTest {
    private val sha = "a".repeat(40)

    // 3 500 lines a side: over the diff's synchronous limit, so its first diff runs in the background.
    private fun base(tag: String) = (0 until 3_500).joinToString("\n") { "$tag line $it of the file" } + "\n"
    private fun working(tag: String) = (0 until 3_500).joinToString("\n") { if (it % 100 == 50) "$tag CHANGED $it" else "$tag line $it of the file" } + "\n"

    // Unclipped: boundsInRoot is cut to the window.
    private fun ComposeUiTest.top(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    private fun ComposeUiTest.settle() {
        waitForIdle()
        Thread.sleep(40) // the background diff (a worker thread)
        waitForIdle()
    }

    @Test fun inline_scrolling_back_to_a_file_moves_the_list_by_the_wheel_only() = scrollBack(sideBySide = false)

    @Test fun side_by_side_scrolling_back_to_a_file_moves_the_list_by_the_wheel_only() = scrollBack(sideBySide = true)

    private fun scrollBack(sideBySide: Boolean) = runComposeUiTest {
        val reads = HashMap<String, Int>()
        val files = listOf("a.txt", "b.txt").map { DiffFile(path = it, status = "modified", added = 35, removed = 35, baseBlob = sha + it.first(), lazy = true) }
        setContent {
            CompositionLocalProvider(
                LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
                dev.supermux.ui.platform.LocalPlatform provides dev.supermux.ui.platform.FakePlatform(),
                LocalWindowWidthClass provides WindowWidthClass.Expanded,
                LocalInputMode provides InputMode.Pointer,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.size(1000.dp, 700.dp)) {
                        DiffView(
                            repos = listOf(RepoDiff(repo = "", files = files)),
                            comments = emptyList(),
                            onAddComment = { _, _, _, _, _, _ -> }, onResolve = {}, onSubmit = {}, onReload = {}, onClose = {},
                            autoExpandAll = true,
                            readFile = { _, path -> reads[path] = (reads[path] ?: 0) + 1; Result.success(working(path)) },
                            baseText = { _, s, _ -> BlobText.Text(base(if (s.endsWith("a")) "a.txt" else "b.txt")) },
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("diff_native_0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        if (sideBySide) onNodeWithTag("diff_side_by_side_toggle").performClick()
        val seen = ArrayList<Float>()
        fun height() = onNodeWithTag("diff_native_0", useUnmergedTree = true).fetchSemanticsNode().size.height.toFloat()
        // Laid out with its diff (a background job): the height settles.
        repeat(60) { settle(); seen += height() }
        println("a.txt heights while settling: ${seen.distinct()}")
        val first = height()
        assertTrue(first in 1_000f..11_000f, "a.txt's diff is folded and full height: $first")

        fun wheel(notches: Float) {
            // Over the files (the middle of the pane), not its header.
            onRoot().performMouseInput { moveTo(center); scroll(notches, ScrollWheel.Vertical) }
            settle()
        }
        // A notch scrolls a per-OS distance (Compose's platform scroll config; far more on Linux than
        // on macOS, where 20 notches skipped past both files), so size the steps in pixels: ~200 px.
        val top0 = top("diff_native_0")
        wheel(1f)
        val perNotch = abs(top("diff_native_0") - top0)
        wheel(-1f)
        assertTrue(perNotch > 0f, "a wheel notch scrolled the list")
        val notches = 200f / perNotch
        // Down until a.txt is far out of view (the list disposes it), well into b.txt.
        fun shown(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        var guard = 0
        while (!shown("diff_native_1") || top("diff_native_1") > -1_500f) { wheel(notches); assertTrue(guard++ < 600, "never reached b.txt") }
        assertEquals(0, onAllNodesWithTag("diff_native_0", useUnmergedTree = true).fetchSemanticsNodes().size, "a.txt was disposed")

        // Back up a notch at a time: what is on screen moves by the same amount every notch, also when
        // a.txt comes back (b.txt's top, or a.txt's bottom once b.txt went off the bottom), and stays
        // put while a.txt's diff lands in the background.
        // b.txt's top, a.txt's bottom: whichever is on screen (each notch compares the same one).
        fun marks() = Pair(
            if (shown("diff_native_1")) top("diff_native_1") else null,
            if (shown("diff_native_0")) top("diff_native_0") + height() else null,
        )
        fun movedSince(before: Pair<Float?, Float?>): Float {
            val now = marks()
            if (before.first != null && now.first != null) return now.first!! - before.first!!
            if (before.second != null && now.second != null) return now.second!! - before.second!!
            error("neither file stayed on screen across a notch")
        }
        var step = Float.NaN
        var steps = 0
        var sinceBack = 0
        while (sinceBack < 6) {
            val before = marks()
            wheel(-notches)
            val moved = movedSince(before)
            if (step.isNaN()) step = moved
            assertTrue(abs(moved - step) <= 1f, "notch $steps moved the text on screen by $moved, the others by $step (a.txt came back resized)")
            steps++
            if (shown("diff_native_0")) sinceBack++
            assertTrue(steps < 400, "never came back to a.txt")
        }
        val before = marks()
        repeat(30) { settle() }
        val drift = movedSince(before)
        assertTrue(abs(drift) <= 1f, "a.txt's diff landed and moved the text on screen by $drift")
        assertEquals(1, reads["a.txt"], "a.txt's working copy was read again when it came back")
    }
}
