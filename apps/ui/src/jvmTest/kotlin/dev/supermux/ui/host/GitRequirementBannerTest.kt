package dev.supermux.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        onNodeWithTag(GitBannerTags.STATUS)
            .assertTextEquals("Apple's installer is open on this computer. Follow it, then this clears by itself.")
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

    @Test fun a_named_host_titles_the_banner() = runComposeUiTest {
        setContent { MaterialTheme { GitRequirementBanner(linux, onInstall = { true }, hostName = "Raspberry Pi") } }
        onNodeWithTag(GitBannerTags.TITLE).assertTextEquals("Raspberry Pi needs git to run agents")
    }

    @Test fun windows_without_winget_has_a_button_and_says_the_download_page_opened() = runComposeUiTest {
        val browser = GitRequirement(ok = false, install = "browser", hint = "Download Git for Windows from https://git-scm.com/download/win")
        setContent { MaterialTheme { GitRequirementBanner(browser, onInstall = { true }, hostName = "Work PC") } }
        onNodeWithTag(GitBannerTags.INSTALL).performClick()
        waitForIdle()
        onNodeWithTag(GitBannerTags.STATUS).assertTextEquals("The Git download page is open on Work PC.")
    }

    @Test fun mingit_status_copy_has_no_permission_wording() {
        assertEquals("Installing git on this computer…", GitBannerCopy.started("mingit", null))
    }

    @Test fun winget_status_copy() {
        assertEquals("Installing git on Work PC… this clears by itself when done.", GitBannerCopy.started("winget", "Work PC"))
    }

    @Test fun a_failed_winget_run_leaves_installing_and_offers_retry() = runComposeUiTest {
        val winget = GitRequirement(ok = false, install = "winget", hint = "Install Git for Windows with winget")
        var req by androidx.compose.runtime.mutableStateOf(winget)
        var calls = 0
        setContent { MaterialTheme { GitRequirementBanner(req, onInstall = { calls++; true }) } }
        onNodeWithTag(GitBannerTags.INSTALL).performClick()
        waitForIdle()
        req = winget.copy(installing = true)
        waitForIdle()
        onNodeWithTag(GitBannerTags.STATUS).assertTextEquals("Installing git on this computer… this clears by itself when done.")
        onNodeWithTag(GitBannerTags.INSTALL).assertIsNotEnabled()
        req = winget.copy(installing = false, installError = "declined")
        waitForIdle()
        onNodeWithTag(GitBannerTags.STATUS).assertTextEquals("Couldn't install git: declined")
        onNodeWithTag(GitBannerTags.INSTALL).assertTextEquals(GitBannerCopy.RETRY).performClick()
        waitForIdle()
        assertEquals(2, calls)
    }
}
