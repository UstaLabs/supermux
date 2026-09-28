# Native editor M4b: search & replace, plus the accessory bar. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- `apps/editor-plugins/search`: find/replace with regex, match case and whole word. It marks every match in the
  viewport, jumps from match to match, supports replace, replace all and select all matches, and puts it all in a
  panel. It is CM6's `@codemirror/search` behaviour in Kotlin.
- The M4a follow-ups.
- A mobile accessory bar, if Ahmet approves it (Task 3 is gated).

**Context.** Read these first:
- the editor-core, editor-compose and plugin READMEs, especially panels (`panelsFacet` + the `panel:<id>` widget
  registry), `revealFacet` (unfold on scroll-into-view) and history (`userEvent` conventions)
- `~/.mux/domains/editor.md`
- today's CM6 search in `apps/android/codemirror/cm6-entry.mjs`, for parity: `searchKeymap`,
  `highlightSelectionMatches`
- the current `:ui` `EditorSearchBar.kt`, for the look supermux users know

**Not pre-verified.** Use strict TDD.

## Ground rules
Same as M4a:
- Build only on the Mac, in the private clone.
- Wire the plugin into `editor-sample`.
- At the end, reinstall on devices and restart the samples.
- Every commit ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 0: M4a follow-ups
- **`HistoryModelTest`:** add a long, agent-heavy case (400 ops, about 85% agent edits, past the 64-mapping cap).
  Document in the README how a capped, merged mapping is judged by the drop rule.
- **Web `selectionchange` blocking:**
  - Block only until the editor has caught up, or for about 100 ms at most. Then re-read the textarea's caret and
    apply it once.
  - A missed `compositionend` (for example, focus changes mid-composition) must not leave caret moves ignored
    indefinitely. Clear the "composing" state on blur or on a focus change.
  - Add a CDP test: move the textarea caret while the textarea's value is ahead of the editor's text, and assert the
    caret ends up where the user put it.
- [x] Commit: `fix(editor-compose,history): M4a review follow-ups`.

### Task 1: The search engine (pure, in the search module, no UI)
- **Query:**
  ```kotlin
  data class SearchQuery(val search: String, val caseSensitive: Boolean = false, val regexp: Boolean = false,
                         val wholeWord: Boolean = false, val replace: String = "")
  ```
  - A literal search supports the `\n`/`\t` escapes, like CM6.
  - A regex search uses Kotlin `Regex`, and must behave the same on JVM, Native and wasm. Test against a shared
    golden, like M2b's regex golden.
  - Replace strings support `$1`–`$9`, `$&` and `$$`.
- **Cursor over the Rope:**
  - An iterator over matches from a position.
  - It works chunk by chunk, with bounded look-ahead for literal searches, so a match that spans chunks is found.
  - A regex search works per line by default. Multi-line regex (`\n` in the pattern) searches a bounded window.
- **Whole word** uses the same word-character rule as `DefaultCommands` (Unicode letters, digits, `_`).
- **Tests:**
  - literal, case, whole word and regex, including invalid regexes, which produce an error state and never throw
  - matches that span Rope chunks
  - emoji and Turkish case folding (`İ`/`i`, `I`/`ı`); document what `caseSensitive = false` does for Turkish
  - a 10 MB document: finding the next match from the end wraps around in under 50 ms on the JVM
  - a match count capped at 10,000, reported as "10,000+"
- [x] Commit: `feat(editor-plugins): search engine (literal, regex, case, whole word, replace templates)`.

### Task 2: The search plugin and panel
- **State:** a `StateField` holding the query and whether the panel is open, updated through effects.
  - Match decorations: a `search-match` mark on every match in the viewport, and `search-match-selected` on the
    current match.
  - Theme colours are in `EditorTheme.classStyles`. Check that they survive theme mode changes (M4a fix).
  - A gutter marker for match lines is optional.
- **Commands:**
  - `openSearchPanel` (`Mod-f`) and `closeSearchPanel` (`Escape`)
  - `findNext` (`Mod-g`, `F3`, `Enter` in the field) and `findPrevious` (`Mod-Shift-g`, `Shift-F3`, `Shift-Enter`)
  - `selectMatches` (`Mod-Alt-Enter` on Mac, `Alt-Enter` elsewhere; match CM6), which makes every match a selection
    range, with multi-cursor
  - `replaceNext` and `replaceAll`
  - `selectNextOccurrence` (`Mod-d`)
  - `gotoLine` (`Mod-Alt-g` in CM6; check `cm6-entry.mjs`)
  - A match inside a fold reveals it (via `scrollIntoView` + `revealFacet`).
- **userEvents:**
  - `select.search` for moving between matches
  - `input.replace` for a single replace
  - `input.replace.all` for replace all
  - Replace all is one undo step. Check it with the history plugin.
- **Panel:** a panel registered as `panel:search` via `panelsFacet`.
  - It contains the search field, the replace field (toggleable), and toggles for case, regex and whole word,
    showing the current state.
  - It shows the match count ("3 of 17") and has prev/next buttons, replace, replace all, and close.
  - It takes focus, and Escape returns focus to the editor.
  - Opening it pre-fills the field from the selection (single line) or the word at the cursor.
  - Typing in the field searches incrementally, debounced about 50 ms, and the current match scrolls into view.
- **On phones:** the panel works with the soft keyboard. Its field is a normal Compose text field, which keeps smart
  punctuation off only if you decide it should (search strings are code, so turn smart quotes off for this field too;
  document it). The buttons are big enough to tap (48 dp).
- **Tests:**
  - every command
  - replace all as one undo step
  - regex replace with groups
  - incremental search with a debounce
  - reveal inside a fold
  - panel focus and Escape
  - selectMatches producing N cursors, followed by typing at all of them
  - `Mod-d` behaviour matching CM6
- [x] Commit: `feat(editor-plugins): search & replace plugin with panel`.

### Task 3: The mobile accessory bar (only if Ahmet approves; the controller will say) — NOT BUILT (no approval yet)
- **The bar:** above the soft keyboard on phones and tablets without a hardware keyboard, shown while the editor has
  focus. Buttons: Undo, Redo, Tab, Shift-Tab, ←, →, ↑, ↓, and Find (opens the search panel).
- **Style:** match the terminal's accessory bar (`apps/terminal-compose/.../TerminalAccessories.kt`) and its
  hide-keyboard button (commit `8b0a6653`).
- **Buttons:**
  - They run editor commands; they don't send key events.
  - They never steal focus from the hidden field, so the keyboard stays up.
  - They repeat when held (arrows, Backspace-like).
- **Where it lives:** in `editor-compose`, as an optional `EditorAccessories` composable the host places. On by
  default in the sample.
- **Tests:** the buttons run commands; the field keeps focus; the keyboard stays up (UI harness plus an emulator check).
- [ ] Commit: `feat(editor-compose): mobile accessory bar`.

### Task 4: Sample, devices, docs
- [x] Wire search (and the accessory bar, if built) into `editor-sample`. Reinstall on devices and restart the samples.
- [x] Write a checklist for Ahmet, for the controller to relay:
  - `Mod-f` and the panel, regex and replace with groups, replace all + undo, `Mod-d`, selecting all matches
  - search inside a fold (it unfolds)
  - on a phone: the panel with the soft keyboard, and the accessory bar
- [x] Write the README and append to `~/.mux/domains/editor.md`. Commit.

## Not in M4b
- **M4c:** LSP. **M4d:** diff + review threads.
- **Project-wide search** across files belongs in the host (`:ui`); the broker already has file search. This plugin
  searches only the open document.
