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
- An immutable balanced tree of text chunks. Each branch stores its **UTF-16 length and line count**,
  so offset↔line lookups are O(log n). *(M0 changed this: there are no UTF-8 byte counts. Byte offsets
  are `editor-syntax`'s concern, see §5.2 and `docs/superpowers/notes/2026-09-native-editor-m0-results.md` §2.)*
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
  fun edit(changes: ChangeSet, old: Rope, new: Rope) // converted to the backend's byte encoding + points
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

### 5.2a Encodings (from M0)
- **Web (decided 2026-09-26):** the same `ses_*` binding, tree-sitter and grammar code compiled to one wasm32
  module, not web-tree-sitter. The result is one engine, the same patched grammars, and the same predicates on
  all four clients, with regexes going through Kotlin's `Regex`. That gives identical colours by construction.
- **ktreesitter 0.25.1 cannot parse UTF-16.** It always converts to UTF-8 itself: *modified* UTF-8 on the JVM,
  standard UTF-8 on Android, and on iOS it reports the UTF-16 length as the byte count, which is a bug that
  truncates non-ASCII text.
- The native backend therefore parses UTF-8 through the read callback and maps byte offsets back to UTF-16
  with a per-platform width table, only for the edited region.
- **Decided 2026-09-25: our own thin binding** over the tree-sitter C API, not ktreesitter. It follows
  terminal-core's pattern: a C wrapper, JNI for Android and JVM, cinterop for iOS.
  - The C core accepts `TSInputEncodingUTF16LE` directly, so **native is UTF-16 end to end, like web**, with
    no byte tables.
  - Web keeps web-tree-sitter.
  - The golden tests are the contract.

### 5.3 Backends and packaging
| Client | Backend | Grammars |
|---|---|---|
| Android, desktop | our binding (JNI) | every grammar's code built in; tables as compressed data |
| iOS | our binding (cinterop, static) | every grammar's code built in; tables as compressed data |
| Web | **our binding compiled to wasm32** (decided 2026-09-26; replaces web-tree-sitter) | every grammar's code in the module; tables fetched on first use |

- No grammar artifacts are published for ktreesitter. We compile the npm packages' `parser.c`/`scanner.c`
  ourselves on the Mac (M0 recipe: `docs/superpowers/notes/m0-artifacts/build-grammars.sh`).
- **Size rules out bundling everything.** M0 measured about 74 MB of iOS object code and 59 MB of wasm for all
  languages (fsharp alone is 11.5 MB).
- **Grammars are code plus data (decided 2026-09-25, option D).** A generated grammar is mostly its parse
  tables. For fsharp, linked and stripped for iOS: 11.4 MB in total, of which 164 KB is code and 11.2 MB is
  const tables, which gzip to 876 KB.
  - The native build **compiles every grammar's code** (lexer + external scanner) into the binding library.
  - It moves each grammar's **tables into a compressed data blob**. The blob is decompressed into memory the
    first time a language is used, and the `TSLanguage`'s table pointers are filled in at load.
  - Tables are data, not executable code, so blobs may later be downloaded on demand even on iOS and Android,
    where both stores forbid downloading executable code. Native grammar libraries must never be downloaded.
- **Core set** (Ahmet, 2026-09-25), with tables bundled in the app: TS/JS/TSX, Python, Kotlin, Swift, Go,
  Rust, Java, C/C++, JSON, YAML, TOML, Markdown, HTML, CSS, shell, SQL.
  - Every other language's code is bundled too. Its tables are bundled compressed at first; moving them to
    on-demand download is an optimisation for later.
  - Kotlin's highlight queries come from nvim-treesitter (Apache-2.0), because the npm package ships none.
- **Fallback if table extraction proves unworkable** (the first M2 risk check): the broker runs web-tree-sitter
  and streams highlight spans for non-core languages to mobile clients.

### 5.4 Languages
Today's bundle (`apps/android/codemirror/cm6-entry.mjs`) highlights about 50 languages: 18
first-class Lezer languages plus legacy stream modes. Target: all with a solid tree-sitter grammar.
M0 inventory (`docs/superpowers/notes/m0-artifacts/grammar-inventory.tsv`):
- 38 of 47 languages have npm grammars. Alternates exist for r, wast (wat) and vb.
- **Missing, so plain text until sourced from git:** erlang, crystal, coffeescript, fortran, cmake,
  dockerfile. Source cmake and dockerfile.
- **No `highlights.scm` shipped:** vue, **kotlin**, groovy, clojure, vb. Queries must come from
  nvim-treesitter (Apache-2.0) or Helix (MPL-2.0), and their licences recorded.
- **No prebuilt wasm:** about 12 languages. We build those with the tree-sitter CLI plus emscripten on the Mac.
- **Licences:** all MIT except dart (ISC).

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
  remembers the file's line ending to restore it on save. A file with MIXED endings is saved with its
  majority ending on every line (M5's `LineEndings`; a lone `\r` is left as text).

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

- **M0 Risk checks: DONE 2026-09-25.** Results in `docs/superpowers/notes/2026-09-native-editor-m0-results.md`:
  every check passed except UTF-16 on native (§5.2a) and grammar size (§5.3). Android ran on an emulator; the
  Fold rerun moves to M5. The original checks:
  1. ktreesitter builds with our Kotlin version on the Mac for JVM, Android and iOS.
  2. web-tree-sitter runs inside the wasmJs app.
  3. An iOS prototype of the real-text hidden field (autocorrect, Turkish, dictation).
  4. Measured app-size cost of the grammars per platform.
- **M1** editor-core.
- **M2** editor-syntax, both backends.
- **M3** editor-compose: desktop first, then web, Android, iOS.
- **M4** the eight plugins.
- **M5** `:ui` integration, then the device passes, including the ktreesitter Android run on the real Galaxy Fold.
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
