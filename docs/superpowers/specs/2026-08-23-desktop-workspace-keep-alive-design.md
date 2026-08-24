# Desktop Workspace Keep-Alive Design

## Problem

The Compose desktop shell renders only the selected workspace. Selecting a session in another workspace removes the previous workspace from composition, disposing its remembered UI state and heavyweight children. Returning to it therefore reloads the workspace and loses transient state such as scroll positions, terminal scrollback, editor state, and in-flight view state.

Keeping every visited workspace composed would preserve that state but would allow terminals, browsers, watchers, and other workspace resources to grow without a bound.

## Decision

Keep the ten most recently viewed workspaces composed in an in-memory least-recently-used cache. The active workspace counts toward the limit, so at most nine inactive workspaces remain warm beside it.

The cache is keyed by workspace ID, not session ID. Selecting another session that belongs to the same workspace neither creates a second cache entry nor recreates the workspace.

## Architecture

Add a small, pure workspace-retention model that owns an ordered set of workspace IDs:

- Selecting a workspace removes its existing entry and appends it as most recently used.
- Entries absent from the current broker workspace list are removed immediately.
- If more than ten entries remain, the least recently used entries are evicted.
- The selected workspace is always retained when it exists in the broker list.

Add a Compose host around the workspace content. It renders each retained workspace under `key(workspaceId)` so every workspace keeps a stable composition identity. The existing desktop `KeepAlivePanel` controls visibility: the active workspace receives the available size, while inactive workspaces remain composed at `0×0`.

Using `KeepAlivePanel` is required because workspace panes may contain Swing/JCEF children. Alpha or z-order alone cannot hide those heavyweight components, while `0×0` layout bounds prevent hidden workspaces from painting, receiving pointer input, or retaining effective keyboard focus without disposing their terminal/browser instances.

The new-session panel remains the visible detail surface while open, but cached workspace layers remain composed underneath it.

## Active-Only Behavior

Keeping workspaces composed also keeps their effects alive, so behaviors representing what the user is currently viewing must be explicitly gated by workspace visibility:

- Only the active workspace updates the shell's viewing-presence layout and view map.
- Only the active workspace consumes one-shot actions such as external file-open and forced-view requests.
- Drag bounds are cleared when the active workspace changes so hidden layouts cannot leave stale interaction geometry.
- Close confirmation UI is shown only by the active workspace.

Workspace-scoped resources whose purpose is to stay warm, including remembered documents, terminal/browser instances, and editor watchers, remain alive until eviction.

## Eviction and Removal

Opening an eleventh distinct workspace disposes the least recently viewed workspace layer through normal Compose lifecycle cleanup. Revisiting an evicted workspace creates a fresh composition. Broker removal of a workspace prunes and disposes it immediately even when the cache is below ten entries.

The cache is intentionally process-local. It does not persist its LRU order across application restarts; broker-backed workspace layout persistence remains unchanged.

## Testing

Tests will cover:

- The retention model orders entries by recent selection, deduplicates sessions sharing a workspace, enforces the ten-entry limit, and prunes removed workspaces.
- A retained inactive workspace is not disposed or remounted across a switch.
- Remembered/scroll state is still present after switching away and back.
- The eleventh workspace evicts and disposes the least recently viewed layer.
- Hidden workspace content has zero layout bounds, using the existing heavyweight-safe contract.

The focused desktop tests will run first, followed by the full desktop test task and desktop compilation/build verification appropriate to the module.

## Out of Scope

- Persisting transient workspace UI state across application restarts.
- Changing broker workspace/layout storage.
- Keeping more than ten workspaces warm or making the limit configurable.
- Applying the cache to Android, iOS/macOS, or the web client.
