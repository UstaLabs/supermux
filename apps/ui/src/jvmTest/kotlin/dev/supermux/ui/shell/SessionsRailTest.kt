package dev.supermux.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The collapsed sessions rail: its three affordances (expand chevron, new session, one tappable
 * avatar per session) and the single adaptive branch it carries — the status-bar inset is consumed
 * only in the Compact width class, which is the phone layout that draws under the system bar.
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

    @Test fun both_width_classes_render_the_rail_and_compact_never_starts_higher() {
        // Compact adds statusBarsPadding(), Expanded does not. The desktop test host reports no
        // status-bar inset, so the padding resolves to zero there — what this pins is that BOTH
        // branches compose and lay the rail out, and that the compact branch can only ever push
        // content DOWN (it adds an inset, never removes one).
        fun railTop(widthClass: WindowWidthClass): Dp {
            var top = Dp.Unspecified
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                        SupermuxTheme(appearance = AppearanceMode.DARK) {
                            SessionsRail(
                                sessions = sessions,
                                selectedId = null,
                                agentState = emptyMap(),
                                onSelect = {},
                                onExpand = {},
                                onNewSession = {},
                            )
                        }
                    }
                }
                onNodeWithTag("rail_expand").assertIsDisplayed()
                onNodeWithTag("rail_session_s1").assertIsDisplayed()
                top = onNodeWithTag("rail_expand").getBoundsInRoot().top
            }
            return top
        }
        val compactTop = railTop(WindowWidthClass.Compact)
        val expandedTop = railTop(WindowWidthClass.Expanded)
        assertTrue(compactTop >= expandedTop, "compact rail must never start above the expanded one")
    }
}
