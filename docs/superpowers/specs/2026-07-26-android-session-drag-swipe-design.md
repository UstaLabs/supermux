# Android Session Drag and Swipe Design

## Goal

Make Android session reordering reliable in both flat and grouped views, and replace immediate swipe execution with a safer reveal-then-tap interaction.

## Confirmed Problems

- In flat mode, a long press raises the row and lets it follow the finger, but neighboring rows do not move and the new order is not persisted.
- In grouped mode, session rows are nested inside a single lazy-list item and have no reorder wiring.
- A normal horizontal gesture can immediately execute an action. During reproduction, it opened the settle confirmation dialog without a separate action tap.
- `SwipeToDismissBox` models dismissal, not a stable open action tray, so its current confirm-and-snap-back behavior does not match the desired interaction.

## Interaction Design

### Reordering

- A long press anywhere on an eligible row starts the drag.
- Pickup emits haptic feedback and raises the row with elevation.
- Neighboring rows animate out of the way as the dragged row crosses them.
- Reordering is limited to the row's current project and task-status section.
- Personal assistants, offline cached sessions, and settled sessions are not reorderable.
- The displayed order changes locally and synchronously during the gesture.
- Releasing the row persists the final ordered IDs once.
- Cancelling the gesture restores the pre-drag order and does not call the reorder endpoint.
- Dragging near the viewport edges auto-scrolls the list.

### Swipe actions

- A horizontal swipe reveals an action tray beneath the row.
- Releasing the swipe leaves the tray open at a fixed action width.
- Swipe distance and velocity never execute an action.
- The user must tap a revealed button to run it.
- Opening another tray closes the previous tray.
- Tapping outside, opening the row, starting a drag, or beginning vertical scrolling closes the open tray.
- Action buttons have icons, text labels, accessible descriptions, and at least 48 dp touch targets.

The existing action mapping remains:

| Session section | Start-side action | End-side action |
| --- | --- | --- |
| In progress | Mute / Unmute | Settle |
| Draft | Edit | Discard |
| Settled | Activate | None |

Settle and discard continue through the existing confirmation dialog. Mute, unmute, edit, and activate run after an explicit button tap.

## Architecture

### Flattened display model

`SessionListScreen` will build a display-item list containing headers, toggles, group chrome, session rows, settled controls, offline rows, and bottom spacing. Session rows will be top-level `LazyColumn` items in both flat and grouped modes.

Grouped cards will keep their current visual appearance by deriving each row's top, middle, bottom, or single position and applying the appropriate container shape and dividers. They will no longer hide several reorderable rows inside one lazy-list item.

Each reorderable row key will encode its project, section, and session ID. A pure helper will validate that a proposed move stays within the same reorder scope and return the next ordered IDs.

### Drag state

The screen will own a local working order while dragging. The reorder library callback will update this state before returning, as required by the library contract. Network persistence will be separated from movement and invoked only from drag-stop when the order changed.

The whole-row long-press modifier will wrap the row's visual content. Starting a drag will close any open swipe tray and disable horizontal swipe handling until the drag ends.

### Swipe state

The dismiss component will be replaced with a row-level anchored horizontal offset:

- closed anchor at zero;
- one open anchor for each available action side;
- fixed reveal width based on the action tray;
- horizontal direction locking after touch slop;
- no action associated with reaching an anchor.

The list screen will hoist the currently open session ID so only one tray can remain open. Button taps invoke the existing callbacks, close the tray, and then show any existing confirmation UI.

## Error Handling

- A reorder persistence failure restores the authoritative order on the next server snapshot and surfaces a concise failure message.
- A swipe-action callback failure follows the existing action-specific behavior; the tray closes after the explicit tap.
- If a session changes section or disappears during a drag, the gesture is cancelled and no stale order is persisted.
- Reorder requests contain only IDs from one validated reorder scope.

## Testing

Tests will be added before production changes:

- pure tests for display-item keys and reorder-scope validation;
- pure tests proving movement is synchronous, cross-section/project moves are rejected, cancellation restores order, and drop persists once;
- pure tests for swipe action mapping by session section;
- UI tests proving a swipe reveals but does not execute, an action executes only after a button tap, only one tray stays open, and long-press drag reorders rows;
- existing shared and Android unit tests;
- emulator verification in flat and grouped modes, including edge auto-scroll and action confirmations.

## Acceptance Criteria

- Flat and grouped session rows visibly reorder under the finger.
- The final order remains after the list refreshes.
- Reordering cannot cross project or task-status boundaries.
- Long-press drag and horizontal swipe do not trigger each other.
- Swiping never invokes mute, settle, edit, discard, or activate by itself.
- Revealed actions remain open until tapped or dismissed.
- Destructive actions still require confirmation.
- TalkBack exposes the row and each revealed action with meaningful labels.
