package dev.supermux.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #144/#146: the desktop chip ("Compact ✕" by the ring) and the touch line (the composer's first line) act the same. */
@OptIn(ExperimentalTestApi::class)
class NearLimitUiTest {
    @Test fun the_chip_compacts_or_hides() = runDesktopComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { NearLimitChip(NearLimitPrompt.Context(87, 267_000), "Claude", onCompact = { calls += "compact" }, onDismiss = { calls += "dismiss" }) }
            }
        }
        onNodeWithTag("near_limit_chip").assertIsDisplayed()
        onNodeWithTag("near_limit_compact").performClick()
        onNodeWithTag("near_limit_dismiss").performClick()
        assertEquals(listOf("compact", "dismiss"), calls)
    }

    @Test fun the_line_compacts_or_hides() = runDesktopComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { NearLimitLine(NearLimitPrompt.Limit(88, 1L), "Claude", onCompact = { calls += "compact" }, onDismiss = { calls += "dismiss" }) }
            }
        }
        onNodeWithTag("near_limit_line").assertIsDisplayed()
        onNodeWithTag("near_limit_compact").performClick()
        onNodeWithTag("near_limit_dismiss").performClick()
        assertEquals(listOf("compact", "dismiss"), calls)
    }
}
