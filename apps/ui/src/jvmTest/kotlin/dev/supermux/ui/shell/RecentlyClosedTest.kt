package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.NewViewKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #135 (Ahmet picked B, folded): the New tab page ends with one "Recently closed" row for the sessions closed in its
 * workspace, which opens their list, newest first; a click reopens one. No row when none was closed.
 */
@OptIn(ExperimentalTestApi::class)
class RecentlyClosedTest {
    private val kinds = NewViewKind.entries.map { NewTabChoice(it, NewTabGlyphs.Chat, it.label, "for ${it.label}") }
    private val closed = listOf(
        ClosedSessionRow("s2", "Fix login redirect", "claude", ClosedAgo.Minutes(12)),
        ClosedSessionRow("s1", "Release notes", "codex", ClosedAgo.Hours(3)),
    )

    private fun ComposeUiTest.page(closed: List<ClosedSessionRow>, onReopen: (String) -> Unit = {}) {
        setContent {
            CompositionLocalProvider(LocalHardwareKeyboard provides true, LocalPointerAvailable provides true) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.size(800.dp, 700.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        NewTabPage(kinds, "supermux", "main", opening = null, onPick = {}, onClose = {}, closed = closed, onReopen = onReopen)
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test fun noneClosedShowsNoRow() = runComposeUiTest {
        page(emptyList())
        onNodeWithTag("new-tab-recently-closed").assertDoesNotExist()
    }

    @Test fun theRowIsFoldedUntilClicked() = runComposeUiTest {
        page(closed)
        onNodeWithTag("new-tab-recently-closed").assertExists()
        onNodeWithTag("new-tab-closed-s2").assertDoesNotExist()
        onNodeWithTag("new-tab-recently-closed").performClick()
        waitForIdle()
        onNodeWithTag("new-tab-closed-s2").assertExists()
        onNodeWithTag("new-tab-closed-s1").assertExists()
    }

    @Test fun clickingAClosedSessionReopensIt() = runComposeUiTest {
        var got: String? = null
        page(closed, onReopen = { got = it })
        onNodeWithTag("new-tab-recently-closed").performClick()
        waitForIdle()
        onNodeWithTag("new-tab-closed-s1").performClick()
        assertEquals("s1", got)
    }

    @Test fun theKeysReachTheFoldAndItsSessions() = runComposeUiTest {
        var got: String? = null
        page(closed, onReopen = { got = it })
        onNodeWithTag("new-tab-page").performKeyInput {
            repeat(kinds.size) { pressKey(Key.DirectionDown) }
            pressKey(Key.Enter)
        }
        waitForIdle()
        onNodeWithTag("new-tab-page").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        assertEquals("s2", got)
    }

    @Test fun closedAgoReadsLikeAClock() {
        val min = 60_000L
        assertEquals(ClosedAgo.JustNow, closedAgo(nowMs = 10 * min, closedAtMs = 10 * min - 20_000))
        assertEquals(ClosedAgo.Minutes(12), closedAgo(nowMs = 100 * min, closedAtMs = 88 * min))
        assertEquals(ClosedAgo.Hours(3), closedAgo(nowMs = 1000 * min, closedAtMs = 1000 * min - 3 * 60 * min - 5 * min))
        assertEquals(ClosedAgo.Days(2), closedAgo(nowMs = 10_000 * min, closedAtMs = 10_000 * min - 49 * 60 * min))
    }
}
