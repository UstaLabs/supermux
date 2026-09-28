package dev.supermux.desktop.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import dev.supermux.desktop.pasteImageShortcut
import dev.supermux.ui.chat.ChatInputFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M5 B2: Edit ▸ Paste image's Ctrl+V exists only while a chat composer is focused. */
class PasteImageShortcutTest {
    @Test fun theAcceleratorIsBoundOnlyForTheChatInput() {
        assertNull(pasteImageShortcut(chatInputFocused = false))
        assertEquals(KeyShortcut(Key.V, ctrl = true), pasteImageShortcut(chatInputFocused = true))
    }

    @Test fun oneComposersBlurNeverClearsAnothersFocus() {
        val a = Any(); val b = Any()
        ChatInputFocus.report(a, true)
        ChatInputFocus.report(b, true)      // focus moved to b …
        ChatInputFocus.report(a, false)     // … a's blur arrives after
        assertTrue(ChatInputFocus.focused)
        ChatInputFocus.report(b, false)
        assertFalse(ChatInputFocus.focused)
    }
}
