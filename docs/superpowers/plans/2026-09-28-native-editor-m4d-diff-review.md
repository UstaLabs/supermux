# Native editor M4d: diff (side-by-side + inline) and review threads. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `apps/editor-plugins/diff`. It has two modes over the same model (spec §7 item 7), both working on switch-over day:
- **Side-by-side.** Two linked editors (M3c `LinkedScroll` + `lineMappingFacet`). The base is on the left and
  read-only; the working copy is on the right and editable.
- **Inline (one column).** Replaces today's walkthrough renderer (`EditorEngine.showDiffRegion`). The working text
  has deleted lines shown as block widgets above their position, and added/changed lines are tinted.

Both modes include:
- Line diff, then a character-level diff inside changed lines.
- Unchanged runs folded behind "⋯ N unchanged lines". Expanding reveals ±20 lines per click, like today's cm6
  `diffContextBefore/After = 20`.
- A revert-hunk action from the gutter (userEvent `edit.revert`).
- Next/previous hunk commands.

Review threads (today's `DiffRegionThread`/`DiffRegionComposer` and `onCommentSubmit`, `onReplySubmit`,
`onResolveThread`, `onComposerState`):
- Threads are block widgets under their line, with a 💬 gutter marker.
- Resolved threads collapse to one line and can be expanded.
- Reply and resolve.
- A composer opened from the gutter (tap or click a line's gutter), with the draft persisted through a host callback.
- Threads map through edits.

**Context:** read these first.
- Today's behaviour to match:
  - `apps/android/codemirror/cm6-entry.mjs`, the diff region part around lines 300–540
  - `apps/ui/src/commonMain/kotlin/dev/supermux/ui/editor/engine/EditorBridge.kt` for `DiffRegionRange`/`Thread`/`Composer`
  - `apps/ui/.../editor/WalkthroughView.kt`, `WalkthroughState.kt`, `DiffView.kt`, `DiffState.kt`
- The editor-core/compose/plugin READMEs: `LinkedScroll`, `LineMapping`/`lineMappingFacet`, the widget registry,
  gutter markers, folds and `Replace`, atomic ranges, history (a read-only base drops undo), and theme
  `diff-add`/`diff-remove` classes.
- The sample's `Demos.kt` `applyDiff`, which is a working prototype.
- `~/.mux/domains/editor.md`.

**Data in, events out.** The plugin takes plain data: base text, working text (or a unified patch), threads, and an
optional composer. It emits host events (`onCommentSubmit`, `onReply`, `onResolve`, `onComposerDraft`, `onRevert`) as
callbacks on a `DiffHost` interface. M5 maps `:ui`'s existing callbacks onto that.

**Not pre-verified.** Strict TDD.

## Ground rules
Same as M4c:
- Build only on the Mac, in the private clone.
- Add a sample demo.
- Reinstall on devices and restart the samples at the end.
- Every commit ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 0: M4c review follow-ups
- **`LspDocument` sync timer:** run `scheduleSync` on the client's scope, not the primary view's, so a pending edit
  still reaches the server when the primary view detaches. Test: A types, A detaches, and the server gets the edit
  within the batch delay.
- **Failed sends:**
  - Document in the `LspTransport` KDoc that an adapter must bump `connection` or report DISCONNECTED after a send
    fails.
  - The client stops sending queued messages after a failure until the next connection.
- **`RangeSet.map` crash:** a zero-width `Decoration.Mark` that is exclusive at both ends throws `invalid range`
  when text is inserted at its point. Drop empty non-point marks, or clamp them.
  - Add a randomized `RangeSet.map` property test to core: ranges and edits compared against a freshly sorted set,
    and `between()` compared against a full scan.
- [ ] Commit: `fix(editor-core,lsp): M4c review follow-ups`.

### Task 1: The diff engine (pure)
- **Line diff:** Myers or histogram diff over lines.
  - It must be robust and fast: 10k-line files with 1k changes in < 50 ms on the JVM.
  - It has a cutoff for pathological inputs (degrade to a coarse diff instead of hanging).
- **Character diff inside changed line pairs:** word-level first, then char-level, with a cost cap.
- **Output:**
  - `DiffResult(hunks: List<Hunk>, lineMapping: LineMapping, charChanges)`.
  - `LineMapping` is the editor-core type from M3c.
- **Incremental recompute:** when the working copy is edited, re-diff only affected regions where possible.
  Otherwise re-diff in a background/sliced job, and never block the UI thread on large files. Use the M4b
  `SearchRunner`-style slicing on wasm.
- **Tests:**
  - random diffs, where applying the hunks to the base must yield the working copy
  - char diffs
  - pathological cases (all lines identical, all lines different, very long lines)
  - performance
- [ ] Commit: `feat(editor-plugins): diff engine (line + char, sliced recompute)`.

### Task 2: Inline mode
- Decorations on the working-copy editor:
  - `diff-add` / `diff-change` line styles and char-level marks.
  - Deleted runs as a block widget (`diff:deleted`) that shows the old lines, read-only and tinted.
  - Gutter markers (`diff-add`/`diff-remove`/`diff-change` bars).
- **Unchanged runs** longer than 2×context fold behind a `Replace` widget "⋯ N unchanged lines". Buttons expand
  ↑20 / ↓20 / all.
  - Expanded state survives thread-only updates. This is today's region-identity rule: expand-context resets only
    when the slice changes.
- **Read-only walkthrough mode** (today's walkthrough renders read-only). Option: `editable = false`.
- **Commands:**
  - `nextHunk` / `prevHunk` (CM6 merge: `Alt-ArrowDown`/`Up`? Check; VS Code uses `F7`/`Shift-F7`; pick one and document it).
  - `revertHunk`.
- **Tests:** decorations match the hunks, expand logic, revert as one undo step, and next/prev.
- [ ] Commit: `feat(editor-plugins): inline diff mode`.

### Task 3: Side-by-side mode
- **API:** `DiffPair(base: EditorView, working: EditorView)` sets up both views.
  - The base is read-only, with the history plugin excluded.
  - Tints and char marks on both sides.
  - `lineMappingFacet` on B, so M3c alignment pads from measured heights.
  - Folded unchanged runs on both sides in sync: expanding one expands the other.
- **Recompute:** edits in the working copy recompute the diff (Task 1) and update the mapping after every edit.
  Alignment must stay correct.
- **Gutter:** revert arrows sit in a "merge" gutter between the panes (or in B's gutter), and a click reverts that
  hunk into B.
- **Tests:**
  - alignment within 1 px after edits, including threads on one side
  - fold sync
  - revert
  - scrolling performance for two 10k-line files with 500 hunks (p95 frame < 16 ms on the JVM)
- [ ] Commit: `feat(editor-plugins): side-by-side diff mode`.

### Task 4: Review threads
- **`ReviewThread(id, line, status, comments)`** as data from the host.
  - Each thread is a block widget (`review:thread`) under its line, with a 💬 gutter marker.
  - Resolved threads collapse to a one-line summary, and a tap expands them. The "force-expanded" set is kept
    per view.
- **Composer:** a block widget (`review:composer`), opened by clicking or tapping a line's gutter, or with a
  "comment" command at the caret.
  - It is a text field (smart punctuation ON: it's prose, not code).
  - Submit/cancel.
  - Its draft goes to `DiffHost.onComposerDraft(line, text)`.
  - It's restored from `ReviewComposer(line, draft)`.
- **Reply / resolve** buttons call the host callbacks.
- **Mapping:** threads map through working-copy edits and stay anchored.
- **Both modes:** threads work in inline and in side-by-side mode (on the working side).
- **Phones:** the soft keyboard in the composer, then focus returning to the editor. This relies on M3c's widget
  input rules.
- **Tests:**
  - render and collapse
  - composer submit, draft persist, and restore
  - reply and resolve
  - mapping through edits
  - a thread update doesn't reset expanded context
  - focus behaviour
- [ ] Commit: `feat(editor-plugins): review threads in diff views`.

### Task 5: Sample, devices, docs
- [ ] Replace the sample's hand-made `applyDiff` demo with the plugin, in both modes. Add a demo "walkthrough" (inline
  read-only with 2 threads and the composer) and a side-by-side demo (editable).
- [ ] Reinstall on devices and restart the samples.
- [ ] Write the checklist for Ahmet (relayed by the controller):
  - both modes
  - expanding context
  - revert
  - next/prev hunk
  - threads: open composer, type, submit, reply, resolve, collapse/expand
  - on a phone, with the soft keyboard
- [ ] Write READMEs and append a dated entry to `~/.mux/domains/editor.md`. Commit.

## After M4d
- **M5:** the `:ui` integration (EditorEngine → Editor, `LspBridge` → `LspTransport`, `DiffView`/`WalkthroughView` →
  the diff plugin), the parity checklist, the full device pass, and the cutover.
