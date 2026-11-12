package dev.supermux.ui.whatsnew

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The card in the sidebar's update-card slot after an update (#90): one quiet card, never a dialog. Explore opens the
 * page, ✕ puts it away; both settle it for this version on this device.
 */
@OptIn(ExperimentalTestApi::class)
class WhatsNewCardTest {

    @Test fun nothing_is_drawn_when_nothing_is_offered() = runComposeUiTest {
        setPlatformContent { WhatsNewCard(offer = null, onExplore = {}, onDismiss = {}) }
        onNodeWithTag("whats_new_card").assertDoesNotExist()
    }

    @Test fun the_card_names_the_version_and_what_this_device_can_now_do() = runComposeUiTest {
        setPlatformContent {
            WhatsNewCard(WhatsNewOffer("0.17.7", chapters = 7, ReleasePlatform.Mac), onExplore = {}, onDismiss = {})
        }
        onNodeWithText("Updated to v0.17.7").assertExists()
        onNodeWithText("7 new things you can do on this Mac.").assertExists()
        onNodeWithText("Explore what's new").assertExists()
    }

    @Test fun each_platform_is_named_its_own_way() = runComposeUiTest {
        var platform by androidx.compose.runtime.mutableStateOf<ReleasePlatform?>(ReleasePlatform.Windows)
        setPlatformContent { WhatsNewCard(WhatsNewOffer("0.17.7", 8, platform), onExplore = {}, onDismiss = {}) }
        onNodeWithText("8 new things you can do on this PC.").assertExists()
        platform = ReleasePlatform.Web
        onNodeWithText("8 new things you can do in the web app.").assertExists()
        platform = ReleasePlatform.Android
        onNodeWithText("8 new things you can do on this phone.").assertExists()
        platform = null
        onNodeWithText("8 new things you can do.").assertExists()
    }

    @Test fun one_thing_is_one_thing() = runComposeUiTest {
        setPlatformContent { WhatsNewCard(WhatsNewOffer("0.17.8", 1, ReleasePlatform.IPhone), onExplore = {}, onDismiss = {}) }
        onNodeWithText("1 new thing you can do on this iPhone.").assertExists()
    }

    @Test fun explore_and_dismiss_each_answer_once() = runComposeUiTest {
        var explored = 0
        var dismissed = 0
        setPlatformContent {
            WhatsNewCard(WhatsNewOffer("0.17.7", 7, ReleasePlatform.Mac), onExplore = { explored++ }, onDismiss = { dismissed++ })
        }
        onNodeWithTag("whats_new_card_explore").performClick()
        onNodeWithTag("whats_new_card_dismiss").performClick()
        assertEquals(1, explored)
        assertEquals(1, dismissed)
    }
}
