# editor-plugins/basics

The editing basics every code editor has, as the native editor's first plugin on the public API
(spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md`, `editor-plugins/*`):

```kotlin
EditorState.create(text, extensions = extensionOf(Syntax.extension(lang), basics()))
```

- **`CloseBrackets`** (CM6's `closeBrackets`): typing `(` `[` `{` `"` `'` `` ` `` inserts the pair
  with the cursor between. A bracket pairs only before whitespace, the line's end or one of
  `CloseBracketsConfig.before` (`)]}:;>,`); a quote only when neither neighbour is a word character
  (letter, digit, `_`; an emoji is not one). Typing a closer that is already after the cursor steps
  over it; an opening character wraps a selection; Backspace between an empty pair deletes both.
  Every cursor decides for itself. The pairs come from the `closeBracketsConfig` facet, so a
  language plugin can change them.
- **`BlockIndent`**: Enter between `{}` / `[]` / `()` opens an indented block, one `indentUnitFacet`
  deeper than the current line; elsewhere Enter is `DefaultCommands.insertNewline`, which already
  keeps the line's indentation.

**Every input path.** Typed text reaches the plugin through `inputHandlerFacet`, which
`EditorView.typeText` asks first; the hidden field (soft and hardware keyboards) and the web's key
path both type through it. A soft keyboard's Backspace arrives there as the deletion of one unit
before the cursor; a hardware Backspace is a key binding. A soft Return runs the keymap's Enter
binding (the field does not insert the newline itself). Only one character per commit is a bracket:
a multi-character commit (a suggestion) is text.

**Dependencies.** `:editor-core` and `:editor-compose`, the latter only for `inputHandlerFacet`,
`indentUnitFacet` and `DefaultCommands`: no Compose UI type is used, the plugin produces
transactions and key bindings (data only, the sandbox-ready contract). Moving those editor-level
facets into `:editor-core` would let plugins depend on the core alone; M4 decides that with the
other plugins.

**Not yet (follows the syntax tree):** no pairing inside strings or comments, no stepping only over
closers the plugin inserted (CM6 tracks them), no language-specific pairs (`<>` in HTML, `'` off
in Rust).

Tests: `./gradlew :editor-plugins:basics:jvmTest` (commonTest: every rule, multi-cursor, emoji,
soft Backspace; jvmTest: brackets and Return through the real hidden field of a composed `Editor`)
and `:editor-plugins:basics:iosSimulatorArm64Test`.
