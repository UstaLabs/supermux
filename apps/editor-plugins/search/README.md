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
  engine on iOS and the web), `^` / `$` are line anchors. The input anchors `\A`, `\z`, `\Z` and `\G`
  are REFUSED (an error state: "use ^ and $"): the document is read in windows, where they would
  answer at a window's edge. A pattern that can match a line break searches across lines; the test
  is a small parser over the pattern (escapes, classes, `\Q…\E`, inline flags): `\n`, `\r`, `\s`,
  `\W`, `\D`, `\v`, `\R`, `\X`, any `\x` / `\u` / octal `\0` / `\c` escape (`\x0a`, `\u000A`,
  `\cJ`), `\P{…}`, a control or space `\p{…}` (`\p{Cc}`, `\p{Space}`), a negated class `[^`, the `s`
  flag (`(?s)`, `(?is:`), a literal line break. Any other pattern runs line by line.
  - **Windows.** A window never starts mid-line without 1,024 units of text before the position it
    searches from (so a look-behind, `\b` and `^` see the text as it is: `^` only at a real line
    start), and never reads further than the range's end plus 1,024 (look-aheads, `$`). Line by line,
    a window is whole lines (32K), or a piece of a longer line; across lines, 64K cut anywhere. At a
    CUT end, a match must end 1,024 units before it or the window grows from the match (doubling, up
    to 2M), so `$`, `\b` and look-aheads never answer at a cut; after a cut window with no match the
    next starts 4K (16K across lines) before its end. So: a look-behind sees at most 1,024 units
    back, a look-ahead at most 1,024 past the range, a match is at most 2M long, and a match longer
    than the overlap that starts near a cut can be missed. `b\x0Aa` over 30,000 lines finds all 29,999;
    `^b[^\n]` on a line longer than 256K matches only at real line starts.
  - A cursor stops at the first match that ends past its range (a viewport's marks on a 10 MB line
    read about the viewport, not the line). An invalid pattern is an error state (`valid` false,
    `error` its message): it finds nothing and nothing throws. Empty matches (`^`, `x*`) are found,
    the cursor steps one character (a surrogate pair whole) past each; the empty line after a final
    line break has its `^` and `$` too (CM6: "prefix every line" reaches it).
- **Case**: `caseSensitive = false` is locale-free. A LITERAL search folds each UTF-16 unit to its
  uppercase's lowercase (a 64K-entry table); a REGEX runs with the engine's IGNORE_CASE, which
  compares uppercase and lowercase the same way inside the Basic Multilingual Plane, so literal and
  regex agree there on every engine (the golden). **Turkish:** `i`, `I`, `İ` and `ı` are then ONE
  letter (search `istanbul` finds `İstanbul`, `ISTANBUL` and `ıstanbul`); turn on match case to tell
  them apart. Other letters fold as usual (`ğ`/`Ğ`, `ş`/`Ş`). OUTSIDE the BMP (Deseret, Adlam: two
  units) a literal search never folds; a regex folds as its engine does (`CaseFoldingTest` prints it:
  the JVM's engine folds them, so `𐐨` finds `𐐀` there; the Kotlin/Native (iOS) and Kotlin/Wasm (web) engine does not, like a literal search). `\p{Lu}` / `\p{Ll}` with ignore case also follow each engine.
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
only match just before it (the next match wraps and scans everything): literal ignoring case 12 ms,
match case 7 ms, whole word 12 ms (budget 50 ms); regex per line 71 ms, multi-line 93 ms (budget
250); counting stops at the cap in 1 ms, a full count with no match 12 ms. With no match at all the
wrap-around scan stops where the first began: one pass over the document, not two. Marks on a 10 MB
single LINE (an 8K viewport): regex 0.1–0.3 ms, literal 0.03 ms (budget 2 ms). On the web the same
scans are 5–10× slower (a literal 10 MB pass ~118 ms, a regex ~300–400 ms): which is why no scan of a
big document runs on a keystroke (below).

**Off the keystroke (`SearchRunner`).** The panel's work: what the find field commits, its buttons'
and keys' finds, and the count. On a document of at most 256K units (`Search.ASYNC_LIMIT`) a find is
done at once. On a bigger one it runs in slices of at most 4 ms of work (the cursors stop every 8K
units read; `yield()` between slices), the panel says "searching…", the selection jumps when the
match is found, and a newer find cancels an older one; `Mod-g` / `F3` in the editor go to the runner
too while the panel is composed (`Search.requestSearch`). The count is sliced the same way ("…"
until it is done), restarted at once when the query changes, and only after the document and the
selection have been still for 120 ms after an edit or a caret move (a caret move on a small
document recounts at once). Measured, a keystroke in the find field on 10 MB with no match anywhere
(`SearchMainThreadTest`, JVM: every UI-thread task timed; `SearchMainThreadWasmTest`, headless
Chrome: M2c's MessageChannel gap monitor): the longest UI task on the JVM 4.1–4.4 ms
(literal, regex, multi-line regex, whole word); the longest the web page was held 8.7–10.7 ms, the
whole find + count done in 0.23 s (literal) to 0.95 s (multi-line regex). Asserted under 16 ms on both.
⚠️ On the web a slice must end with a REAL event-loop turn: kotlinx-coroutines' JS dispatcher runs up
to 16 queued tasks per turn, so `yield()` alone held the page ~70 ms; `giveBackThread()` posts a
MessageChannel message there (and is `yield()` elsewhere).

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
| `Mod-d` | `selectNextOccurrence` | CM6's: an empty cursor selects its word; then the next occurrence after the last range, wrapping only up to the last range's start (so a range never grows), whole words only when the selection is one; the main range stays and the new one is scrolled into view (editor-compose's `EditorEffects.scrollTo`) |
| `Mod-Alt-g` | `gotoLine` | CM6's syntax: `12`, `+3`, `-2`, `50%`, `12:5` (`Search.gotoLineSelection`); numbers too big are clamped (`99999999999` is the last line); input that is no line keeps the panel open with an error |

- **userEvents**: `select.search` (moving between matches, and the incremental search), 
  `select.search.matches`, `input.replace` (one replacement), `input.replace.all` (all: ONE
  transaction, so ONE undo step with the history plugin), `select` (Mod-d, go to line).
- **Folds**: every match is selected with `scrollIntoView`, so a fold holding it opens (the surface
  asks editor-compose's `revealFacet`, which the fold plugin answers). Replace all edits inside folds
  on purpose, in its ONE transaction (`EditorAnnotations.atomicWhole`: the surface lets it through):
  the fold plugin opens each fold it edits in that same transaction and registers, through
  editor-core's `invertedEffectsFacet`, the folds to restore, so one undo brings the text AND the
  folds back.
- **Read-only**: `replaceNext` and `replaceAll` return false first: nothing is replaced, nothing
  unfolds. A zero-length match at the cursor (`^`) IS replaced by `replaceNext` (CM6).

## The panel

`panel:search`, at the top by default, in supermux's look (`:ui`'s search field: a rounded, faint
field in the editor's font): the chevron (show the replace row), the find field, match case `Aa`, whole
word `W` and regex `.*` toggles showing their state (checkbox semantics), the count ("3 of 17", "No
results", "Invalid regex: …", "10,000+ matches"; counted after the document, query or selection
change, a moment later on a file over 1 MB), previous `↑`, next `↓`, select all, close `×`; the
replace row has its field, Replace and Replace all. Below 560 dp wide the toggles, count and select
all move to a second row.

- **Focus**: opening it gives the find field the focus with its text selected; Escape anywhere in
  the panel closes it and gives the focus back to the editor (so does ×, and so does any close while
  the panel holds the focus: a toolbar, a command). Tab / Shift-Tab move through the fields, toggles
  and buttons (a ring shows the focus; Enter or Space presses); a tap or a click presses WITHOUT
  taking the focus (a soft keyboard stays up). Alt-C / Alt-W / Alt-R (Apple Cmd-Alt-C / W / R) toggle
  match case, whole word and regex (VS Code's; CM6 has none). Buttons first commit what the fields
  hold, so a click right after typing uses the new text.
- **Fields vs the query**: the fields show the query only when it changed from OUTSIDE (Mod-f with a
  new selection): the runner remembers what the panel itself last committed, so a keystroke typed
  just before the debounce fires is never overwritten (`noKeystrokeIsDroppedWhateverTheGapToTheDebounce`:
  gaps 0–120 ms). The panel reads only the plugin's state (derived) and the runner's count in its own
  small scope: an edit or a caret move recomposes nothing of it.
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
- `Mod-d` behaves like CM6's.
- A search inside a fold opens the fold (CM6 unfolds too); replace all opens every fold holding a match (and undo folds them again).
- `\A`, `\z`, `\Z`, `\G` are refused (CM6 searches per line, where they are line anchors).
- No `test` hook in the query (CM6's `SearchQuery.test`), no screen-reader announcement of the
  match text (the count is a polite live region).

Tests: `./gradlew :editor-plugins:search:jvmTest` (commonTest: the engine and its golden, every
command and key, replace all as one undo step, regex groups, reveal in a fold, selectMatches then
typing at every cursor, Mod-d as CM6, go to line, the count, the themes; jvmTest: the 10 MB
performance and the panel in a composed Editor: Mod-f focus, the debounce, Enter / Shift-Enter /
Escape, the buttons and toggles, replace all from the panel, select all matches then typing),
`:editor-plugins:search:iosSimulatorArm64Test` and `:editor-plugins:search:wasmJsBrowserTest` (the
common tests), and `:editor-sample:webInputTest` mode `search` (the panel on the web over CDP).
