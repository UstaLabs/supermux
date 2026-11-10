package dev.supermux.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Card #121: the one thin strip that says the app is offline or reconnecting. */
@OptIn(ExperimentalTestApi::class)
class ConnectionStripTest {

    @Test fun no_strip_while_a_host_is_online_or_none_is_paired() {
        assertEquals(ConnectionBanner.None, connectionBanner(anyHost = true, anyOnline = true, offlineForMs = 60_000, retrying = false))
        assertEquals(ConnectionBanner.None, connectionBanner(anyHost = false, anyOnline = false, offlineForMs = 60_000, retrying = false))
    }

    @Test fun a_short_drop_or_a_launch_shows_nothing_then_connecting_then_offline() {
        assertEquals(ConnectionBanner.None, connectionBanner(true, false, offlineForMs = 1_000, retrying = false))
        assertEquals(ConnectionBanner.Connecting, connectionBanner(true, false, offlineForMs = 3_000, retrying = false))
        assertEquals(ConnectionBanner.Offline, connectionBanner(true, false, offlineForMs = 9_000, retrying = false))
    }

    @Test fun retry_says_connecting() {
        assertEquals(ConnectionBanner.Connecting, connectionBanner(true, false, offlineForMs = 60_000, retrying = true))
    }

    @Test fun offline_list_strip_says_so_and_retries() = runComposeUiTest {
        var retries = 0
        setPlatformContent {
            CompositionLocalProvider(LocalConnectionStatus provides ConnectionStatus(ConnectionBanner.Offline) { retries++ }) {
                ConnectionStrip(inChat = false)
            }
        }
        onNodeWithText("Offline · showing saved chats").assertIsDisplayed()
        onNodeWithTag("connection_retry").performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test fun offline_chat_strip_says_messages_wait() = runComposeUiTest {
        setPlatformContent {
            CompositionLocalProvider(LocalConnectionStatus provides ConnectionStatus(ConnectionBanner.Offline) {}) {
                ConnectionStrip(inChat = true)
            }
        }
        onNodeWithText("Offline · messages send when you're back").assertIsDisplayed()
        onNodeWithTag("connection_retry").assertDoesNotExist()
    }

    @Test fun connecting_strip_has_no_retry() = runComposeUiTest {
        setPlatformContent {
            CompositionLocalProvider(LocalConnectionStatus provides ConnectionStatus(ConnectionBanner.Connecting) {}) {
                ConnectionStrip(inChat = false)
            }
        }
        onNodeWithText("Connecting…").assertIsDisplayed()
        onNodeWithTag("connection_retry").assertDoesNotExist()
    }

    @Test fun online_draws_no_strip() = runComposeUiTest {
        setPlatformContent { ConnectionStrip(inChat = false) }
        onNodeWithTag("connection_strip").assertDoesNotExist()
    }
}
