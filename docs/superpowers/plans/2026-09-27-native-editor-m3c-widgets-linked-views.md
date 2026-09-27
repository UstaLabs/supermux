# Native editor M3c: gutters, widgets, folds, panels and linked views. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `:editor-compose` the remaining surface features that M4's plugins build on. After this milestone,
`editor-compose` can draw everything the M4 plugins will describe:
- **Gutter markers:** diff +/−, lint dots, 💬 comment markers, fold arrows.
- **Block widgets:** real Compose content with its own height *between* lines, for diff alignment gaps,
  "⋯ 120 unchanged lines" and review threads.
- **Inline widgets.**
- **`Replace` decorations:** folded regions, drawn with a placeholder.
- **Panels:** top or bottom strips, such as the search bar.
- **Linked views:** two editors sharing a scroll and line-alignment controller, needed for the side-by-side diff.
- **Scroll writes moved out of draw.** This is M3a's remaining debt.
- **A performance pass on devices.**

**Context.**
- editor-core already has the decoration data:
  - `Decoration.Mark`, `LineStyle`, `InlineWidget(key, side)`, `BlockWidget(key, above, estimatedHeightLines)` and
    `Replace(widget?)`, all with `WidgetKey(type, id)`.
  - `decorationsFacet`.
- `HeightMap.setBlockHeight` exists but nothing uses it.
- `EditorScrollState` is public but minimal, and anchoring is off while it is shared.
- Read the M3a and M3b READMEs, `apps/editor-compose/README.md`, and `~/.mux/domains/editor.md`.

**Design rules.**
- Plugins describe everything as DATA (spec §4.4, the sandbox rule).
- Widget *content* is provided by the host or plugin through a composable registry keyed by `WidgetKey.type`. This is
  the one sanctioned exception to the data-only rule, and it applies to compiled-in plugins only.

**Not pre-verified.** Use TDD. The interfaces below are the contract.

## Ground rules
The same as M3b:
- Mac-only builds, synced with `scripts/editor/mac-sync.sh`, run over `ssh mac "$MACENV; …"`.
- Do dev builds in the private clone `~/work/native-editor-m3b`, so the samples Ahmet is running stay untouched.
- Reinstall the samples on devices at the end.
- Every commit ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Measure before draw
Move all measuring and scroll or anchor mutation out of the draw phase into a pre-draw layout step:
- Compose `Layout`/`onPlaced`, or `SubcomposeLayout` when Task 3 needs subcomposition.
- Draw then only reads.

**Tests:**
- No scroll-state write happens during draw. Add a test hook that fails if the scroll changes inside a `DrawScope`.
- There are no extra frames.
- The wrap-scroll-up no-jump test still passes.
- Perf is unchanged (editor-sample perf 4/4).

- [ ] Commit: `refactor(editor-compose): measure and anchor before draw`.

### Task 2: Gutter markers
```kotlin
/** A plugin's markers for a gutter column; the editor draws one column per distinct `column` id, in precedence order. */
data class GutterMarker(val column: String, val kind: String, val tooltip: String? = null)   // kind resolved by the theme
val gutterMarkersFacet: Facet<RangeSet<GutterMarker>, List<RangeSet<GutterMarker>>>        // in editor-core (data)
```

- **Columns.** Each marker column has a fixed width, set by `EditorTheme.gutterColumns[column]`. Line numbers stay a
  built-in column.
- **Drawing.** `kind` maps to an icon or colour in the theme. Examples:
  - `diff-add`, `diff-remove`, `diff-change` (thin bars)
  - `lint-error`, `lint-warning`
  - `comment` (💬)
  - `fold-open`, `fold-closed`
- **Clicks.** A click or tap on a marker reports `onGutterClick(column, line, marker)` to the host or plugin through
  `EditorView`.
- **Accessibility.** Markers have a semantics label, taken from `tooltip`.

**Tests:**
- Markers draw on the right lines.
- Markers follow edits, because RangeSet mapping moves them.
- A click reports the right line.
- Several columns keep a stable order.

- [ ] Commit: `feat(editor-compose): gutter marker columns`.

### Task 3: Block widgets
```kotlin
/** Composable content for widget keys; installed by the host/plugins. */
class WidgetRegistry { fun register(type: String, content: @Composable WidgetScope.(WidgetKey) -> Unit) }
@Composable fun Editor(..., widgets: WidgetRegistry = remember { WidgetRegistry() })
```

- **Placement.** `BlockWidget` decorations for visible lines are subcomposed and placed above or below their line.
  Their measured height goes into `HeightMap.setBlockHeight`.
  - Before a widget is measured, `estimatedHeightLines` is used for its height.
  - When the real height is measured, scroll anchoring must keep the visible content still. This has the same
    no-jump requirement as wrap mode.
- **Lifecycle.** Widgets outside the viewport are disposed. A widget's state must survive a scroll out and back in:
  keep it by `WidgetKey` with a small retained cache, so a comment being drafted in a review thread isn't lost.
- **Input.**
  - Widgets receive their own pointer and keyboard input: a text field inside a comment widget must work, including
    the soft keyboard.
  - The editor's gestures and the hidden-field pointer shield must not steal those events.
  - Focus moves into a widget and back to the editor.
- **Selection.** Selection and cursor movement skip over block widgets. Up and Down arrows move from the line above
  the widget to the line below it.

**Tests:**
- Placement and height feedback.
- Scrolling past a tall widget, with no jumps.
- A text field inside a widget receives typing (JVM harness) while the editor doesn't.
- Retained state across scroll.
- Arrow keys skip the widget.

- [ ] Commit: `feat(editor-compose): block widgets with real heights`.

### Task 4: Inline widgets and Replace (folds)
- **`InlineWidget`.** A small composable, or a drawn chip, inside the line at a point, with `side` deciding cursor
  affinity. It takes up horizontal space in the line layout: use an `AnnotatedString` placeholder so the text
  measures around it. The caret can sit on either side of it.
- **`Replace(widget)`.**
  - The replaced range is hidden. It may span lines: a fold hides whole lines, and the HeightMap treats them as zero
    height.
  - Its placeholder widget, e.g. "…", is shown in its place.
  - The caret can't enter the hidden range. Arrow keys jump over it.
  - A click on the placeholder reports to the plugin, which uses it to unfold.
  - The line numbers after the fold stay correct: they are the real line numbers, with the hidden ones skipped.
- **Tests:**
  - An inline widget keeps text layout and hit-testing correct.
  - A fold hides lines, the line numbers skip them, arrow keys jump over, and unfolding restores them.
  - A fold survives edits outside it and above it.

- [ ] Commit: `feat(editor-compose): inline widgets and replaced (folded) ranges`.

### Task 5: Panels
```kotlin
data class Panel(val id: String, val top: Boolean)                       // data, from a facet
val panelsFacet: Facet<Panel, List<Panel>>
// content comes from the WidgetRegistry under type "panel:<id>"
```
- **Layout.** Panels are strips attached to the editor, at the top or bottom, outside the scrolling area. They take
  their own height, and the editor viewport shrinks by that height.
- **Input.** Panels take focus and input: the search field in M4 lives here. Escape returns focus to the editor.
- **Tests:** a panel shrinks the viewport, gets typing, and Escape returns focus.

- [ ] Commit: `feat(editor-compose): editor panels`.

### Task 6: Linked views
```kotlin
/** Keeps two (or more) editors aligned line-by-line for side-by-side diff. */
class LinkedScroll(val mapping: LineMapping)   // LineMapping: line in view A ↔ line in view B (from the diff plugin, M4)
@Composable fun Editor(..., linked: LinkedScroll? = null)
```
- **Scroll sync.** Scrolling either view scrolls the other so that corresponding lines stay at the same y. Wheel,
  fling, drag and keyboard-driven scrolling all go through the shared controller.
- **Alignment gaps.** The diff plugin (M4) inserts `BlockWidget` gaps so that aligned rows have equal height. This
  task only provides the synchronisation, plus a test `LineMapping` with gap widgets that proves rows line up.
- **Anchoring works while linked.** This fixes M3a's "anchoring is off while shared".
- **Tests:**
  - Two editors with a mapping, including inserted and deleted runs, plus gap widgets:
    - scrolling either keeps corresponding lines aligned within 1 px;
    - fling works on both;
    - editing one view (it's the working copy) keeps alignment after the mapping updates.
- **Sample.** Add a "side-by-side demo" in `editor-sample` with two views of the same file and a fake mapping, so
  Ahmet can feel it before M4's real diff.

- [ ] Commit: `feat(editor-compose): linked views for side-by-side`.

### Task 7: Device performance pass
- [ ] Reinstall the samples:
  - iPhone and iPad via `iosApp/device.sh`, keeping `--console` capture to a log;
  - Fold via adb `100.123.34.70:<port>`: check `adb devices` first;
  - desktop and web samples.
- [ ] Measure on the devices and record the results in the README against spec §6.6:
  - iPad fling at 120 Hz;
  - typing latency on the Fold;
  - opening the 10k-line file;
  - scrolling with block widgets and gutter markers present.
- [ ] Send Ahmet a short checklist through the controller for the new features: gutter clicks, a demo fold, a demo
  review-thread block widget with a text field on a phone, a panel, and the side-by-side demo.
- [ ] Commit: `docs(editor-compose): M3c device results`.

### Task 8: Docs and memory
- [ ] Update the README: the widget registry contract, the gutter and panel facets, linked views, and the
  measure-before-draw model.
- [ ] Append to `~/.mux/domains/editor.md`.
- [ ] Commit.

## Not in M3c
- **M4:** the plugins that produce these decorations (fold, search, lsp, diff, history, basics+), plus view settings.
