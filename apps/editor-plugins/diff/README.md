# editor-plugins/diff

The diff views (spec §7 item 7): CM6's `@codemirror/merge` in Kotlin on the public API. It has two
modes over one model, a line diff with a character diff inside changed lines, and review threads.
Plan: `docs/superpowers/plans/2026-09-28-native-editor-m4d-diff-review.md`.

```kotlin
// Inline (one column; today's walkthrough renderer): the working copy against its base.
val view = EditorView(EditorState.create(working, extensions = extensionOf(
    inlineDiff(base, DiffConfig(editable = false, context = 20), host), review(host), history(),
)))
InlineDiffEditor(view)                        // Editor(readOnly = !editable) + the widgets

// Side by side: two views, linked. A is read-only (create it WITHOUT history()).
val pair = DiffPair(EditorView(EditorState.create(base)), EditorView(EditorState.create(working, extensions = extensionOf(history(), review(host)))), DiffConfig(), host)
SideBySideDiff(pair)
```

**Data in, events out.** The host gives plain data: the base and working texts (`Diff.load`,
`DiffPair.load`; a git patch instead of the base: `UnifiedPatch.base(working, patch)`), the threads
(`Review.setThreads`) and a composer (`Review.setComposer`). Everything the user does comes back
through `DiffHost` (`diffHostFacet`). M5 maps `:ui`'s walkthrough callbacks onto it.

| `DiffHost` | when | today's CM6 bridge |
|---|---|---|
| `onCommentSubmit(line, text)` | the composer's Comment / Cmd-Ctrl-Enter (text trimmed, never empty) | `onCommentSubmit` |
| `onReply(threadId, text)` | a thread's Reply / Cmd-Ctrl-Enter | `onReplySubmit` |
| `onResolve(threadId)` | a thread's Resolve | `onResolveThread` |
| `onComposerOpen(line)` | a tap on a line's `comment` gutter cell, the `review.comment` command | `onDiffLineClick` |
| `onComposerDraft(line, text)` | every change of the composer's text | `onComposerState(line, text)` |
| `onComposerClosed()` | Cancel / Escape | `onComposerState(0, "")` |
| `onRevert(hunk)` | after a revert (the edit is done) | none |

Lines are 0-based lines of the working copy (the host's 1-based line − 1).

## The engine (pure)

- `LineDiff.diff(a, b, DiffOptions)`: `DiffResult(hunks, coarse)` with editor-core's `LineMapping`
  (`lineMapping`). `DiffHunk(aFrom, aTo, bFrom, bTo, chars)`: sorted, never touching (at least one
  pair of equal lines between two).
- Algorithm: **patience** (the lines unique on both sides of a region anchor it, their longest
  increasing run pairs them: git's `--patience`, readable diffs of code), trimmed prefix and suffix,
  then **Myers's O(ND)** inside every gap between anchors and wherever no line is unique (the
  shortest edit script there). A region with nothing in common is one hunk, exactly.
- **Cost cap** (`DiffOptions.maxCost`, 2M): Myers's steps × region size per region; past it the
  region is ONE coarse hunk (`DiffResult.coarse`): 20k random lines over a two-line alphabet take
  ~2 ms instead of hanging.
- **Characters** (`CharDiff`), inside changed runs of both sides (≤ 400 lines, ≤ 20k chars, ≤ 400k
  steps): words first (letters, digits, `_`; runs of spaces; any other character alone), then
  characters inside a replaced pair of short runs (≤ 64) when more is common than not
  (`count` → `counter` is `er` inserted; `foo` → `bar` stays one replacement), and changes split by up
  to three spaces merged. A run over 70 % changed on both sides is **rewritten**: no character marks
  (they would only fragment around the few spaces in common). `CharChange` offsets are RELATIVE to
  the hunk's text on each side (its lines joined by `\n`), so edits elsewhere never move them.
- **Incremental** (`Splice`): an edit of B re-diffs only the region it touches, grown to every hunk
  it touches (its edges are equal lines that pair on both sides; the hunks after it move by the
  line delta). A region over 4,000 lines is one coarse hunk at once (the line mapping stays right)
  and the whole diff is redone in the background.
- **Off the UI thread** (`DiffJobs`): a worker thread on the JVM, Android and iOS; on the web slices
  of ≤ 4 ms with a real macrotask between them (a MessageChannel message: kotlinx-coroutines' JS
  dispatcher runs 16 queued tasks per event-loop turn, so `yield()` alone does not let the page
  paint; search's SearchRunner rule).

## The model (both modes)

`Diff.model(state)`: `DiffModel` in the working copy's state (B): the base, the hunks aligned with
B's text, what the user expanded, `ready` (a diff exists) and `pending` (a background re-diff is
due). `DiffConfig`:
- `context` (3; today's walkthrough: 20) equal lines kept around every change; longer unchanged runs
  fold behind **"⋯ N unchanged lines"** (an atomic `Replace` with the `diff:collapsed` widget: ↓ N /
  ↑ N / all, `expandStep` 20; ↑ reveals the lines just above the next change, ↓ those just below the
  previous one). Runs under 3 lines are shown. **Expanded runs survive edits** (mapped) and thread
  updates; a new slice (`Diff.load`, `Diff.setBase`, `DiffPair.load`) folds everything again
  (today's region-identity rule). A search match or a definition inside a folded run opens it
  (`revealFacet`); a delete into one opens it too (the surface's unfold-first policy).
- `syncLines` (3,000): a whole diff of at most that many lines (both sides) is computed IN the
  transaction; a bigger one by the view plugin's worker (`recomputeDelayMs` after the typing settles,
  0 for the first). `offThread = false` runs it on the view's scope (tests on a virtual clock).
- `editable` (true): the working copy may be edited and the revert arrows are offered. False: the
  read-only walkthrough (`InlineDiffEditor` passes `Editor(readOnly = true)`).

**Revert** (`Diff.revert(target, hunk)`, `Diff.revertHunk` at the cursor, the ↶ arrow in the
`revert` gutter column): the base's lines back in place of the hunk's, ONE transaction with
userEvent `edit.revert` (one undo step; a read-only view drops it); then `DiffHost.onRevert`.

**Next / previous change**: `Diff.nextHunk` / `prevHunk` (wrapping), bound to **F7 / Shift-F7**
(VS Code's diff review) and **Alt-F5 / Shift-Alt-F5** (VS Code's "next change"). CM6's merge view
binds none. The sample has chips for phones.

## Inline mode

`inlineDiff(base, config, host)` on the working copy's state:
- `diff-add` (an inserted line) and `diff-change` (a line of a replaced run) line tints,
  `diff-add-text` on the characters that changed, for the lines around the viewport
  (`EditorViewport`: a big file costs what the screen shows).
- The deleted lines: a block widget `diff:deleted` ABOVE their place (a deletion at the end: below
  the last line), read-only, tinted `diff-remove`, the removed characters `diff-remove-text`, one row
  per line at the editor's line height, tabs expanded; over 400 lines, "show N more".
- Gutter: `diff` bars (`diff-add`, `diff-change`, `diff-remove` on the line under a deletion) and the
  `revert` arrows (`diff-revert`, editable only).

## Side-by-side mode

`DiffPair(base, working, config, host)` sets both views up with `StateEffect.appendConfig`
(editor-core, CM6's): B gets the model and `lineMappingFacet` (M3c's linked views then pad the rows
from both sides' MEASURED heights: wrapping on one side, zoom, threads on B), A the base side:
`diff-remove` tints and `diff-remove-text` marks and the same folded runs, fed from B's model after
every B transaction. Expanding a run on either side expands both (A forwards to B). The revert
arrows are in B's gutter. `SideBySideDiff(pair)`: A (read-only) left, B right.

⚠️ **Create the pair, and push the host's data (`Review.setThreads`, `setComposer`, `Diff.load`),
OUTSIDE composition** (a `DisposableEffect` / `LaunchedEffect`, a host's own code), never inside a
`remember {}`: those dispatch, and a dispatch made while the composition runs is written in the
composition's snapshot; a dispatch from elsewhere in the meantime (the syntax worker's) wins and the
composition's is lost (the sample's walkthrough lost its threads that way).

## Review threads

`review(host, ReviewConfig)` on the working copy (inline, or B side by side):
- `ReviewThread(id, line, resolved, comments)`: a block widget `review:thread` under its line and a
  `comment` marker (a speech bubble). Resolved: one line, "✓ resolved · N replies", until tapped (the
  expanded set is per view, `Review.toggleExpanded`). Open: the comments (author "Agent" / "You" / a
  name, time, body), a reply field + Reply, Resolve.
- Threads follow edits of the working copy; a host update keeps a thread where the edits moved it
  when the host still gives the line it gave before (a new reply), else moves it to the new line.
- **The composer** (`review:composer`): a tap on any line's `comment` gutter cell (the column is
  always there while `canComment`: an invisible `comment-add` marker keeps it), or `Review.comment`
  at the caret. It scrolls into view and takes the focus (on a phone the keyboard rises), its draft
  is in the state (a widget scrolled far away comes back with it) and goes to `onComposerDraft` as
  typed. Cmd/Ctrl-Enter or Comment submits, Escape or Cancel closes; the focus goes back to the
  editor. `Review.setComposer(ReviewComposer(line, draft, focus))` (today's rule): null closes it,
  the line of the open one keeps what is typed (the host's draft is taken only while the open one is
  empty: a gutter tap, then the host hands back the draft it kept), another line opens there.
  `focus = false`: a composer the host shows on its own does not take the focus.
- Prose, not code: the fields use the platform face and keep iOS Smart Punctuation (no
  `codeTextInput`). Their buttons never take the editor's focus (taps, not `clickable`).

## Parity with today's walkthrough (CM6 `cmShowDiffRegion`)

- Today the host sends the file, 1-based ranges it derived from a unified diff and the deleted lines;
  now it sends the base (or `UnifiedPatch.base`) and the plugin diffs. Today only the step's authored
  range is decorated; now the whole file's changes are.
- Today the slice is the changed range ± 20 lines with ONE ↑ and ONE ↓ button outside the editor;
  now every unchanged run folds with its own expander inside the text (context 20 for parity).
- Today a click anywhere in a line opens the composer; now the `comment` gutter cell (a tap in the
  text places the caret) or the comment command.
- Today's change colour is amber and the gutter shows `+ − ±` glyphs; now amber lines with green
  character marks and drawn bars (the web has no glyph font), plus revert arrows when editable.
- Horizontal-wheel paging between walkthrough steps (`onDiffPage`) stays the host's (M5).
- The composer's draft and the expanded threads survive a threads-only update, as today.

## Performance (Mac JVM, shared, `DiffPerfTest` / `SideBySideUiTest`)

| | measured | budget |
|---|---|---|
| 10k lines, 1k changes, warm | 5 ms (line diff alone 1.8 ms) | 50 ms |
| the same, first diff in a cold JVM | 45-110 ms (runs on the worker: `syncLines`) | |
| a keystroke's re-diff (10k lines, 836 hunks) | 0.02-0.3 ms | |
| a keystroke's whole dispatch in a side-by-side pair (10k lines, 452 hunks, a thread: B's splice and decorations, the mapping, A's push) | p95 2.6 ms | |
| 20k lines over a two-line alphabet (the cost cap) | 1-2.5 ms, coarse | never hangs |
| side-by-side scroll, 2 × 10k lines, 457 hunks, 1400×1200 @2x | p95 10.5-12.7 ms (the same linked editors without the diff: 9.8-13.4) | 16 ms |
| web (headless Chrome): 10k lines / 1k changes in slices | page held ≤ 5.5 ms | one frame |

## Tests

`./gradlew :editor-plugins:diff:jvmTest` (on the Mac): the engine (random diffs apply to the working
copy, char diffs, pathological inputs, the incremental splice through 300 random edits, the patch),
the inline and side-by-side models, review threads (commonTest; also `iosSimulatorArm64Test` and
`wasmJsBrowserTest`), and composed editors (`jvmTest`: the widgets, expanders, alignment within 1 px
through scrolls, edits, expansions from either side, one-side wrap and a thread on B, the composer's
focus, submit, Escape and a restored draft, the scroll budget, PNGs under `build/diff-renders`).
