# editor-plugins/basics

The editing basics every code editor has, as the native editor's first plugin on the public API
(spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md`, `editor-plugins/*`):

```kotlin
EditorState.create(text, extensions = extensionOf(Syntax.extension(lang), basics()))
```

- **`CloseBrackets`** (CM6's `closeBrackets`): typing `(` `[` `{` `"` `'` `` ` `` inserts the pair
  with the cursor between. A bracket pairs only before whitespace, the line's end or one of
  `CloseBracketsConfig.before` (`)]}:;>`, CM6's default: before a `,` a bracket is typed alone); a
  quote only when neither neighbour is a word character (letter, digit, `_`; an emoji is not one).
  Typing a closer steps over the one after the cursor only when the plugin inserted it (the
  `insertedClosers` state field tracks them through every change, as CM6 does); an opening character
  wraps a selection; Backspace between an empty pair deletes both. All or nothing across cursors
  (CM6): when one cursor would type the character plainly, it is typed plainly at every cursor. The pairs come from the `closeBracketsConfig` facet, so a
  language plugin can change them.
- **`BlockIndent`**: Enter (and Shift-Enter) between `{}` / `[]` / `()` opens an indented block, one `indentUnitFacet`
  deeper than the current line; elsewhere Enter is `DefaultCommands.insertNewline`, which already
  keeps the line's indentation.
- **`IndentOnInput`** (CM6's `indentOnInput`, the opener-line rule): a `}` `)` `]` typed as the first
  non-blank character of a line re-indents the line to the indentation of its opener's line (the
  opener found by bracket matching's scan, so string brackets do not count with syntax on). The
  brace and the re-indent are one `input.type` transaction: one undo takes both. At every cursor.
  Tree-based indentation (`indents.scm`) is later.
- **`ActiveLine`** (CM6's `highlightActiveLine`): a `LineStyle` `active-line` on each empty cursor's
  line, painted in `EditorTheme.currentLine`. The surface has no built-in current-line highlight any
  more: an editor without basics shows none.
- **`BracketMatching`** (CM6's `bracketMatching`): an empty cursor next to a bracket marks it and its
  partner `matching-bracket`, or `nonmatching-bracket` for a partner of another kind or none before
  the document's edge. CM6's candidate order (a closer before the cursor, an opener before it, then
  after it). The scan stops after 10,000 characters (nothing marked then). With syntax on, only
  brackets of the same token context count (`tokenContextFacet`, editor-core: the kind, code /
  string / comment, AND the language of the injection layer), so `f("(", x)` pairs the code
  parentheses and a `(` in Markdown prose never pairs with one inside a fenced block. editor-syntax
  answers from its spans (a binary search over the non-overlapping spans, no scan) and the layers
  the worker reports. Where it does not know (outside the window it has parsed, plain text) a
  position is unknown and counts as in a plain scan; without a language layer every bracket counts.
- **`SelectionMatches`** (CM6's `highlightSelectionMatches`): one non-empty single-line selection of
  2 to 200 characters, not all blank, marks its other occurrences `selection-match`; one empty cursor
  in a word marks the word's other whole-word occurrences. Only in the viewport (editor-compose's
  `EditorViewport`; 100 lines around the cursor before the first paint); over 100 matches marks
  nothing.
- **`lineNumbers(enabled)`**: editor-compose's `lineNumbersFacet`, overriding
  `Editor(showLineNumbers = …)`; in a compartment the host toggles it at run time.

`basics()` is all of the above except `lineNumbers` (CloseBrackets, BlockIndent, IndentOnInput,
ActiveLine, BracketMatching, SelectionMatches). The theme's `light` / `dark` style every class.

**Deliberate differences from CM6.** The active line marks only EMPTY cursors' lines (CM6 marks
every range's head line). Selection matches mark the word under the cursor by default (CM6's
`highlightWordAroundCursor` is off by default), with no delay (CM6 has none either); the word itself
is not marked (CM6 gives it `cm-selectionMatch-main`). A selection must be 2 characters (CM6: 1).
Bracket matching compares token contexts (code / string / comment, and the layer's language), not
tree node types.

**Every input path.** Typed text reaches the plugin through `inputHandlerFacet`, which
`EditorView.typeText` asks first; the hidden field (soft and hardware keyboards) and the web's key
path both type through it. A soft keyboard's Backspace arrives there as the deletion of one unit
before the cursor; a hardware Backspace is a key binding. A soft Return runs the keymap's Enter
binding (the field does not insert the newline itself). Only one character per commit is a bracket:
a multi-character commit (a suggestion) is text.

**Related, not here.** Indentation keys (Tab, Shift-Tab `indentLess`, `Mod-]` / `Mod-[`) are
`DefaultCommands` in `:editor-compose`, next to Tab. Quote pairing on iOS relies on the hidden
field's Smart Punctuation being off (editor-compose's shim): with it on, the keyboard types `“`, which
is not a bracket. Verified on the devices in M3b: brackets and Enter between braces on the Fold,
straight-quote pairing on the iPhone (and in `device-checks/ios-sim.sh`).

**Dependencies.** `:editor-core` and `:editor-compose`, the latter only for `inputHandlerFacet`,
`indentUnitFacet` and `DefaultCommands`: no Compose UI type is used, the plugin produces
transactions and key bindings (data only, the sandbox-ready contract). Moving those editor-level
facets into `:editor-core` would let plugins depend on the core alone; M4 decides that with the
other plugins.

**Not yet:** close-bracket pairing is not suppressed inside strings or comments (CM6's is not either,
unless a language configures it; `tokenContextFacet` makes it possible), and no language-specific
pairs yet (`<>` in HTML, `'` off in Rust): language plugins will provide `closeBracketsConfig`.

Tests: `./gradlew :editor-plugins:basics:jvmTest` (commonTest: every rule, multi-cursor, emoji,
soft Backspace, the basics+ features, a 1.9 MB file never stalling; jvmTest: brackets and Return through the real hidden field of a composed `Editor`)
and `:editor-plugins:basics:iosSimulatorArm64Test`.
