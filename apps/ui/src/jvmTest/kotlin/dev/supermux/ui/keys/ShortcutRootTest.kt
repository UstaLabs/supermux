package dev.supermux.ui.keys

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.KeyChord
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
        var dialog by mutableStateOf(true)
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(200.dp).testTag("root")) {
                RegisterCommand(AppCommands.NewSession) { ran++; true }
                if (dialog) Dialog(onDismissRequest = {}) { BasicTextField("", {}, Modifier.testTag("dialog_field")) }
            }
        }
        onNodeWithTag("dialog_field").performClick()
        onNodeWithTag("dialog_field").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(0, ran)
        // The control: with the dialog gone, the same key runs.
        dialog = false
        waitForIdle()
        onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(1, ran)
    }

    @Test fun aHandlerThatDeclinesLetsTheKeyThrough() = runComposeUiTest {
        val down = mutableListOf<Key>()
        var tried = 0
        val focus = FocusRequester()
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher(os = KeyOs.Other) }) {
                RegisterCommand(AppCommands.SplitRight) { tried++; false }
                Box(
                    Modifier.size(50.dp)
                        // Sees the key and leaves it unused, so it goes on to the root's bubble pass.
                        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) down += it.key; false }
                        .focusRequester(focus).focusable().testTag("stub"),
                )
            }
        }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("stub").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Backslash) } }
        waitForIdle()
        assertTrue(Key.Backslash in down, "the declined key reached the focused element")
        assertEquals(1, tried, "and the bubble did not offer it to the handler again")
    }

    @Test fun theKeeperLeavesAFieldThatTookFocusAlone() = runComposeUiTest {
        var show by mutableStateOf(false)
        lateinit var focusManager: FocusManager
        setContent {
            focusManager = LocalFocusManager.current
            ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(200.dp)) {
                if (show) {
                    val field = remember { FocusRequester() }
                    BasicTextField("", {}, Modifier.focusRequester(field).testTag("autofocus"))
                    LaunchedEffect(field) { field.requestFocus() }
                }
            }
        }
        waitForIdle()
        // The focus goes, and in the same frame a field appears that takes it (an autofocused search box).
        runOnIdle {
            focusManager.clearFocus()
            show = true
        }
        waitForIdle()
        onNodeWithTag("autofocus").assertIsFocused()
    }

    @Test fun twoRegistrationsOfOneCommandTheNewestWins() = runComposeUiTest {
        val runs = mutableListOf<String>()
        var second by mutableStateOf(false)
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(200.dp).testTag("root")) {
                RegisterCommand(AppCommands.NewSession) { runs += "A"; true }
                if (second) RegisterCommand(AppCommands.NewSession) { runs += "B"; true }
            }
        }
        waitForIdle()
        second = true
        waitForIdle()
        onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(listOf("B"), runs)
        // B goes; A, still composed, runs again.
        second = false
        waitForIdle()
        onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(listOf("B", "A"), runs)
    }

    @Test fun hiddenContentRegistersNothing() = runComposeUiTest {
        val runs = mutableListOf<String>()
        var withVisible by mutableStateOf(false)
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(200.dp).testTag("root")) {
                // Kept-alive content that is hidden, such as a workspace in the background.
                CompositionLocalProvider(LocalKeyScopeActive provides false) {
                    RegisterCommand(AppCommands.NewSession) { runs += "hidden"; true }
                }
                if (withVisible) RegisterCommand(AppCommands.NewSession) { runs += "visible"; true }
            }
        }
        waitForIdle()
        onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(emptyList<String>(), runs)
        withVisible = true
        waitForIdle()
        onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(listOf("visible"), runs)
    }

    @Test fun invokeAndCanInvokeFollowTheFocusedScope() = runComposeUiTest {
        val dispatcher = ShortcutDispatcher()
        var ran = 0
        val chatField = FocusRequester()
        val filesField = FocusRequester()
        lateinit var focusManager: FocusManager
        setContent {
            focusManager = LocalFocusManager.current
            ShortcutRoot(dispatcher, Modifier.size(400.dp)) {
                Column {
                    val chat = rememberKeyScope(KeyContext.Chat)
                    ProvideKeyScope(chat) {
                        RegisterCommand(AppCommands.FindInChat) { ran++; true }
                        Column(Modifier.keyScope(chat)) {
                            BasicTextField("", {}, Modifier.focusRequester(chatField))
                            Box(Modifier.size(80.dp))
                        }
                    }
                    val files = rememberKeyScope(KeyContext.Files)
                    ProvideKeyScope(files) {
                        Column(Modifier.keyScope(files)) {
                            BasicTextField("", {}, Modifier.focusRequester(filesField))
                            Box(Modifier.size(80.dp).testTag("files_area"))
                        }
                    }
                    // As a menu item's `enabled` reads it: composition must follow it with nothing else changing.
                    BasicText(dispatcher.canInvoke(AppCommands.FindInChat).toString(), Modifier.testTag("can_find"))
                }
            }
        }
        waitForIdle()
        onNodeWithTag("can_find").assertTextEquals("false")

        runOnIdle { chatField.requestFocus() }
        onNodeWithTag("can_find").assertTextEquals("true")
        runOnIdle { assertTrue(dispatcher.invoke(AppCommands.FindInChat)) }
        assertEquals(1, ran)

        runOnIdle { filesField.requestFocus() }
        onNodeWithTag("can_find").assertTextEquals("false")
        runOnIdle { assertFalse(dispatcher.invoke(AppCommands.FindInChat)) }
        assertEquals(1, ran)

        // The chat took the focus last, with no click: once the focus is gone from it, it still counts.
        runOnIdle { chatField.requestFocus() }
        runOnIdle { focusManager.clearFocus() }
        onNodeWithTag("can_find").assertTextEquals("true")

        // A click in another pane makes that one the pane used last.
        onNodeWithTag("files_area").performClick()
        onNodeWithTag("can_find").assertTextEquals("false")
    }

    // The dispatcher alone, on synthetic events: what its two passes do with a held key and its echoes.

    /** A dispatcher whose focused window root has [command]; its handler calls [onRun]. */
    private fun dispatcherWith(os: KeyOs, command: AppCommand, onRun: () -> Unit) = ShortcutDispatcher(os = os).apply {
        val root = KeyScope(KeyContext.Window, viewId = null, parent = null)
        tracker.register(root)
        root.hasFocus = true
        root.put(command.id) { onRun(); true }
    }

    /** A key that nothing focused uses, through [ShortcutRoot]'s preview pass, then its bubble pass. */
    private fun ShortcutDispatcher.press(event: KeyEvent) = onPreview(event) || onBubble(event)

    @OptIn(InternalComposeUiApi::class)
    private fun keyEvent(key: Key, type: KeyEventType, char: Char? = null, ctrl: Boolean = false, shift: Boolean = false, meta: Boolean = false) =
        KeyEvent(key = key, type = type, codePoint = char?.code ?: 0, isCtrlPressed = ctrl, isMetaPressed = meta, isShiftPressed = shift)

    @Test fun aKeyHeldDownRunsItsCommandOnce() {
        var ran = 0
        val dispatcher = dispatcherWith(KeyOs.Other, AppCommands.NewTerminal) { ran++ }
        val ctrlShiftT = keyEvent(Key.T, KeyEventType.KeyDown, ctrl = true, shift = true)
        assertTrue(dispatcher.press(ctrlShiftT))
        assertTrue(dispatcher.onPreview(ctrlShiftT), "the auto-repeat is swallowed before the focused element")
        assertEquals(1, ran)
        // Another chord on the same key is no repeat: it resolves as a press of its own.
        assertTrue(dispatcher.press(keyEvent(Key.T, KeyEventType.KeyDown, ctrl = true)))
        assertEquals(2, ran)
    }

    @Test fun theTypedEchoOfATakenKeyIsSwallowed() {
        var ran = 0
        val dispatcher = dispatcherWith(KeyOs.Other, AppCommands.OpenSettings) { ran++ }
        assertTrue(dispatcher.press(keyEvent(Key.Comma, KeyEventType.KeyDown, ',', ctrl = true)))
        assertEquals(1, ran)
        // Linux AWT follows the used Ctrl+, with a typed ',' that still carries Ctrl: an Unknown event on desktop.
        assertTrue(dispatcher.onPreview(keyEvent(Key.Unknown, KeyEventType.Unknown, ',', ctrl = true)), "no ',' is typed")
        assertTrue(dispatcher.onPreview(keyEvent(Key.Comma, KeyEventType.KeyUp, ',', ctrl = true)))
        assertFalse(dispatcher.onPreview(keyEvent(Key.Unknown, KeyEventType.Unknown, ',')), "a ',' typed after the release is the user's")
    }

    @Test fun theMacMenusEchoOfAKeyIsIgnored() {
        var ran = 0
        val dispatcher = dispatcherWith(KeyOs.Apple, AppCommands.NewSession) { ran++ }
        dispatcher.nativeMenuChords = setOf(KeyChord("n", meta = true))
        val cmdN = keyEvent(Key.N, KeyEventType.KeyDown, 'n', meta = true)
        // The window's own hook sees the press first; then the dispatcher's passes judge it.
        dispatcher.onWindowKey(cmdN)
        assertTrue(dispatcher.press(cmdN))
        assertEquals(1, ran)
        // Then the native menu fires its key equivalent for that same press.
        assertFalse(dispatcher.invokeFromMenu(AppCommands.NewSession))
        assertEquals(1, ran)
        // A click on the menu item, with no key press before it.
        assertTrue(dispatcher.invokeFromMenu(AppCommands.NewSession))
        assertEquals(2, ran)
    }

    @Test fun forgettingHeldKeysLeavesTheirReleaseAlone() {
        val dispatcher = dispatcherWith(KeyOs.Other, AppCommands.NewSession) { }
        assertTrue(dispatcher.press(keyEvent(Key.N, KeyEventType.KeyDown, ctrl = true)))
        // The window lost the focus with N down: N's release goes to another window.
        dispatcher.forgetHeldKeys()
        assertFalse(dispatcher.onPreview(keyEvent(Key.N, KeyEventType.KeyUp, ctrl = true)))
    }
}
