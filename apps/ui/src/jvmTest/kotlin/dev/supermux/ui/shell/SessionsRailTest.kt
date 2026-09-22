package dev.supermux.ui.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.TestIds
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The collapsed sessions rail: its three affordances (expand chevron, new session, one tappable
 * avatar per session), and the status-bar inset it consumes unconditionally so that nothing draws
 * under the system bar on any device that has one.
 */
@OptIn(ExperimentalTestApi::class)
class SessionsRailTest {

    private val sessions = listOf(
        SessionInfo(id = "s1", name = "alpha", workdir = "/w/a", agent = "claude"),
        SessionInfo(id = "s2", name = "beta", workdir = "/w/b", agent = "cursor"),
    )

    @Test fun renders_expand_new_session_and_one_avatar_per_session() = runComposeUiTest {
        var expanded = 0
        var created = 0
        val selected = mutableListOf<String>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionsRail(
                    sessions = sessions,
                    selectedId = "s1",
                    agentState = emptyMap(),
                    onSelect = { selected += it },
                    onExpand = { expanded++ },
                    onNewSession = { created++ },
                )
            }
        }
        onNodeWithTag("rail_expand").assertIsDisplayed().performClick()
        onNodeWithTag(TestIds.NEW_SESSION).assertIsDisplayed().performClick()
        onNodeWithTag("rail_session_s1").assertIsDisplayed()
        onNodeWithTag("rail_session_s2").assertIsDisplayed().performClick()
        assertEquals(1, expanded)
        assertEquals(1, created)
        assertEquals(listOf("s2"), selected)
    }

    @Test fun rail_content_starts_below_the_status_bar_inset() {
        // The rail is drawn edge-to-edge under the system status bar, so its first affordance —
        // the expand chevron — must sit BELOW that inset, on every device that has one. The inset
        // is injected rather than read from the platform because the desktop test host reports an
        // empty one, which would make a `statusBarsPadding()`-only assertion vacuous.
        fun expandTop(inset: WindowInsets): Dp {
            var top = Dp.Unspecified
            runComposeUiTest {
                setContent {
                    SupermuxTheme(appearance = AppearanceMode.DARK) {
                        SessionsRail(
                            sessions = sessions,
                            selectedId = null,
                            agentState = emptyMap(),
                            onSelect = {},
                            onExpand = {},
                            onNewSession = {},
                            statusBarInset = inset,
                        )
                    }
                }
                onNodeWithTag("rail_expand").assertIsDisplayed()
                top = onNodeWithTag("rail_expand").getBoundsInRoot().top
            }
            return top
        }
        val flush = expandTop(WindowInsets(0, 0, 0, 0))
        val inset = expandTop(WindowInsets(left = 0, top = 64, right = 0, bottom = 0))
        assertTrue(
            inset > flush,
            "a status-bar inset must push the rail down (flush=$flush, inset=$inset)",
        )
    }
}
