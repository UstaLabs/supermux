package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The walkthrough slideshow, now shared (Android gains it in cluster C4). Steps here carry no
 * `path`, so the slide is pure markdown and no code surface — and therefore no engine — is needed;
 * the diff-region half is covered by [WalkthroughRegionTest] and `EditorSurfaceTest`.
 */
@OptIn(ExperimentalTestApi::class)
class WalkthroughViewTest {

    private fun state() = WalkthroughState("s1").apply {
        applyWalkthrough(
            Walkthrough(
                id = "w1", sessionId = "s1", title = "Tour", revision = 1,
                steps = listOf(
                    WalkthroughStep(id = "a", ord = 0, title = "First step", bodyMd = "A"),
                    WalkthroughStep(id = "b", ord = 1, title = "Second step", bodyMd = "B"),
                ),
            ),
        )
    }

    private fun host(state: WalkthroughState): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(editorEngine = FakeEditorEngineFactory()),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WalkthroughView(
                    state = state,
                    repos = emptyList(),
                    readFile = { _, _ -> Result.success("") },
                    onAddComment = { null },
                    onResolve = { false },
                    onOpenFile = { _, _, _ -> },
                    onClose = {},
                    modifier = Modifier,
                )
            }
        }
    }

    @Test
    fun it_shows_the_current_step() = runComposeUiTest {
        setContent(host(state()))
        waitForIdle()

        onNodeWithTag("walkthrough_view").assertIsDisplayed()
        onNodeWithText("First step").assertIsDisplayed()
        onNodeWithText("1 / 2").assertIsDisplayed()
    }

    @Test
    fun left_and_right_page_the_slideshow() = runComposeUiTest {
        val state = state()
        setContent(host(state))
        waitForIdle()

        onNodeWithTag("walkthrough_view").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(1, state.stepIndex)
        onNodeWithText("Second step").assertIsDisplayed()

        onNodeWithTag("walkthrough_view").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals(0, state.stepIndex)
    }

    @Test
    fun the_drawer_lists_every_step_and_jumps_to_one() = runComposeUiTest {
        val state = state()
        setContent(host(state))
        waitForIdle()

        onNodeWithTag("walkthrough_drawer").performClick()
        waitForIdle()
        onNodeWithText("Walkthrough steps").assertIsDisplayed()

        onNodeWithText("Second step").performClick()
        waitForIdle()
        assertEquals(1, state.stepIndex)
        onNodeWithText("Walkthrough steps").assertDoesNotExist()
    }
}
