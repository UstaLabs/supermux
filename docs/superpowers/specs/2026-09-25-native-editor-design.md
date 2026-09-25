# Native editor: design

**Status:** agreed in conversation on 2026-09-25, awaiting review of this written spec.
**Visual companion:** https://editor-arch.ustalabs.com (paired devices only).
**Precedent:** the terminal rewrite (`apps/terminal-core` + `apps/terminal-compose`, merged to `dev` 2026-09-24).

## 1. Goal

Replace CodeMirror 6, which runs inside four web views (Android `WebView`, iOS `WKWebView`, desktop
KCEF/JCEF, web iframe) and is driven over a JavaScript string bridge, with **one editor we own**:

- A shared pure-Kotlin core (text, selection, transactions, extension API).
- tree-sitter parsing on every client.
- One shared Compose surface that does all the drawing.
- A clean cutover. CodeMirror, every web view and the bridge are deleted in the same merge. The old
  and new editors never coexist.

The foundation must support plugins. Our own features are the first plugins and use the same public
API anyone else would.

## 2. Rulings

| Topic | Decision |
|---|---|
| Scope | A full editor we own, built the way the terminal was. |
| Cutover bar | Parity with today's editor (listed in §7), **plus the VS Code-style side-by-side diff**. |
| Plugins | Compiled-in Kotlin modules now. The API is designed so a sandbox (runtime-loaded plugins) can be added later without changing it. |
| Parsing | tree-sitter: `ktreesitter` (io.github.tree-sitter, 0.25.1) on Android/JVM/iOS, `web-tree-sitter` on wasmJs, behind our own interface. |
| Languages | Every language today's bundle highlights that has a solid tree-sitter grammar. The rest open as plain text, and that list is published before cutover. |
| Heavy work | Builds, test suites and simulators run on the Mac (`ssh mac`, private checkout). The Linux host is RAM-constrained, so no Gradle there. |

## 3. Modules

Dependencies point downwards only. Editor modules never depend on `:shared` or `:ui` (same rule as the
terminal modules).

```
:ui + the four apps     host: tabs, panes, file tree, saving, broker LSP channel
editor-plugins/*        our features, on the public API only
editor-compose          the one shared Compose surface
editor-core             pure commonMain Kotlin: rope, state, transactions, extensions
editor-syntax           our parsing interface; ktreesitter / web-tree-sitter backends
```

`editor-compose` depends on `editor-core`. Plugins depend on `editor-core`, on `editor-syntax` when they
need the tree, and on `editor-compose` only for block-widget composables. `editor-core` depends on
nothing but the Kotlin stdlib and coroutines.

## 4. editor-core

### 4.1 Text: rope
- An immutable balanced tree of text chunks. Each branch stores its **UTF-16 length, UTF-8 byte length
  and line count**, so offset↔line and UTF-16↔UTF-8 conversions are O(log n).
- Edits produce a new rope that shares unchanged nodes with the old one. Snapshots are free, so
  undo, background parsing and diff snapshots need no locks.

### 4.2 State
`EditorState` is one immutable value holding:
- `doc: Rope`
- `selection: EditorSelection`: **one or more ranges** plus a main index. Multiple ranges are drawn
  from day one, because Alt-drag column selection exists today.
- each extension's state-field values.

### 4.3 Transactions
`Transaction` = plain data:
- `changes: ChangeSet`: retain/insert/delete spans. It supports `compose`, `invert` and `mapPos` (with
  association), and mapping one change set through another.
- `selection`: optional new selection, otherwise the old one mapped through the changes.
- `effects`: typed plugin messages.
- `annotations`, including `origin`: `input`, `input.ime`, `paste`, `undo`, `redo`, `disk`, `lsp`,
  `command`. `agent` is reserved for later.

`state.apply(tr)` returns a new state. A disk change, an LSP edit and (later) an agent edit are ordinary
transactions.

### 4.4 Extensions
A plugin is an `Extension` (a value, possibly nested lists of them) contributing any of:

1. **Facets.** Configuration inputs from many sources, combined by a facet-specific rule (tab size,
   line wrap, keymaps, language, gutters).
2. **State fields.** Plugin memory: `create(state)` and `update(value, tr)`.
3. **Commands.** `(EditorContext) -> Boolean`, named and discoverable.
4. **Keybindings.** Key chord → command, per platform (`Mod` = Cmd on Apple, Ctrl elsewhere).
5. **Decorations**, as data:
   - mark (a styled span)
   - inline widget
   - line decoration
   - gutter marker
   - **block widget**, which has real height between lines
6. **Panels.** Top or bottom strips (search bar).
7. **View plugins.** React to viewport and geometry changes (visible range, scroll).

Each extension has a precedence (`lowest…highest`) for resolving conflicts.

**Sandbox rule:** the plugin API only exchanges immutable data (state, transactions, decorations,
command results). No plugin gets a reference to the renderer, the Compose tree or platform APIs. The
one exception is block-widget *content*, which compiled-in plugins provide as a composable. A future
sandboxed plugin would provide a declarative widget description instead.

### 4.5 Built on the core, not in it
Undo history, search, folding, LSP, diff and highlighting are all plugins (§7).

## 5. editor-syntax

### 5.1 Interface (ours; plugins never see backend types)
```kotlin
interface SyntaxEngine {
  fun language(id: String): SyntaxLanguage?        // lazy, may load a grammar (web: fetch .wasm)
}
interface SyntaxSession {                           // one per open document + language
  fun edit(changes: ChangeSet, old: Rope, new: Rope) // tree-sitter InputEdit in UTF-8 bytes + points
  suspend fun reparse(snapshot: Rope): SyntaxTree   // off the UI thread, time-limited
}
interface SyntaxTree {
  fun highlights(lines: IntRange): List<HighlightSpan>   // captures → theme token ids
  fun nodeAt(pos: Int): SyntaxNode?
  fun folds(lines: IntRange): List<FoldRange>
  fun indentAt(line: Int): IndentHint?
}
```
(The signatures are indicative. The plan fixes the exact API.)

### 5.2 Behaviour
- Every transaction's changes are forwarded as tree-sitter edits. The reparse runs on a snapshot,
  off the UI thread. Until it lands, the previous highlight spans are **mapped through the changes**,
  so nothing flickers and nothing blocks typing.
- Highlights are queried only for the visible lines plus overscan.
- **Injections** (`injections.scm`) are needed for parity: Vue/HTML `<script>`/`<style>` and Markdown
  fenced code.
- Queries used: `highlights.scm`, `injections.scm`, `folds.scm` and `indents.scm` where they exist.
  Capture names map to supermux theme tokens. **The same query files are used on every backend.**
- **Limits:** a parse timeout plus a file-size limit. Beyond either, the document opens as plain text
  with a visible "syntax off" note.

### 5.3 Backends and packaging
| Client | Backend | Grammars |
|---|---|---|
| Android, desktop | ktreesitter (JNI) | bundled (built via `ktreesitter-plugin`) |
| iOS | ktreesitter (cinterop) | bundled |
| Web | web-tree-sitter (official WASM) | one `.wasm` per language, fetched on first use |

### 5.4 Languages
Today's bundle (`apps/android/codemirror/cm6-entry.mjs`) highlights about 50 languages: 18
first-class Lezer languages plus legacy stream modes. Target: all with a solid tree-sitter grammar.
The plan produces the exact grammar table (source, license, query source) and the plain-text list.

## 6. editor-compose

### 6.1 Layout
- A **height map** holds every line's height: wrapped lines, block widgets, folded regions, with
  estimates for lines not yet measured. Scroll offset ↔ line lookups are O(log n).
- Only visible lines plus overscan are measured with Compose's `TextMeasurer`. Layouts are cached per
  line, keyed by text, decorations, width and font.

### 6.2 Drawing
- One Canvas draws:
  - background line decorations (current line, diff tints)
  - selection ranges (all of them)
  - text runs styled from mark decorations
  - cursors (blinking)
- The gutter is a separate column: line numbers, fold arrows, diff markers, lint markers, 💬.
- Block widgets are real composables placed between lines by the layout.
- Compose may draw over the editor. The KCEF "swap the pane, don't overlay" limitation goes away.

### 6.3 Input
- **Hardware keys** → keymap facet → commands.
- **Soft keyboard / IME:** a hidden text field mirrors a **window of real document text around the
  cursor**, so autocorrect, Turkish input, suggestions, dictation and CJK composition work.
  - Before and after each IME edit, the field is diffed into a transaction with origin `input.ime`.
  - Composing text is shown as an underline decoration until it is committed.
  - The iOS Enter/Backspace handling reuses the terminal's findings
    (`terminal-compose/TerminalTextInput.kt`: a soft keyboard edits a field and does not press keys).
- **Touch:**
  - Tap places the cursor and raises the keyboard.
  - Long-press selects a word, with drag handles and a copy/paste menu.
  - Fling scrolls, and pinch zooms the font (10–24px).
- **Mouse:**
  - Click places the cursor without raising a soft keyboard.
  - Drag selects, double-click selects a word, triple-click selects a line, the wheel scrolls.
  - Alt-drag makes a column selection.
- Focus rules match the terminal's: touch takes focus and raises the keyboard, a mouse click only
  takes focus, and tab activation never pops the keyboard.

### 6.4 Linked views
Two editor views can share a scroll/line-alignment controller. This is used by the side-by-side diff.

### 6.5 Accessibility
Visible lines are exposed as semantics, and cursor/line changes are announced (VoiceOver, TalkBack).

### 6.6 Performance targets
- Keystroke → painted frame ≤ 16ms.
- Smooth 120Hz scrolling on the iPad Air.
- A 10MB file opens in < 1s (syntax off above the size limit).

## 7. Plugins at cutover

1. **basics:**
   - line numbers, active-line highlight
   - Tab/Shift-Tab indent
   - close brackets, bracket matching, indent on input
   - selection-match highlight
   - column selection
   - `Mod-S` → host save
2. **history:** undo/redo with inverted change sets, grouped by typing bursts.
3. **highlight:** the language set from §5.4, including injections.
4. **fold:** gutter arrows, fold/unfold/fold-all, placeholder widget.
5. **search:** find/replace panel with regex, match case and whole word; all matches marked.
6. **lsp:** everything today's `@codemirror/lsp-client` setup provides: completions, hover, diagnostics
   (gutter + underline), go to definition.
   - Transport is the **existing broker LSP channel**: the host's connect / message / disconnect and
     `onLspOut`.
   - No broker changes.
7. **diff:** side-by-side (two linked views, base read-only, working copy editable) and one-column
   (walkthrough) modes.
   - Line diff, then character diff inside changed lines. Block-widget gaps keep rows aligned.
   - Revert-hunk command, and folded unchanged runs with "expand ↑/↓ 20".
   - Review threads, reply/resolve, and the comment composer with a persisted draft. The base selector
     stays in the host.
8. **view settings:** font zoom (pinch, `Mod +/−/0`, 10–24px, persisted per app) and line wrap.

The markdown preview is not part of the editor and is unchanged.

## 8. Host integration (`:ui`)

- **Deleted:**
  - `EditorEngine`, `EditorEngineFactory` and its four implementations
  - `EditorBridge`, the push planner and every `cm*` JS call
  - `EditorScrollReader`
- **The panes** use one composable, roughly `Editor(state, extensions, onTransaction)`.
- **The 11 `EditorCallbacks`** become host callbacks or plugin effects.
- **Editor settings** (`EditorSettingsScreen`, `editor-config.ts`) keep their values.
- **Per-session editor state** stays owned by session id, as it is today.
- **Line endings:** the rope knows only `\n`. The host normalizes `\r\n` to `\n` on load and
  remembers the file's line ending to restore it on save.

## 9. Removed at cutover

- `apps/android/codemirror/`, `apps/android/src/main/assets/editor/`
- the Android WebView data-dir setup in `SupermuxApplication`
- `apps/iosApp/Supermux/EditorWeb/`, `WKWebViewEditorEngine`, `IosEditorEngineFactory`
- `KeepAlivePanel` stays: it hides every pane, not only the editor. Its 0×0 strategy is re-checked
  once the editor no longer owns a UIKit or AWT view.
- `apps/web/editor/editor-shim.js`, `apps/web/karma.config.d/editor-shim.js`, `WebEditorEngine*`
- desktop `editor/` (`JcefRuntime`, `EditorWebAssets`, `DesktopEditorEngine*`), and the KCEF
  dependency and JCEF workarounds in `Main.kt`/`DesktopPlatform.kt`. JCEF is used only by the editor,
  so desktop drops Chromium entirely.
- `tests/cm6-bundle-drift.test.ts` and the engine/bridge tests.

## 10. Milestones

- **M0 Risk checks (first; stop and rethink if one fails):**
  1. ktreesitter builds with our Kotlin version on the Mac for JVM, Android and iOS.
  2. web-tree-sitter runs inside the wasmJs app.
  3. An iOS prototype of the real-text hidden field (autocorrect, Turkish, dictation).
  4. Measured app-size cost of the grammars per platform.
- **M1** editor-core.
- **M2** editor-syntax, both backends.
- **M3** editor-compose: desktop first, then web, Android, iOS.
- **M4** the eight plugins.
- **M5** `:ui` integration, then the device passes.
- **Cutover:** the deletions in §9, in the same merge.

## 11. Testing

- **Core:**
  - Randomized property tests: rope vs `String` under thousands of edits.
  - `ChangeSet` laws: compose, invert (undo restores exactly), position mapping.
- **Syntax:** golden highlight tests per language. The same file must produce identical spans on JVM,
  iOS and wasmJs. These catch UTF-8/UTF-16 offset bugs and backend drift.
- **Surface:**
  - Height-map and layout unit tests.
  - Input replays, including recorded iOS soft-keyboard sequences.
- **Performance:** automated checks for §6.6.
- **Devices:** web, Mac desktop, Galaxy Fold, iPad Air M2, iPhone 15 Pro, against a throwaway broker.
  This is the 2026-09-24 terminal recipe.
- **Parity checklist** built from `cm6-entry.mjs` and `EditorCallbacks`. Every item is ticked on a
  device before §9 runs.
- All Gradle builds and suites run on the Mac.

## 12. Later (designed for, not built)

- Runtime-loaded sandboxed plugins (option B).
- Multi-cursor editing UI beyond column selection.
- Agent edits streaming live into the open buffer as tracked changes you accept or reject
  (origin `agent`).
