# editor-compose

The native editor's one surface: a Compose Multiplatform composable that draws and edits an
`editor-core` `EditorState` on a single Canvas, for Android, the desktop JVM, iOS and the browser.
Spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md` §6. Plan: `…/plans/2026-09-26-native-editor-m3a-surface.md`.

It depends on `:editor-core` and Compose only: no `:shared`, no `:ui`, and not `:editor-syntax`.
Syntax colours (and any plugin's styling) arrive as editor-core decorations whose classes the
theme resolves. `:editor-sample` shows the wiring.

```kotlin
val view = rememberEditorView { EditorState.create(text, extensions = Syntax.extension("kotlin")) }
Editor(view, Modifier.fillMaxSize(), onViewport = { r -> view.dispatch(TransactionSpec(effects = listOf(Syntax.setViewport.of(r)))) })
```

## Architecture

```
EditorView            state (Compose state), dispatch(spec), listeners, viewport, focused, readOnly
  └ EditorController  one composed surface (EditorSurfaceHooks: follows every transaction)
      ├ HeightMap     every line's height (measured, else estimated); top/lineAt O(log n)
      ├ LineLayouts   TextMeasurer layouts of visible lines, LRU by (text, spans) per configuration
      ├ Geometry      offset <-> content position, caret and selection rects, visible lines
      ├ EditorScroll  x/y in pixels; ScrollableStates for Modifier.scrollable (wheel, drag, fling)
      ├ EditorPointer click / drag / multi-click / Alt-drag column; tap, long press
      └ FieldSync     the hidden field <-> the document (EditorInputField)
Painter               background, current line, LineStyle backgrounds, selections, text, cursors, gutter
DefaultCommands       movement, selection, insert/delete/newline/tab, select all; defaultKeymap()
```

| File | What |
|---|---|
| `EditorView.kt` | `EditorView`, `rememberEditorView`, the surface hooks interface |
| `Editor.kt` | `@Composable Editor(...)`, `EditorController` (configuration, anchoring, scroll-into-view) |
| `EditorTheme.kt`, `EditorFont.kt` | colours, token styles, the packaged JetBrains Mono |
| `HeightMap.kt` | chunked line heights with two Fenwick trees over the chunks |
| `LineLayouts.kt`, `Geometry.kt` | layout cache, tab stops, offset/position mapping |
| `Painter.kt` | one frame |
| `EditorScroll.kt`, `EditorPointer.kt` | scrolling, pointer gestures |
| `EditorInput.kt` | the windowed hidden field (`FieldWindow`, `diffField`, `FieldSync`), hardware keys, web fast typing |
| `DefaultCommands.kt`, `TextBoundaries.kt`, `EditorFacets.kt` | commands, grapheme/word rules, `tabSizeFacet` / `indentUnitFacet` |

**Coordinates.** Offsets are UTF-16 (as everywhere in the editor). Geometry works in *content*
coordinates (x from the text area's left edge, y from the document top); the surface adds the
gutter and the scroll.

**What is laid out.** Only the visible lines plus `EditorDefaults.OVERSCAN_LINES` (4) each side.
A line's measured height goes into the height map; the painter looks at the visible range again
after measuring, so a wrapped line that turned out taller moves the lines below it in the same
frame. The viewport reported through `onViewport` / `view.viewport` is those lines' text.

**Scroll anchoring.** The first visible line is the anchor: when heights change above it (a wrapped
line measured for the first time, an edit above the viewport such as a disk reload), the scroll
follows it so the text on screen stays put. A gesture or scroll-into-view since the last paint wins.

**Keep the cursor visible.** A transaction with `scrollIntoView` (every default command, every
typed character) scrolls the least needed to show the main cursor with a margin of a line
(four cells horizontally, without wrapping). Page moves scroll a page as well.

## The theme contract

`EditorTheme` carries every colour the surface paints, the font and the line height. Plugins never
see it; they put semantic class names on decorations:

- `Decoration.Mark(classes)`: classes in `tokens` (the `tok-*` vocabulary of editor-syntax's
  `TokenClasses.ALL`, all 29 coloured in `light` and `dark`) or in `classStyles` (`search-match`,
  `diff-add`, ...) style the text. A class the theme does not know draws nothing.
- `Decoration.LineStyle(classes)`: `lineClassBackgrounds` paints the line's background.
- When several marks cover the same text, styles merge in `decorationsFacet` order (highest
  precedence first) and the later one wins per attribute. `ime-composition` is the surface's own
  class (an underline).

The default face is JetBrains Mono 2.304 shipped as a Compose resource (the browser has no system
monospace; OFL notice in `THIRD-PARTY-NOTICES.md`). Ligatures are off, so a caret fits between the
`-` and `>` of `->`. Tabs are real characters drawn as placeholders as wide as the way to the next
tab stop (`tabSizeFacet`), so document offsets are layout offsets.

## The input model

**A soft keyboard does not press keys: it edits the focused text field** (terminal-compose's
lesson). The surface's focus target is an invisible `BasicTextField` holding a **window of real
document text** around the main cursor (about 200 units each side, extended to whole lines where
it can be, never splitting a surrogate pair). That is what makes autocorrect, Turkish, suggestions,
dictation and CJK composition work; the design passed all 8 checks on an iPhone in M0.

- Each user edit of the field is diffed against the window (`diffField`) and dispatched as a
  transaction, `input` or `input.ime` while composing. It is applied **inside the input event**
  (the field's `InputTransformation`), so the edit is painted in the next frame; the committed
  field state then follows, where the composition is known: the composition underline, the IME's
  own caret moves, and re-windowing happen there.
- A soft Return (iOS inserts `"\n"`) becomes `insertNewline` (indentation kept). The field is
  multi-line for this: a single-line field turns Return into an IME action and drops it.
- With several cursors, typing or a soft Backspace at the main cursor happens at every cursor. Typing
  over a selection wider than the window replaces all of it.
- After every transaction (a view listener, synchronously) the field is re-synced: rewritten when it
  no longer shows the document's text at its window or the caret left it, and re-windowed when the
  caret comes within 32 units of an edge with more document beyond it, never while composing. So a
  held Backspace walks past the window's start instead of being eaten (M0 checklist step 3).
- `readOnly` makes the field read-only and drops user edits in the view (`input*`, `delete*`,
  `paste*`, `undo`, `redo`); the selection still moves, programmatic changes still apply.

**Hardware keys** are previewed by the surface (an ancestor of the field, so it sees them first):
the state's keymap facet (`runKey`), then `defaultKeymap`'s bindings as the lowest-precedence
fallback (a state needs no keymap of its own; a plugin overrides a default by binding the same
key). Unbound keys reach the field, which is where typed characters come from. While the IME
composes, every key is the IME's. The field's own undo/redo chords are swallowed (its history knows
only its window). **On the web**, a plain printable key is typed by the surface inside the DOM key
event itself and the event cancelled, because Compose for the web handles queued input only at the
next animation frame, after that frame drew (two frames of latency otherwise). IME composition,
Ctrl/Meta chords, Alt outside Apple platforms, dead keys and bound chords still go the ordinary way.

**Focus rules** (the terminal's): a **touch** takes focus AND raises the soft keyboard, every time
(a field that is already focused starts no new input session, so a keyboard the user dismissed
would never come back); a **mouse click** takes focus and never raises a keyboard; nothing raises
the keyboard when an editor merely appears.

**Pointer.** Mouse: click places the caret, Shift-click extends, drag selects with auto-scroll
past an edge, double click selects a word, triple click a line, Alt-drag makes one range per line.
Touch: a tap places the caret; a long press selects a word (handles are M3b); a drag scrolls and
never selects. The pointer node sits inside `Modifier.scrollable` and consumes only what is a
selection, so the scrollable normalizes wheel notches, tracks velocity and runs flings.

## Performance (spec §6.6)

Asserted on the desktop JVM by `:editor-sample:jvmTest` (a real `ImageComposeScene` at 2200x1640,
Skia raster, syntax on, best of 3 on a shared Mac), and measured in the real desktop window and in
Chrome by the sample's in-app benchmark (`-Psample.bench=true`, `:editor-sample:webBench`):

| | target | JVM scene | desktop window | Chrome |
|---|---|---|---|---|
| keystroke -> painted frame (10k lines, p95) | <= 16 ms | 11-14 ms | key event -> paint 17.8 ms (vsync wait + 2.7 ms work) | key event -> paint 19.6 ms (vsync wait + 2.3 ms work) |
| scroll frame (10k lines, p95) | <= 16 ms | 9.5-12 ms | 3.3 ms work, no dropped frame | 2.5 ms work, no dropped frame |
| 10 MB file -> first frame | < 1 s | 41-51 ms | | |

On a 60 Hz display a key event waits up to one frame for the next vsync; the edit is always painted
in the next frame. The web cold start is asserted by `:editor-sample:webColdStartTest`.

## Tests

`./gradlew :editor-compose:jvmTest` (on the Mac: see `scripts/editor/mac-sync.sh`): the pure logic
in `commonTest` (height map, commands, grapheme and word rules, the field sync, the web fast-typing
decision; also run by `iosSimulatorArm64Test`) and the real composable in the desktop UI harness
(`jvmTest`: geometry against a real TextMeasurer, painting and pixels, scrolling, pointer gestures,
typing through the real field). Compose's harness cannot open an IME composing region, so
composition is tested at `FieldSync`; real-device IME checks are M3b's.

## Not here yet

M3b: selection handles, the copy/paste menu, the clipboard (until then Mod-c/x/v are the hidden
field's own and act on its window only: a paste arrives as typed input, a copy of a selection wider
than the window is cut short), pinch and Mod +/-/0 zoom, accessibility semantics, the device IME
pass. M3c: gutter markers, block widgets (`HeightMap.setBlockHeight` exists), inline widgets,
`Replace` (folds), panels, linked views, device performance (120 Hz iPad).
