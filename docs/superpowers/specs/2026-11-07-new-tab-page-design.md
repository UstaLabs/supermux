# New tab page instead of the "+" menu, and easy splits (#91) — design

Status: approved by Ahmet on 2026-11-07 ("the tabs are now good and it can be merged"), after a
clickable prototype on his Mac (branch `proto/91-new-tab-page`, throwaway, not for merging).

## What changes

Today the "+" at the end of a pane's tab strip opens a drop-down (Chat, Terminal, Files, Changes,
Display), and the only way to make a split is to drag a tab onto a pane edge. Ahmet: *"Instead of
that, we should have an empty tab that has the options of what to show."*

After:

- **"+" opens a New tab** at once, at the end of that pane's tabs, selected. Its page lists the view
  kinds. Picking one turns this tab into that view, in the same place. Esc or × closes it like any tab.
- **Split right / split down buttons**, one pair at the workspace's top-right corner, add a new
  rightmost column or a new bottom row holding a New tab. Picking a view makes it a real pane with that
  view; closing the New tab closes the pane.

## The page (M3 · palette — his pick)

- An eyebrow: **New tab · ‹workspace› · ‹branch›** (branch in the mono face).
- A short list in the app's own chrome, no cards and no frames: per row a thin glyph, the name, a dim
  one-line description, and its key (1–5) on the right. One row is always selected (a subtle fill).
- Rows: Chat "Start a chat with an agent", Terminal "A shell in ‹folder›", Files "Browse and edit
  files", Changes "Review what changed", Display "Watch an agent's screen". The glyphs are drawn for
  the page (one stroke weight, round ends, no fills); no stock icons.
- **Instant.** No entrance, row, selection or opening animation (his call: *"the card animation makes
  the app feel slow; it should be instant"*). The one thing that eases is the glyph's tint on hover /
  selection, which he likes.
- No search or filter: five items do not need one (his call).
- A hint line under the list: "↑↓ to move · ↵ to open · esc to close" (keyboard devices only).

## Behaviour

- **Keys while the page has focus:** 1–5 open that row; ↑/↓ move; Enter opens; Esc closes the New tab
  (the tab shown before it comes back). Digits with ⌘/Ctrl/Alt held do nothing: ⌘1–9 is kept for
  "go to tab N" (#96). Esc is consumed only when it closes something.
- **Files and Changes open in this tab** (his call), not as a split. If one is already open in the
  workspace, its row shows a small green dot and picking it switches to that tab and closes the New tab.
- **Chat** turns the tab into the existing new-chat launcher.
- **"+" again** while that pane has a New tab selects it instead of opening a second.
- **Switching away** leaves the New tab in the strip, unselected, like a browser tab.
- **Picking** keeps the New tab, marked "opening…", until the broker has made the view; the view then
  takes the New tab's place and index. A refused add leaves the New tab there.
- **Split right / split down keys:** ⌘\ and ⌘⌥\ (⌘⇧\ is the editor's go-to-bracket), registered by
  #96's central shortcut system as `workspace.splitRight` / `workspace.splitDown`. This card exposes
  `NewTabsState.splitWorkspace(direction, tree)`, which the buttons call and #96 binds.

## Split buttons

- **One pair for the whole workspace**, fixed at its top-right corner: [split down] [split right] at
  the right end of the top-right pane's strip, thin glyphs, dim until hovered, one click each.
- **Split right** adds a new rightmost column the full height of the workspace; **split down** adds a
  new bottom row its full width (with a bottom row already there, one more row: his pick "A").
- The new pane opens on the New tab page, instantly. A pick fills it; Esc or × closes it.
- Panes opened but not yet picked are drawn in the order he opened them (right, right, down puts the
  row under all three columns). A pick out of that order keeps every pane where it is drawn.

## Where New tabs live

On the client only, per window — never sent to the broker. An unpicked tab is a question, not
workspace state: other devices do not see it, a restart does not bring it back, and no broker,
protocol or older app changes. (The broker refuses a layout naming a view it does not have, so the New
tab cannot be part of the synced layout.)

The pane layer draws it like any tab: WorkspacePanes draws the New tabs (and pending panes) into the
tree it hands PaneHost, and every edit PaneHost makes is replayed on the real tree with them put in
first and taken out after — the same replay rule WorkspaceLayoutState already follows. Select,
reorder and move between panes work. A New tab cannot become a pane on its own by being dropped on a
pane edge, nor torn out into a window; those drags change nothing. A New tab whose pane goes away goes
with it. The state lives in `WorkspacePanesKept` (#89), so a rotation or width change keeps it.

## Phone (Compact) and tablet

- Phone: the tab grid's "+" opens the same page full screen instead of today's bottom sheet, with the
  phone's four kinds (no Chat, as today), touch-sized rows, no key hints and no keyboard popping up.
  Back returns to the grid. No split buttons (no panes on a phone).
- Tablet: the pane tree with touch sizing (taller rows), key hints only with a hardware keyboard.

## Testing

- Pure (jvm): drawing New tabs and pending panes into a layout and taking them out; replaying edits
  around them (select, reorder, move, an edge drop refused, a real tab split off); where a pick lands;
  a pending pane's pick splitting the new view off its anchor while the anchor keeps its tab.
- UI (desktop jvmTest): "+" shows a selected New tab and the page; 1–5 and ↑↓/Enter pick; modified
  digits do nothing; Esc closes; "+" twice; an already-open Files switches; split right opens a pane
  with the page, Esc closes it, a pick fills it.
- Phone: the grid's "+" opens the page; a pick adds the view.
- Real run on the Mac through `~/work/supermux-hotreload` under its lease.
