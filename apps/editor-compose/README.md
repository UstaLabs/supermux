# editor-compose

The native editor's one surface: a Compose Multiplatform composable that draws and edits an
`editor-core` `EditorState` on a single Canvas, for Android, the desktop JVM, iOS and the browser.
Spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md` §6. Plans: `…/plans/2026-09-26-native-editor-m3a-surface.md`,
`…/plans/2026-09-26-native-editor-m3b-touch-a11y-devices.md`.

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
      ├ EditorPointer click / drag / multi-click / Alt-drag column; tap, double/triple tap, long press, handles, pinch
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
| `EditorTouch.kt`, `EditorMenu.kt` | touch handles (geometry, drawing, hit targets), the selection menu |
| `EditorSemantics.kt` | the accessibility node (`AccessibleText`, `LineAnnouncer`) |
| `EditorClipboard.kt` | the clipboard seam (platform, web Clipboard API) |
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
A line's measured height goes into the height map; the layout pass looks at the visible range again
after measuring, so a wrapped line that turned out taller moves the lines below it in the same
frame. The viewport reported through `onViewport` / `view.viewport` is those lines' text.

**Measure before draw.** The surface is a `SubcomposeLayout`. Its layout pass
(`EditorController.layoutFrame`) does everything that measures or moves: the size, the pending
scroll, the anchor, clamping, the visible lines' layouts (and the caret's and the handles' lines,
before the anchor is restored), and it builds a `SurfaceFrame` in surface pixels (text runs,
selection and cursor rects, gutter numbers, handles, the caret the hidden field is placed at). The
canvas's draw pass only paints that frame; `DrawGuard` (strict in every UI test) fails a line
measured or a scroll written inside a draw. The pass observes the state, the composition, the
handles and `EditorScrollState.version` (bumped by every scroll it did not make itself), and reads
the position unobserved: its own anchoring and clamping never cost a second layout or a second
frame (`MeasureBeforeDrawTest`: one layout pass and one paint per scroll step, the anchored
position in that paint).

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
  multi-line for this: a single-line field turns Return into an IME action and drops it. When a
  binding takes Return (or any handler takes the input) without an edit, the field is rewritten to
  the document at once, so the `\n` the IME put there never reaches the document on the next key.
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
ObjC input view classes `CMPEditMenuView`, the base of `ComposeTextInputView`, and `CMPTextInputView`,
looked up by name, no class-list scan): a typed `"` stays U+0022. The getters answer `.no` only while
an editor's field has the focus and the system default otherwise, so **every other Compose text field
of the app keeps the user's Smart Punctuation** (the sample's settings sheet has a plain field for this
check). Autocorrect and suggestions are unchanged.
⚠️ **It depends on Compose-internal iOS class names** (those two, and the runtime class, e.g.
`EditorSampleandroidx.compose.ui.window.ComposeTextInputView18`), verified with **Compose
Multiplatform 1.12.0**. Once per focus, after the input session, the surface checks the focused view's
three traits; when they are not all `.no` it logs one warning (`editor-compose: iOS Smart
Punctuation is NOT off …`, stdout + NSLog) and reports it in `EditorDiagnostics.smartPunctuation`,
which the sample shows in its status line and `apps/editor-sample/device-checks/ios-sim.sh` asserts
(`smart-punctuation-shim`, the `"` key must type U+0022, and the plain field must type a curly
quote). Re-run that check on every Compose upgrade.

**The floating cursor (iOS).** A long press on the space bar turns the keyboard into a trackpad.
Compose moves the caret through the focused field's own layout, which for the 1 dp hidden field meant
a caret wandering inside the window, unrelated to the finger (selection only: it never touched the
text). So the shim also replaces the three floating cursor calls of the focused input view's class
(`beginFloatingCursorAtPoint:` / `updateFloatingCursorAtPoint:` / `endFloatingCursor`, patched on the
first responder's class once the keyboard is up) and, while an editor has the focus, sends them to its
`FloatingCursor`: the caret moves by the finger's travel through the editor's layout from where it was
at the start (only the travel counts), scrolled into view; any other Compose text field keeps
Compose's handling. `EditorDiagnostics.floatingCursor` says `mapped`. Maestro cannot long-press the
space bar, so `ios-sim.sh` (`floating-cursor`) sends UIKit's own calls through the sample's probe
(`debugDriveFloatingCursor`, debug API) and asserts 3 lines down with the text unchanged. Android has
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

**The web's text input** is Compose's TEXTAREA, which exists only while an input session runs
(any focus, see the focus rules). Compose web 1.12 (`DomInputStrategy`) needs three corrections:
the TEXTAREA gets the focus back after a mouse press (the press focuses the canvas); plain
`beforeinput` insertText / insertReplacementText is taken by the editor at the TEXTAREA's selection
(`FieldSync.onDomInsert`) so the browser never edits it; and its `value` setter is wrapped to put the
editor's selection back (Compose sets the selection only when its numbers changed, while setting
`value` moves the DOM caret to the end, so IME text landed at the window's end). `beforeinput` reads
the DOM selection before anything else and never resyncs for `insertReplacementText` (the
autocorrect's own replacement). Composition stays Compose's.
All of this state is **per editor** (`newWebInputState()`, kept in `EditorController.platformInput`):
each editor binds to the TEXTAREA of its own input session when it takes the focus, and every
listener (input, copy/cut/paste, the refocus, which runs only for presses on the canvas) acts only on
events whose target is that editor's TEXTAREA. No globals: two editors and a plain DOM `<input>` on one
page stay apart. `:editor-sample:webInputTest` asserts all of it over CDP (the `?two=1` page: two
editors and a plain input).

**On the web**, Compose handles queued DOM input only at the next animation frame, after that frame
drew: a key it handles is painted two frames late. So while an editor is composed, a capture-phase
DOM keydown listener serves hardware keys inside the event itself and cancels them: bound chords run
their command (Backspace, Enter, arrows, Tab, plugins' bindings), plain characters are typed; Mod-c/x/v
are hidden from Compose and served by the browser's copy/cut/paste events (synchronous clipboardData,
the whole selection, no permission prompt). Only for a HARDWARE keyboard aimed at Compose's own
textarea: never after a touch or pen pointer, and on a touch-capable device only once a physical-key
keydown (a non-empty `code`) has been seen, because iOS Safari's soft keyboard sends real key values
and must keep going through the field for autocorrect and predictions. That is a heuristic
(`isHardwareKey`); `EditorView.webKeyboard` forces it (AUTO / HARDWARE / SOFT) and
`EditorView.onKeyPath` reports each key's path (the sample's debug input log shows it). IME
composition, unbound shortcuts and dead keys go the ordinary way.

**Focus rules** (the terminal's): a **touch** takes focus AND raises the soft keyboard, every time;
a **mouse click** takes focus and never raises a keyboard; nothing raises the keyboard when an editor
merely appears or a host focuses it (`focus(showKeyboard = false)`).
- ⚠️ With `BasicTextField(TextFieldState)` (Compose 1.12) the keyboard is the field's **input
  session**: it starts on focus only when `KeyboardOptions.showKeyboardOnFocus` is true, and
  `SoftwareKeyboardController.show()` does nothing until a session exists. So
  `EditorController.keyboardOnFocus` is that option: true on desktop and web always (the desktop
  IME and the web's TEXTAREA need a session on any focus), on Android and iOS only after a touch
  (reset on blur). It lives on the surface, not the view, so a document switch keeps it (a host's
  programmatic focus followed by a switch never asks for a keyboard).
- A touch takes the focus **one frame later**, once the field carries the option, so the session
  starts inside the focus change; started from a recomposition, iOS never made its input view first
  responder. Every later tap asks the platform again once the session exists
  (`InputMethodManager.showSoftInput` on Android, the keyboard controller on iOS), so a keyboard the
  user dismissed comes back.
- A host showing another document gives the SAME `Editor` a new view while the field keeps the
  focus: the surface carries its focus to the new view and keeps the input session (without that
  the caret vanished, and on Android/iOS typing stayed bound to the previous document's field).

**The hidden field never takes a pointer.** A pointer shield (a sibling above the field, consuming
nothing, `EditorDefaults.SHIELD_DP` = 64 dp square at the caret, which covers the field's 48 dp touch
target) takes those hits; the surface's own gestures (the parent's `pointerInput`) see everything, and
any other child of the surface elsewhere (a host's overlay) still gets its pointers.
Without it, a long press on an empty line or a line's end on the iPhone hit the field (it sits at the
caret, and Compose expands its touch target to 48 dp; Compose's `touchSelectionFirstPress` ignores
consumption) and crashed in Compose's own touch selection:
```
kotlin.IllegalArgumentException: start and end cannot be negative. [start: -1, end: -1]
  androidx.compose.ui.text#TextRange(kotlin.Int)
  …TransformedTextFieldState#placeCursorBeforeCharAt(kotlin.Int)
  …selection.moveCaretByLongPress
  …selection.UIKitTextFieldTextDragObserver.onStart
  …text.selection.$touchSelectionFirstPressCOROUTINE$0.invokeSuspend
```
The field keeps its place at the caret, where the keyboard and IME candidates anchor. One cost:
**Android stylus handwriting into the field is blocked** (Compose starts it from a stylus press on
the field, which the shield takes). Handwriting into the editor needs its own entry point, later.

**Semantics of the field.** The field holds only a window of text, so a screen reader must never see
it: on iOS and the web (which ignore `hideFromAccessibility`) its semantics are cleared; on Android
and the desktop it is `hideFromAccessibility` (UI tests drive it as `hasSetTextAction() and !ContentDescription`).

**Grapheme limits.** `TextBoundaries` follows the parts of UAX #29 an editor meets (combining marks,
ZWJ sequences, flags, emoji modifiers), not all of it: Indic conjuncts (GB9c: consonant + virama +
consonant in Devanagari and others) are not joined, so the caret can stop inside a conjunct and a
Backspace takes one piece of it.

**Pointer.** Mouse: click places the caret, Shift-click extends, drag selects with auto-scroll
past an edge, double click selects a word, triple click a line, Alt-drag makes one range per line.
The pointer node sits inside `Modifier.scrollable` and consumes only what is a selection, so the
scrollable normalizes wheel notches, tracks velocity and runs flings.

## The touch model

- **Tap:** the caret, the soft keyboard, and one caret handle (a drop under the caret, dragged to
  move it). A tap inside a selection that has handles keeps it and toggles the menu; elsewhere it
  collapses it.
- **Double tap:** the first tap places the caret AT ONCE (a single tap never waits for a second);
  a second within the double-tap timeout upgrades it to the word under the finger (the word rules of
  `TextBoundaries`, as double-click), with both handles and the menu; a third selects the line.
- **Long press:** selects the word and shows two teardrop handles in `EditorTheme.selectionHandle`;
  keep the finger down and drag to extend by words; the menu appears on release. Where there is no
  word (an empty line, past a line's end, below the last line) it places the caret with its handle
  and the menu (Paste, Select All): the "paste here" gesture.
- **Handles:** drag one end (the other stays; snapped to caret positions, auto-scrolling at an edge);
  touch targets are 48 dp. A handle hangs below its tip: a finger ABOVE the tip is on the text row and
  is text, never the handle (a slow second tap on a word used to grab the caret handle). The handles
  follow the selection through scrolling and edits, and hide on typing, a mouse click, a keyboard
  selection or a blur. Android shows its magnifier while an end is dragged; iOS has none (Compose
  offers no loupe there and ours is cut).
- **A finger drag** scrolls and never selects; a **pinch** zooms (below).

## The selection menu

Cut, Copy, Paste, Select All, from `DefaultCommands`, near the selection (or the caret). Cut and Copy
when something is selected, Paste only when the clipboard has text, Select All unless everything is
selected; read-only offers only Copy and Select All. It hides while a handle is dragged or the view
scrolls (for `EditorMenu.SCROLL_SETTLE_MILLIS`) and comes back after; typing or a new caret hides it.
- **Android and iOS use the platform's toolbar** (`LocalTextToolbar`: Android's floating action
  mode, iOS's `UIEditMenuInteraction`); the rect passed is padded past the handles (iOS put the menu
  below the rect, over them). **Desktop and web draw their own** popup (Compose's toolbar there is not
  a touch menu).
- ⚠️ **Deciding to show Paste never reads the clipboard.** `EditorClipboard.hasText()` must not read:
  on iOS a read of `UIPasteboard.string` shows the paste permission prompt (and Compose's
  `ClipboardManager.hasText()` IS such a read), so iOS asks `UIPasteboard.hasStrings`. The clipboard
  is read only when Paste is chosen, and then **synchronously inside the menu's own action**
  (`EditorClipboard.readNow()`, the platform clipboard's text): iOS asks no "Allow Paste" for a read
  inside its paste action. `ios-sim.sh` (`menu-paste-no-prompt`) asserts the prompt never shows. On the web Compose's clipboard reads nothing: the menu uses the
  async Clipboard API; the keys use the browser's own copy/cut/paste events.

## Zoom

`Mod +` / `Mod −` / `Mod 0` (also the number pad's) step the font size one point, 10 to 24
(`EditorZoom`), and a two-finger pinch scales it continuously. The line at the top stays at the top
(anchored to it, a fraction of a line kept). `EditorView.fontSize` (null: the theme's size) is the
state; `Editor(onFontSize = …)` hears every change (a pinch once the fingers lift) so the host can
keep it per app.

## Accessibility

One editable text element, never the whole document: the visible lines plus the caret's line
(`AccessibleText` maps offsets both ways; a line over 2,000 units is exposed around the caret).
The selection is exposed and settable (move by character or word, select), typing at the caret,
copy/cut/paste and click actions, `Editor(label = …)` as the content description, and a polite live
region says the line a caret move lands on. Line numbers are drawn, never exposed.
- The text node is its own layout node on the Canvas, not the scroll node: sharing a node with
  `scrollable` made macOS show an `AXScrollArea` without text. With `SetText` it is an
  **`AXTextField`** whose value is the exposed lines (checked in the running desktop sample's AX tree).
- The exposed text joins lines that are not neighbours in the document (the caret's lines, the lines
  around other carets, a partly exposed long line) with a `\n` that is not in the document. A
  `SetText` that removes such a joiner is **refused** (it would delete text the AT never saw); joins
  across real line breaks between shown lines apply as one transaction.
- **On the web** the browser's focused TEXTAREA is always in the accessibility tree (Chrome refuses
  `aria-hidden` on a focused element), so it IS the editor's one text box: labelled with the editor's
  label, holding the lines around the caret with its selection on the editor's caret; the surface
  exposes no second one there.

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

## The device pass (M3b)

Ahmet on his devices, with the sample (`:editor-sample`: Android `dev.supermux.editor.sample`, iOS
`iosApp/`, desktop, web):

| Device | What he reported | Result |
|---|---|---|
| Galaxy Fold (Android 16) | the Android pass; the caret vanished after switching tabs a few times (typing still worked); asked for double tap and auto-closing brackets | done: the caret fix (the surface carries its focus to a new view), double/triple tap and the basics plugin added |
| iPhone 15 Pro and iPad Air M2 (iOS 26) | curly quotes in code; Shift-Tab did not outdent; a long press on an empty line or a line's end crashed the iPhone (the iPad did not) | all three confirmed fixed by him: the Smart Punctuation shim, Shift-Tab / indentLess, the pointer shield |
| Mac desktop sample, web sample | in use throughout the pass | no open report from him |

Only what he reported is listed: items of the M3b checklist he did not report on are in the gaps below.

The automated pass on the Mac (`mac:~/work/editor-pass/`, then the checks kept in the repo):

| Surface | Check | Result |
|---|---|---|
| iOS Simulator | `device-checks/ios-sim.sh` (Maestro, console captured): a tap starts the input session and the keys type; Return, Backspace; `"` types U+0022 and pairs; the shim's traits all `.no`; one labelled text element; long presses (a word, an empty line, a line end, the caret, keyboard up) give the menu with no Kotlin exception | pass |
| Android emulator (API 35) | `device-checks/android-keyboard.sh`: no keyboard before a tap or on a scroll, a tap shows it (`mInputShown`), a second tap brings it back; Turkish, Return, Backspace through the IME (ADB keyboard); handles, menu, pinch, fling, zoom keys, TalkBack (the controller's pass) | pass (Back-dismiss once flaky at load 100+) |
| Chrome (headless, CDP) | `:editor-sample:webInputTest`: TEXTAREA on focus, `Input.insertText` / IME at the caret after the editor moved it, the paste event, Mod-V, one labelled text box | pass |
| Desktop JVM | the UI harness (touch, menu, zoom, semantics, document switch, the field's pointer spy) | pass |

**Known gaps.** Dictation and Japanese kana→kanji composition are not yet confirmed on the devices
with the final build (they passed M0's probe, and web IME is covered over CDP). The web's
hardware-keyboard heuristic is a heuristic (override: `EditorView.webKeyboard`). On Android and iOS
a focus that starts with a trackpad or mouse click starts no input session (IME and dictation then
need a tap); not yet checked on the iPad with its trackpad. Backgrounding and resuming the app was
not tested for the caret bug (only document switching). Device frame times (120 Hz iPad, typing on
the Fold) were not measured; that moves to M3c. The Smart Punctuation shim depends on Compose
internals (above): re-run `ios-sim.sh` on every Compose upgrade. Undo is M4's history plugin.

## Tests

`./gradlew :editor-compose:jvmTest` (on the Mac: see `scripts/editor/mac-sync.sh`): the pure logic
in `commonTest` (height map, commands, grapheme and word rules, the field sync, the web key-path
decision, the accessible text; also run by `iosSimulatorArm64Test`) and the real composable in the
desktop UI harness (`jvmTest`: geometry against a real TextMeasurer, painting and pixels, scrolling,
pointer gestures, touch handles, the menu with a fake platform toolbar, zoom, the semantics tree,
document switching, typing through the real field). Compose's harness cannot open an IME composing
region, so composition is tested at `FieldSync`; the device checks live in
`apps/editor-sample/device-checks/` and `:editor-sample:webInputTest`. Counts at the end of M3b:
JVM 183, iOS simulator 74.

## Not here yet

M3c: gutter markers, block widgets (`HeightMap.setBlockHeight` exists), inline widgets, `Replace`
(folds), panels, linked views, device frame times (120 Hz iPad), moving scroll writes out of draw.
An accessory bar for soft keyboards (Tab / Shift-Tab, arrows).
