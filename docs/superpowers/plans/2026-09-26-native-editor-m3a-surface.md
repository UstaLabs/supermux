# Native editor M3a: the Compose editor surface (see and type). Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:editor-compose`, one shared Compose surface that draws an `editor-core` `EditorState` with the syntax
colours from `editor-syntax`. You can scroll it, click or tap to place the cursor, drag to select, and type with a
hardware keyboard or a phone's soft keyboard, including IME composition. There is also `:editor-sample`, a desktop
app plus a web page that opens a real Kotlin file, so Ahmet can try it.

**Scope split.**
- **M3a (this plan)** is the surface and typing.
- **M3b** is touch selection handles, the copy/paste menu, clipboard, pinch zoom and accessibility.
- **M3c** is gutter markers, block widgets, linked views (for side-by-side diff), panels, and the device performance passes.

**Architecture** (spec §6):
```
EditorView (state holder, CommandTarget)  ── dispatch(spec) ─► EditorState.update ─► listeners (SyntaxWorker.onState …)
   │ state: EditorState (Compose state)      viewport ─► onViewport(range) (host sends Syntax.setViewport)
   ▼
@Composable Editor(view, theme, modifier)
   ├─ HeightMap            line tops/heights (estimates for unmeasured), O(log n) offset↔line↔y
   ├─ LineLayouts          TextMeasurer per visible line, cached by (text, marks, width, font)
   ├─ Painter (Canvas)     line backgrounds → selections → text → cursors;  gutter column (line numbers)
   ├─ ScrollState          vertical (+ horizontal when not wrapping); wheel, drag/fling
   ├─ Pointer              click/tap → cursor; drag → selection; double/triple click → word/line
   └─ Input                hidden text field holding a WINDOW of real text around the cursor (the M0 probe),
                           diffed into transactions (userEvent "input"/"input.ime"); hardware keys → keymap → commands
```
- `editor-compose` depends on `:editor-core` (api) and Compose only. It does **not** depend on `:editor-syntax`;
  the sample wires syntax in through its public API. That's the same rule as terminal-compose: no `:shared`, no `:ui`.
- Decorations come from `decorationsFacet`. Marks whose classes are `tok-*` are coloured by `EditorTheme`, and any
  other class goes through `EditorTheme.classStyles`.

**Tech stack:** Kotlin 2.4.10 Multiplatform (jvm, android, iosArm64, iosSimulatorArm64, wasmJs), Compose Multiplatform
1.12.0, and the Compose desktop UI test harness (`compose.desktop.uiTestJUnit4`) for jvmTest, as `:terminal-compose`
uses it.

**Not pre-verified.** TDD, with the interfaces below as the contract. Study `apps/terminal-compose` before starting and
reuse its hard-won lessons:
- `TerminalTextInput.kt`: soft keyboards EDIT a field. iOS never delivers Enter/Backspace as key events. The M0 probe
  in `docs/superpowers/notes/m0-artifacts/ImeProbe.kt` is the proven windowed version.
- `TerminalInputPolicy.kt`: touch vs mouse routing and the focus rules. Touch takes focus AND raises the keyboard, a
  mouse click only takes focus, and tab activation never pops the keyboard.
- `TextRunCache.kt`, `TerminalPainter.kt`: layout caching.
- `ScrollController.kt`: fling and wheel.
- `TerminalFont.kt`: shipping JetBrains Mono as a Compose resource.

## Ground rules
- **No builds on the Linux host.** Use `scripts/editor/mac-sync.sh` and `ssh mac "$MACENV; …"`. The Mac has a
  display: `:editor-sample:run` opens a real window there. Say when it's open so Ahmet can look.
- Commits end with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `docs/` needs `git add -f`.
- Performance is part of the contract, not an afterthought: the spec §6.6 targets are checked in Task 9.

## File map (`apps/editor-compose/src/commonMain/kotlin/dev/supermux/editor/compose/`)
| File | What |
|---|---|
| `EditorView.kt` | `EditorView` (state, dispatch, listeners, viewport, focus), `rememberEditorView` |
| `EditorTheme.kt` | colours for the token classes / selection / cursor / gutter / background, the font, line height; light and dark |
| `HeightMap.kt` | per-line heights, estimates, mapping through `ChangeSet`s, y↔line queries |
| `LineLayouts.kt` | visible-line layout cache: `AnnotatedString` from marks → `TextLayoutResult` |
| `Geometry.kt` | offset ↔ (line, x, y) ↔ pointer position, using HeightMap + LineLayouts |
| `Painter.kt` | drawing |
| `EditorScroll.kt` | the scroll state |
| `EditorPointer.kt` | click/drag/multi-click → selection transactions |
| `EditorInput.kt` | the windowed hidden field (from ImeProbe) + key handling |
| `DefaultCommands.kt` | cursor movement, selection extension, insert/delete/newline/tab, select all; `defaultKeymap` |
| `Editor.kt` | the `@Composable Editor(...)` |

`apps/editor-sample/`: a desktop `main`, plus a wasm page (`index.html`). It opens a bundled ~2k-line Kotlin file and
a Markdown file, wires up `SyntaxWorker` (dispatching on the UI thread), and shows the frame time.

---

### Task 1: Module skeleton, theme and font
- **Build:** `apps/editor-compose/build.gradle.kts`, mirroring `:terminal-compose` (the 5 targets; compose
  runtime/foundation/ui; resources for the font; jvmTest with the desktop UI test harness). Add it to
  `settings.gradle.kts` with a one-line comment.
- **`EditorTheme`:**
  ```kotlin
  @Immutable data class EditorTheme(
      val background: Color, val foreground: Color, val selection: Color, val cursor: Color,
      val currentLine: Color, val gutterForeground: Color, val gutterBackground: Color,
      val tokens: Map<String, SpanStyle>,          // "tok-keyword" -> SpanStyle(color = …, fontWeight = …)
      val classStyles: Map<String, SpanStyle> = emptyMap(),   // non-token mark classes (search-match, diff-add…)
      val lineClassBackgrounds: Map<String, Color> = emptyMap(), // LineStyle classes -> background
      val fontFamily: FontFamily, val fontSizeSp: Float = 13f, val lineHeightFactor: Float = 1.45f,
  ) { companion object { fun light(font: FontFamily): EditorTheme; fun dark(font: FontFamily): EditorTheme } }
  ```
  The token colours must cover **every class in `editor-syntax`'s `Captures.kt` vocabulary**. Add a test that reads
  that vocabulary; the test may depend on `:editor-syntax`, but main code must not.
  - Tune the palette to supermux's look: read the app's colours in `apps/ui/.../theme` for the teal accent and the
    neutrals.
- **Font:** a packaged JetBrains Mono, the same approach as `TerminalFont.kt`, with its own copy in this module and the
  OFL notice in `THIRD-PARTY-NOTICES.md`.
- [ ] Tests: the vocabulary is fully coloured; the light and dark themes are different; the font loads (jvmTest).
  Commit: `feat(editor-compose): module, theme and packaged font`.

### Task 2: HeightMap
```kotlin
class HeightMap(lineCount: Int, estimatedLineHeight: Float) {
    val totalHeight: Float
    fun top(line: Int): Float                // 0-based line
    fun height(line: Int): Float
    fun lineAt(y: Float): Int                 // clamped
    fun setMeasured(line: Int, height: Float) // replaces the estimate
    fun applyChanges(changes: ChangeSet, before: Rope, after: Rope) // lines replaced by estimates
    fun setBlockHeight(line: Int, above: Float, below: Float)       // M3c block widgets; 0 by default
}
```
- **Implementation:** a Fenwick tree or a balanced chunked array over line heights. `top`/`lineAt` are O(log n), and
  `applyChanges` costs O(changed lines + log n).
- **Tests:**
  - A random property test against a plain `FloatArray` model through random edits and measurements.
  - A 1,000,000-line document: `lineAt` takes under 5 µs, and an edit touching 1 line under 50 µs (JVM, best of 5).
- [ ] Commit: `feat(editor-compose): height map with O(log n) queries`.

### Task 3: LineLayouts and Geometry
- **`LineLayouts`:**
  - Builds an `AnnotatedString` for a line from its text plus the `Mark` decorations intersecting it, taken from
    **every** RangeSet in `state.facet(decorationsFacet)`. Classes resolve through the theme; later RangeSets in
    precedence order win on conflicts.
  - Measures it with `TextMeasurer`. With wrapping on it uses a fixed width constraint; with wrapping off it doesn't
    wrap.
  - Caches by `(line text, the line's mark spans relative to the line, width-or-unbounded, font signature)`, as an
    LRU capped at a few thousand lines.
- **`Geometry`:**
  - `offsetAt(position: Offset): Int`
  - `rectFor(offset: Int): Rect` (the caret rect)
  - `selectionRects(range): List<Rect>` (multi-line, including wrapped lines)
  - `visibleLines(scrollY, viewportHeight, overscan): IntRange`
- **Tests** (jvmTest, real `TextMeasurer`):
  - `offsetAt(rectFor(o).center) == o` for every offset of a sample with tabs, emoji, CJK and a combining mark. The
    caret must never land inside a surrogate pair or a grapheme cluster: use Compose's `TextLayoutResult` offsets.
  - Selection rects across 3 lines are 3 rects, and there are more for a wrapped line.
  - Changing marks invalidates only the affected line's cache entry.
- [ ] Commit: `feat(editor-compose): line layout cache and geometry`.

### Task 4: EditorView and DefaultCommands
```kotlin
@Stable class EditorView(initial: EditorState) : CommandTarget {
    override val state: EditorState          // backed by mutableStateOf
    override fun dispatch(spec: TransactionSpec)
    fun addListener(l: (Transaction) -> Unit): () -> Unit   // returns remove
    val viewport: StateFlow<IntRange>                        // UTF-16 [start, end) incl. overscan
    var focused: Boolean                                     // Compose focus mirror
    internal var geometry: Geometry?                          // set by the surface; vertical moves need it
}
@Composable fun rememberEditorView(initial: () -> EditorState): EditorView
```
- **`DefaultCommands`**, all pure `Command`s over `CommandTarget`. The vertical ones take a `Geometry` through the
  target (`EditorView` implements an internal `GeometryTarget`), so they keep their goal column:
  - `cursorLeft/Right/Up/Down`, `cursorLineStart/End` (smart Home: first non-blank, then column 0),
    `cursorDocStart/End`, `cursorPageUp/Down`, `cursorWordLeft/Right`
  - each of the above as a `select…` variant that extends the selection
  - `insertNewline`, `insertTab` (uses a `tabSizeFacet`/`indentUnitFacet` defined here)
  - `deleteBackward`/`deleteForward`/`deleteWordBackward`, `selectAll`
  - Every command applies to **every** selection range (multi-range from the start).
  - Word boundaries use Unicode-aware word logic that is consistent across platforms. Don't use platform
    BreakIterator; write a simple letter-or-digit-or-underscore class rule and test it with Turkish, CJK and emoji.
- **`defaultKeymap`** binds them: arrows, Home/End, PageUp/PageDown, `Mod-Home`/`Mod-End`, `Alt`/`Mod` word moves
  (Mac: Alt-Arrow words, Mod-Arrow line start/end), Shift variants, Backspace/Delete, `Alt-Backspace`, Enter, Tab,
  `Mod-a`.
- **Tests:** every command against editor-core states: multi-range, at document edges, and with emoji, where a delete
  removes a whole surrogate pair, and ideally a whole grapheme.
- [ ] Commit: `feat(editor-compose): EditorView and the default editing commands`.

### Task 5: Painter and the Editor composable
```kotlin
@Composable fun Editor(
    view: EditorView, modifier: Modifier = Modifier, theme: EditorTheme = EditorTheme.default(),
    lineWrap: Boolean = false, showLineNumbers: Boolean = true, readOnly: Boolean = false,
    onViewport: (IntRange) -> Unit = {},
)
```
- **Paint order:**
  1. background
  2. current-line highlight (only when the selection is empty)
  3. `LineStyle` backgrounds
  4. selection rects (all ranges)
  5. text layouts
  6. cursors, blinking at 530 ms with Compose animation, and paused while typing
  7. the gutter: line numbers, right-aligned, with a width that tracks the digit count
- **Only visible lines plus overscan** are laid out and drawn. Measured heights feed the `HeightMap`.
- **The viewport** is published through `onViewport` and `view.viewport`, debounced to at most once per frame.
- **Tests** (desktop UI harness):
  - Render a 10k-line state. Only visible lines are measured: count it through a test hook.
  - Scrolling moves the drawn lines.
  - A screenshot sanity check: pixels at the cursor rect have the cursor colour.
  - The marks from a decorations facet change the drawn text colour at those offsets.
- [ ] Commit: `feat(editor-compose): the Editor surface draws visible lines, selections and cursors`.

### Task 6: Scrolling
- **Vertical and horizontal scroll:** vertical always; horizontal only when not wrapping.
  - Mouse wheel and trackpad: smooth, with trackpad precision.
  - Touch drag and fling: reuse terminal-compose's `ScrollController` ideas.
  - Keyboard page moves.
- **Keep the cursor visible:** after a transaction with `scrollIntoView = true`, which the default commands and typing
  set, scroll the minimum amount needed to show the main cursor plus a margin.
- **Tests:** wheel scrolling changes the visible range; typing at the bottom edge scrolls; a fling decays.
- [ ] Commit: `feat(editor-compose): scrolling and keep-cursor-visible`.

### Task 7: Pointer
- **Mouse:**
  - Click places the cursor, and focuses without raising a soft keyboard.
  - Shift-click extends the selection.
  - Drag selects, with auto-scroll at the edges.
  - Double-click selects a word, triple-click a line.
  - Alt-drag makes a **column (multi-range) selection**, which today's CM6 has.
- **Touch:**
  - A tap places the cursor, takes focus AND raises the soft keyboard.
  - A long-press selects a word; the handles come in M3b.
  - A drag scrolls; it never selects.
- **Tests:** synthetic pointer events in the UI harness for each gesture. Column selection yields one range per line.
- [ ] Commit: `feat(editor-compose): pointer selection (click, drag, multi-click, column)`.

### Task 8: Text input (hardware + soft keyboard + IME)
- **Hardware keys:** `onPreviewKeyEvent` → `KeyChord` → `runKey(view, chord, apple = isApplePlatform)`. When no
  binding consumes the key, character input arrives through the hidden field.
- **The hidden field:** port `ImeProbe`'s `FieldWindow` + `diffField` design (proven on the iPhone in M0, all 8
  checks) into `EditorInput.kt`.
  - The field holds a window of real document text around the main cursor: ±N chars, clamped to line boundaries
    where possible so autocorrect sees whole words.
  - Each change is diffed into a transaction, with userEvent `input`, or `input.ime` while composing.
  - The window is rebuilt after cursor moves or document changes that come from outside the field.
  - Composition shows as an underline decoration.
  - The terminal's iOS lessons apply: Enter and Backspace arrive as field edits, never as key events. The windowed
    field has real text before the cursor, so Backspace at a window edge must re-window instead of being eaten; M0
    checklist step 3 proved this works.
  - `readOnly` disables all of it.
- **Tests:**
  - Unit tests on the diff/window logic (port M0's `WindowDiffTest`).
  - UI-harness tests: typing ASCII, Turkish, and a newline via the field; composition (`setComposingText` if the
    desktop harness supports it, otherwise a unit-level simulation); a selection replaced by typed text; multi-range
    typing inserts at every cursor.
  - A soft-keyboard Backspace at a window edge.
  - Real device checks (autocorrect, dictation, CJK) come in M3b's device pass.
- [ ] Commit: `feat(editor-compose): hardware keys and windowed soft-keyboard/IME input`.

### Task 9: The sample and the performance targets
- **`:editor-sample`**, desktop JVM plus a wasmJs page:
  - It opens a bundled real Kotlin file of ~2k lines. Take `apps/ui/.../EditorPanel.kt` or similar from this repo,
    copied as a resource, and also generate a 10k-line one.
  - It wires `Syntax.extension("kotlin")` + `SyntaxWorker(NativeBackend or WasmBackend, …)`, with its dispatch hopped
    to the UI thread.
  - Wrap and theme toggles, and a frame-time overlay.
- **Performance targets** (spec §6.6), measured and asserted on the desktop JVM. On the Mac, run
  `:editor-sample:run` for manual confirmation.
  - Keystroke → painted frame ≤ 16 ms (p95 over 200 keystrokes in the middle of the 10k-line file, with syntax on).
  - Scrolling the 10k-line file continuously has a frame p95 ≤ 16 ms on the desktop.
  - Opening a 10 MB file (syntax off above the limit) takes < 1 s to the first frame.

  Report the numbers. If a target is missed, find the cause before claiming done.
- **The web page:** the wasmJs sample must also run in Chrome on the Mac. Report the frame times.
- **Web cold start** (from the M2c review): the first highlights-query compile for a language is one
  uninterruptible call, 26–116 ms, and it happens when a file of that language is first opened. Start compiling the
  document's language (plus Markdown's usual injection languages) through `backend.sharedQuery` right after
  `WasmBackend.load()` / when the editor mounts, before the first paint. Add a cold-page test in headless Chrome that
  reports, and asserts, a ceiling for the longest main-thread hold from page load to the first coloured frame. Pick
  the ceiling from the measurement, so a regression is caught.
- **Show Ahmet.** Leave `:editor-sample:run` open on the Mac and report it in the final message, so it can be looked at
  on the Mac screen. Also build the wasm page and state where it's served, so it can be exposed with a supermux link.
- [ ] Commit: `feat(editor-sample): try the native editor (desktop + web)`.

### Task 10: Docs and memory
- [ ] A README for `apps/editor-compose` covering the architecture, the theme contract, and the input model and its
  focus rules. Append a dated entry to `~/.mux/domains/editor.md`. Commit.

## Not in M3a
- **M3b:** touch selection handles + the copy/paste/select-all menu, the clipboard (Mod-c/x/v), pinch and Mod +/−/0
  font zoom (10–24 px), accessibility (VoiceOver/TalkBack semantics), and the device pass for IME (autocorrect,
  Turkish, dictation, CJK on the iPhone and the Fold).
- **M3c:** gutter markers, block widgets (real heights in the HeightMap), inline widgets, the `Replace` decoration
  (folds), panels, linked views (a shared scroll/line-alignment controller), and the device performance pass
  (120 Hz on the iPad).
