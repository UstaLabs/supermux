# Native editor M4a: the core plugins (history, basics, highlight, fold, view settings). Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first plugins that make this a working editor. Each one is a module under `apps/editor-plugins/*`,
written only against the public `editor-core` / `editor-compose` APIs (spec §7):
1. **history:** undo/redo (the biggest missing piece)
2. **basics+:** line numbers as a plugin toggle, active-line highlight, bracket matching, auto-indent on input,
   selection-match highlight, plus the existing closeBrackets
3. **highlight:** `editor-syntax` wired in as a plugin, with a `SyntaxHost` that owns the worker, and the theme tokens
4. **fold:** fold arrows in the gutter, fold/unfold/fold-all, folds from the syntax tree
5. **view settings:** zoom and wrap as persisted settings, with a plugin-level API for the host

**M4 is split:** M4a (this plan), M4b search/replace, M4c LSP, M4d diff + review threads.

**Context.** Read these before starting:
- `apps/editor-core` and its README.
- `apps/editor-compose/README.md`, which describes all of M3a–M3c, including:
  - the atomic-ranges and reveal/atomicDelete facets
  - the exempt userEvents (`undo`/`redo` pass the atomic check)
  - `EditorAnnotations.remote`
  - the widget registry, gutter markers and panels
- `apps/editor-plugins/basics` and its README: closeBrackets, BlockIndent and the indent commands.
- `apps/editor-syntax/native/README.md`: `Syntax.extension`, `SyntaxWorker`, `Syntax.folds`, `Captures.kt` tokens.
- `~/.mux/domains/editor.md`.

**Parity target:** today's CM6 editor, `apps/android/codemirror/cm6-entry.mjs`. Where CM6 has a behaviour, match it
unless the spec says otherwise.

**Not pre-verified.** Use strict TDD. Each plugin's public surface is an `Extension` value plus named commands.

## Ground rules
Same as M3c:
- Builds run on the Mac only, in the private clone `~/work/native-editor-m3b`, synced from the worktree.
- Put every plugin in `editor-sample` so Ahmet can try it.
- At the end, reinstall on the iPhone, iPad and Fold and restart the desktop and web samples.
- Commits end with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: history (`apps/editor-plugins/history`)
- **Model:** CM6's `@codemirror/commands` history, redone in Kotlin.
  - A `StateField` holds done/undone stacks of events.
  - Each event is inverted `ChangeSet`s + selection before/after + effects that ask to be undoable.
- **Grouping:**
  - Consecutive `input.*` transactions within `newGroupDelay` (500 ms) at adjacent positions join one event.
  - `EditorAnnotations.imeJoinPrevious` always joins.
  - `paste`, `input.drop` and commands start new events.
  - A selection-only transaction doesn't create an event, but is remembered for "undo selection" (`Mod-u`), like CM6.
- **Remote and non-user changes** (userEvent `disk`, `remote`, `agent`, `lsp`, or `EditorAnnotations.remote`) are **not**
  undone by local undo. Undo events are mapped through them, which needs `ChangeSet.map(other)`: operational
  transform against a concurrent change. That was deferred from M1. **Implement it in `editor-core` now, with a
  property test (the OT convergence law, CM6's `mapDesc`/`map`).**
- **Commands:**
  - `undo` (`Mod-z`)
  - `redo` (`Mod-Shift-z`, `Mod-y` on non-Apple)
  - `undoSelection` / `redoSelection` (`Mod-u` / `Mod-Shift-u`)
- **Undo/redo transactions** carry userEvent `undo`/`redo`, which passes the atomic-range policy (M3c).
- **Soft keyboards:** the hidden field's own undo must stay swallowed (M3a) so all undo goes through history. On iOS,
  shake-to-undo is out of scope; document it.
- **Folds:** once history exists, switch the fold delete policy to CM6's delete-the-whole-fold, via `atomicDeleteFacet`
  in the fold plugin (Task 4). Ahmet was asked; if he hasn't answered, **keep unfold-first as the default** and make
  delete-whole a fold-plugin option.
- **Tests:**
  - typing groups; IME composition is one step
  - paste is its own step
  - undo after a remote edit maps correctly (a remote insert before the local edit)
  - multi-cursor undo restores all cursors
  - redo after undo
  - a new edit clears redo
  - undo of a whole-fold delete restores the fold's text
  - the history depth cap: 100 events by default, configurable
  - the `ChangeSet.map` property tests
- [ ] Commit: `feat(editor-plugins): history (undo/redo) with remote-change mapping`.

### Task 2: basics+ (extend `apps/editor-plugins/basics`)
- **Active line:** a `LineStyle` "active-line" on every cursor's line when its range is empty. It replaces the
  surface's built-in current-line highlight. Keep an `EditorTheme` colour for it.
- **Bracket matching:** when the caret is next to a bracket, mark it and its match with a "matching-bracket" mark
  (a "nonmatching-bracket" mark if there is no match).
  - Use the **syntax tree** when available, via an optional hook that `editor-syntax` provides, so brackets in
    strings are ignored.
  - Fall back to a character scan limited to 10k chars.
- **Auto-indent on input:** after typing a closing `}` / `)` / `]` as the first non-blank character of a line,
  re-indent that line to match its opener's line. Tree-based when possible (tree-sitter `indents.scm` exists for
  some languages; start with the opener-line rule, as CM6's `indentOnInput` does).
- **Selection-match highlight:** with a non-empty single-line selection (≥ 2 word chars), mark the other occurrences
  in the visible range. Also mark the word under the cursor after a short delay, as CM6's `highlightSelectionMatches`
  does. Limit it to the viewport.
- **Line numbers:** already built in; add a plugin-level `lineNumbers(enabled)` facet so the host can toggle them.
- **Tests:** each feature, including multi-cursor, emoji, and that a large file never stalls (bounded scans).
- [ ] Commit: `feat(editor-plugins): basics+ (active line, bracket matching, indent on input, selection matches)`.

### Task 3: highlight (`apps/editor-plugins/highlight`)
- A `SyntaxHost` that owns a `SyntaxWorker` per `EditorView`:
  - It calls `onState` after every transaction (use `EditorView.addListener`).
  - It hops the worker's dispatch to the UI thread.
  - It feeds `Syntax.setViewport` from `view.viewport`.
  - It precompiles queries on mount (the web cold start, M3a).
  - It shows "syntax off" through a panel or status when `Syntax.isOff`.
  - It disposes on view dispose.
- **API:** `highlight(language: String?, backend: SyntaxBackend): Extension` plus
  `rememberSyntaxHost(view, backend)` for Compose hosts. `editor-sample` already wires this by hand; move that code
  here and have the sample use the plugin.
- **Theme tokens:** covered by `EditorTheme` (M3a). Add a test that every token class the syntax layer emits has a
  colour in both themes.
- **Tests:**
  - A worker hookup test on JVM: open, type, spans arrive.
  - Dispose frees native handles (`ses_debug_live_trees` returns to 0).
  - A document switch resets the worker.
- [ ] Commit: `feat(editor-plugins): highlight plugin (syntax host + worker wiring)`.

### Task 4: fold (`apps/editor-plugins/fold`)
- **Fold ranges** come from `Syntax.folds` (tree-sitter `folds.scm`) when highlight is on, and otherwise from
  indentation (CM6 `foldService` fallback). The fold ranges are line-based.
- **Fold state:** a `StateField` of folded ranges, as `RangeSet<Decoration.Replace(fold = true)>`, mapped through
  edits. Effects `foldEffect` / `unfoldEffect`.
- **Gutter:** fold-open / fold-closed markers via `gutterMarkersFacet`. A click toggles the fold (via
  `gutterClickFacet`).
- **Commands:**
  - `fold` / `unfold` at the cursor (`Mod-Alt-[` / `Mod-Alt-]`)
  - `foldAll` / `unfoldAll` (`Mod-Alt-0` / `Mod-Alt-J`?). Match CM6's keys; check `cm6-entry.mjs` / `@codemirror/language`.
- **`revealFacet` handler:** unfold when a selection with `scrollIntoView` lands inside a fold (search results,
  go-to-definition).
- **`atomicDeleteFacet`:** the policy described in Task 1. A fold-plugin option `deleteFoldWhole` defaults to false
  until Ahmet decides.
- **Placeholder widget:** the "⋯" chip. A click unfolds.
- **Tests:** fold/unfold via command, gutter and placeholder; folds survive edits outside them; an edit inside a
  fold's range unfolds it (as CM6 does); `foldAll` over a 10k-line file stays under 100 ms; reveal on a search-like
  selection; undo interplay with history.
- [ ] Commit: `feat(editor-plugins): fold (syntax/indent folds, gutter, commands, reveal)`.

### Task 5: view settings (`apps/editor-plugins/view`)
- **Settings as data:** `EditorSettings(fontSize, lineWrap, tabSize, indentUnit, showLineNumbers, theme)` as a facet
  plus compartments, so the host can change them at runtime with `view.dispatch(reconfigure…)`.
- **Zoom:** changes from `onFontSize` persist through a host callback. Match today's editor: 10–24 px, default 13,
  saved per app. Read the current `:ui` behaviour in `apps/ui/.../editor/EditorPanel.kt` / `EditorSettingsScreen.kt`
  so M5 can map it one-to-one.
- **Tests:** reconfigure at runtime changes wrap, tab size and theme without losing state; zoom round-trips through
  the callback.
- [ ] Commit: `feat(editor-plugins): view settings (wrap, zoom, tab size, theme) as runtime-configurable facets`.

### Task 6: sample, devices, docs
- [ ] `editor-sample` uses all the plugins. Add a settings toggle for "delete fold whole".
- [ ] Reinstall on the devices and restart the samples.
- [ ] Prepare a short checklist for Ahmet (the controller relays it): undo/redo on the phone keyboard and a hardware
  keyboard, bracket matching, indent on `}`, selection matches, fold via gutter and commands, fold + undo.
- [ ] READMEs for each plugin module. Append a dated entry to `~/.mux/domains/editor.md`. Commit.

## Not in M4a
- **M4b:** search/replace. **M4c:** LSP. **M4d:** diff + review threads.
