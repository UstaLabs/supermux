package dev.supermux.ui.settings.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KitFieldsTest {
    @Test fun typing_in_search_reports_the_query() = runComposeUiTest {
        var q by mutableStateOf("")
        setPlatformContent { KitSearchField(q, { q = it }) }
        onNodeWithTag("settings_search").performTextInput("dark")
        assertEquals("dark", q)
    }

    @Test fun escape_clears_the_search() = runComposeUiTest {
        var q by mutableStateOf("dark")
        setPlatformContent { KitSearchField(q, { q = it }) }
        onNodeWithTag("settings_search").requestFocus()
        onNodeWithTag("settings_search").performKeyInput { pressKey(Key.Escape) }
        assertEquals("", q)
    }

    @Test fun the_clear_button_clears_the_search() = runComposeUiTest {
        var q by mutableStateOf("worktree")
        setPlatformContent { KitSearchField(q, { q = it }) }
        onNodeWithTag("settings_search_clear").performClick()
        assertEquals("", q)
    }

    @Test fun the_hint_and_the_shortcut_show_while_empty() = runComposeUiTest {
        setPlatformContent { KitSearchField("", {}, shortcut = "⌘F") }
        onNodeWithText("Search settings").assertExists()
        onNodeWithText("⌘F").assertExists()
    }

    @Test fun a_secret_field_hides_its_text_until_shown() = runComposeUiTest {
        setPlatformContent { KitTextField("sk-123", {}, label = "API key", secret = true, fieldTag = "key") }
        onNodeWithTag("key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        onNodeWithText("Show").performClick()
        onNodeWithTag("key").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    @Test fun a_field_shows_its_label_and_error() = runComposeUiTest {
        setPlatformContent { KitTextField("", {}, label = "Name", error = "A name is needed.") }
        onNodeWithText("Name").assertExists()
        onNodeWithText("A name is needed.").assertExists()
    }

    @Test fun enter_submits_a_field() = runComposeUiTest {
        var submitted = 0
        setPlatformContent { KitTextField("x", {}, fieldTag = "f", onSubmit = { submitted++ }) }
        onNodeWithTag("f").requestFocus()
        onNodeWithTag("f").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, submitted)
    }
}
