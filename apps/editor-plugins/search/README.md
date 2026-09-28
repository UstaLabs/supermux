# editor-plugins/search

Find and replace as a plugin (spec §7: "search: find/replace panel with regex, match case and whole
word; all matches marked"), CM6's `@codemirror/search` in Kotlin: a pure engine over the `Rope` and a
plugin with a panel. Only the open document: project-wide search is the host's (`:ui`, the broker's
file search).

```kotlin
val view = EditorView(EditorState.create(text, extensions = extensionOf(search(), history(), fold(), ...)))
val registry = WidgetRegistry().also { Search.registerWidgets(it) }   // panel:search, panel:goto-line
Editor(view, widgets = registry)
```

## The engine (`SearchQuery`, pure Kotlin)

`SearchQuery(search, caseSensitive = false, regexp = false, wholeWord = false, replace = "")`, plain
data. `cursor(doc, from, to)` iterates the matches in order (not overlapping); `nextMatch` /
`prevMatch` wrap around the document and never return the current selection; `matchAll(doc, limit)`
(null past the limit), `count(doc)` (capped at 10,000: `MatchCount.label` "10,000+") and
`replacement(match)`.

- **Literal**: CM6's escapes `\n`, `\r`, `\t`, `\\` (any other backslash is itself). The document is
  read in windows of 32,768 units that overlap by the needle's length, so a match across Rope chunks
  or windows is found, and 10 MB is scanned without ever being one string.
- **Regex**: Kotlin `Regex` (java.util.regex on the JVM and Android, the Kotlin/Native and Kotlin/Wasm
  engine on iOS and the web), `^` / `$` are line anchors. Per line by default (windows of whole lines);
  a pattern that can match a line break (`\n`, `\r`, `\s`, `\W`, `\D`, `[^`, a literal line break:
  CM6's test) searches across lines in a window of 256K that doubles when a match comes within 64K
  of its end, up to 2M: a longer multi-line match is not found whole. An invalid pattern is an error
  state (`valid` false, `error` its message): it finds nothing and nothing throws. Empty matches (`^`,
  `x*`) are found, the cursor steps one character (a surrogate pair whole) past each.
- **Case**: `caseSensitive = false` folds each UTF-16 unit locale-free (its uppercase, then lowercase;
  a 64K-entry table), the same for literal and regex. **Turkish:** `i`, `I`, `İ` and `ı` are then ONE
  letter (search `istanbul` finds `İstanbul`, `ISTANBUL` and `ıstanbul`); turn on match case to tell
  them apart. Other letters fold as usual (`ğ`/`Ğ`, `ş`/`Ş`). A character outside the BMP is not folded.
- **Whole word**: CM6's rule: the match's first character or the one before it is not a word
  character, and its last or the one after it neither. Word characters are the editor's: letters,
  digits, `_` (a surrogate pair, so an emoji, is not one).
- **`\w` / `\W`** are rewritten to the editor's word characters (`[\p{L}\p{Nd}_]`, and a look-ahead
  for `\W`). The engines disagree on the plain ones: `\w` is ASCII everywhere, but the Kotlin/Native
  and Wasm engine folds case INTO the class (with ignore case `İ` and `ı` are `\w` there, `ğ` is not;
  on the JVM neither is). Written out they agree, and `\w+` finds `ağaç` and `İstanbul` whole.
  ⚠️ That engine also mishandles a literal next to a `\p{…}` in a NEGATED class (`[^\p{L}_]` matches
  `_` there): a `\w` inside a negated class becomes `\p{L}\p{Nd}\p{Pc}` to avoid it; a user's own
  `[^\p{L}_]` still differs on iOS and the web. `\p{Lu}` with ignore case follows each engine.
- **Replace**: unquoted like a literal; a regex's replacement is a template: `$&` the match, `$$` a
  `$`, `$1`…`$99` a group (the longest group number that exists, CM6's rule; a group that did not
  take part is empty), anything else as typed. A literal query's replacement is no template (CM6).

`SearchEngineTest` has a golden (43 queries over one text: case, `\w`, `\b`, classes, Unicode
properties, look-around, back-references, emoji, combining marks, Turkish) written from the JVM; it
holds on the iOS simulator and in headless Chrome (`wasmJsBrowserTest`: the common tests run there,
Karma with the Mac's Google Chrome).

**Performance** (`SearchPerfTest`, JVM, Mac, best of 5): a 10 MB document, the cursor at its end, the
only match just before it (the next match wraps and scans everything): literal ignoring case 11 ms,
match case 6 ms, whole word 11 ms (budget 50 ms); regex per line 85 ms, multi-line 115 ms (budget
250); counting stops at the cap in 2 ms, a full count with no match 12 ms.

## The plugin

`search(SearchConfig(top = true, caseSensitive, regexp, wholeWord))`. The state is `Search.field`
(a `SearchState`: the query, the panel open, the replace row, go-to-line, focus requests), changed by
`Search.setQueryEffect`, `togglePanel`, `toggleReplace`, `focusPanel`, `toggleGotoLine`. The panels
come from it (editor-core's `panelsFacet`, a computed input that is null when closed).

- **Marks**: while the panel is open and the query valid, every match in editor-compose's
  `EditorViewport` (a window of about a screen around what is laid out; at most 200K units around
  the cursor on a huge line) gets `search-match`, and one that IS a selection range also
  `search-match-selected`. `EditorTheme.light` / `dark` colour both (`EditorTheme.searchClasses`), and
  a theme mode switch (view settings) keeps them.
- **Commands and keys** (CM6's `searchKeymap`, all also `NamedCommand`s `search.*`):

| key | command | notes |
|---|---|---|
| `Mod-f` | `openSearchPanel` | fills the query from a one-line selection (at most 100 characters), else the word at an empty cursor, else the last query; open, it gives the field the focus again |
| `Escape` | `closeSearchPanel` | false when closed (the key goes on) |
| `Mod-g`, `F3`, Enter in the field | `findNext` | wraps; without a query it opens the panel (CM6) |
| `Mod-Shift-g`, `Shift-F3`, Shift-Enter | `findPrevious` | |
| `Alt-Enter` (Apple `Mod-Alt-Enter`) | `selectMatches` | a selection range per match (at most 1,000, CM6), the one at the cursor main; the panel then gives the editor the focus, so typing reaches every match |
| `Mod-Shift-l` | `selectSelectionMatches` | every occurrence of the selected text (CM6) |
| Enter in the replace field | `replaceNext` | CM6's: the selection is the match: replace it and select the next; else select the next |
| (button) | `replaceAll` | one transaction |
| `Mod-d` | `selectNextOccurrence` | CM6's: an empty cursor selects its word; then the next occurrence after the last range (wrapping), whole words only when the selection is one; the main range stays |
| `Mod-Alt-g` | `gotoLine` | CM6's syntax: `12`, `+3`, `-2`, `50%`, `12:5` (`Search.gotoLineSelection`) |

- **userEvents**: `select.search` (moving between matches, and the incremental search), 
  `select.search.matches`, `input.replace` (one replacement), `input.replace.all` (all: ONE
  transaction, so ONE undo step with the history plugin), `select` (Mod-d, go to line).
- **Folds**: every match is selected with `scrollIntoView`, so a fold holding it opens (the surface
  asks editor-compose's `revealFacet`, which the fold plugin answers). Local input never edits inside a
  fold (the surface refuses it), so replace all first asks the reveal handlers for every match: the
  folds holding one open, then everything is replaced in one transaction.

## The panel

`panel:search`, at the top by default, in supermux's look (`:ui`'s search field: a rounded, faint
field in the editor's font): the chevron (show the replace row), the find field, match case `Aa`, whole
word `W` and regex `.*` toggles showing their state (checkbox semantics), the count ("3 of 17", "No
results", "Invalid regex: …", "10,000+ matches"; counted after the document, query or selection
change, a moment later on a file over 1 MB), previous `↑`, next `↓`, select all, close `×`; the
replace row has its field, Replace and Replace all. Below 560 dp wide the toggles, count and select
all move to a second row.

- **Focus**: opening it gives the find field the focus with its text selected; Escape anywhere in
  the panel closes it and gives the focus back to the editor (so does ×). The buttons never take the
  focus (a soft keyboard stays up); they first commit what the fields hold, so a click right after
  typing uses the new text.
- **Incremental search**: typing searches `SEARCH_DEBOUNCE_MS` (50 ms) after the last keystroke:
  the query is set and the first match at or after the selection's start is selected and scrolled
  into view (VS Code's; CM6 only marks), in one transaction.
- **Phones**: the fields are ordinary Compose text fields, so the soft keyboard types into them;
  the keyboard's action key (Search) finds the next match. They are code fields: no capitalization,
  no autocorrect, and on iOS `Modifier.codeTextInput()` (editor-compose) turns Smart Punctuation off
  while one has the focus, so `"` stays a straight quote in a search string (the editor's own switch
  and owner rule: every other field of the app keeps the user's setting). Buttons are 48 dp on
  Android, iOS and a touch browser (`pointer: coarse`), 30 dp with a mouse.
- `panel:goto-line`: "Go to line", a field holding the cursor's line, Go and ×; Enter goes (scrolled
  into view) and closes, Escape closes.

## Deliberate differences from CM6

- The panel is at the top (CM6: bottom) and fills the query from the word at an empty cursor (CM6:
  only from a selection).
- Typing in the field selects the first match (VS Code; CM6 only marks the matches).
- `\w` / `\W` mean Unicode letters, digits and `_` (CM6: JavaScript's ASCII `\w`); case folding is
  per UTF-16 unit (CM6 folds with `toLowerCase`, so `İ` becomes two characters there).
- Select all matches has keys (`Alt-Enter`, Apple `Mod-Alt-Enter`); CM6 binds none (only its "all"
  button). No key replaces all (VS Code's `Mod-Alt-Enter` is select-all here on Apple).
- `Mod-d` behaves like CM6's, except that the main range is not scrolled to the new occurrence (the
  surface scrolls the main range into view; the new range is not the main one).
- A search inside a fold opens the fold (CM6 unfolds too); replace all opens every fold holding a match.
- No `test` hook in the query (CM6's `SearchQuery.test`), no screen-reader announcement of the
  match text (the count is a polite live region).

Tests: `./gradlew :editor-plugins:search:jvmTest` (commonTest: the engine and its golden, every
command and key, replace all as one undo step, regex groups, reveal in a fold, selectMatches then
typing at every cursor, Mod-d as CM6, go to line, the count, the themes; jvmTest: the 10 MB
performance and the panel in a composed Editor: Mod-f focus, the debounce, Enter / Shift-Enter /
Escape, the buttons and toggles, replace all from the panel, select all matches then typing),
`:editor-plugins:search:iosSimulatorArm64Test` and `:editor-plugins:search:wasmJsBrowserTest` (the
common tests), and `:editor-sample:webInputTest` mode `search` (the panel on the web over CDP).
