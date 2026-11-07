package dev.supermux.ui.keys

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.supermux.editor.compose.isApplePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ShortcutRootTest {
    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    @Test fun aWindowKeyRunsWithNothingFocused() = runComposeUiTest {
        var ran = 0
        lateinit var focusManager: FocusManager
        setContent {
            focusManager = LocalFocusManager.current
            ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(200.dp).testTag("root")) {
                RegisterCommand(AppCommands.NewSession) { ran++; true }
                BasicTextField("", {}, Modifier.testTag("field"))
            }
        }
        onNodeWithTag("field").performClick()
        waitForIdle()
        runOnIdle { focusManager.clearFocus() }
        waitForIdle()
        onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(1, ran)
    }

    @Test fun aFocusedTextBoxLeavesTheAppItsKeys() = runComposeUiTest {
        var ran = 0
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher() }) {
                RegisterCommand(AppCommands.ToggleSidebar) { ran++; true }
                BasicTextField("", {}, Modifier.testTag("field"))
            }
        }
        onNodeWithTag("field").performClick()
        onNodeWithTag("field").performKeyInput { withKeyDown(mod) { pressKey(Key.B) } }
        waitForIdle()
        assertEquals(1, ran)
    }

    @Test fun offAppleAFocusedTerminalKeepsCtrlTAndGivesUpCtrlShiftT() = runComposeUiTest {
        val down = mutableListOf<Key>()
        var ran = 0
        val focus = FocusRequester()
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher(os = KeyOs.Other) }) {
                RegisterCommand(AppCommands.NewTerminal) { ran++; true }
                val terminal = rememberKeyScope(KeyContext.Terminal)
                ProvideKeyScope(terminal) {
                    Box(
                        Modifier.size(50.dp).keyScope(terminal)
                            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) down += it.key; true }
                            .focusRequester(focus).focusable().testTag("term"),
                    )
                }
            }
        }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("term").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
        waitForIdle()
        assertEquals(0, ran, "^T belongs to the shell")
        assertTrue(Key.T in down)
        down.clear()
        onNodeWithTag("term").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.T) } } }
        waitForIdle()
        assertEquals(1, ran)
        assertFalse(Key.T in down, "the app took Ctrl+Shift+T before the terminal")
    }

    @Test fun onAppleCmdTPassesAFocusedTerminalReleaseIncluded() = runComposeUiTest {
        val seen = mutableListOf<Pair<KeyEventType, Key>>()
        var ran = 0
        val focus = FocusRequester()
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher(os = KeyOs.Apple) }) {
                RegisterCommand(AppCommands.NewTerminal) { ran++; true }
                val terminal = rememberKeyScope(KeyContext.Terminal)
                ProvideKeyScope(terminal) {
                    Box(
                        Modifier.size(50.dp).keyScope(terminal)
                            .onPreviewKeyEvent { seen += it.type to it.key; true }
                            .focusRequester(focus).focusable().testTag("term"),
                    )
                }
            }
        }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("term").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.T) } }
        waitForIdle()
        assertEquals(1, ran)
        assertTrue(seen.none { it.second == Key.T }, "neither ⌘T's press nor its release reach the terminal: $seen")
    }

    @Test fun theScopePressedLastStandsInWhenOnlyTheRootHasFocus() = runComposeUiTest {
        var ran = 0
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(300.dp).testTag("root")) {
                val chat = rememberKeyScope(KeyContext.Chat)
                ProvideKeyScope(chat) {
                    RegisterCommand(AppCommands.FindInChat) { ran++; true }
                    Box(Modifier.size(100.dp).keyScope(chat).testTag("transcript"))
                }
            }
        }
        onNodeWithTag("transcript").performClick()
        waitForIdle()
        onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.F) } }
        waitForIdle()
        assertEquals(1, ran)
    }

    @Test fun keysSentToADialogNeverReachTheWindow() = runComposeUiTest {
        var ran = 0
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher() }) {
                RegisterCommand(AppCommands.NewSession) { ran++; true }
                Dialog(onDismissRequest = {}) { BasicTextField("", {}, Modifier.testTag("dialog_field")) }
            }
        }
        onNodeWithTag("dialog_field").performClick()
        onNodeWithTag("dialog_field").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(0, ran)
    }

    @Test fun aHandlerThatDeclinesLetsTheKeyThrough() = runComposeUiTest {
        val down = mutableListOf<Key>()
        val focus = FocusRequester()
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher(os = KeyOs.Other) }) {
                RegisterCommand(AppCommands.SplitRight) { false }
                Box(
                    Modifier.size(50.dp)
                        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) down += it.key; true }
                        .focusRequester(focus).focusable().testTag("stub"),
                )
            }
        }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("stub").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Backslash) } }
        waitForIdle()
        assertTrue(Key.Backslash in down)
    }
}
