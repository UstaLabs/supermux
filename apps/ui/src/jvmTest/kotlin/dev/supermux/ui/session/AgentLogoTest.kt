package dev.supermux.ui.session

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundled brand marks: [hasAgentLogo] is the predicate every caller branches on, so it must
 * agree exactly with the set of drawables that [AgentLogo] can actually resolve — and an agent
 * outside that set must degrade to an initial rather than crash.
 */
@OptIn(ExperimentalTestApi::class)
class AgentLogoTest {

    @Test fun known_agents_have_a_logo_and_unknown_ones_do_not() {
        for (agent in listOf("claude", "cursor", "grok", "codex", "opencode")) {
            assertTrue(hasAgentLogo(agent), "$agent should have a bundled mark")
            assertTrue(hasAgentLogo(agent.uppercase()), "$agent lookup must be case-insensitive")
        }
        assertFalse(hasAgentLogo("aider"))
        assertFalse(hasAgentLogo(""))
        assertFalse(hasAgentLogo(null))
    }

    @Test fun every_known_agent_renders_its_mark() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentLogo(agent = "claude", size = 24.dp, modifier = Modifier.testTag("logo_claude"))
                AgentLogo(agent = "cursor", size = 24.dp, modifier = Modifier.testTag("logo_cursor"))
                AgentLogo(agent = "grok", size = 24.dp, modifier = Modifier.testTag("logo_grok"))
                AgentLogo(agent = "codex", size = 24.dp, modifier = Modifier.testTag("logo_codex"))
                AgentLogo(agent = "opencode", size = 24.dp, modifier = Modifier.testTag("logo_opencode"))
            }
        }
        for (agent in listOf("claude", "cursor", "grok", "codex", "opencode")) {
            onNodeWithTag("logo_$agent", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun unknown_agent_falls_back_to_an_initial() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                AgentLogo(agent = "aider", size = 24.dp, modifier = Modifier.testTag("logo_aider"))
            }
        }
        onNodeWithTag("logo_aider", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("A", useUnmergedTree = true).assertIsDisplayed()
    }
}
