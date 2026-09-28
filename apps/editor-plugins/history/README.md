# editor-plugins/history

Undo and redo for the native editor, as a plugin on the public API: CM6's `@codemirror/commands`
history redone in Kotlin (spec §7: "undo/redo with inverted change sets, grouped by typing bursts").

```kotlin
EditorState.create(text, extensions = extensionOf(history(), basics(), ...))
```

## The model

A state field holds two branches, done and undone. Each event is the step's **inverted
`ChangeSet`**, the effects that ask to be undone with it (`History.invertedEffects`, CM6's
`invertedEffects`), the selection before it, and the selections the user moved through after it.
Undo applies the top event with userEvent `undo` (redo: `redo`), restores the selection and scrolls
it into view; the step moves to the other branch. A new edit clears the redo branch. Each branch
keeps `HistoryConfig.depth` steps (100 by default).

| key | command | CM6 |
|---|---|---|
| `Mod-z` | `History.undo` | same |
| `Mod-y` (Apple: `Mod-Shift-z`), `Mod-Shift-z` | `History.redo` | same; CM6 binds `Ctrl-Shift-z` on Linux only |
| `Mod-u` | `History.undoSelection` | same |
| `Alt-u` (Apple: `Mod-Shift-u`) | `History.redoSelection` | same |

The commands are also `NamedCommand`s in `commandsFacet` (`history.undo`, ...). `History.undoDepth` /
`redoDepth` say how far each can go.

## Grouping

- **Typing and deleting in one burst is one step**: a transaction with userEvent `input`,
  `input.type*`, `input.ime*`, `delete.backward*` or `delete.forward*` joins the previous step when
  that one was typing or deleting too, came within `newGroupDelay` (500 ms), touches it, and no
  cursor move happened in between.
- **An IME composition is one step, past the typing delay**: a step carrying
  `EditorAnnotations.imeJoinPrevious` (the composition's second step; its first character went out
  as plain `input`) always joins, and so does every `input.ime` step after another `input.ime` step,
  until the group is 2 s old or a newline is composed (then a new step).
- **A newline starts a step** (Enter, a soft Return); the line typed after it joins it (CM6's Enter
  is a non-joinable `input`, and typing after it joins).
- **Paste, drop and every other command are steps of their own** on both sides: nothing joins a
  paste and a paste joins nothing (`paste`, `input.drop`, `input.indent`, `delete.cut`,
  `delete.dedent`, a transaction without a userEvent).
- A selection-only transaction is not a step. It is remembered for `undoSelection` (a run of moves
  with the same `select*` userEvent within the delay counts once, CM6's rule), and it ends a typing
  burst.

**The `lsp` userEvent contract.** `lsp` and `lsp.*` mean SERVER-INITIATED edits only (a workspace
edit the language server pushes, `workspace/applyEdit`): history does not record them (never undone
locally; undo steps are mapped through them) and the surface does not police them. Edits the USER
triggers through LSP carry recorded, policed userEvents: `input.complete` (a completion accepted),
`edit.rename`, `edit.codeAction`, `edit.format`. Each is an undo step of its own (never joined with
typing) and meets the atomic-range rules like any local edit. M4c follows this.

## Changes that are not ours

Transactions with userEvent `disk`, `remote`, `agent` or `lsp` (or a sub-event), with
`EditorAnnotations.remote`, or with `History.addToHistory` false are **not recorded, and local undo
never undoes them**. Every event of both branches is **mapped** through such a change instead
(`ChangeSet.map`, operational transform against a concurrent change, in `editor-core`, with the
convergence law as its property test): the newest event maps over the change as it is, and each
older one over the change as seen before the newer ones (`mapping.map(event, before = true)`). So an
undo after a remote insert before the local edit undoes the local edit where it now is, and puts the
caret back where it was, mapped. An event whose text the remote change deleted entirely is dropped,
and so is one whose every change lies INSIDE text the remote change deleted or rewrote (the user
deleted a word, then an agent rewrote the paragraph around it): undoing it would put the word back
into the middle of the agent's output.

Mapping is LAZY, CM6's scheme: only the top event of each branch is mapped, its `mapped` carries
what the events below need, and they are mapped when they become the top; remembered selections
are mapped only when read. An agent streaming edits costs about 0.006 ms per transaction with 100
steps and 200 remembered selections (`HistoryPerfTest`, budget 0.1 ms).

## Soft keyboards

All undo goes through this history. The hidden input field's own undo and redo chords stay
swallowed (editor-compose), because the field's private history knows only its ±200-unit window.
Out of scope: iOS shake-to-undo and the iPad keyboard's shortcut-bar undo/redo buttons (they talk to
the platform text field's own undo manager, not to the editor); an accessory-bar undo button for
soft keyboards comes with the accessory bar.

## Folds

Undo and redo pass the surface's atomic-range rules (`undo` / `redo` are exempt userEvents), so an
undo that restores a fold's text is never refused. With the fold plugin's `deleteFoldWhole` option
(CM6's policy: Backspace at a fold's end deletes the whole fold), undo brings the text back.

## Deliberate differences from CM6

- A transaction without a userEvent (a host's programmatic edit) is a step of its own; CM6 lets it
  join a typing burst.
- `delete.cut` and `delete.dedent` never join (CM6 joins every `delete.*`): commands are steps.
- Nothing joins a paste (CM6 lets typing right after a paste join it).
- Remote / disk / agent / LSP changes are never undone locally (CM6 records every change unless
  `addToHistory` is false; today's CM6 editor even records a reload from disk as an undoable edit).
- A step lying inside text a remote change deleted or rewrote is dropped (CM6 keeps it and would
  re-insert its text).
- An IME group is capped at 2 s and a newline (CM6 joins every `input.type.compose` step).

Tests: `./gradlew :editor-plugins:history:jvmTest` (commonTest: grouping, IME, paste, remote mapping,
multi-cursor, redo, depth, selection undo, keys, folds; jvmTest: typing through a composed Editor's
real hidden field, then Mod-z / Mod-Shift-z as key events) and `:editor-plugins:history:iosSimulatorArm64Test`.
`ChangeSet.map`'s tests are `editor-core`'s `ChangeSetMapTest`.
