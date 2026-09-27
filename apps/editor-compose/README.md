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

**Public API for hosts.** `EditorView.typeText(text, userEvent)` is the one entry point for typed
text (the hidden field, the web's key path and `DefaultCommands.insertText` all use it); plugins
hook it through `inputHandlerFacet` (CM6's inputHandler: given the main range and the text, dispatch
something else and return true), asked for plain typing only, never while composing, never for a
paste. `paste(text)` distributes one line per cursor when the counts match. `scrollPosition` /
`restoreScroll` save and restore by document position (applied at the first paint when given
earlier). `focus(showKeyboard = false)`: a host's focus never raises a soft keyboard.
`coordsAtPos(offset)` gives the caret rect in the surface, for popups. `EditorScrollState` can be
passed to several `Editor`s to scroll them together (clamped to the largest; anchoring is off while
shared): the seam M3c's linked views align lines on.

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

**Huge lines.** A line longer than 10,000 units (minified code, a 1 MB JSON line) is never laid out,
sliced or hashed whole: it is cut into pieces at grapheme boundaries, laid out only where they are
looked at. Without wrapping, 2,048-unit pieces sit side by side, each starting where the MEASURED
widths of the pieces before it end (an unmeasured one is estimated at a piece of cells), so CJK and
other wide text neither overlaps nor misses its clicks; with wrapping, rows
of as many units as cells fit the width (the line's height is known without shaping; a wide
character may overhang). A keystroke on a 1 MB line takes ~6 ms to its frame.

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
- Every field edit at the main range (typing, Backspace, autocorrect, each composition step) is
  made relative to EVERY range, as CM6 does (another range takes the edit's extension only when the
  text around it matches what was replaced around the main range; else an insertion goes over its
  own selection, a deletion deletes one grapheme there, a replacement leaves that range untouched): the diff is clamped to cover the field's previous
  selection and the caret, so typing over a selection wider than the window replaces all of it, and
  repeated characters cannot misplace a Backspace. The window never holds another range, so the other
  cursors' typing never forces a rewrite mid-composition. An edit away from the caret (an
  autocorrect of an earlier word) is applied once, every range kept.
- A huge edit (a paste of 200k units into the field) re-windows at once.
- **An external change while the IME composes** (a disk reload, the syntax worker never: it only adds
  decorations) that touches the window rewrites the field: the composition ends there, the platform
  IME commits or abandons its preedit, and the text already typed stays in the document.
- `readOnly` makes the field read-only and drops user edits in the view (`input*`, `delete*`,
  `paste*`, `undo`, `redo`); the selection still moves, programmatic changes still apply.

**Indentation.** Tab with only cursors inserts up to the next indent stop; Tab with a selection
(and `Mod-]`) indents every touched line by one `indentUnitFacet`; Shift-Tab (and `Mod-[`) removes one
unit (a tab, or leading spaces up to the unit's width) from every touched line, cursor or
selection. These are hardware keys only: a soft keyboard has no Tab key, so an accessory-bar
indent/outdent action is a later addition.

**Smart Punctuation (iOS).** The hidden field answers `.no` for `smartQuotesType`,
`smartDashesType` and `smartInsertDeleteType` (Compose Multiplatform 1.12 exposes none of them, so a
small cinterop shim, `src/nativeInterop/cinterop/uikitTraits.def`, adds the getters to Compose's
input view classes): a typed `"` stays U+0022. Autocorrect and suggestions are unchanged. Android has
no platform setting for this; Gboard and Samsung Keyboard type straight quotes by default.

**Hardware keys** are previewed by the surface (an ancestor of the field, so it sees them first):
the state's keymap facet (`runKey`), then `defaultKeymap`'s bindings as the lowest-precedence
fallback (a state needs no keymap of its own; a plugin overrides a default by binding the same
key). Unbound keys reach the field, which is where typed characters come from. While the IME
composes, every key is the IME's. The field's own undo/redo and clipboard chords are swallowed (its
history and clipboard know only its window): **copy, cut and paste are the editor's commands**
(Mod-c/x/v), through an `EditorClipboard` (`Editor(clipboard = …)`, the platform's by default):
copy puts every range on the clipboard, one line per range; with nothing selected it copies
nothing (on the web the browser's default copy runs, leaving the clipboard alone). A pasted CRLF or
lone CR, or one arriving through the hidden field, becomes `\n`. Off Apple, Ctrl+Alt+<character> (AltGr
on Windows) matches only a binding that names `Ctrl-Alt`, never `Mod-Alt`.

**On the web**, Compose handles queued DOM input only at the next animation frame, after that frame
drew: a key it handles is painted two frames late. So while an editor is composed, a capture-phase
DOM keydown listener serves hardware keys inside the event itself and cancels them: bound chords run
their command (Backspace, Enter, arrows, Tab, plugins' bindings), plain characters are typed; Mod-c/x/v
are hidden from Compose and served by the browser's copy/cut/paste events (synchronous clipboardData,
the whole selection, no permission prompt). Only for a HARDWARE keyboard aimed at Compose's own
textarea: never after a touch or pen pointer, and on a touch-capable device only once a physical-key
keydown (a non-empty `code`) has been seen, because iOS Safari's soft keyboard sends real key values
and must keep going through the field for autocorrect and predictions. That is a heuristic. IME
composition, unbound shortcuts and dead keys go the ordinary way.

**Focus rules** (the terminal's): a **touch** takes focus AND raises the soft keyboard, every time
(a field that is already focused starts no new input session, so a keyboard the user dismissed
would never come back); a **mouse click** takes focus and never raises a keyboard; nothing raises
the keyboard when an editor merely appears or a host focuses it (`showKeyboardOnFocus = false`).

**Grapheme limits.** `TextBoundaries` follows the parts of UAX #29 an editor meets (combining marks,
ZWJ sequences, flags, emoji modifiers), not all of it: Indic conjuncts (GB9c: consonant + virama +
consonant in Devanagari and others) are not joined, so the caret can stop inside a conjunct and a
Backspace takes one piece of it. The painter still writes the scroll position during draw when the
anchor moves it (measuring happens there); a pre-draw measuring phase would move that out.

**Pointer.** Mouse: click places the caret, Shift-click extends, drag selects with auto-scroll
past an edge, double click selects a word, triple click a line, Alt-drag makes one range per line.
Touch: a tap places the caret; a long press selects a word (handles are M3b); a drag scrolls and
never selects. The pointer node sits inside `Modifier.scrollable` and consumes only what is a
selection, so the scrollable normalizes wheel notches, tracks velocity and runs flings.

## Performance (spec §6.6)

Asserted on the desktop JVM by `:editor-sample:jvmTest` (a real `ImageComposeScene` at 2200x1640,
Skia raster, syntax on, best of 3 on a shared Mac), and measured in the real desktop window and in
Chrome by the sample's in-app benchmark (`-Psample.bench=true`, `:editor-sample:webBench`):

| | target | JVM (harness / scene) | desktop window | Chrome |
|---|---|---|---|---|
| keystroke -> painted frame (10k lines, syntax on, p95) | <= 16 ms | 11.3 ms through the real field; syntax settle frame 7.5 ms | key event -> paint 17.8 ms (vsync wait + 2.7 ms work) | key event -> paint: x 18.2, Backspace 18.2, Enter 18.0, arrows 18.2-19.7 ms (vsync wait + ~2.5 ms work) |
| keystroke on a 1 MB single line (p95) | <= 16 ms | 6.0 ms | | |
| scroll frame (10k lines, p95) | <= 16 ms | 9.5-12 ms | 3.3 ms work, no dropped frame | 2.5 ms work, no dropped frame |
| 10 MB file -> first frame | < 1 s | 41-54 ms | | |

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

M3b: selection handles, the copy/paste menu (the commands exist: `DefaultCommands.copy/cut/paste`),
pinch and Mod +/-/0 zoom, accessibility semantics, the device IME
pass. M3c: gutter markers, block widgets (`HeightMap.setBlockHeight` exists), inline widgets,
`Replace` (folds), panels, linked views, device performance (120 Hz iPad).
