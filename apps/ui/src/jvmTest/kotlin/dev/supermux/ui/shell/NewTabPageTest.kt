package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.NewViewKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NewTabPageTest {
    private val rows = NewViewKind.entries.map {
        NewTabChoice(it, NewTabGlyphs.Chat, it.label, "what ${it.label} is for", alreadyOpen = it == NewViewKind.EDITOR)
    }

    private fun ComposeUiTest.page(
        onPick: (NewViewKind) -> Unit = {},
        onClose: () -> Unit = {},
        keyboard: Boolean = true,
    ) {
        setContent {
            CompositionLocalProvider(LocalHardwareKeyboard provides keyboard, LocalPointerAvailable provides keyboard) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.size(800.dp, 600.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        NewTabPage(rows, workspace = "supermux", branch = "main", opening = null, onPick = onPick, onClose = onClose)
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test fun everyKindHasARow() = runComposeUiTest {
        page()
        for (label in listOf("Chat", "Terminal", "Files", "Changes", "Display")) onNodeWithText(label).assertExists()
    }

    @Test fun aDigitOpensThatRow() = runComposeUiTest {
        var got: NewViewKind? = null
        page(onPick = { got = it })
        onNodeWithTag("new-tab-page").performKeyInput { pressKey(Key.Two) }
        assertEquals(NewViewKind.TERMINAL, got)
    }

    // ⌘/Ctrl + digit is kept for "go to tab N" (#96).
    @Test fun aDigitWithCommandIsLeftAlone() = runComposeUiTest {
        var got: NewViewKind? = null
        page(onPick = { got = it })
        onNodeWithTag("new-tab-page").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Two) } }
        onNodeWithTag("new-tab-page").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Two) } }
        assertNull(got)
    }

    @Test fun theFirstRowIsSelectedAndDownThenEnterOpensTheSecond() = runComposeUiTest {
        var got: NewViewKind? = null
        page(onPick = { got = it })
        onNodeWithTag("new-tab-page").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        assertEquals(NewViewKind.TERMINAL, got)
    }

    @Test fun escapeClosesTheTab() = runComposeUiTest {
        var closed = false
        page(onClose = { closed = true })
        onNodeWithTag("new-tab-page").performKeyInput { pressKey(Key.Escape) }
        assertTrue(closed)
    }

    @Test fun anAlreadyOpenRowShowsItsDot() = runComposeUiTest {
        page()
        // The row is one clickable, so its dot is merged into it: look in the unmerged tree.
        onNodeWithTag("new-tab-open-editor", useUnmergedTree = true).assertExists()
        onNodeWithTag("new-tab-open-terminal", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aKeyboardShowsKeyHints() = runComposeUiTest {
        page()
        onNodeWithText("to move", substring = true).assertExists()
    }

    @Test fun touchShowsNoKeyHints() = runComposeUiTest {
        page(keyboard = false)
        onNodeWithText("to move", substring = true).assertDoesNotExist()
    }

    // Ahmet: "the card animation makes the app feel slow. It should be instant." One frame after
    // ↓ the new row looks exactly selected and the old one exactly at rest: nothing in between.
    @Test fun theSelectionMovesWithinOneFrame() = runComposeUiTest {
        page()
        fun wash(tag: String): Color = onNodeWithTag(tag).captureToImage().toPixelMap().let { it[6, it.height / 2] }
        val selected = wash("new-tab-choice-chat")
        val atRest = wash("new-tab-choice-display")
        mainClock.autoAdvance = false
        onNodeWithTag("new-tab-page").performKeyInput { pressKey(Key.DirectionDown) }
        mainClock.advanceTimeByFrame()
        assertEquals(selected, wash("new-tab-choice-terminal"))
        assertEquals(atRest, wash("new-tab-choice-chat"))
    }
}
