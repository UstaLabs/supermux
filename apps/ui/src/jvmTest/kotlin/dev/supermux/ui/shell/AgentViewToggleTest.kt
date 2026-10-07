package dev.supermux.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The toggle's geometry is the one thing that differs between the two apps, so both branches are
 * pinned: no pointer device keeps Android's thumb-sized 28dp segments, a mouse/touchpad keeps
 * desktop's tighter 24dp ones. The branch reads [LocalPointerAvailable], not [LocalInputMode] — a
 * touch tablet with a Bluetooth keyboard reports `InputMode.Pointer` but must still get thumb-sized
 * targets, which the keyboard-only case below pins. The `agent_view_*` tags are load-bearing for the
 * phone UI tests and desktop's `ChatHeaderTest`, so they are asserted in both modes.
 */
@OptIn(ExperimentalTestApi::class)
class AgentViewToggleTest {

    @Composable
    private fun host(
        mode: InputMode,
        nativeView: Boolean,
        pointer: Boolean = mode == InputMode.Pointer,
        onSetNative: (Boolean) -> Unit,
    ) {
        CompositionLocalProvider(
            LocalInputMode provides mode,
            LocalPointerAvailable provides pointer,
            LocalWindowWidthClass provides
                if (mode == InputMode.Touch) WindowWidthClass.Compact else WindowWidthClass.Expanded,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AgentViewToggle(nativeView = nativeView, onSetNative = onSetNative)
            }
        }
    }

    @Test fun touch_segments_are_at_least_the_android_28dp_tall() = runComposeUiTest {
        setContent { host(InputMode.Touch, nativeView = false) {} }

        onNodeWithTag("agent_view_chat", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent_view_native", useUnmergedTree = true).assertIsDisplayed()
        val h = onNodeWithTag("agent_view_chat", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().height
        assertTrue(h >= 28.dp, "touch segment height was $h, expected >= 28.dp")
    }

    @Test fun pointer_segments_use_desktops_tighter_24dp() = runComposeUiTest {
        setContent { host(InputMode.Pointer, nativeView = false) {} }

        onNodeWithTag("agent_view_chat", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent_view_native", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            24.dp,
            onNodeWithTag("agent_view_native", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().height,
        )
    }

    @Test fun tapping_native_asks_for_the_native_view_under_touch() = runComposeUiTest {
        val seen = mutableListOf<Boolean>()
        setContent { host(InputMode.Touch, nativeView = false) { seen += it } }

        onNodeWithTag("agent_view_native", useUnmergedTree = true).performClick()
        assertEquals(listOf(true), seen)
    }

    @Test fun clicking_chat_asks_for_the_transcript_under_pointer() = runComposeUiTest {
        val seen = mutableListOf<Boolean>()
        setContent { host(InputMode.Pointer, nativeView = true) { seen += it } }

        onNodeWithTag("agent_view_chat", useUnmergedTree = true).performClick()
        assertEquals(listOf(false), seen)
    }

    // A touch tablet with a Bluetooth keyboard attached: InputMode is Pointer (shortcut hints are
    // worth showing) but there is no mouse, so the segments must stay thumb-sized.
    @Test fun a_keyboard_without_a_mouse_still_gets_the_thumb_sized_segments() = runComposeUiTest {
        setContent { host(InputMode.Pointer, nativeView = false, pointer = false) {} }

        assertEquals(
            28.dp,
            onNodeWithTag("agent_view_chat", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().height,
        )
    }

    @Test fun a_real_pointer_device_gets_the_tighter_segments() = runComposeUiTest {
        setContent { host(InputMode.Pointer, nativeView = false, pointer = true) {} }

        assertEquals(
            24.dp,
            onNodeWithTag("agent_view_chat", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().height,
        )
    }
}
