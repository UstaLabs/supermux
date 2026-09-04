package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.editor.engine.UnavailableEditorEngineFactory
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec for the shared settings primitives (the union of both apps' `settings/SettingsShared.kt`). */
@OptIn(ExperimentalTestApi::class)
class SettingsSharedTest {

    private class RecordingPlatform : Platform {
        override val editorEngine = UnavailableEditorEngineFactory("no engine under test")
        override val caps = Caps(false, false, false, false, false, false, false, false)
        override val haptics: Haptics = NoHaptics
        val copied = mutableListOf<String>()
        override fun openUrl(url: String) = Unit
        override fun copyToClipboard(text: String) { copied.add(text) }
        override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> =
            listOf(PickedFile("a", "text/plain", ByteArrayChunkSource(ByteArray(0))))
        override suspend fun scanQr(): String? = null
    }

    @Test
    fun secretField_masks_its_value() = runComposeUiTest {
        setContent {
            SecretField(
                value = "hunter2",
                onValueChange = {},
                placeholder = "sk-…",
                modifier = Modifier.testTag("secret"),
            )
        }
        // The password transformation replaces every rendered character with a bullet: what the
        // field DRAWS is masked. (`InputText` still carries the raw value — that is the IME/a11y
        // channel and is what makes the field usable at all; the [Password] semantics flag is what
        // tells the platform to treat it as a secret.)
        onNodeWithTag("secret").assertTextEquals("•".repeat("hunter2".length))
        onNodeWithTag("secret").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun secretField_shows_its_placeholder_while_empty() = runComposeUiTest {
        setContent {
            SecretField(value = "", onValueChange = {}, placeholder = "paste a token")
        }
        onNodeWithText("paste a token").assertIsDisplayed()
    }

    @Test
    fun submitOnEnter_fires_on_enter_when_enabled() = runComposeUiTest {
        var submits = 0
        setContent {
            BasicTextField(
                value = "x",
                onValueChange = {},
                modifier = Modifier
                    .testTag("field")
                    .submitOnEnter(enabled = true) { submits++ },
            )
        }
        onNodeWithTag("field").requestFocus()
        onNodeWithTag("field").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, submits)
    }

    @Test
    fun submitOnEnter_is_inert_when_disabled_or_another_key() = runComposeUiTest {
        var submits = 0
        setContent {
            BasicTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier
                    .testTag("field")
                    .submitOnEnter(enabled = false) { submits++ },
            )
        }
        onNodeWithTag("field").requestFocus()
        onNodeWithTag("field").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("field").performKeyInput { pressKey(Key.A) }
        assertEquals(0, submits)
    }

    @Test
    fun secretField_submits_on_enter_when_a_submit_is_wired() = runComposeUiTest {
        var submits = 0
        setContent {
            SecretField(
                value = "token",
                onValueChange = {},
                placeholder = "p",
                modifier = Modifier.testTag("secret"),
                onSubmit = { submits++ },
            )
        }
        onNodeWithTag("secret").requestFocus()
        onNodeWithTag("secret").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, submits)
    }

    @Test
    fun copyableCommand_copies_through_the_platform() = runComposeUiTest {
        val platform = RecordingPlatform()
        setContent {
            CompositionLocalProvider(LocalPlatform provides platform) {
                Box { CopyableCommand("claude setup-token") }
            }
        }
        onNodeWithText("claude setup-token").assertIsDisplayed()
        onNodeWithContentDescription("Copy").performClick()
        assertEquals(listOf("claude setup-token"), platform.copied)
    }

    @Test
    fun headers_and_captions_render_their_text() = runComposeUiTest {
        setContent {
            Column {
                SettingsSectionHeader("CONNECTIONS")
                SettingsCaption("Stored on the broker.")
            }
        }
        onNodeWithText("CONNECTIONS").assertIsDisplayed()
        onNodeWithText("Stored on the broker.").assertIsDisplayed()
        assertTrue(SettingsDetailMaxWidth.value > 0f)
    }
}
