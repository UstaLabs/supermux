package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.proto.SlashCommand
import dev.supermux.proto.ControlAction
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The TOUCH branch of [Composer] — everything that differs from the pointer shape the rest of the
 * composer suites cover: the `+` attach MENU (with its camera entries, gated on `caps.camera`), the
 * model/effort `PickerSheet` instead of a `DropdownMenu`, and the `/command` menu, which is shared
 * by both input modes.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerTouchTest {

    private fun touch(caps: Caps = NO_CAPS) = FakePlatform(caps = caps)

    private val cameraCaps = NO_CAPS.copy(camera = true)

    @Test fun attach_menu_offers_photos_and_files_but_no_camera_without_the_capability() =
        runComposeUiTest {
            setPlatformContent(touch(), pointer = false, inputMode = InputMode.Touch) {
                Composer(
                    draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                    onSend = { _, _ -> }, onInterrupt = {},
                    onUpload = { _, _, _, _, _ -> "f" },
                )
            }
            onNodeWithTag("composer-attach").performClick()
            waitForIdle()
            onNodeWithTag("attach_menu_photos").assertIsDisplayed()
            onNodeWithTag("attach_menu_files").assertIsDisplayed()
            onNodeWithTag("attach_menu_camera").assertDoesNotExist()
            onNodeWithTag("attach_menu_record_video").assertDoesNotExist()
        }

    @Test fun camera_entries_appear_with_the_capability_and_stage_the_capture() = runComposeUiTest {
        val platform = touch(cameraCaps).apply {
            captureResult = dev.supermux.ui.platform.PickedFile(
                "shot.jpg",
                "image/jpeg",
                dev.supermux.net.ByteArrayChunkSource(byteArrayOf(1)),
            )
        }
        setPlatformContent(platform, pointer = false, inputMode = InputMode.Touch) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, _, _, _, _ -> "f" },
            )
        }
        onNodeWithTag("composer-attach").performClick()
        waitForIdle()
        onNodeWithTag("attach_menu_camera").assertIsDisplayed()
        onNodeWithTag("attach_menu_record_video").assertIsDisplayed()

        onNodeWithTag("attach_menu_camera").performClick()
        waitUntil(timeoutMillis = 5_000L) { platform.captures.isNotEmpty() }
        // Tagged with the composer's requester so a capture that outlives an activity recreation
        // comes back HERE, not to the new-session launcher.
        assertEquals(listOf("image:$COMPOSER_PICK_REQUESTER"), platform.captures)
    }

    @Test fun a_pick_delivered_after_a_recreation_is_staged_from_pendingPicks() = runComposeUiTest {
        val platform = touch()
        val uploaded = mutableListOf<String>()
        setPlatformContent(platform, pointer = false, inputMode = InputMode.Touch) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "f" },
            )
        }
        waitForIdle()
        assertTrue(
            platform.pendingPicks.tryEmit(
                dev.supermux.ui.platform.PickedFile(
                    "rotated.png",
                    "image/png",
                    dev.supermux.net.ByteArrayChunkSource(byteArrayOf(1)),
                ),
            ),
        )
        waitUntil(timeoutMillis = 5_000L) { uploaded.isNotEmpty() }
        assertEquals(listOf("rotated.png"), uploaded)
    }

    @Test fun model_pill_opens_a_bottom_sheet_under_touch_not_a_dropdown() = runComposeUiTest {
        var picked: String? = null
        setPlatformContent(touch(), pointer = false, inputMode = InputMode.Touch) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                models = ModelsResponse(
                    agent = "claude",
                    models = listOf(ModelInfo(id = "opus", displayName = "Opus")),
                    current = "opus",
                ),
                onPickModel = { picked = it },
            )
        }
        // The dropdown row (the pointer branch's tag) never exists under touch.
        onNodeWithTag("composer-model-opus").assertDoesNotExist()
        onNodeWithTag("composer-model-pill").performClick()
        waitForIdle()
        onNodeWithText("Select Model").assertIsDisplayed()
        // Click the "Default" row: "Opus" would be ambiguous, since the pill itself shows it.
        onNodeWithText("Default").performClick()
        waitForIdle()
        assertEquals("", picked)
    }

    // The slash menu is not input-mode-specific: it renders above the card on both hosts, a tap
    // inserts an insert-only command's text, and a CONTROL command clears the token and fires
    // onControl instead.
    @Test fun slash_menu_filters_and_inserts_under_touch() = runComposeUiTest {
        var draft by mutableStateOf("")
        setPlatformContent(touch(), pointer = false, inputMode = InputMode.Touch) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                commands = listOf(
                    SlashCommand(id = "review", name = "review", family = "code"),
                    SlashCommand(id = "compact", name = "compact", family = "session"),
                ),
            )
        }
        onNodeWithTag("composer-input").performTextInput("/rev")
        waitForIdle()
        onNodeWithTag("chat_slash_item_review").assertIsDisplayed()
        onNodeWithTag("chat_slash_item_compact").assertDoesNotExist()
        onNodeWithTag("chat_slash_item_review").performClick()
        waitForIdle()
        assertEquals("/review ", draft)
    }

    @Test fun slash_menu_control_command_clears_the_token_and_fires_onControl() = runComposeUiTest {
        var draft by mutableStateOf("")
        var controlled: String? = null
        setPlatformContent(touch()) {
            Composer(
                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                commands = listOf(
                    SlashCommand(id = "rename", name = "rename", family = "session", action = ControlAction(kind = "rename")),
                ),
                onControl = { controlled = it.name },
            )
        }
        onNodeWithTag("composer-input").performTextInput("/ren")
        waitForIdle()
        onNodeWithTag("chat_slash_item_rename").performClick()
        waitForIdle()
        assertEquals("rename", controlled)
        assertEquals("", draft)
    }

    @Test fun an_unresolved_command_set_shows_the_loading_hint() = runComposeUiTest {
        setPlatformContent(touch()) {
            Composer(
                draft = "/re", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                commands = emptyList(),
                commandsResolved = false,
            )
        }
        onNodeWithTag("chat_slash_loading").assertIsDisplayed()
    }
}
