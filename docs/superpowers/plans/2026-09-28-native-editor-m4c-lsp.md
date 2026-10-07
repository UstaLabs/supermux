# Native editor M4c: LSP, plus the tooltip, autocomplete and lint layers it needs. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Match today's CM6 LSP features over the **existing broker LSP channel**, with no broker changes. Today's
feature set (`apps/android/codemirror/cm6-entry.mjs`, lines ~62-76 and ~720-860, from `@codemirror/lsp-client`) is:
- document sync (didOpen and didChange, incremental)
- diagnostics
- completion, triggered while typing and on trigger characters `. : " ' \` < / @ #`
- hover tooltips
- signature help
- format (formatKeymap)
- rename (renameKeymap)
- jump to definition (jumpToDefinitionKeymap)
- find references (findReferencesKeymap)

The surface doesn't have the building blocks for this yet, so M4c also adds three generic layers:
1. **Tooltips** (`editor-compose`): a tooltip facet (data: position, above/below, content key) and a popup layer that
   positions content through `coordsAtPos`. It stays inside the editor bounds, avoids the soft keyboard, and closes on
   scroll/edit as configured.
2. **Autocomplete** (`apps/editor-plugins/autocomplete`): CM6 `@codemirror/autocomplete` behaviour.
   - Completion sources (suspend, cancellable) and a popup list.
   - Keyboard: ↑/↓ to move, Enter/Tab to accept, Escape to close, `Ctrl-Space` to open.
   - Filtering/fuzzy match with match highlighting, and a detail/documentation side panel.
   - Accepting applies `textEdit` or `insertText`, including snippets (`$1`, `${1:foo}`, `$0` tabstops, with Tab
     moving between fields). Snippets can be a follow-up only if they get too big; if so, say so in the report.
   - Accept uses userEvent `input.complete` (the M4a lsp contract).
   - Touch: tap to accept, and the list scrolls.
3. **Lint** (`apps/editor-plugins/lint`): CM6 `@codemirror/lint` behaviour.
   - Diagnostics as data: range, severity, message, source, actions.
   - Squiggle marks (`lint-error/warning/info/hint`).
   - Gutter markers.
   - A hover tooltip showing the message(s).
   - `nextDiagnostic` (`F8`).
   - A diagnostics panel (`Mod-Shift-m`).
   - Diagnostics map through edits.

Then **`apps/editor-plugins/lsp`** builds the client on those layers.

**Context:** read these first.
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/LspBridge.kt`: the host side, with `lspStatus`,
  `lspOpen`, `lspRpcOut`, and `LspRpcIn` frames per session and server.
- `src/core/lsp/bridge.ts`, `server.ts`, `framing.ts`: the broker is a JSON-RPC pipe.
- The editor-compose README and every plugin README. They cover the userEvent contract, `lsp` = server-initiated,
  `input.complete` / `edit.rename` / `edit.codeAction` / `edit.format` recorded, panels, widgets, reveal, and history.
- `~/.mux/domains/editor.md` and `~/.mux/domains/claudemux.digest.md` (LSP broker notes).

**Transport boundary.** The plugin talks to an interface:
```kotlin
interface LspTransport { suspend fun send(message: String); val incoming: Flow<String>; val status: StateFlow<LspConnState> }
```
The host adapts `LspBridge` to this in M5. The sample gets a **fake in-process LSP server** that implements
completion, hover, diagnostics, definition, references, rename, format and signatureHelp on a toy language. This keeps
M4c testable end to end without the broker. The sample should *also* connect to a real server through a local stdio
adapter on the Mac (e.g. `kotlin-language-server` or `typescript-language-server`, if one is installed there). If none
is installed, record that, and M5 validates against the broker.

**Not pre-verified.** Strict TDD.

## Ground rules
Same as M4b: Mac-only builds in the private clone; wire everything into `editor-sample`; reinstall on devices and
restart the samples at the end; every commit ends with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Tooltip layer (`editor-compose`)
- `Tooltip(pos, above = false, key: WidgetKey, strict = true)` as data, delivered through `tooltipsFacet`. Content
  comes from the widget registry (`tooltip:<type>`).
- Placement:
  - Flip above/below to fit the editor bounds.
  - Clamp horizontally.
  - Never cover the caret line.
  - Account for the soft keyboard via the window insets.
- Hover support: a `hoverTooltip(source)` helper. Mouse hover with a delay (CM6: 300 ms). Touch: long-press on
  a symbol? No, long-press selects. Use a tap on a word **while nothing is selected and the keyboard is up**? Too
  magic. Instead add an explicit "show hover" command, and show hover info in the LSP flows on touch: completion
  docs, signature help. Document the choice.
- Tests: placement flips at the edges; the tooltip closes on scroll, edit, or Escape; the hover delay works.
- [ ] Commit: `feat(editor-compose): tooltip layer`.

### Task 2: Autocomplete plugin
- The CM6 feature set described above: sources, a state field, and a popup via the tooltip layer, anchored at the
  completion start.
- Filtering: CM6's FuzzyMatcher semantics.
- Sorting: boost, then score, then label.
- Performance: a completion list of 5,000 items filters in under 5 ms per keystroke on the JVM.
- Cancellation: typing cancels in-flight source calls.
- Soft keyboard: accepting a completion keeps the keyboard up. On iOS, check that autocorrect doesn't fight the
  inserted completion. The field window gets rewritten after accept.
- Snippets: tabstop fields as a state field of ranges, mapped through edits. Tab and Shift-Tab move between fields.
- Tests:
  - open, filter, navigate, accept, escape
  - trigger characters
  - a snippet with 2 fields plus `$0`
  - multi-cursor accept (CM6 applies it to all ranges when possible)
  - undo of an accept is one step
  - the soft-keyboard accept path via `typeText`
- [ ] Commit: `feat(editor-plugins): autocomplete`.

### Task 3: Lint plugin
- `setDiagnostics(state, list)` as an effect. A field holds the diagnostics as a `RangeSet`, mapped through edits.
- Squiggle marks (theme classes), gutter markers, a hover tooltip (Task 1), `F8`/`Shift-F8`, and the panel
  (`Mod-Shift-m`) listing the diagnostics. Clicking one moves the caret there (revealing it if it's folded).
- Actions: rendered as buttons in the tooltip. They run through a callback supplied by the source (LSP code actions
  in Task 4), with userEvent `edit.codeAction`.
- Tests: mapping through edits, severity ordering, the panel, next/prev, and a diagnostics update replacing the old
  diagnostics.
- [ ] Commit: `feat(editor-plugins): lint`.

### Task 4: LSP client plugin (`apps/editor-plugins/lsp`)
- JSON-RPC over `LspTransport`: request/response ids, notifications, cancellation (`$/cancelRequest`), and timeouts.
  Use `kotlinx.serialization` JSON, which is already in the catalog. Only the message types we use are modelled.
- Lifecycle:
  - `initialize` sends client capabilities for the features below, plus `positionEncoding` negotiation: prefer
    **utf-16** (our native unit).
  - Then `initialized`, `textDocument/didOpen` (languageId from the syntax registry), incremental `didChange`
    (batched per ~50 ms; `ChangeSet.iterChanges` → LSP ranges), `didClose`, and `shutdown`/`exit` on dispose.
  - Server-initiated `workspace/applyEdit` is applied with userEvent `lsp` (not recorded, not policed).
- Features:
  - `publishDiagnostics` → the lint plugin.
  - `completion`, including `completionItem/resolve` for docs → an autocomplete source. Trigger characters come from
    the server capabilities, falling back to today's list.
  - `hover` → a hover tooltip, rendering markdown as plain text or basic styling. The M5 host may supply a markdown
    renderer.
  - `signatureHelp` → a tooltip above the caret while inside a call.
  - `definition` → the same file: move and reveal. Another file: `onNavigate(uri, range)` host callback.
  - `references` → a panel listing results. Clicking one navigates (same file or host callback).
  - `rename` (`F2`) → a small inline panel/prompt, then `WorkspaceEdit`. Same-file edits get userEvent
    `edit.rename`; other-file edits go through the host callback.
  - `formatting` (`Shift-Alt-f`, matching CM6 formatKeymap) → userEvent `edit.format`.
  - `codeAction` → lint actions.
- Keys: match CM6 exactly (check `@codemirror/lsp-client`'s keymaps: F12, Shift-F12, F2, Shift-Alt-f / Mod-Shift-i?).
- Position conversions: LSP line/character (utf-16) ↔ Rope offsets, O(log n). Test with emoji and Turkish text.
- Tests, against a scripted fake server in the tests:
  - initialize negotiation
  - sync correctness: random edits, with the fake server's document equal to ours after every batch
  - each feature
  - cancellation on typing
  - server errors and timeouts never crash
  - a reconnect after the transport drops (resend didOpen)
  - a stale response for an old document version is ignored
- [ ] Commit: `feat(editor-plugins): LSP client`.

### Task 5: Sample, devices, docs
- [ ] `editor-sample`:
  - The fake LSP server is wired to the Kotlin sample. It gives completions on `.`, diagnostics on a marker word,
    hover on identifiers, go-to-definition within the file, and rename.
  - If a real LSP server exists on the Mac, add a desktop-only "real LSP" toggle (stdio adapter, desktop sample only).
- [ ] Reinstall on devices and restart the samples.
- [ ] Write a checklist for Ahmet (relayed by the controller). On desktop and on the phone, with the soft keyboard:
  completion popup, accept, snippet fields, hover, signature help, F8 diagnostics and the panel, F12, Shift-F12,
  F2 rename, format, a code action from a diagnostic.
- [ ] READMEs, and append a dated entry to `~/.mux/domains/editor.md`. Commit.

## Not in M4c
- M4d: diff and review threads.
- The host adapter from `LspBridge` to `LspTransport`, and cross-file navigation, are M5.
