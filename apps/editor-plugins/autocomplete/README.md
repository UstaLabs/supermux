# editor-plugins/autocomplete

CM6's `@codemirror/autocomplete` in Kotlin, on the public API: completion sources, the popup through
editor-compose's tooltip layer, CM6's fuzzy matching and sorting, snippets with tab stops. The LSP
plugin (`editor-plugins/lsp`) is one of its sources.

```kotlin
val view = EditorView(EditorState.create(text, extensions = extensionOf(autocompletion(), completionSourcesFacet.of(mySource), history())))
val registry = WidgetRegistry().also { Autocomplete.registerWidgets(it) }   // tooltip:completion
Editor(view, widgets = registry)
```

## Sources

`CompletionSource { ctx -> CompletionResult? }` is a `suspend` function: it runs on the UI thread's
scope (a source with heavy work moves it off) and is **cancelled** by the next keystroke that asks
again. `ctx` has the state, the cursor `pos`, `explicit` (`Ctrl-Space`), the `triggerCharacter` typed,
`matchBefore(regex)` and `wordStart()`. A `CompletionResult(from, options, to = null, validFor = null,
filter = true)`: while the text from `from` to the cursor still matches `validFor`, typing filters
the options again without asking; with no `validFor` (an LSP `isIncomplete` list) every keystroke
asks again, the old list shown meanwhile. Sources come from `completionSourcesFacet` (every plugin's)
or `autocompletion(override = listOf(...))`; several are asked in parallel and their options merged.

`Completion(label, displayLabel, detail, info, type, boost, apply, sortText, resolveInfo)`: `label`
is matched and inserted, `displayLabel` shown; `info` shows in the side panel, `resolveInfo` fetches
it when the option is selected (50 ms later; LSP `completionItem/resolve`). `apply`:
`CompletionApply.Text(text)`, `.Template(Snippet)`, `.WithEdits(text, edits, snippet)` (an LSP item's
`additionalTextEdits`, in the same transaction; edits overlapping the inserted range are dropped) or
`.Custom { target, completion, from, to -> }`.

**When it asks** (`AutocompleteConfig`): typing a word character (letters, digits, `_`, `$`) with the
list closed, after `activateOnTypingDelay` (100 ms, CM6's) from the LAST keystroke (a burst asks
once); a trigger character (`triggerCharacters` + every `completionTriggersFacet` input, the LSP
server's), whatever the list; a result that went stale; `Ctrl-Space` at once. "Typing" is `input`,
`input.type` and `input.ime` (a soft keyboard's composition counts: Gboard composes every word), not
paste, replace, indent or a completion. An answer for a document that changed meanwhile (a remote
edit) is mapped through the changes, and dropped if the cursor went before its start; a remote,
agent or LSP edit never cancels a pending ask, a paste / undo / redo does.

## Filtering and sorting

CM6's `FuzzyMatcher`, rule for rule (the penalties: case folded -200, not the whole label -100, by
word starts -100, not at the start -700, a gap -1100, minus the label's length; one typed character
matches only at the label's start; two must be adjacent, by word starts or a substring). Sorted by
score + `boost`, then `sortText` (else the label) — by UTF-16 order, where CM6 uses `localeCompare`;
an option equal to the one before it (label, detail, type, apply, boost) is dropped (CM6). The
matched characters are bold in the list. **Performance** (`CompletionPerfTest`, JVM, Mac): 5,000
options, a 12-character identifier typed one key at a time: the worst keystroke 0.7 ms (best of 11
rounds; single rounds up to 2 ms); budget 5 ms.

## Keys (CM6's `completionKeymap`, highest precedence)

| key | while the list is open |
|---|---|
| `Ctrl-Space` (Apple also `Alt-\``, `Alt-i`) | open it (always) |
| `ArrowDown` / `ArrowUp` | next / previous (wrapping) |
| `PageDown` / `PageUp` | a page (8), stopping at the ends |
| `Enter`, `Tab` | accept (`Tab`: `acceptOnTab`, VS Code's; CM6 binds only Enter) |
| `Escape` | close |

For `interactionDelay` (75 ms, CM6's) after the list opens, the keys are not the list's: a fast
`foo⏎` typed as it opened is still a newline. A soft keyboard's Return reaches `Enter` (the surface
turns a soft Return into its binding), so it accepts too.

## Accepting

One transaction, userEvent **`input.complete`** (recorded, policed: one undo step of its own; the
M4a lsp contract). Text goes over `[from, cursor)` at the main cursor and at every other cursor where
the text at the same offsets is the same (CM6's `insertCompletionText`; the others are left alone),
each cursor after its text. A snippet goes at the main cursor only (CM6). Touch: a tap on a row
accepts it without taking the focus (`detectTapGestures`), so the soft keyboard stays up; the hidden
field is rewritten after the accept (a composition in progress ends there, so iOS autocorrect does
not fight the inserted text: the field's text IS the document's again); the list scrolls with a
finger (a drag there is the list's, never the editor's).

## Snippets

`Snippet.parse(template)`: CM6's syntax (`${}` / `${name}` a field, `${1}` / `${1:name}` numbered,
the same number twice one field, `${0}` the last stop, `\{` `\}` literal braces); `Snippet.fromLsp`
converts LSP's (`$1`, `${1:foo}`, `$0`, `\$`). Newlines indent like the snippet's line plus one indent
unit per leading tab. After the insert, the first field is selected (every range of it); `Tab` /
`Shift-Tab` move between fields, `Escape` leaves them; reaching the last stop, or the cursor leaving
the current field, ends it. The fields (`Snippets.field`, `FieldRange`s) move with edits and are
marked `snippet-field` (`EditorTheme.completionClasses`).

## Deliberate differences from CM6

- `Tab` accepts (configurable); sorting ties by UTF-16 order, not `localeCompare`.
- No sections, no `commitCharacters`, no `closeOnBlur` (the list stays while a widget or panel has
  the focus), no `maxRenderedOptions` (the list is lazy: only its visible rows are composed).
- The list's icons are letters coloured with the theme's token styles (the web has no emoji font).

Tests: `./gradlew :editor-plugins:autocomplete:jvmTest` (commonTest: open, filter, navigate, accept,
Escape, the interaction delay, trigger characters, cancellation, mapped / dropped answers, a
throwing source, a snippet with 2 fields and `$0`, leaving a field, multi-cursor accept, one undo
step, additional edits, resolved info, the matcher; jvmTest: the 5,000-option performance and the
popup in a composed Editor through the real field: tap to accept keeps the focus, hardware keys,
scrolling) and `:editor-plugins:autocomplete:iosSimulatorArm64Test`.
