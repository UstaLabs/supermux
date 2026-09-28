# editor-plugins/fold

Code folding as a plugin (spec §7: "fold: gutter arrows, fold/unfold/fold-all, placeholder
widget"), CM6's `@codemirror/language` folding in Kotlin, on the surface's M3c folds (hidden lines,
the "⋯" chip, atomic ranges, reveal).

```kotlin
EditorState.create(text, extensions = extensionOf(highlight("kotlin"), fold(), history()))
fold(FoldConfig(deleteFoldWhole = true))   // CM6's Backspace policy (see below)
```

## Ranges

Line-based: a line folds from its END to where its block ends, the text after that joined to the
line's row (`fun f() {⋯}`).
- **From the language first**: editor-core's `foldServiceFacet`. editor-syntax answers it from
  tree-sitter's `folds.scm` when highlighting is on: the OUTERMOST fold node starting on the line and
  ending on a later one (CM6's syntaxFolding picks the outermost too); the fold stops at the start
  of the node's last line when that line begins with its closing token (`}` `)` `]` `end`), so the
  brace stays visible, else at the node's end (`def f():⋯`).
- **Indentation otherwise** (for a line no service answers for, and for plain text): the lines after
  it that are indented deeper, blank lines among them included, trailing blank lines not
  (`Fold.indentFold`). Syntax folds are known only where the worker has looked (around the
  viewport), so a `foldAll` far from it folds by indentation there.
- `Fold.foldable(state, lineFrom, lineTo)` is that rule, for other plugins.

## State and edits

`Fold.field` is a `RangeSet` of `Decoration.Replace(WidgetKey("fold", id), fold = true)` in
`decorationsFacet`, mapped through every edit; `Fold.foldEffect` / `unfoldEffect` (`FoldRange`,
mapped too) change it. A fold is atomic (the surface's rule): local input never takes a piece of
it. An edit that does touch its hidden text (a remote, programmatic or undo edit) unfolds it, as
CM6's does; a deletion covering it removes it. Folds survive edits outside them.

## Gutter, chip, reveal

- **Gutter**: `fold-open` ("Fold") and `fold-closed` ("Unfold") markers in the `fold` column,
  computed for the lines in editor-compose's `EditorViewport` only (a big file costs what the screen
  shows). A click or tap (and the marker's accessibility action) toggles, through
  `gutterClickFacet`.
- **Chip**: the placeholder is the surface's drawn "⋯" chip (the `fold` widget type has no
  registered content); a click on it unfolds (`widgetClickFacet`).
- **Reveal**: a selection scrolled into view inside a fold (a search match, go-to-definition)
  unfolds that fold and keeps the selection (`revealFacet`).
- **Deleting into a fold** (Backspace at its end, Delete at its start, a soft keyboard deleting its
  placeholder): by default the surface's policy unfolds it first (JetBrains-style) and the next
  Backspace deletes normally. `FoldConfig(deleteFoldWhole = true)` is CM6's policy instead: the
  whole folded text goes with that one keystroke, and undo (history) brings it back. The default
  stays unfold-first until Ahmet decides (asked 2026-09-28, no answer yet).

## Commands and keys

CM6's `foldKeymap`:

| key | command |
|---|---|
| `Ctrl-Shift-[` (Apple `Cmd-Alt-[`) | `Fold.foldCode`: the first cursor line that can fold |
| `Ctrl-Shift-]` (Apple `Cmd-Alt-]`) | `Fold.unfoldCode`: the folds on the cursors' lines |
| `Ctrl-Alt-[` | `Fold.foldAll`: every top-level range (past each fold it makes) |
| `Ctrl-Alt-]` | `Fold.unfoldAll` |

`Fold.toggleFold` too; all five are `NamedCommand`s (`fold.fold`, …). Today's CM6 editor does not
bind foldKeymap at all (it is not in `cm6-entry.mjs`'s keymap); these are CM6's defaults.

## Deliberate differences from CM6

- Indentation folding is a built-in fallback (CM6 folds by indentation only when a language opts
  in with a fold service).
- A selection set inside a fold WITHOUT `scrollIntoView` is moved out to the fold's edge (the M3c
  surface rule); CM6 unfolds any fold the main cursor lands in.
- Backspace next to a fold unfolds first by default (CM6 deletes it whole; that is the option).

Tests: `./gradlew :editor-plugins:fold:jvmTest` (commonTest: ranges from a service and from
indentation, commands and CM6's keys, foldAll/unfoldAll, `foldAll` over 10,500 lines well under
100 ms, gutter markers in the viewport and their clicks, the chip, edits outside and inside, a
search-like reveal, Backspace with both policies and undo; jvmTest: the gutter arrows' nodes of a
composed Editor fold and unfold). The syntax side (`folds.scm` → line ranges on real Kotlin) is
tested in `:editor-plugins:highlight`.
