package dev.supermux.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.GitRequirement
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GitRequirementBannerTest {

    private val mac = GitRequirement(ok = false, install = "xcode-select", hint = "Install Apple's Command Line Tools (xcode-select --install)")
    private val linux = GitRequirement(ok = false, install = "manual", hint = "Install git with your package manager (e.g. sudo apt install git)")

    @Test fun nothing_when_git_is_there_or_unknown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                GitRequirementBanner(GitRequirement(ok = true), onInstall = { true })
                GitRequirementBanner(null, onInstall = { true })
            }
        }
        onNodeWithTag(GitBannerTags.BANNER).assertDoesNotExist()
    }

    @Test fun title_hint_and_install_on_a_one_click_host() = runComposeUiTest {
        var calls = 0
        setContent { MaterialTheme { GitRequirementBanner(mac, onInstall = { calls++; true }) } }
        onNodeWithText(GitBannerCopy.TITLE).assertExists()
        onNodeWithTag(GitBannerTags.HINT).assertTextEquals(mac.hint)
        onNodeWithTag(GitBannerTags.INSTALL).assertTextEquals(GitBannerCopy.INSTALL).performClick()
        waitForIdle()
        assertEquals(1, calls)
        onNodeWithTag(GitBannerTags.STATUS).assertTextEquals(GitBannerCopy.STARTED)
    }

    @Test fun a_manual_host_has_the_hint_and_no_button() = runComposeUiTest {
        setContent { MaterialTheme { GitRequirementBanner(linux, onInstall = { true }) } }
        onNodeWithText(GitBannerCopy.TITLE).assertExists()
        onNodeWithTag(GitBannerTags.HINT).assertTextEquals(linux.hint)
        onNodeWithTag(GitBannerTags.INSTALL).assertDoesNotExist()
    }

    @Test fun the_button_waits_while_starting_and_reports_a_failure() = runComposeUiTest {
        val gate = CompletableDeferred<Boolean>()
        setContent { MaterialTheme { GitRequirementBanner(mac, onInstall = { gate.await() }) } }
        onNodeWithTag(GitBannerTags.INSTALL).performClick()
        waitForIdle()
        onNodeWithTag(GitBannerTags.INSTALL).assertIsNotEnabled()
        gate.complete(false)
        waitForIdle()
        onNodeWithTag(GitBannerTags.STATUS).assertTextEquals(GitBannerCopy.FAILED)
    }

    @Test fun the_host_name_shows_when_given() = runComposeUiTest {
        setContent { MaterialTheme { GitRequirementBanner(linux, onInstall = { true }, hostName = "Raspberry Pi") } }
        onNodeWithTag(GitBannerTags.HOST).assertTextEquals("Raspberry Pi")
    }
}
