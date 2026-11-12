package dev.supermux.ui.i18n

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.SettingsKeys
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** #123: the app's language beyond strings — the rules a translated text is shaped by. */
@OptIn(ExperimentalTestApi::class)
class AppLanguageTest {

    private fun prefsIn(tag: String?) = UiPrefs(FakeSettingsStore().also { s ->
        if (tag != null) s.map.value = mapOf(SettingsKeys.LANGUAGE to tag)
    })

    /** Kotlin's uppercase() has no Turkish: "gizlilik" must become "GİZLİLİK", not "GIZLILIK". */
    @Test fun uppercase_follows_turkish() = runComposeUiTest {
        setPlatformContent(uiPrefs = prefsIn("tr")) { SupermuxTheme { Text("gizlilik".uppercaseInAppLanguage()) } }
        waitForIdle()
        onNodeWithText("GİZLİLİK").assertExists()
    }

    @Test fun uppercase_in_english_stays_plain() = runComposeUiTest {
        setPlatformContent(uiPrefs = prefsIn("en")) { SupermuxTheme { Text("privacy".uppercaseInAppLanguage()) } }
        waitForIdle()
        onNodeWithText("PRIVACY").assertExists()
    }

    @Test fun an_unknown_stored_tag_follows_the_system() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.parse("xx"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.parse(null))
        assertEquals(AppLanguage.TURKISH, AppLanguage.parse("tr"))
    }
}
