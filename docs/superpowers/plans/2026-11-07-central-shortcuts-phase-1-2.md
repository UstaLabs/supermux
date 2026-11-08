# Central keyboard shortcuts — Phase 1 (core) + Phase 2 (desktop): Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One registry of app commands with per-OS keys, resolved by one dispatcher per window that knows where the focus is, replacing today's scattered handlers; strict ⌘ on Apple / Ctrl elsewhere.

**Architecture:** A pure core (`dev.supermux.ui.keys`: commands, keymap, chord labels, resolver) plus Compose glue: *key scopes* (regions that track focus and hold command handlers) and a `ShortcutRoot` at the root of each window's content that runs the dispatcher's preview and bubble passes. A focus keeper makes the root take focus back whenever nothing in the window holds it, so a key always reaches the root. Dialogs and popups are separate Compose layers whose keys never reach the root, so app shortcuts are off while one is in front.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.12, the editor's `KeyChord` (`apps/editor-core`), JUnit via `kotlin.test` + Compose desktop UI tests (`runComposeUiTest`).

**Spec:** `docs/superpowers/specs/2026-11-07-central-shortcuts-design.md` (approved 2026-11-07). One deviation, recorded in the spec's "As built" in Task 11: the dispatcher sits at the root of the window's *content* with a focus keeper, not in per-platform window hooks. The reason is that Compose desktop dialogs and popups are layers that leave the main layer's focus untouched (checked in `CanvasLayersComposeSceneImpl` bytecode: `requestFocus(layer)` only sets `focusedLayer`). A window-level hook would therefore fire app shortcuts behind any of the ~100 dialogs and popups.

## Ground rules (read before every task)

- Repo root: `/home/ahmet/.mux/worktrees/supermux-3962b5bf/c2e36f60-5794-4cf9-aeab-1869665ed6d2`. Gradle lives in `apps/`.
- **Every Gradle run** goes through the box's heavy-job lock, from the repo root:
  ```bash
  flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- \
    apps/gradlew -p apps -Pkotlin.compiler.execution.strategy=in-process --no-daemon -Dorg.gradle.jvmargs=-Xmx2g --console=plain <tasks>
  ```
  Below this is written `GRADLE <tasks>`. One `:ui:jvmTest` class takes ~9–18 min here, so batch test classes in one run.
- Never run a broad suite (`bun test`, all of `:ui:jvmTest`). No Android emulator on this box.
- Commit trailer (supermux rule): `Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>`. Commit with explicit paths, never `git add -A`.
- Jvm tests run on Linux, where the primary modifier is Ctrl. Tests that press the primary modifier use `val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft` (import `dev.supermux.editor.compose.isApplePlatform`), as the editor's tests do.

## File map

**Create** (all under `apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/`):
- `AppCommands.kt`: `CommandGroup`, `AppCommand`, the `AppCommands` catalog.
- `Keymap.kt`: `KeyOs`, `currentKeyOs`, `AppBinding`, `bind`, `bindOther`, `Keymap`, `DefaultKeymap`.
- `ChordLabels.kt`: `formatChord` (⇧⌘N / Ctrl+Shift+N); `composeKeyOf` / `keyNameOf` (key name ↔ Compose `Key`, for menus).
- `KeyResolver.kt`: `KeyPhase`, `ScopeView`, `KeyHit`, `resolveKey`, `passesOwner`. Pure.
- `KeyScopes.kt`: `KeyContext`, `KeyScope`, `KeyScopeTracker`, `LocalKeyScope`, `rememberKeyScope`, `Modifier.keyScope`, `ProvideKeyScope`, `RegisterCommand`.
- `ShortcutDispatcher.kt`: `ShortcutDispatcher`, `LocalShortcutDispatcher`, `ShortcutRoot`.

**Create tests** (under `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/`): `KeymapTest.kt`, `KeyResolverTest.kt`, `KeymapConflictTest.kt`, `ShortcutRootTest.kt`.

**Modify:**
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/SupermuxApp.kt`: `ShortcutRoot` replaces the root box and its shortcut modifiers; window commands; `NewTerminal` registration.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ShellUiState.kt`: drop `newTerminalAction`.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspacePanes.kt`: a key scope per pane; `paneKeyContext`; `addTerminalInWindow`.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/windows/ExtraWindowPanes.kt`: `ShortcutRoot` + `NewTerminal`.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt` + `ChatSearch.kt`: find in chat through the registry.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/EditorPanes.kt` + `files/FileSearch.kt`: go to file through the registry.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Composer.kt`: strict paste-image key; drop `ChatInputFocus` reports.
- `apps/editor-plugins/diff/src/commonMain/kotlin/dev/supermux/editor/plugins/diff/ReviewWidgets.kt`: strict ⌘↩ / Ctrl+Enter.
- `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt` + `shell/DesktopMainMenu.kt`: the dispatcher hoisted; menu keys from the keymap; macOS menu-owned keys; Linux/Windows menu not over a terminal.

**Delete:** `ui/shell/ShellShortcuts.kt`, `ui/shell/WorkspaceShortcuts.kt`, `ui/chat/ChatInputFocus.kt`, and the tests `ui/shell/ShellShortcutsTest.kt`, `ui/shell/WorkspaceShortcutsTest.kt`, `desktop/.../editor/PasteImageShortcutTest.kt`.

---

# Phase 1 — the core (no behaviour change in the app)

### Task 1: Commands, keymap and chord labels

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/AppCommands.kt`
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/Keymap.kt`
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/ChordLabels.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/KeymapTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.supermux.ui.keys

import androidx.compose.ui.input.key.Key
import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeymapTest {
    @Test fun modIsCmdOnAppleAndCtrlElsewhere() {
        val b = bind("Mod-Shift-n", AppCommands.MoveToNewWindow)
        assertEquals(KeyChord("n", shift = true, meta = true), b.chord(KeyOs.Apple))
        assertEquals(KeyChord("n", ctrl = true, shift = true), b.chord(KeyOs.Other))
    }

    @Test fun anOtherOnlyKeyHasNoAppleChord() {
        val b = bindOther("Ctrl-Shift-t", AppCommands.NewTerminal)
        assertNull(b.chord(KeyOs.Apple))
        assertEquals(KeyChord("t", ctrl = true, shift = true), b.chord(KeyOs.Other))
    }

    @Test fun newTerminalHasCtrlShiftTOnlyOffApple() {
        assertEquals(listOf(KeyChord("t", meta = true)), DefaultKeymap.chordsFor(AppCommands.NewTerminal, KeyOs.Apple))
        assertEquals(
            listOf(KeyChord("t", ctrl = true), KeyChord("t", ctrl = true, shift = true)),
            DefaultKeymap.chordsFor(AppCommands.NewTerminal, KeyOs.Other),
        )
    }

    @Test fun ctrlNIsNotAnAppKeyOnAMac() {
        assertEquals(listOf("session.new"), DefaultKeymap.bindingsFor(KeyChord("n", meta = true), KeyOs.Apple).map { it.command })
        assertTrue(DefaultKeymap.bindingsFor(KeyChord("n", ctrl = true), KeyOs.Apple).isEmpty())
    }

    @Test fun everyBindingNamesACatalogCommand() {
        val ids = AppCommands.all.map { it.id }.toSet()
        assertTrue(DefaultKeymap.bindings.all { it.command in ids })
    }

    @Test fun chordsAreDrawnTheWayEachOsWritesThem() {
        assertEquals("⇧⌘N", formatChord(KeyChord("n", shift = true, meta = true), KeyOs.Apple))
        assertEquals("⌃⌥⇧⌘K", formatChord(KeyChord("k", ctrl = true, alt = true, shift = true, meta = true), KeyOs.Apple))
        assertEquals("Ctrl+Shift+N", formatChord(KeyChord("n", ctrl = true, shift = true), KeyOs.Other))
        assertEquals("⌘,", formatChord(KeyChord(",", meta = true), KeyOs.Apple))
        assertEquals("Ctrl+Alt+\\", formatChord(KeyChord("\\", ctrl = true, alt = true), KeyOs.Other))
        assertEquals("⌘↩", formatChord(KeyChord("Enter", meta = true), KeyOs.Apple))
        assertEquals("Ctrl+Enter", formatChord(KeyChord("Enter", ctrl = true), KeyOs.Other))
    }

    @Test fun keyNamesRoundTripThroughComposeKeys() {
        assertEquals(Key.N, composeKeyOf("n"))
        assertEquals(Key.Comma, composeKeyOf(","))
        assertEquals(Key.Backslash, composeKeyOf("\\"))
        assertEquals("n", keyNameOf(Key.N))
        assertEquals("/", keyNameOf(Key.Slash))
        assertNull(composeKeyOf("NoSuchKey"))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.keys.KeymapTest'`
Expected: compilation FAILS (`Unresolved reference: bind`, `AppCommands`, …).

- [ ] **Step 3: Write `AppCommands.kt`**

```kotlin
package dev.supermux.ui.keys

/** Where a command sits in the shortcuts list. */
enum class CommandGroup(val title: String) {
    General("General"),
    Sessions("Sessions"),
    Workspace("Workspace"),
    Chat("Chat"),
    Files("Files"),
}

/**
 * Something the keyboard can do: a stable [id] that keys and handlers refer to, and a [title] for menus and the
 * shortcuts list. Spec: docs/superpowers/specs/2026-11-07-central-shortcuts-design.md.
 */
data class AppCommand(val id: String, val title: String, val group: CommandGroup)

/** Every app command. A handler is registered with [RegisterCommand] by whatever owns the state it acts on. */
object AppCommands {
    val NewSession = AppCommand("session.new", "New session", CommandGroup.Sessions)
    val MoveToNewWindow = AppCommand("window.moveToNew", "Move to a new window", CommandGroup.Workspace)
    val NewTerminal = AppCommand("workspace.newTerminal", "New terminal tab", CommandGroup.Workspace)
    val ToggleSidebar = AppCommand("sidebar.toggle", "Show or hide the sidebar", CommandGroup.General)
    val GoToFile = AppCommand("files.goTo", "Go to file", CommandGroup.Files)
    val FindInChat = AppCommand("chat.find", "Find in chat", CommandGroup.Chat)
    val OpenSettings = AppCommand("settings.open", "Settings", CommandGroup.General)
    val ShowShortcuts = AppCommand("shortcuts.show", "Keyboard shortcuts", CommandGroup.General)
    val PasteImage = AppCommand("composer.pasteImage", "Paste an image into the message", CommandGroup.Chat)
    val SplitRight = AppCommand("workspace.splitRight", "Split right (a new column)", CommandGroup.Workspace)
    val SplitDown = AppCommand("workspace.splitDown", "Split down (a new row)", CommandGroup.Workspace)

    val all: List<AppCommand> = listOf(
        NewSession, MoveToNewWindow, NewTerminal, ToggleSidebar, GoToFile, FindInChat,
        OpenSettings, ShowShortcuts, PasteImage, SplitRight, SplitDown,
    )
}
```

- [ ] **Step 4: Write `Keymap.kt`**

```kotlin
package dev.supermux.ui.keys

import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.core.KeyChord

/**
 * The two keyboard conventions. On [Apple], ⌘ is the app's modifier and Ctrl belongs to the shell and to text
 * editing; everywhere else ([Other]) Ctrl is the app's.
 */
enum class KeyOs { Apple, Other }

/** This device's convention, from the one detector the editor already uses (#29 step 10 will feed it). */
val currentKeyOs: KeyOs get() = if (isApplePlatform) KeyOs.Apple else KeyOs.Other

/**
 * One default key for [command]: [apple] and [other] are its chord under each convention, null where it has none.
 *
 * [appFirst]: the app takes the key before a focused text box or editor sees it. Only for a key they would eat
 * (paste-image's ⌘V, split's Ctrl+\ off Apple, which text boxes use as "deselect").
 * [overOwner]: off Apple, the key also reaches the app while a terminal or remote display has the focus. On Apple
 * every ⌘ key does ([passesOwner]).
 */
data class AppBinding(
    val command: String,
    val apple: KeyChord?,
    val other: KeyChord?,
    val appFirst: Boolean = false,
    val overOwner: Boolean = false,
) {
    fun chord(os: KeyOs): KeyChord? = if (os == KeyOs.Apple) apple else other
}

/** [keys] in the editor's grammar (`"Mod-Shift-n"`; Mod is ⌘ on Apple and Ctrl elsewhere); [mac] replaces it on Apple. */
fun bind(keys: String, command: AppCommand, mac: String = keys, appFirst: Boolean = false, overOwner: Boolean = false) =
    AppBinding(command.id, KeyChord.parse(mac, apple = true), KeyChord.parse(keys, apple = false), appFirst, overOwner)

/** A key that exists only off Apple. */
fun bindOther(keys: String, command: AppCommand, appFirst: Boolean = false, overOwner: Boolean = false) =
    AppBinding(command.id, null, KeyChord.parse(keys, apple = false), appFirst, overOwner)

class Keymap(val bindings: List<AppBinding>) {
    fun bindingsFor(chord: KeyChord, os: KeyOs): List<AppBinding> = bindings.filter { it.chord(os) == chord }

    fun chordsFor(command: AppCommand, os: KeyOs): List<KeyChord> =
        bindings.filter { it.command == command.id }.mapNotNull { it.chord(os) }
}

/**
 * The default keys (spec, "Default keys (v1)"). Off Apple a focused terminal keeps every Ctrl key; the app's keys
 * there are Ctrl+Shift+T, Ctrl+Shift+N and Ctrl+, (`overOwner`).
 */
val DefaultKeymap = Keymap(
    listOf(
        bind("Mod-n", AppCommands.NewSession),
        bind("Mod-Shift-n", AppCommands.MoveToNewWindow, overOwner = true),
        bind("Mod-t", AppCommands.NewTerminal),
        bindOther("Ctrl-Shift-t", AppCommands.NewTerminal, overOwner = true),
        bind("Mod-b", AppCommands.ToggleSidebar),
        bind("Mod-p", AppCommands.GoToFile),
        bind("Mod-f", AppCommands.FindInChat),
        bind("Mod-,", AppCommands.OpenSettings, overOwner = true),
        bind("Mod-/", AppCommands.ShowShortcuts),
        bind("Mod-v", AppCommands.PasteImage, appFirst = true),
        bind("Mod-\\", AppCommands.SplitRight, appFirst = true),
        bind("Mod-Alt-\\", AppCommands.SplitDown, appFirst = true),
    ),
)
```

- [ ] **Step 5: Write `ChordLabels.kt`**

```kotlin
package dev.supermux.ui.keys

import androidx.compose.ui.input.key.Key
import dev.supermux.editor.core.KeyChord

/** A chord as this device writes it: `⌃⌥⇧⌘N` on Apple (Apple's modifier order), `Ctrl+Alt+Shift+N` elsewhere. */
fun formatChord(chord: KeyChord, os: KeyOs): String {
    val key = keyLabel(chord.key, os)
    if (os == KeyOs.Apple) {
        return buildString {
            if (chord.ctrl) append('⌃')
            if (chord.alt) append('⌥')
            if (chord.shift) append('⇧')
            if (chord.meta) append('⌘')
            append(key)
        }
    }
    return buildList {
        if (chord.ctrl) add("Ctrl")
        if (chord.alt) add("Alt")
        if (chord.shift) add("Shift")
        if (chord.meta) add("Super")
        add(key)
    }.joinToString("+")
}

private fun keyLabel(key: String, os: KeyOs): String = when (key) {
    "Enter" -> if (os == KeyOs.Apple) "↩" else "Enter"
    "Escape" -> if (os == KeyOs.Apple) "⎋" else "Esc"
    "Backspace" -> if (os == KeyOs.Apple) "⌫" else "Backspace"
    "Tab" -> if (os == KeyOs.Apple) "⇥" else "Tab"
    "ArrowUp" -> "↑"
    "ArrowDown" -> "↓"
    "ArrowLeft" -> "←"
    "ArrowRight" -> "→"
    else -> if (key.length == 1) key.uppercase() else key
}

/** The editor's key names ([KeyChord.key]) for the Compose keys a menu accelerator can carry. */
private val NAMED_KEYS: Map<String, Key> = buildMap {
    val letters = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z)
    letters.forEachIndexed { i, k -> put(('a' + i).toString(), k) }
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    digits.forEachIndexed { i, k -> put(i.toString(), k) }
    put(",", Key.Comma); put(".", Key.Period); put("/", Key.Slash); put("\\", Key.Backslash)
    put("[", Key.LeftBracket); put("]", Key.RightBracket); put("-", Key.Minus); put("=", Key.Equals)
    put(";", Key.Semicolon); put("'", Key.Apostrophe); put("`", Key.Grave)
    put("Enter", Key.Enter); put("Escape", Key.Escape); put("Tab", Key.Tab); put("Space", Key.Spacebar)
}
private val KEY_NAMES: Map<Key, String> = NAMED_KEYS.entries.associate { (name, key) -> key to name }

/** The Compose [Key] for a [KeyChord.key] name, or null when a menu cannot carry it. */
fun composeKeyOf(name: String): Key? = NAMED_KEYS[name]

/** The [KeyChord.key] name of a Compose [Key], or null. */
fun keyNameOf(key: Key): String? = KEY_NAMES[key]
```

- [ ] **Step 6: Run the test to see it pass**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.keys.KeymapTest'`
Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/AppCommands.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/Keymap.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/ChordLabels.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/KeymapTest.kt
git commit -m "feat(keys): one catalog of app commands and their keys per OS (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 2: The resolver

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/KeyResolver.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/KeyResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyResolverTest {
    private class Scope(override val opaque: Boolean = false, vararg commands: AppCommand) : ScopeView {
        private val ids = commands.map { it.id }.toSet()
        override fun handles(commandId: String) = commandId in ids
    }

    private val window = Scope(false, AppCommands.NewSession, AppCommands.NewTerminal, AppCommands.ToggleSidebar)
    private val terminal = Scope(opaque = true)

    @Test fun aWindowKeyWaitsForTheBubbleWhenNoOwnerHasTheFocus() {
        val cmdN = KeyChord("n", meta = true)
        assertNull(resolveKey(cmdN, listOf(window), KeyPhase.Preview, KeyOs.Apple))
        assertEquals("session.new", resolveKey(cmdN, listOf(window), KeyPhase.Bubble, KeyOs.Apple)?.binding?.command)
    }

    @Test fun onAppleEveryCmdKeyPassesAFocusedTerminal() {
        val hit = resolveKey(KeyChord("t", meta = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple)
        assertEquals("workspace.newTerminal", hit?.binding?.command)
        assertEquals(1, hit?.scopeIndex)
    }

    @Test fun offAppleAFocusedTerminalKeepsCtrlT() {
        assertNull(resolveKey(KeyChord("t", ctrl = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Other))
    }

    @Test fun offAppleCtrlShiftTPassesAFocusedTerminal() {
        val hit = resolveKey(KeyChord("t", ctrl = true, shift = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Other)
        assertEquals("workspace.newTerminal", hit?.binding?.command)
    }

    @Test fun onAppleCtrlTIsNeverAnAppKey() {
        val ctrlT = KeyChord("t", ctrl = true)
        assertNull(resolveKey(ctrlT, listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
        assertNull(resolveKey(ctrlT, listOf(window), KeyPhase.Bubble, KeyOs.Apple))
    }

    @Test fun theTerminalsOwnCmdKeysStayWithIt() {
        assertNull(resolveKey(KeyChord("c", meta = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
        assertNull(resolveKey(KeyChord("f", meta = true), listOf(terminal, window), KeyPhase.Preview, KeyOs.Apple))
    }

    @Test fun anAppFirstKeyRunsBeforeTheFocusedElement() {
        val composer = Scope(false, AppCommands.PasteImage)
        val hit = resolveKey(KeyChord("v", ctrl = true), listOf(composer, window), KeyPhase.Preview, KeyOs.Other)
        assertEquals("composer.pasteImage", hit?.binding?.command)
    }

    @Test fun theDeepestHandlerWins() {
        val keymap = Keymap(listOf(bind("Mod-k", AppCommands.FindInChat), bind("Mod-k", AppCommands.GoToFile)))
        val chat = Scope(false, AppCommands.FindInChat)
        val files = Scope(false, AppCommands.GoToFile)
        val ctrlK = KeyChord("k", ctrl = true)
        assertEquals("chat.find", resolveKey(ctrlK, listOf(chat, files), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.command)
        assertEquals("files.goTo", resolveKey(ctrlK, listOf(files, chat), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.command)
    }

    @Test fun aDeeperHandlerThatWaitsBlocksAShallowerAppFirstOne() {
        val keymap = Keymap(listOf(bind("Mod-k", AppCommands.FindInChat), bind("Mod-k", AppCommands.GoToFile, appFirst = true)))
        val chat = Scope(false, AppCommands.FindInChat)
        val outer = Scope(false, AppCommands.GoToFile)
        val ctrlK = KeyChord("k", ctrl = true)
        assertNull(resolveKey(ctrlK, listOf(chat, outer), KeyPhase.Preview, KeyOs.Other, keymap))
        assertEquals("chat.find", resolveKey(ctrlK, listOf(chat, outer), KeyPhase.Bubble, KeyOs.Other, keymap)?.binding?.command)
    }

    @Test fun noHandlerMeansNoHit() {
        assertNull(resolveKey(KeyChord("p", meta = true), listOf(window), KeyPhase.Bubble, KeyOs.Apple))
        assertNull(resolveKey(KeyChord("n", meta = true), emptyList(), KeyPhase.Bubble, KeyOs.Apple))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.keys.KeyResolverTest'`
Expected: compilation FAILS (`Unresolved reference: resolveKey`).

- [ ] **Step 3: Write `KeyResolver.kt`**

```kotlin
package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord

/** [Preview]: before the focused element sees the key. [Bubble]: after it left the key unused. */
enum class KeyPhase { Preview, Bubble }

/** One scope of the focus stack, as the resolver sees it. */
interface ScopeView {
    /** A terminal or remote display: it takes every key it can name, so the app claims its own keys there first. */
    val opaque: Boolean

    /** Whether a handler for [commandId] is registered here right now. */
    fun handles(commandId: String): Boolean
}

/** The binding to run, and the index in the stack of the scope whose handler runs it. */
data class KeyHit(val binding: AppBinding, val scopeIndex: Int)

/**
 * The binding [chord] runs in [phase], or null. [stack] is deepest first.
 *
 * The deepest scope with a handler for one of the chord's bindings wins. In [KeyPhase.Bubble] it runs. In
 * [KeyPhase.Preview] it runs only where the app must go first: while a terminal or remote display has the focus, a
 * key that [passesOwner] (the owner would otherwise swallow it); anywhere else an [AppBinding.appFirst] key. Every
 * other key waits for the bubble, so the focused text box or editor gets first refusal.
 */
fun resolveKey(
    chord: KeyChord,
    stack: List<ScopeView>,
    phase: KeyPhase,
    os: KeyOs,
    keymap: Keymap = DefaultKeymap,
): KeyHit? {
    val candidates = keymap.bindingsFor(chord, os)
    if (candidates.isEmpty()) return null
    val hit = stack.withIndex().firstNotNullOfOrNull { (i, scope) ->
        candidates.firstOrNull { scope.handles(it.command) }?.let { KeyHit(it, i) }
    } ?: return null
    return when (phase) {
        KeyPhase.Bubble -> hit
        KeyPhase.Preview -> hit.takeIf {
            if (stack.first().opaque) passesOwner(it.binding, chord, os) else it.binding.appFirst
        }
    }
}

/** Whether [binding] reaches the app past a focused terminal or remote display: on Apple every ⌘ key, elsewhere only [AppBinding.overOwner] keys. */
fun passesOwner(binding: AppBinding, chord: KeyChord, os: KeyOs): Boolean =
    binding.overOwner || (os == KeyOs.Apple && chord.meta)
```

- [ ] **Step 4: Run the test to see it pass**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.keys.KeyResolverTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/KeyResolver.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/KeyResolverTest.kt
git commit -m "feat(keys): resolve a key against the focus stack; the deepest handler wins, the terminal keeps its keys (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 3: The conflict test

**Files:**
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/KeymapConflictTest.kt`

This test locks the default keymap against text editing, the terminal and the editor. It passes on the keymap from Task 1. Prove it bites before relying on it: in Step 2, temporarily add `bind("Mod-z", AppCommands.ShowShortcuts)` to `DefaultKeymap`, run it and see it fail, then remove that line.

- [ ] **Step 1: Write the test**

```kotlin
package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertTrue

/** Spec "Testing": the default keys never fight each other, text editing, the terminal or the editor. */
class KeymapConflictTest {
    private fun chords(os: KeyOs, vararg specs: String): Set<KeyChord> =
        specs.map { KeyChord.parse(it, apple = os == KeyOs.Apple) }.toSet()

    /** Keys Compose's text fields edit with (read from the 1.12 library), plus the editor's swallowed chords. */
    private val textEditing = mapOf(
        KeyOs.Apple to chords(
            KeyOs.Apple, "Mod-a", "Mod-c", "Mod-v", "Mod-x", "Mod-z", "Mod-Shift-z", "Mod-y",
            "Ctrl-a", "Ctrl-e", "Ctrl-f", "Ctrl-b", "Ctrl-p", "Ctrl-n", "Ctrl-h", "Ctrl-d", "Ctrl-k", "Ctrl-o",
            "Mod-ArrowLeft", "Mod-ArrowRight", "Mod-ArrowUp", "Mod-ArrowDown", "Mod-Backspace", "Alt-Backspace",
        ),
        KeyOs.Other to chords(
            KeyOs.Other, "Mod-a", "Mod-c", "Mod-v", "Mod-x", "Mod-z", "Mod-Shift-z", "Mod-y",
            "Ctrl-h", "Ctrl-\\", "Ctrl-ArrowLeft", "Ctrl-ArrowRight", "Ctrl-Backspace", "Ctrl-Delete",
            "Ctrl-Home", "Ctrl-End", "Shift-Insert", "Ctrl-Insert",
        ),
    )

    /** The terminal's own keys (terminal-compose TerminalShortcuts.kt). */
    private val terminalOwn = mapOf(
        KeyOs.Apple to chords(KeyOs.Apple, "Mod-c", "Mod-v", "Mod-a", "Mod-f", "Mod-=", "Mod--", "Mod-0", "Mod-+"),
        KeyOs.Other to chords(
            KeyOs.Other, "Ctrl-Shift-c", "Ctrl-Shift-v", "Ctrl-Shift-a", "Ctrl-Shift-f", "Ctrl-Shift-=",
            "Ctrl-=", "Ctrl--", "Ctrl-0",
        ),
    )

    /** The editor's Mod keys (editor-compose DefaultCommands.kt and editor-plugins; spec audit). */
    private val editorOwn = listOf(
        "Mod-s", "Mod-a", "Mod-c", "Mod-x", "Mod-v", "Mod-z", "Mod-y", "Mod-Shift-z", "Mod-u", "Mod-d", "Mod-g",
        "Mod-Shift-g", "Mod-Shift-l", "Mod-Alt-g", "Mod-i", "Mod-/", "Mod-Shift-\\", "Mod-Shift-k", "Mod-Shift-m",
        "Mod-Enter", "Mod-]", "Mod-[", "Mod-=", "Mod--", "Mod-0", "Mod-f", "Mod-Shift-Space",
    )

    /**
     * Deliberate overlaps (spec "Testing"): ⌘/ and ⌘F stay the editor's inside it; find-in-chat and paste-image are
     * handled only by a chat and a composer; split right takes "deselect" (Ctrl+\) from text boxes off Apple.
     */
    private val allowed = setOf(
        AppCommands.ShowShortcuts.id to "Mod-/",
        AppCommands.FindInChat.id to "Mod-f",
        AppCommands.PasteImage.id to "Mod-v",
        AppCommands.SplitRight.id to "Ctrl-\\",
    )

    private fun isAllowed(b: AppBinding, os: KeyOs) =
        allowed.any { (cmd, spec) -> cmd == b.command && KeyChord.parse(spec, apple = os == KeyOs.Apple) == b.chord(os) }

    private fun clashes(os: KeyOs, taken: Set<KeyChord>, filter: (AppBinding) -> Boolean = { true }) =
        DefaultKeymap.bindings.filter { b -> filter(b) && b.chord(os) in taken && !isAllowed(b, os) }

    @Test fun noKeyRunsTwoCommands() {
        for (os in KeyOs.entries) {
            val byChord = DefaultKeymap.bindings.filter { it.chord(os) != null }.groupBy { it.chord(os) }
            val twice = byChord.filterValues { list -> list.map { it.command }.toSet().size > 1 }
            assertTrue(twice.isEmpty(), "$os: $twice")
        }
    }

    @Test fun appKeysAreNotTextEditingKeys() {
        for (os in KeyOs.entries) assertTrue(clashes(os, textEditing.getValue(os)).isEmpty(), "$os: ${clashes(os, textEditing.getValue(os))}")
    }

    @Test fun keysThatPassATerminalAreNotItsOwn() {
        for (os in KeyOs.entries) {
            val bad = clashes(os, terminalOwn.getValue(os)) { b -> b.chord(os)?.let { passesOwner(b, it, os) } == true }
            assertTrue(bad.isEmpty(), "$os: $bad")
        }
    }

    @Test fun appKeysAreNotEditorKeys() {
        for (os in KeyOs.entries) {
            val editor = chords(os, *editorOwn.toTypedArray())
            assertTrue(clashes(os, editor).isEmpty(), "$os: ${clashes(os, editor)}")
        }
    }
}
```

- [ ] **Step 2: Prove it bites, then run it green**

Temporarily add `bind("Mod-z", AppCommands.ShowShortcuts),` to `DefaultKeymap`.
Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.keys.KeymapConflictTest'`
Expected: FAIL in `appKeysAreNotTextEditingKeys` and `appKeysAreNotEditorKeys`, naming `shortcuts.show`.
Remove the line, run again. Expected: PASS, 4 tests.

- [ ] **Step 3: Commit**

```bash
git add apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/KeymapConflictTest.kt
git commit -m "test(keys): the default keys never fight text editing, the terminal or the editor (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 4: Key scopes, the dispatcher and `ShortcutRoot`

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/KeyScopes.kt`
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/ShortcutDispatcher.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/ShortcutRootTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run it to see it fail**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.keys.ShortcutRootTest'`
Expected: compilation FAILS (`Unresolved reference: ShortcutRoot`).

- [ ] **Step 3: Write `KeyScopes.kt`**

```kotlin
package dev.supermux.ui.keys

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** What a region of the UI is to the keyboard. [opaque] regions (a terminal, a remote display) take every key they can name. */
enum class KeyContext(val opaque: Boolean = false) {
    Window,
    Chat,
    Files,
    Editor,
    Terminal(opaque = true),
    Display(opaque = true),
    Other,
}

/** A region with a [KeyContext]: whether it holds the focus, and the command handlers registered inside it. */
class KeyScope internal constructor(val context: KeyContext, val viewId: String?, val parent: KeyScope?) : ScopeView {
    private val handlers = LinkedHashMap<String, () -> Boolean>()
    internal var hasFocus = false
    internal var tracker: KeyScopeTracker? = null

    override val opaque: Boolean get() = context.opaque
    override fun handles(commandId: String): Boolean = commandId in handlers

    /** Runs [commandId]'s handler here; false when there is none, or it declined. */
    fun run(commandId: String): Boolean = handlers[commandId]?.invoke() == true

    /** The window's root scope. */
    val root: KeyScope get() = parent?.root ?: this

    /** This scope, then each ancestor. */
    fun stack(): List<KeyScope> = generateSequence(this) { it.parent }.toList()

    internal fun put(commandId: String, handler: () -> Boolean) {
        handlers[commandId] = handler
    }

    internal fun remove(commandId: String, handler: () -> Boolean) {
        if (handlers[commandId] === handler) handlers.remove(commandId)
    }
}

/** One window's scopes: which hold the focus, and which a pointer went down in last. */
class KeyScopeTracker {
    private val scopes = ArrayList<KeyScope>()
    internal var lastPressed: KeyScope? = null

    internal fun register(scope: KeyScope) {
        scopes += scope
        scope.tracker = this
    }

    internal fun unregister(scope: KeyScope) {
        scopes -= scope
        if (lastPressed === scope) lastPressed = null
    }

    /**
     * Where a key lands, deepest scope first: the deepest scope holding the focus, then its ancestors. When only the
     * window's root holds it (a click in a transcript or on a sidebar row leaves it there), the scope pressed last
     * stands in, minus an opaque scope that has no focus: its keys are not going to it.
     */
    fun stack(): List<KeyScope> {
        val focused = scopes.filter { it.hasFocus }
        val deepest = focused.firstOrNull { f -> focused.none { it.parent === f } }
        if (deepest == null || deepest.parent == null) {
            val pressed = lastPressed?.stack()?.dropWhile { it.opaque && !it.hasFocus }
            if (!pressed.isNullOrEmpty()) return pressed
        }
        return deepest?.stack() ?: scopes.firstOrNull { it.parent == null }?.let { listOf(it) }.orEmpty()
    }

    /** True while a terminal or remote display holds the focus. */
    val focusIsOpaque: Boolean get() = stack().firstOrNull()?.let { it.opaque && it.hasFocus } == true
}

/** The nearest enclosing scope. */
val LocalKeyScope = staticCompositionLocalOf<KeyScope?> { null }

internal val LocalKeyScopeTracker = staticCompositionLocalOf<KeyScopeTracker?> { null }

/**
 * A child of the current [LocalKeyScope], registered with this window's tracker while composed. Put [keyScope] on
 * its region and [ProvideKeyScope] around its content.
 */
@Composable
fun rememberKeyScope(context: KeyContext, viewId: String? = null): KeyScope {
    val parent = LocalKeyScope.current
    val tracker = LocalKeyScopeTracker.current
    val scope = remember(parent, context, viewId) { KeyScope(context, viewId, parent) }
    DisposableEffect(scope, tracker) {
        tracker?.register(scope)
        onDispose { tracker?.unregister(scope) }
    }
    return scope
}

/** Tracks whether [scope]'s region holds the focus, and makes it the scope pressed last when a pointer goes down in it. */
fun Modifier.keyScope(scope: KeyScope): Modifier = this
    .onFocusChanged { scope.hasFocus = it.hasFocus }
    .pointerInput(scope) {
        // Initial pass, outer before inner: the innermost scope under the pointer is the last to write.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            scope.tracker?.lastPressed = scope
        }
    }

@Composable
fun ProvideKeyScope(scope: KeyScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKeyScope provides scope, content = content)
}

/**
 * Registers [handler] for [command] in [scope] (by default the nearest one) while composed and [enabled]. The
 * handler returns false to decline; the key then goes on as if unbound.
 */
@Composable
fun RegisterCommand(
    command: AppCommand,
    enabled: Boolean = true,
    scope: KeyScope? = LocalKeyScope.current,
    handler: () -> Boolean,
) {
    val latest by rememberUpdatedState(handler)
    DisposableEffect(scope, command.id, enabled) {
        if (scope == null || !enabled) return@DisposableEffect onDispose { }
        val registered: () -> Boolean = { latest() }
        scope.put(command.id, registered)
        onDispose { scope.remove(command.id, registered) }
    }
}
```

- [ ] **Step 4: Write `ShortcutDispatcher.kt`**

```kotlin
package dev.supermux.ui.keys

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import dev.supermux.editor.compose.keyChordOf
import dev.supermux.editor.core.KeyChord

/** One window's keyboard: resolves each key against [keymap] and its [tracker]'s focus stack, and runs the command. */
class ShortcutDispatcher(val os: KeyOs = currentKeyOs, val keymap: Keymap = DefaultKeymap) {
    val tracker = KeyScopeTracker()

    /**
     * Keys the macOS menu bar runs itself. The menu fires its key equivalent even when Compose already used the key,
     * so these are only swallowed here: the focused terminal never sees them, and nothing runs twice.
     */
    var nativeMenuChords: Set<KeyChord> = emptySet()

    /** Keys whose press the app used: their release is swallowed too. */
    private val taken = HashSet<Key>()

    /** The preview pass, before the focused element. */
    fun onPreview(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) return taken.remove(event.key)
        if (event.type != KeyEventType.KeyDown) return false
        val chord = chordOf(event) ?: return false
        if (chord in nativeMenuChords) return take(event)
        return run(chord, KeyPhase.Preview, event)
    }

    /** The bubble pass, after the focused element left the key unused. */
    fun onBubble(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val chord = chordOf(event) ?: return false
        return run(chord, KeyPhase.Bubble, event)
    }

    /** Runs [command] where the focus is, as a menu item does. False when nothing there can run it. */
    fun invoke(command: AppCommand): Boolean =
        tracker.stack().firstOrNull { it.handles(command.id) }?.run(command.id) == true

    private fun run(chord: KeyChord, phase: KeyPhase, event: KeyEvent): Boolean {
        val stack = tracker.stack()
        val hit = resolveKey(chord, stack, phase, os, keymap) ?: return false
        return stack[hit.scopeIndex].run(hit.binding.command) && take(event)
    }

    private fun take(event: KeyEvent): Boolean {
        taken += event.key
        return true
    }

    private fun chordOf(event: KeyEvent): KeyChord? {
        val chord = keyChordOf(event) ?: return null
        // Off Apple, Ctrl+Alt is AltGr on many layouts: a chord that typed a character is that character.
        val cp = event.utf16CodePoint
        if (os == KeyOs.Other && chord.ctrl && chord.alt && cp >= 0x20 && cp != 0x7F && cp != 0xFFFF) return null
        return chord
    }
}

val LocalShortcutDispatcher = staticCompositionLocalOf<ShortcutDispatcher?> { null }

/**
 * The root of a window's content: its [KeyContext.Window] scope, and [dispatcher]'s two passes over every key.
 *
 * Dialogs and popups are layers with their own focus; their keys never reach this node, so app shortcuts are off
 * while one is in front. For the same reason focus must not fall out of the window's content: when nothing here
 * holds it (a `clearFocus()`, a focused element that went away), the root takes it back on the next frame, and the
 * scope pressed last stands in for where the user is ([KeyScopeTracker.stack]).
 */
@Composable
fun ShortcutRoot(dispatcher: ShortcutDispatcher, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val root = remember(dispatcher) { KeyScope(KeyContext.Window, viewId = null, parent = null) }
    DisposableEffect(root) {
        dispatcher.tracker.register(root)
        onDispose { dispatcher.tracker.unregister(root) }
    }
    val focus = remember { FocusRequester() }
    var lost by remember { mutableStateOf(true) }
    LaunchedEffect(lost) {
        if (!lost) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    CompositionLocalProvider(
        LocalKeyScopeTracker provides dispatcher.tracker,
        LocalShortcutDispatcher provides dispatcher,
        LocalKeyScope provides root,
    ) {
        Box(
            modifier
                .onPreviewKeyEvent(dispatcher::onPreview)
                .onKeyEvent(dispatcher::onBubble)
                .keyScope(root)
                .onFocusChanged { lost = !it.hasFocus }
                .focusRequester(focus)
                .focusable(),
            content = content,
        )
    }
}
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.keys.ShortcutRootTest' --tests 'dev.supermux.ui.keys.KeyResolverTest' --tests 'dev.supermux.ui.keys.KeymapTest' --tests 'dev.supermux.ui.keys.KeymapConflictTest'`
Expected: PASS, 7 + 10 + 7 + 4 tests.
If `keysSentToADialogNeverReachTheWindow` fails, STOP. The design's modal claim is then wrong; report it with the failure output, don't patch around it.

- [ ] **Step 6: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/KeyScopes.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/keys/ShortcutDispatcher.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/keys/ShortcutRootTest.kt
git commit -m "feat(keys): key scopes and one dispatcher per window, with a focus keeper so a key always reaches it (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

**Phase 1 done:** message Muxie (`4960bc72-67ba-40cb-89ef-6d6cfd47b299`) with branch `mux/central-shortcut-system`, the SHA, card #96 and "phase 1, pure core, no app wiring"; cc Tally (`0275cbdb-209a-4302-a948-b7a96a762b1e`).

---

# Phase 2 — wire the app and the desktop

### Task 5: The main window runs on `ShortcutRoot`

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/SupermuxApp.kt`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ShellUiState.kt:373-378`
- Delete: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ShellShortcuts.kt`, `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspaceShortcuts.kt`, `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/ShellShortcutsTest.kt`, `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/WorkspaceShortcutsTest.kt`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/AppShellTest.kt`

- [ ] **Step 1: Write the failing test.** Add to `AppShellTest` (next to `shell_shortcuts_are_gated_off_while_the_launcher_overlay_is_up`). Add a class member `private val mod = if (dev.supermux.editor.compose.isApplePlatform) Key.MetaLeft else Key.CtrlLeft`. In the existing gating test, replace `withKeyDown(Key.CtrlLeft)` with `withKeyDown(mod)`.

```kotlin
    @Test fun the_sidebar_key_works_with_nothing_focused() = runComposeUiTest {
        // The bug Ahmet reported as "⌘N only works while an input has focus": a click in the transcript or on a
        // sidebar row leaves nothing focused, and the shortcuts hung on a box that then saw no key.
        val app = appFor(mutableListOf())
        val ui = ShellUiState()
        lateinit var focus: androidx.compose.ui.focus.FocusManager
        setPlatformContent(uiPrefs = UiPrefs(InMemorySettingsStore())) {
            focus = androidx.compose.ui.platform.LocalFocusManager.current
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        runOnIdle { focus.clearFocus() }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed)
        onRoot().performKeyInput { withKeyDown(mod) { pressKey(Key.B) } }
        waitForIdle()
        assertTrue(ui.sidebarCollapsed)
    }
```

Add the import `androidx.compose.ui.test.onRoot` if the file lacks it.

- [ ] **Step 2: Run it to see it fail**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.shell.AppShellTest'`
Expected: `the_sidebar_key_works_with_nothing_focused` FAILS (`expected true`), the rest pass.

- [ ] **Step 3: Swap the root box for `ShortcutRoot`.** In `SupermuxApp.kt`:

(a) Add a parameter at the end of `SupermuxApp(…)`'s parameter list, after `settingsSection`:
```kotlin
    /** This window's keyboard. A desktop host passes its own, to drive its menu bar through it. */
    shortcuts: ShortcutDispatcher = remember { ShortcutDispatcher() },
```

(b) Delete these lines (around line 732):
```kotlin
    // Root focus so the shortcuts (Ctrl/Cmd B/N) resolve even before the user clicks into a pane;
    // once the composer/terminal is focused, key events still bubble up here.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
```

(c) Replace (around line 788):
```kotlin
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
                // Gate the pane/sidebar shortcuts OFF while any destination above Home is up: it
                // is modal, so a chord it leaves unhandled must NOT bubble here and silently
                // mutate the layout behind it.
                .then(
                    if (ui.overlayOpen) Modifier
                    else Modifier.shellShortcuts(ui, onNewSession, onMoveToNewWindow).newTerminalShortcut(ui),
                ),
        ) {
```
with
```kotlin
        ShortcutRoot(shortcuts, Modifier.fillMaxSize()) {
            // The window's commands. Those that change the layout are off while a page is up (Settings, Archived,
            // New session…): it is modal, so a key must not change the layout behind it. New session and Settings
            // open a page of their own, so they work over another page.
            RegisterCommand(AppCommands.NewSession, enabled = !ui.launcherOpen) { onNewSession(); true }
            RegisterCommand(AppCommands.MoveToNewWindow, enabled = !ui.overlayOpen) { onMoveToNewWindow(); true }
            RegisterCommand(AppCommands.ToggleSidebar, enabled = !ui.overlayOpen) {
                ui.sidebarCollapsed = !ui.sidebarCollapsed
                true
            }
            RegisterCommand(AppCommands.OpenSettings, enabled = !ui.settingsOpen) { ui.openSettings(); true }
```
The closing `}` of the old `Box` now closes `ShortcutRoot`. Its content is a `BoxScope` lambda, as before.

(d) In `WorkspacePanel`, replace (around line 1899):
```kotlin
        androidx.compose.runtime.DisposableEffect(ui) {
            val action = { addTerminal() }
            ui.newTerminalAction = action
            onDispose { if (ui.newTerminalAction === action) ui.newTerminalAction = null }
        }
```
with
```kotlin
        RegisterCommand(AppCommands.NewTerminal, enabled = !ui.overlayOpen, scope = LocalKeyScope.current?.root) {
            addTerminal()
            true
        }
```
Also change that block's leading comment from `// Ctrl/Cmd+T: a terminal tab…` to `// ⌘T / Ctrl+T (Ctrl+Shift+T in a terminal off Apple): a terminal tab…`.

(e) Add imports: `dev.supermux.ui.keys.AppCommands`, `dev.supermux.ui.keys.LocalKeyScope`, `dev.supermux.ui.keys.RegisterCommand`, `dev.supermux.ui.keys.ShortcutDispatcher`, `dev.supermux.ui.keys.ShortcutRoot`. Remove an import that is now unused (`FocusRequester`, `focusRequester`, `focusable`) only if nothing else in the file uses it. Check with `grep -n 'FocusRequester\|focusRequester\|focusable()' SupermuxApp.kt`.

- [ ] **Step 4: Drop the old pieces.** In `ShellUiState.kt`, delete lines 373–378 (the `newTerminalAction` doc comment, the property and the blank line after it). Delete the four files:
```bash
git rm apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ShellShortcuts.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspaceShortcuts.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/ShellShortcutsTest.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/WorkspaceShortcutsTest.kt
```
Then `grep -rn 'newTerminalAction\|shellShortcuts\|ShellShortcut\|WorkspaceShortcut' apps/ --include=*.kt` must print only comments; fix any code hit.

- [ ] **Step 5: Run the tests to see them pass**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.shell.AppShellTest' --tests 'dev.supermux.ui.keys.*'`
Expected: PASS, including `the_sidebar_key_works_with_nothing_focused` and `shell_shortcuts_are_gated_off_while_the_launcher_overlay_is_up`.

- [ ] **Step 6: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/SupermuxApp.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/ShellUiState.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/AppShellTest.kt
git commit -m "feat(keys): the main window's shortcuts go through ShortcutRoot and work with nothing focused (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 6: Panes get key scopes; find in chat and go to file move into the registry

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspacePanes.kt` (`WorkspacePaneContent`, ~lines 1040–1136; add `paneKeyContext`)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt:976-986`, `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatSearch.kt:178-181`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/EditorPanes.kt:165-172`, `apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/FileSearch.kt:143-160`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/FileSearchTest.kt`

- [ ] **Step 1: Rewrite the go-to-file test so it fails.** In `FileSearchTest.kt`, delete `onlyPlainCmdOrCtrlPIsTheChord`. Replace `cmdOrCtrlPInThePaneFocusesTheField` with the test below, and add the member `private val mod = if (dev.supermux.editor.compose.isApplePlatform) Key.MetaLeft else Key.CtrlLeft`.

```kotlin
    @Test fun theGoToFileKeyFocusesTheField() = runComposeUiTest {
        val view = TreeViewState("/w")
        val focus = FocusRequester()
        val inner = FocusRequester()
        setContent(
            host {
                ShortcutRoot(remember { ShortcutDispatcher() }) {
                    RegisterCommand(AppCommands.GoToFile) { focus.requestFocus(); true }
                    FileSearch(null, view, "/w", onOpen = {}, focusRequester = focus) {
                        Box(Modifier.size(10.dp).focusRequester(inner).focusable().testTag("tree_stub"))
                    }
                }
            },
        )
        runOnIdle { inner.requestFocus() }
        onNodeWithTag("tree_stub").performKeyInput { withKeyDown(mod) { pressKey(Key.P) } }
        waitForIdle()
        onNodeWithTag("files_search_field").assertIsFocused()
    }
```
Imports: `dev.supermux.ui.keys.{AppCommands, RegisterCommand, ShortcutDispatcher, ShortcutRoot}`, `androidx.compose.runtime.remember`; drop the `goToFileShortcut` and `isGoToFileChord` imports.

- [ ] **Step 2: Run it to see it fail**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.files.FileSearchTest'`
Expected: compilation passes; `theGoToFileKeyFocusesTheField` PASSES already (the root and the handler are in the test). This step only proves the test harness works. The behaviour change is checked in Step 7 on the real panes. Continue.

- [ ] **Step 3: One key scope per pane.** In `WorkspacePanes.kt`, add near `WorkspacePaneContent`:

```kotlin
/** A pane's [KeyContext], from its view's kind (ViewHost.kt's switch). */
internal fun paneKeyContext(view: ViewDto): KeyContext = when (view.kind) {
    "chat" -> KeyContext.Chat
    // A terminal or remote display is scoped where it is drawn (Step 3b), so every one is, not only a pane's.
    "editor" -> when (view.stateString("mode")) {
        "file", "diff" -> KeyContext.Editor
        else -> KeyContext.Files
    }
    else -> KeyContext.Other
}
```

In `WorkspacePaneContent`, change
```kotlin
    } else if (v != null) {
        key(hostId, viewId) {
            // The store this VIEW belongs to (its chat session's host, else the workspace's).
            val viewApp = appFor(v.chatSessionId() ?: current.primarySessionId ?: session?.id ?: "")
            ViewHost(
```
to
```kotlin
    } else if (v != null) {
        key(hostId, viewId) {
            // The store this VIEW belongs to (its chat session's host, else the workspace's).
            val viewApp = appFor(v.chatSessionId() ?: current.primarySessionId ?: session?.id ?: "")
            // What the keyboard knows about this pane: its kind (a terminal keeps its keys) and its view.
            val paneScope = rememberKeyScope(paneKeyContext(v), viewId)
            ProvideKeyScope(paneScope) {
            ViewHost(
```
and the end of the same call from
```kotlin
                modifier = Modifier.fillMaxSize().observePress(viewId) {
                    ws.focusedViewId = viewId
                    if (v.filePathOrNull() != null) ws.focusedFileViewId = viewId
                },
            )
        }
```
to
```kotlin
                modifier = Modifier.fillMaxSize().keyScope(paneScope).observePress(viewId) {
                    ws.focusedViewId = viewId
                    if (v.filePathOrNull() != null) ws.focusedFileViewId = viewId
                },
            )
            }
        }
```
Imports: `dev.supermux.ui.keys.{KeyContext, ProvideKeyScope, keyScope, rememberKeyScope}`.

- [ ] **Step 3b: The terminal and the remote display are scoped where they are drawn.** This covers every terminal, not only workspace panes: Android's chat terminal tabs too.
  - In `apps/ui/src/commonMain/kotlin/dev/supermux/ui/terminal/GhosttyTerminalViewFactory.kt` (~line 444), change `Box(modifier.testTag(TERMINAL_GRID_TAG)) {` to:
    ```kotlin
        // A terminal takes every key it can name; the app claims its own keys here first (KeyContext.Terminal).
        val terminalKeys = rememberKeyScope(KeyContext.Terminal)
        ProvideKeyScope(terminalKeys) {
        Box(modifier.keyScope(terminalKeys).testTag(TERMINAL_GRID_TAG)) {
    ```
    Then close the extra `}` after that `Box`'s own closing brace.
  - In `apps/ui/src/commonMain/kotlin/dev/supermux/ui/display/DisplayPanel.kt`, the surface tagged `"vnc_surface"` gets the same treatment:
    - `val displayKeys = rememberKeyScope(KeyContext.Display)` before it;
    - `.keyScope(displayKeys)` right after `.testTag("vnc_surface")`;
    - the surface wrapped in `ProvideKeyScope(displayKeys) { … }`.
  - Imports: `dev.supermux.ui.keys.{KeyContext, ProvideKeyScope, keyScope, rememberKeyScope}`.

- [ ] **Step 3c: Hidden content registers nothing.** Kept-alive content stays composed while hidden: up to 10 workspaces, the phone's last tabs, Android's visited chats. Its root-level registrations (go to file, a session-only chat's find) would otherwise compete with the visible ones. `RegisterCommand` already honours `LocalKeyScopeActive`; provide it at the four keep-alive sites:
  - `shell/WorkspaceKeepAlive.kt`, in `WorkspaceKeepAliveHost`: `content(workspaceId, active)` becomes `CompositionLocalProvider(LocalKeyScopeActive provides active) { content(workspaceId, active) }`.
  - `shell/WorkspacePanes.kt:804`: inside `KeepAlivePanel(visible = id == tabs.selectedId && !hideUnderGrid) { … }`, wrap the content in `CompositionLocalProvider(LocalKeyScopeActive provides (id == tabs.selectedId && !hideUnderGrid)) { … }`.
  - `terminal/TerminalTabs.kt:434`: the same, with `id == activeId`.
  - `shell/SupermuxApp.kt:1620`: inside `Box(Modifier.keepAlivePanel(visible)) { … }`, the same with `visible`.

- [ ] **Step 4: Find in chat through the registry.** In `ChatPanel.kt`, delete these lines from the `Column` modifier chain:
```kotlin
            .onPreviewKeyEvent { event ->
                if (!isFindChord(event)) return@onPreviewKeyEvent false
                find.show()
                true
            }
```
and add, on the line before `Column(`:
```kotlin
    // ⌘F / Ctrl+F: this chat's find bar, also right after a click in the transcript (which leaves no focus).
    RegisterCommand(AppCommands.FindInChat) { find.show(); true }
```
Import `dev.supermux.ui.keys.AppCommands` and `dev.supermux.ui.keys.RegisterCommand`; remove the `onPreviewKeyEvent` import if it becomes unused. In `ChatSearch.kt` delete `isFindChord` (the KDoc line `/** Ctrl/Cmd+F, exactly. */` and the function), and any imports only it used.

- [ ] **Step 5: Go to file through the registry.** In `EditorPanes.kt` (the explorer pane, around line 165), delete:
```kotlin
            // ⌘P / Ctrl+P while focus is anywhere in the pane (the tree, the header).
            .goToFileShortcut { runCatching { searchFocus.requestFocus() } },
```
so the modifier chain ends at `.background(cs.surfaceContainerHigh),`. Then add, before that `Box(`:
```kotlin
    // ⌘P / Ctrl+P: from this pane, and from anywhere in the window while the pane is open.
    val goToFile = { runCatching { searchFocus.requestFocus() }.isSuccess }
    RegisterCommand(AppCommands.GoToFile, handler = goToFile)
    RegisterCommand(AppCommands.GoToFile, scope = LocalKeyScope.current?.root, handler = goToFile)
```
Imports: `dev.supermux.ui.keys.{AppCommands, LocalKeyScope, RegisterCommand}`; drop `dev.supermux.ui.files.goToFileShortcut`. In `FileSearch.kt` delete `isGoToFileChord`, the KDoc block above `goToFileShortcut`, and `goToFileShortcut`, plus imports only they used.

- [ ] **Step 6: Compile and run the touched tests**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.files.FileSearchTest' --tests 'dev.supermux.ui.shell.AppShellTest' --tests 'dev.supermux.ui.keys.*'`
Expected: PASS. Also `grep -rn 'isFindChord\|goToFileShortcut\|isGoToFileChord' apps --include=*.kt` prints nothing.

- [ ] **Step 7: Prove the pane path end to end.** Add to `AppShellTest` a test that registers through a real pane. If the existing helpers can open a workspace with a chat view (look for a test that calls `ui.selectSession` on a workspace fixture in `AppShellTest`/`WorkspacePanesTest`), write `cmd_f_after_a_transcript_click_opens_that_chats_find_bar`: open the workspace chat, click the node tagged `TestIds.CHAT_VIEW`, then `onRoot().performKeyInput { withKeyDown(mod) { pressKey(Key.F) } }`, and assert the find field (`ChatSearch`'s field test tag) exists. If no fixture opens a workspace chat in under ~30 lines, skip this test, say so in the task report, and cover the path in Task 10's manual check instead.

- [ ] **Step 8: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspacePanes.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatSearch.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/EditorPanes.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/files/FileSearch.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/files/FileSearchTest.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/shell/AppShellTest.kt
git commit -m "feat(keys): every pane is a key scope; find in chat and go to file run through the registry (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 7: Strict modifier for paste-image and the review field

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Composer.kt:471-516` (`isComposerPasteKey`, `handleComposerPasteKey`), `Composer.kt:1249-1250` (`ChatInputFocus` reports)
- Modify: `apps/editor-plugins/diff/src/commonMain/kotlin/dev/supermux/editor/plugins/diff/ReviewWidgets.kt:122`
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/ComposerPasteTest.kt`

- [ ] **Step 1: Make the tests strict (they fail).** In `ComposerPasteTest.kt`:
- In `pasteKey_ctrlV_down_isPaste`, `pasteKey_otherKeys_areNotPaste`, `handleComposerPasteKey_ctrl_invokesOnPasteImage`, `handleComposerPasteKey_textOnly_doesNotConsume_andDoesNotStage` and `handleComposerPasteKey_nonPasteKey_doesNotProbeClipboard`, add the argument `os = KeyOs.Other` to the `isComposerPasteKey(…)` / `handleComposerPasteKey(…)` call.
- In `pasteKey_metaV_down_isPaste` and `handleComposerPasteKey_meta_invokesOnPasteImage`, add `os = KeyOs.Apple`.
- In `pasteKey_shift_v_isNotPaste_fallsThroughForPlainText`, add `os = KeyOs.Other` to the Ctrl call and `os = KeyOs.Apple` to the Meta call.
- Add:
```kotlin
    @Test fun pasteKey_theOtherFamily_isNotPaste() {
        // Strict ⌘ on Apple / Ctrl elsewhere (#96): ⌃V on a Mac and Super+V on Linux are not paste-image.
        assertFalse(isComposerPasteKey(Key.V, KeyEventType.KeyDown, ctrlPressed = true, metaPressed = false, os = KeyOs.Apple))
        assertFalse(isComposerPasteKey(Key.V, KeyEventType.KeyDown, ctrlPressed = false, metaPressed = true, os = KeyOs.Other))
    }
```
- Add members `private val mod = if (dev.supermux.editor.compose.isApplePlatform) Key.MetaLeft else Key.CtrlLeft` and `private val otherMod = if (dev.supermux.editor.compose.isApplePlatform) Key.CtrlLeft else Key.MetaLeft`.
- In `ctrlShiftV_keyEvent_fallsThrough_doesNotStage`, `ctrlV_keyEvent_stagesPasteImage` and `textOnlyCtrlV_doesNotStage_andFieldAcceptsTypedText`, replace `withKeyDown(Key.CtrlLeft)` with `withKeyDown(mod)`.
- Replace `metaV_keyEvent_stagesPasteImage` with:
```kotlin
    /** The other modifier family (Super+V on Linux, ⌃V on a Mac) never stages: strict ⌘/Ctrl (#96). */
    @Test fun theOtherModifier_V_doesNotStage() = runComposeUiTest {
        val uploaded = mutableListOf<String>()
        val platform = FakePlatform().apply { clipboard.images = listOf(picked("from-other-v.png")) }
        setPlatformContent(platform) {
            Composer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                onUpload = { _, name, _, _, _ -> uploaded.add(name); "file-$name" },
            )
        }
        onNodeWithTag("composer-input").performClick()
        onNodeWithTag("composer-input").performKeyInput { withKeyDown(otherMod) { pressKey(Key.V) } }
        waitForIdle()
        assertTrue(uploaded.isEmpty())
        assertEquals(0, platform.clipboard.probes)
    }
```
Import `dev.supermux.ui.keys.KeyOs`.

- [ ] **Step 2: Run it to see it fail**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.chat.ComposerPasteTest'`
Expected: compilation FAILS (`Cannot find a parameter with this name: os`).

- [ ] **Step 3: Make the predicate strict.** In `Composer.kt`, replace `isComposerPasteKey` (keep its position) with:
```kotlin
/**
 * Pure paste-image key predicate: `true` only for a KeyDown V with this platform's primary modifier (⌘ on Apple,
 * Ctrl elsewhere, never the other one, #96) and Shift **not** held. Ctrl/Cmd+Shift+V is the conventional "paste
 * as plain text" chord and must fall through to the text field.
 */
internal fun isComposerPasteKey(
    key: Key,
    type: KeyEventType,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    shiftPressed: Boolean = false,
    os: KeyOs = currentKeyOs,
): Boolean =
    type == KeyEventType.KeyDown &&
        key == Key.V &&
        !shiftPressed &&
        if (os == KeyOs.Apple) metaPressed && !ctrlPressed else ctrlPressed && !metaPressed
```
In `handleComposerPasteKey`, add the parameter `os: KeyOs = currentKeyOs,` after `shiftPressed: Boolean,`, and pass `os` to `isComposerPasteKey(key, type, ctrlPressed, metaPressed, shiftPressed, os)`. Import `dev.supermux.ui.keys.KeyOs` and `dev.supermux.ui.keys.currentKeyOs`.

- [ ] **Step 4: Drop `ChatInputFocus` from the composer.** Delete `Composer.kt:1249-1250`:
```kotlin
    androidx.compose.runtime.LaunchedEffect(inputFocused) { ChatInputFocus.report(inputInteraction, inputFocused) }
    androidx.compose.runtime.DisposableEffect(inputInteraction) { onDispose { ChatInputFocus.report(inputInteraction, false) } }
```
If `inputFocused` is now unused, delete its declaration too. (Task 9 deletes `ChatInputFocus.kt` once the desktop stops reading it.)

- [ ] **Step 5: Strict submit in the review field.** In `ReviewWidgets.kt:122`, replace
```kotlin
                    (e.key == Key.Enter || e.key == Key.NumPadEnter) && (e.isMetaPressed || e.isCtrlPressed) -> { onSend(); true }
```
with
```kotlin
                    (e.key == Key.Enter || e.key == Key.NumPadEnter) &&
                        (if (isApplePlatform) e.isMetaPressed && !e.isCtrlPressed else e.isCtrlPressed && !e.isMetaPressed) -> { onSend(); true }
```
Import `dev.supermux.editor.compose.isApplePlatform` if the file lacks it.

- [ ] **Step 6: Run the tests to see them pass**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.chat.ComposerPasteTest' :editor-plugins:diff:jvmTest --tests '*ReviewUiTest*'`
Expected: PASS. `ReviewUiTest` already presses `mod` (Meta on Apple, else Ctrl).

- [ ] **Step 7: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Composer.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/ComposerPasteTest.kt apps/editor-plugins/diff/src/commonMain/kotlin/dev/supermux/editor/plugins/diff/ReviewWidgets.kt
git commit -m "fix(keys): paste-image and review send take only this platform's modifier (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 8: Torn-off windows get the dispatcher and ⌘T

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspacePanes.kt` (add `addTerminalInWindow`)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/SupermuxApp.kt` (`addTerminal` uses it)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/windows/ExtraWindowPanes.kt`

- [ ] **Step 1: Share the terminal placement.** Add to `WorkspacePanes.kt`:
```kotlin
/**
 * A terminal tab in window [hostId]: in the group of the pane pressed last, else of the file last worked in, else
 * the window's first group, the way "+ → Terminal" adds one there.
 */
fun addTerminalInWindow(ui: ShellUiState, hostId: String, ws: WorkspaceSession, app: HostStore, workspaceId: String) {
    val tree = ui.windows.layoutFor(hostId, ws.layoutSync.tree)
    val gid = ws.focusedViewId?.let { groupIdOf(tree, it) }
        ?: ws.focusedFileViewId?.let { groupIdOf(tree, it) }
        ?: firstGroupId(tree)
        ?: return
    app.addWorkspaceView(workspaceId, NewViewKind.TERMINAL, gid) { newViewId ->
        ui.windows.expandClaim(hostId, setOf(newViewId), ws.layoutSync.tree)
    }
}
```
In `SupermuxApp.kt`'s `addTerminal`, replace the `else` branch body:
```kotlin
            } else {
                val hostId = ui.windows.mainHostId
                val tree = ui.windows.layoutFor(hostId, layoutSync.tree)
                val gid = ws.focusedViewId?.let { groupIdOf(tree, it) }
                    ?: ws.focusedFileViewId?.let { groupIdOf(tree, it) }
                    ?: firstGroupId(tree)
                if (gid != null) {
                    wsApp.addWorkspaceView(current.id, NewViewKind.TERMINAL, gid) { newViewId ->
                        ui.windows.expandClaim(hostId, setOf(newViewId), layoutSync.tree)
                    }
                }
            }
```
with
```kotlin
            } else {
                addTerminalInWindow(ui, ui.windows.mainHostId, ws, wsApp, current.id)
            }
```

- [ ] **Step 2: The extra window's root.** In `ExtraWindowPanes.kt`, wrap the `WorkspacePanes(…)` call:
```kotlin
    ShortcutRoot(remember(hostId) { ShortcutDispatcher() }, modifier) {
        // A torn-off window has the pane and workspace keys; the window-level ones (new session, settings,
        // sidebar) belong to the main window.
        RegisterCommand(AppCommands.NewTerminal) { addTerminalInWindow(ui, hostId, ws, bind.app, current.id); true }
        WorkspacePanes(
            …unchanged arguments…,
            modifier = Modifier.fillMaxSize(),
            …
        )
    }
```
In the argument list, only `modifier = modifier` changes to `modifier = Modifier.fillMaxSize()`. Imports: `dev.supermux.ui.keys.{AppCommands, RegisterCommand, ShortcutDispatcher, ShortcutRoot}`, `dev.supermux.ui.shell.addTerminalInWindow`, `androidx.compose.foundation.layout.fillMaxSize`.

- [ ] **Step 3: Compile and run the shell tests**

Run: `GRADLE :ui:jvmTest --tests 'dev.supermux.ui.shell.AppShellTest' --tests '*ExtraWindow*' --tests 'dev.supermux.ui.keys.*'`
Expected: PASS. If `--tests '*ExtraWindow*'` matches no class, Gradle reports "No tests found" for that filter. Drop the filter and say so in the report.

- [ ] **Step 4: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/WorkspacePanes.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/SupermuxApp.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/shell/windows/ExtraWindowPanes.kt
git commit -m "feat(keys): torn-off windows run the dispatcher; ⌘T opens a terminal in that window (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 9: Desktop: menus from the keymap, macOS menu-owned keys, no menu over a terminal

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/DesktopMainMenu.kt` (`MenuShortcut`, `MainMenuEntry.Toggle`, `DesktopMenuBar`)
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt` (~lines 907–1035, 1978, 2364–2365)
- Delete: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatInputFocus.kt`, `apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/PasteImageShortcutTest.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/MenuShortcutTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.ui.input.key.Key
import dev.supermux.editor.core.KeyChord
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.KeyOs
import kotlin.test.Test
import kotlin.test.assertEquals

class MenuShortcutTest {
    @Test fun aMacMenuShowsCmdNForNewSession() {
        assertEquals(MenuShortcut(Key.N, meta = true), menuShortcutFor(AppCommands.NewSession, KeyOs.Apple))
    }

    @Test fun elsewhereItIsCtrlN() {
        assertEquals(MenuShortcut(Key.N, ctrl = true), menuShortcutFor(AppCommands.NewSession, KeyOs.Other))
    }

    @Test fun aShortcutKnowsItsChord() {
        assertEquals(KeyChord(",", meta = true), MenuShortcut(Key.Comma, meta = true).chord())
    }

    @Test fun theCustomMenuWritesKeysTheDevicesWay() {
        assertEquals("Ctrl+Shift+N", MenuShortcut(Key.N, ctrl = true, shift = true).label(KeyOs.Other))
        assertEquals("⌘B", MenuShortcut(Key.B, meta = true).label(KeyOs.Apple))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `GRADLE :desktop:test --tests 'dev.supermux.desktop.shell.MenuShortcutTest'`
Expected: compilation FAILS (`Unresolved reference: menuShortcutFor`).

- [ ] **Step 3: `DesktopMainMenu.kt`.** Replace the `label` property of `MenuShortcut` and add the helpers:
```kotlin
data class MenuShortcut(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    fun toKeyShortcut(): KeyShortcut = KeyShortcut(key, ctrl = ctrl, meta = meta, alt = alt, shift = shift)

    /** The keymap chord this accelerator is, or null for a key the keymap has no name for. */
    fun chord(): KeyChord? = keyNameOf(key)?.let { KeyChord(it, ctrl = ctrl, alt = alt, shift = shift, meta = meta) }

    /** "Ctrl+N" / "⌘N", the way the device writes it (the custom ☰ menu draws this). */
    fun label(os: KeyOs = currentKeyOs): String = chord()?.let { formatChord(it, os) } ?: KeyEvent.getKeyText(key.nativeKeyCode)
}

/** [command]'s first key on [os], as a menu accelerator; null when it has none a menu can carry. */
fun menuShortcutFor(command: AppCommand, os: KeyOs = currentKeyOs): MenuShortcut? =
    DefaultKeymap.chordsFor(command, os).firstNotNullOfOrNull { c ->
        composeKeyOf(c.key)?.let { MenuShortcut(it, ctrl = c.ctrl, shift = c.shift, alt = c.alt, meta = c.meta) }
    }

/** Every accelerator in [this] menu, as keymap chords. */
fun List<MainMenuGroup>.menuChords(): Set<KeyChord> = flatMap { it.entries }.mapNotNull { e ->
    when (e) {
        is MainMenuEntry.Action -> e.shortcut?.chord()
        is MainMenuEntry.Toggle -> e.shortcut?.chord()
        MainMenuEntry.Separator -> null
    }
}.toSet()
```
Give `MainMenuEntry.Action` and `MainMenuEntry.Toggle` an `enabled` flag, and give Toggle a shortcut too:
```kotlin
    data class Action(val label: String, val shortcut: MenuShortcut? = null, val enabled: Boolean = true, val onClick: () -> Unit) : MainMenuEntry
    data class Toggle(val label: String, val checked: Boolean, val shortcut: MenuShortcut? = null, val enabled: Boolean = true, val onToggle: () -> Unit) : MainMenuEntry
```
In `DesktopMenuBar`:
```kotlin
                        is MainMenuEntry.Action -> Item(entry.label, enabled = entry.enabled, shortcut = entry.shortcut?.toKeyShortcut(), onClick = entry.onClick)
                        is MainMenuEntry.Toggle -> CheckboxItem(entry.label, checked = entry.checked, enabled = entry.enabled, shortcut = entry.shortcut?.toKeyShortcut()) { entry.onToggle() }
```
In `MainMenuButton`, pass `entry.enabled` through to the row (`DropdownMenuItem(enabled = …)`).
In `MainMenuButton`, the Action row's trailing text `Text(s.label, …)` becomes `Text(s.label(), …)`. Then `grep -rn 'MenuShortcut\|MainMenuEntry.Toggle' apps/desktop/src` and fix every other reader of `.label` (now `label()`) and every positional `Toggle(…)` call (the new `shortcut` sits third). Add a trailing shortcut to the Toggle row the same way: `trailingIcon = entry.shortcut?.let { s -> { Text(s.label(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) } }`. Imports: `dev.supermux.editor.core.KeyChord`, `dev.supermux.ui.keys.{AppCommand, DefaultKeymap, KeyOs, composeKeyOf, currentKeyOs, formatChord, keyNameOf}`.

- [ ] **Step 4: `Main.kt`.**
(a) After `val mainMenuState = remember { MainMenuState() }` add:
```kotlin
        // The main window's keyboard: the menu bar drives it, and its menu keys gate the Linux menu.
        val shortcuts = remember { ShortcutDispatcher() }
```
(b) The `Window(onPreviewKeyEvent = …)` becomes:
```kotlin
            onPreviewKeyEvent = { e ->
                // The window sees every key before Compose sends it to a layer. The dispatcher notes a press of a macOS
                // menu key here, so that the menu's own firing of it right after is ignored (ShortcutDispatcher.onWindowKey).
                shortcuts.onWindowKey(e)
                // F10 / Alt+F/E/V open the Linux and Windows menu, but never over a terminal or remote screen:
                // Alt+F there is the shell's (forward a word), as in VS Code.
                e.type == KeyEventType.KeyDown &&
                    !shortcuts.tracker.focusIsOpaque &&
                    mainMenuState.onWindowKey(e.key, e.isAltPressed, e.isCtrlPressed, e.isShiftPressed, e.isMetaPressed)
            },
```
(c) Replace the comment block that starts `// Menu bar (paired only). DEDUPE DECISION:` (through `// button in the sidebar band.`) with:
```kotlin
            // Menu bar (paired only). The keys come from the keymap (DefaultKeymap), so the menu shows ⌘N on a
            // Mac and Ctrl+N elsewhere. The items run through the dispatcher and grey out when their command can't run.
            // One menu, two renderings (DesktopMainMenu.kt): the native menu bar, or, under the Linux custom
            // chrome (no system frame to hang a menu bar in), the main-menu button in the sidebar band.
```
(d) Menu entries: in the File group, make the first entries:
```kotlin
                        MainMenuEntry.Action("New Session", menuShortcutFor(AppCommands.NewSession), enabled = shortcuts.canInvoke(AppCommands.NewSession)) { shortcuts.invokeFromMenu(AppCommands.NewSession) },
                        MainMenuEntry.Action("New Terminal", menuShortcutFor(AppCommands.NewTerminal), enabled = shortcuts.canInvoke(AppCommands.NewTerminal)) { shortcuts.invokeFromMenu(AppCommands.NewTerminal) },
```
change `MainMenuEntry.Action("Settings…") { ui.openSettings() }` to `MainMenuEntry.Action("Settings…", menuShortcutFor(AppCommands.OpenSettings), enabled = shortcuts.canInvoke(AppCommands.OpenSettings)) { shortcuts.invokeFromMenu(AppCommands.OpenSettings) }`. In the Edit group, replace the Paste image entry and its comments with:
```kotlin
                        // ⌘V / Ctrl+V in the composer pastes an image itself (Composer.kt); the menu item has no
                        // key, or it would take every paste from the code editor.
                        MainMenuEntry.Action("Paste image") { ui.requestPasteImage() },
```
In the View group:
```kotlin
                        MainMenuEntry.Toggle(
                            "Show Sidebar",
                            checked = !ui.sidebarCollapsed,
                            shortcut = menuShortcutFor(AppCommands.ToggleSidebar),
                            enabled = shortcuts.canInvoke(AppCommands.ToggleSidebar),
                        ) { shortcuts.invokeFromMenu(AppCommands.ToggleSidebar) },
```
(e) Replace `if (paired && linuxChrome == null && windowsChrome == null) DesktopMenuBar(mainMenu)` with:
```kotlin
            val nativeMenu = paired && linuxChrome == null && windowsChrome == null
            if (nativeMenu) DesktopMenuBar(mainMenu)
            // macOS fires a menu key equivalent even when Compose already used the key (the JDK hands the key to
            // Java first, then lets the menu act: AWTView.m). The dispatcher stays the one judge of a key: it notes
            // the press (onWindowKey above), and the menu's echo of it is ignored (invokeFromMenu). A menu item
            // clicked with the mouse runs normally.
            SideEffect { shortcuts.nativeMenuChords = if (nativeMenu && isMacOs()) mainMenu.menuChords() else emptySet() }
```
(f) Pass the dispatcher to the app: in the `SupermuxApp(` call (~line 1978), add the argument `shortcuts = shortcuts,`.
(g) Delete `pasteImageShortcut` (`Main.kt:2364-2365`, the function and its KDoc) and the now-unused `KeyShortcut` import if nothing else uses it. Imports: `dev.supermux.ui.keys.{AppCommands, ShortcutDispatcher}`, `dev.supermux.desktop.shell.{menuShortcutFor, menuChords}`, `androidx.compose.runtime.SideEffect`.

- [ ] **Step 5: Delete the flag and its test**
```bash
git rm apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatInputFocus.kt apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/PasteImageShortcutTest.kt
grep -rn 'ChatInputFocus\|pasteImageShortcut' apps --include=*.kt
```
Expected: the grep prints nothing.

- [ ] **Step 6: Run the tests and compile the hosts**

Run: `GRADLE :desktop:test --tests 'dev.supermux.desktop.shell.MenuShortcutTest' --tests '*LinuxWindowChromeTest*' --tests '*DesktopMainMenu*'`
Then: `GRADLE :desktop:compileKotlin :android:compileDebugKotlin :web:compileKotlinWasmJs`
Expected: PASS and BUILD SUCCESSFUL. A filter that matches no class only reports "No tests found"; say which in the report.

- [ ] **Step 7: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/DesktopMainMenu.kt apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/MenuShortcutTest.kt
git commit -m "feat(desktop): menus take their keys from the keymap (⌘N on a Mac), macOS menu keys run once, no Linux menu over a terminal (#96)" -m "Co-Authored-By: Claude Opus 5.5 - Claude Code in Supermux <noreply@anthropic.com>"
```

### Task 10: Run it for real on Linux

Run the branch's desktop app on this box under Xvfb or the noVNC display (`mux:browser`/`start_display`), signed into a throwaway fleet as earlier desktop tests did (see `domains/infra.md` "A released desktop app, run as a brand-new user"). Check each item and record PASS/FAIL with a screenshot:

- [ ] Click in a chat transcript, then press Ctrl+B: the sidebar toggles. Press Ctrl+N: New session opens.
- [ ] Focus a workspace terminal and press Ctrl+T: the shell receives ^T (in `bash`, type `ab`, press Ctrl+T, see `ba`). Press Ctrl+Shift+T: a new terminal tab opens.
- [ ] In the terminal, Alt+F moves the shell cursor forward a word; the ☰ menu does not open.
- [ ] Click in a chat transcript, then press Ctrl+F: that chat's find bar opens.
- [ ] Ctrl+P from a chat pane focuses "Go to file…" in the Files pane.
- [ ] With the Settings page open, Ctrl+B does nothing.
- [ ] Open a dialog (Rename session), press Ctrl+N: nothing opens behind it.
- [ ] In the composer, and again in a terminal, press Ctrl+,: Settings opens and no `,` is typed (AWT follows Ctrl+, with a typed `,`; the dispatcher swallows that echo).

Fix anything that fails, test-first, with its own commit.

### Task 11: The Mac, the train, the record

- [ ] **Step 1: Take the Mac hot-reload lease.** Read `mac:~/work/supermux-hotreload/.lease`. If it is held and unexpired, wait or message the holder. Otherwise write `Central Keyboard Shortcuts (#96) 43e0862e-c81d-4fa5-8f9c-488735c26817 until <now+2h>`. Also check `lsof +D ~/work/supermux-hotreload/apps -t`. Rsync this branch's `apps/` in and update `SOURCE_COMMIT`. Run the desktop app there (`domains/claudemux.digest.md`, "Running the desktop app on the Mac with hot reload").
- [ ] **Step 2: Check on the Mac:**
  - ⌘N with nothing focused opens New session **once**.
  - ⌘T from a focused terminal opens **exactly one** terminal. This is the menu-vs-dispatcher check.
  - With a dialog open (Rename session), ⌘N opens nothing behind it. File › New Terminal clicked with the mouse opens one terminal.
  - With Settings open, ⌘B does nothing and View › Show Sidebar is greyed out.
  - ⌃T in the terminal reaches the shell.
  - The menu bar shows ⌘N, ⌘T, ⌘, and ⌘B.
  - ⌘F after a transcript click opens the find bar.
  - If ⌘T opens two terminals, the menu fired BEFORE Java saw the key. Then reverse the echo: `invokeFromMenu` notes the command, and the dispatcher ignores the key press that follows within the same second. If ⌘T opens none, the menu does not fire after Compose used the key; set `nativeMenuChords` to `emptySet()`. Record what the Mac showed in the spec's "As built".
- [ ] **Step 3: Release the lease** (delete `.lease`).
- [ ] **Step 4: Record it.** Append "As built (phase 1–2)" to the spec: the content-root + focus-keeper deviation and its reason (the `CanvasLayersComposeSceneImpl` finding), the Mac menu result, and anything Task 10 changed. Append the durable facts to `~/.mux/domains/claudemux.md`. Commit the spec with `git add -f` (docs are ignored; the fleet force-adds specs).
- [ ] **Step 5: The train.** Message Muxie: branch `mux/central-shortcut-system`, the tip SHA, card #96, "phase 2: desktop wiring; targeted tests green; Linux + Mac checked". Cc Tally. Then `issues comment 96` with the same summary.

---

## Later plans (not in this one)

- **Phase 3:** Android (verify on the Fold/DeX: the focus keeper and Ctrl keys; `onProvideKeyboardShortcuts`). iOS (verify on the iPad: whether the root receives hardware keys with only the root focused; if not, key commands on a container view controller; iPadOS 26 menu). Web (a capture-phase `keydown` listener that `preventDefault`s app keys synchronously, since Compose's web text input acts a frame late; browser-tab-reserved keys).
- **Phase 4:** design review FIRST, per Ahmet's standing rule (2026-11-08): a clickable HTML prototype of the screen in the app's real look (Theme.kt colours, Geist, our chrome; no stock Material chips, pills, FABs or big rounded cards), served as a paired relay preview link, with switches for the choices he must make. Then the shortcuts screen and `ShowShortcuts` (⌘/), built on today's section API as #97 described: a `SettingsSection.KeyboardShortcuts` entry, `desc()`/`icon()` in SettingsHub.kt, a branch in FleetSettingsSections.kt and DesktopSettingsSections.kt, the web route `shortcuts` in UrlRoutes.kt; no top bar of its own when `topBarShown`; body capped at `SettingsDetailMaxWidth`; rows exposed as (label, keys, group) for #97's future settings search. `workspace.splitRight` / `workspace.splitDown` handlers call #91's `splitWorkspace(direction)` once it lands (registered in the workspace, not per pane).
