# Host-wide FileSystemService and the new Files pane

Date: 2026-09-27
Status: design approved in conversation; not implemented
Baseline: supermux `mux/supermux-67` @ `b0c254b5` (contains `dev` @ `2dc52672`)
Visual companion: https://files-pane-design.ustalabs.com (ephemeral; this file is the source of truth)

## 1. Background and decision

The Files pane is slow, never updates, and looks dated. The causes are structural:

- **The broker blocks on every listing.** `FsService.listDir` (`src/core/editor/fs-service.ts`) does
  `readdirSync` + `statSync` per entry, then `execSync git rev-parse` and `execSync git check-ignore`.
  Every folder expand freezes the event loop (chats, terminals, WebSocket).
- **Search walks the whole tree synchronously** (`searchFiles`), including `node_modules` and `.git`,
  with a substring match and the first 20 hits in walk order.
- **The watcher is per session and per socket.** `FsWatcher` is keyed by session name, recursive, and
  started only by `editor_open`. A socket stores a single `_editorCb`; a second `editor_open` overwrites
  it without unsubscribing the first (leak). Two sessions on one folder run two recursive watchers.
- **The app state is per pane.** `ExplorerPaneForWorkspace` does `remember(workspaceId) { ExplorerState() }`,
  so each pane and workspace lists folders separately. `FileTree` puts only the top-level nodes in the
  `LazyColumn`; expanded subtrees render eagerly inside one item. `TreeNode.children` is a plain
  `MutableList`. The tree ignores `fs_changed`.

Decisions made with Ahmet:

1. **Files are addressed by absolute path on a host**, not by workspace, session or worktree.
   A workspace only says which folder its tree starts at.
2. **One `FileSystemService` per host on the broker, and one per host in the app** (in `HostStore`).
3. **Clients subscribe to folders.** The broker watches exactly the subscribed folders and pushes a full
   snapshot of a folder when it changes.
4. **Share the data, keep the view per pane.** Folder contents are shared; which folders are open,
   selection, scroll and search text belong to one pane.
5. **No deny-list.** `/fs/*` has the same trust level as a terminal: any paired device with full access.

## 2. Scope and decomposition

This spec covers two sub-projects. Each gets its own implementation plan, in this order:

- **A. FileSystemService** (broker + protocol + app service + compatibility wrappers). Shippable on its
  own: step A1 alone makes today's Files pane stop blocking the broker.
- **B. Files pane rebuild** on top of A (tree view state, flattened lazy tree, live updates, polish,
  moving other consumers).

Out of scope: diffs and code review (`/fs/diff`, `/fs/refs`, `workdir-diff.ts`) stay as they are; LSP;
syncing view state across devices; diff-based (delta) folder updates.

## 3. Terms

- **Path:** an absolute, normalised path on the broker host (`/home/ahmet/projects/supermux/src`).
  No `..` segments, no trailing slash, no NUL bytes. Relative paths are rejected.
- **Real path:** `fs.realpath(path)`. Used as the key for sharing watchers and cache entries, so symlinked
  paths and case variants (macOS) collapse to one entry.
- **Snapshot:** the full listing of one folder at one version.
- **Version:** `"<bootId>:<counter>"`. `bootId` is random per broker start; `counter` increases whenever a
  folder's entries change. Opaque to clients; only compared for equality.
- **Subscriber:** a WebSocket connection that has sent `fs_sub` for a path and not yet `fs_unsub`.

## 4. Sub-project A: FileSystemService

### 4.1 Broker module layout

New directory `src/core/fs/`. Each file has one job:

| File | Responsibility |
|---|---|
| `paths.ts` | `normalizeAbsPath(p)`: reject relative, NUL, resolve `.`/`..`, strip trailing `/`. `realKey(p)`: realpath with an in-memory cache invalidated on `fs_gone`. |
| `dir-cache.ts` | `DirCache`: load a folder asynchronously, single-flight, compare with the previous entries, assign versions, bounded LRU. |
| `dir-watchers.ts` | `DirWatchers`: one non-recursive `fs.watch` per real folder, debounce, polling fallback, detects the folder itself disappearing. |
| `subscriptions.ts` | `SubscriptionRegistry`: `byDir` / `bySocket` maps, grace-period teardown, per-socket cap. |
| `repo-info.ts` | `RepoInfo`: nearest-repo lookup, ignored set, status map, async git, debounced refresh. |
| `search-index.ts` | `SearchIndex`: per-scope path list and fuzzy ranking. |
| `file-ops.ts` | read / write / stat / rename / move / mkdir / touch / delete. |
| `file-system-service.ts` | `FileSystemService`: the facade that wires the above and emits frames. |

`src/core/editor/fs-service.ts` keeps only `getDiff` and what `/fs/diff` needs; `listDir`, `readFile`,
`writeFile`, `searchFiles` and `gitIgnoredRelPaths` move out. `src/core/editor/fs-watcher.ts` is deleted
in B2, when the editor's stale banner moves to folder subscriptions. Until then it stays, with its
callback leak fixed in A2 (a socket's previous `editor_open` watch is released before a new one is added).

**Rule for every file in `src/core/fs/`:** no `*Sync` fs calls and no `execSync`. Git runs through
`Bun.spawn` with a 5 s timeout and output read to completion; a timeout kills the process and is treated
as "no git info".

### 4.2 Data shapes (broker, TypeScript)

```ts
export interface FsEntry {
  name: string
  type: "file" | "dir" | "symlink"
  size?: number                 // files and symlinks-to-files
  mtime?: number                // epoch ms
  ignored: boolean              // false outside a git repo
  git?: "M" | "A" | "D" | "R" | "?" | "U" | "*"   // "*" = folder with changes inside
  target?: "file" | "dir"       // symlinks only; absent when the link is broken
}

export interface DirSnapshot {
  path: string                  // as the client asked
  real: string
  version: string
  entries: FsEntry[]            // dirs (and symlinks to dirs) first, then natural, case-insensitive order
  truncated?: { total: number } // present only when entries were cut at the limit
}

export interface SearchHit {
  path: string                  // absolute
  name: string
  type: "file" | "dir"
  score: number
  hits: number[]                // character indexes into `path` that matched
}

export type FsOp =
  | { op: "rename" | "move"; path: string; to: string }
  | { op: "mkdir" | "touch" | "delete"; path: string }
```

### 4.3 Components

**DirCache**
- Load: `fs.promises.readdir(real, { withFileTypes: true })`, then `lstat` for each entry with at most
  32 in flight; for symlinks also `stat` to fill `target` (failure → broken link, no `target`).
- Single-flight: concurrent loads of the same real folder share one promise.
- Compare with the previous snapshot on `name, type, size, mtime, ignored, git, target`. Equal → keep the
  version and do not notify. Different → next version, notify.
- Snapshots over 5,000 entries keep the first 5,000 in sort order and set `truncated.total`.
- Bound: 2,000 folders. Folders with at least one subscriber are pinned; others are evicted least recently
  used.

**DirWatchers**
- One non-recursive `fs.watch(real)` per subscribed real folder.
- Debounce per folder: 100 ms trailing; if events keep arriving, flush at least every 1 s.
- On flush: reload through `DirCache`; if the folder no longer exists (or is no longer a directory), emit
  `gone`.
- If `fs.watch` throws (e.g. `ENOSPC`, the inotify limit), poll that folder every 5 s instead and log
  `fs_watch_fallback` once per folder. Count fallbacks in `/debug`.
- An entry that is itself a git directory's internals (`.git/**`) is never subscribed through the tree
  (the tree may list `.git` but expanding it works like any other folder; `RepoInfo` has its own watch).

**SubscriptionRegistry**
- `byDir: Map<real, Map<SocketId, Set<path>>>` and `bySocket: Map<SocketId, Map<path, real>>`.
- `subscribe(sock, path, since?)`:
  1. normalise; over 500 subscriptions on this socket → `fs_err TOO_MANY_SUBS`;
  2. resolve real path; failure → `fs_err` with the errno code, nothing held;
  3. first subscriber of this real folder (or it is in its grace period) → cancel teardown, start the
     watcher if needed;
  4. get the snapshot from `DirCache`; if `since === version` send `fs_dir {unchanged:true}`, else the
     full snapshot.
- `unsubscribe(sock, path)` and `dropSocket(sock)`: remove; a real folder left with no subscribers stops
  its watcher after a 10 s grace period.
- `dropSocket` is called from the WebSocket `close` handler. This replaces `_editorCb` / `_editorSession`.

**RepoInfo** (one per git repository, keyed by the repository's top-level folder)
- Lookup: from a folder, walk up with async `stat` until a `.git` entry is found (a folder, or a file for
  worktrees and submodules). Cache folder → repo (and "no repo").
- Ignored set: `git ls-files --others --ignored --exclude-standard --directory -z`. An entry is ignored if
  its repo-relative path, or any parent of it, is in the set.
- Status: `git status --porcelain=v2 -z --untracked-files=all`. Files get their letter; every parent
  folder of a changed path gets `"*"`.
- Refresh (500 ms debounce per repo): when any `DirWatchers` flush happens inside the repo, and on changes
  to the git directory's `index`, `HEAD`, or any `.gitignore` in a subscribed folder (one extra watch on
  the git directory). After a refresh, subscribed folders in the repo whose `ignored`/`git` values changed
  are reloaded and pushed.
- Outside a repository: `ignored: false`, no `git`.

**SearchIndex** (one per scope folder)
- Built on the first search in a scope:
  - inside a repo: `git ls-files -co --exclude-standard -z` from the scope folder;
  - outside a repo: async walk skipping `node_modules`, `.git`, `build`, `dist`, `.next`, `.nuxt`, `out`,
    `target`, `.gradle`, `Pods`, stopping at 200,000 paths.
- Folders are included as entries (derived from file paths).
- Match: fuzzy subsequence match on the path, case-insensitive. Score (higher is better): +bonus for
  matches in the last path segment, +bonus for consecutive matches, +bonus at word starts (`/`, `-`, `_`,
  `.`, camelCase), −penalty per path character. Ties broken by shorter path, then alphabetically.
- Returns the top `limit` (default 50, max 200) with `hits`.
- Freshness: rebuilt at query time if older than 30 s. Evicted after 10 minutes without a query.

**FileOps**
- `read`: unchanged rules from today (1 MB limit → 413, NUL byte in the first 8 KB → 415).
- `write`: atomic `.tmp` + rename, creating parent folders; returns `{ size, mtime }`.
- `stat`: one `FsEntry` plus `real`.
- `rename` / `move`: `fs.promises.rename`; target exists → `EEXIST`.
- `mkdir`: recursive. `touch`: create empty file, `EEXIST` if present.
- `delete`: move to the OS trash (Linux: freedesktop trash in `~/.local/share/Trash` with a `.trashinfo`
  file; macOS host: `~/.Trash`). If the path is on a different filesystem from the trash, the op fails
  with `EXDEV` (409) and deletes nothing; the app then asks "Delete permanently?" and, only if confirmed,
  sends `{op:"delete", path, permanent: true}` for a real recursive delete. `/`, mount roots and the home
  folder are never deleted (`EACCES`). The app always confirms before calling it. *(Revised 2026-09-28
  after review: the original silent fallback to `rm -rf` was unsafe.)*
- Each op reloads the affected parent folders through `DirCache` right away (does not wait for the
  watcher), so subscribers see the result immediately.

**Compatibility wrappers**
- `/workspaces/:id/fs`, `/fs/read`, `/fs/write`, `/fs/search` and the `/sessions/:id/fs*` equivalents keep
  their URLs, parameters and response shapes. They resolve the workdir, keep today's containment check
  (a path escaping the workdir is rejected), join the relative path and call `FileSystemService`.
  Listing responses map `FsEntry` back to today's shape (`modified` as ISO string, `type` symlinks reported
  as their target type).
- `fs_changed` keeps coming from the existing `FsWatcher` (driven by `editor_open`/`editor_close`) until B2
  moves the stale banner to folder subscriptions; then `FsWatcher`, `fs_changed` and the watch handling in
  `editor_open`/`editor_close` are removed.

### 4.4 Protocol

**WebSocket (existing connection; compression already on):**

| Direction | Frame |
|---|---|
| app → broker | `{"type":"fs_sub","path":"/abs/dir","since":"b7:41"}` (`since` optional) |
| app → broker | `{"type":"fs_unsub","path":"/abs/dir"}` |
| broker → app | `{"type":"fs_dir","path","version","entries":[…],"truncated"?:{"total"}}` |
| broker → app | `{"type":"fs_dir","path","version","unchanged":true}` |
| broker → app | `{"type":"fs_gone","path"}`: the folder was deleted or moved; the broker has dropped the subscription |
| broker → app | `{"type":"fs_err","path","code","message"}`: `ENOENT`, `EACCES`, `ENOTDIR`, `EINVAL` (bad path), `TOO_MANY_SUBS`; no subscription is held |

`fs_dir` is sent: once in reply to each `fs_sub`, and to every subscriber of the folder whenever its version
changes. A socket subscribed to the same real folder under two paths gets one frame per path.

**HTTP (device token required; proxy and shared-link cookies are rejected):**

| Endpoint | Result |
|---|---|
| `GET /fs/list?path=` | `DirSnapshot` (fills the cache, no subscription) |
| `GET /fs/stat?path=` | `FsEntry & { real }` |
| `GET /fs/read?path=` | text; 413 / 415 as today |
| `PUT /fs/write?path=` (text body) | `{ size, mtime }` |
| `GET /fs/search?scope=&q=&limit=` | `SearchHit[]` |
| `POST /fs/ops` (JSON `FsOp`) | 204 |

Errors: `400` for an invalid path (`EINVAL`), `404` `ENOENT`, `403` `EACCES`, `409` `EEXIST`, body
`{ error: code, message }`.

The `/fs` prefix is already in `API_PREFIXES` and no top-level `/fs/*` route exists today, so there is no clash.

### 4.5 App: FileSystemService (Kotlin, `apps/shared`)

New package `dev.supermux.fs`:

```kotlin
sealed interface DirState {
    data object Unloaded : DirState
    data class Loading(val previous: DirSnapshot?) : DirState
    data class Ready(val snap: DirSnapshot) : DirState
    data class Failed(val code: String, val message: String, val previous: DirSnapshot?) : DirState
    data object Gone : DirState
}

class FileSystemService(api: BrokerApi, send: suspend (ClientFrame) -> Unit, scope: CoroutineScope) {
    fun dir(path: String): StateFlow<DirState>
    fun subscribe(path: String): DirSubscription          // close() releases
    suspend fun list(path: String): Result<DirSnapshot>
    suspend fun stat(path: String): Result<FsEntry>
    suspend fun read(path: String): Result<String>
    suspend fun write(path: String, text: String): Result<WriteResult>
    suspend fun search(scope: String, q: String, limit: Int = 50): Result<List<SearchHit>>
    suspend fun op(op: FsOp): Result<Unit>
    internal fun onFrame(frame: ServerFrame)             // FsDir / FsGone / FsErr
    internal fun onConnectionChange(connected: Boolean)
}
```

- One instance per `HostStore`, created with it; `HostStore`'s reducer forwards `fs_dir`, `fs_gone`,
  `fs_err` to `onFrame`.
- **Ref-counting:** `subscribe` increments a count per path. 0 → 1 sends `fs_sub` (with `since` if a
  snapshot is cached) unless the path is in its grace period. 1 → 0 starts a 10 s grace timer; if it runs
  out, send `fs_unsub` and keep the snapshot as evictable cache.
- **Reconnect:** on `onConnectionChange(true)`, send `fs_sub` with `since` for every path with count > 0,
  following the existing pattern of re-asserting `viewing` frames. While reconnecting, cached states stay
  as they are (no flash to empty).
- **States:** a subscribe with no cached snapshot sets `Loading(null)`; a cached snapshot stays `Ready`
  until the reply. `fs_err` → `Failed(code, message, previous)`. `fs_gone` → `Gone`, count kept, no
  frame (the broker already dropped it); consumers react to `Gone`.
- **Frame ordering:** replies are applied in arrival order; a snapshot whose version equals the current
  one is ignored.
- **Cache bound:** 1,000 folders on desktop, 300 on phones; subscribed paths are pinned; others evicted
  least recently used.
- **Compose helper** in `apps/ui`:
  ```kotlin
  @Composable fun FileSystemService.collectDir(path: String): State<DirState>
  ```
  subscribes in a `DisposableEffect(path)` and releases in `onDispose`.
- New `ClientFrame`s `FsSub(path, since?)`, `FsUnsub(path)` and `ServerFrame`s `FsDir`, `FsGone`, `FsErr`
  in `Frames.kt`. `FsEntry` in `BrokerApi.kt` gains the optional fields; old fields stay for the wrappers.

### 4.6 Error handling summary

| Situation | Broker | App |
|---|---|---|
| Bad path | `fs_err EINVAL` / HTTP 400 | `Failed`, error row |
| Folder missing at subscribe | `fs_err ENOENT` | `Failed` |
| Folder deleted while subscribed | `fs_gone`, subscription dropped | `Gone` |
| Permission denied | `fs_err EACCES` | `Failed`, error row, tap retries |
| inotify limit | polling fallback, logged once | nothing visible |
| git missing / times out | no `ignored`/`git` data | entries without badges |
| Socket closes | `dropSocket` | reconnect re-subscribes |
| Broker restart | new `bootId` | `since` never matches → full snapshots |

## 5. Sub-project B: Files pane rebuild

### 5.1 View state

```kotlin
@Stable
class TreeViewState(rootPath: String) {
    var rootPath by mutableStateOf(rootPath)
    var expanded by mutableStateOf(persistentSetOf<String>())   // absolute paths
    var selected by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    val list = LazyListState()
}
```

- Held per Files view in a per-host, in-memory holder keyed by view id, so dragging, splitting and tab
  switches keep it. Not persisted to the broker and not synced across devices.
- `rootPath` starts at the workspace's workdir. Breadcrumbs may move it above the workdir; a "workspace
  root" chip returns to it. If the workspace's workdir changes, `rootPath`, `expanded` and `selected` reset.

### 5.2 Rendering

- `flatten(view, dir)` walks `rootPath` and every expanded folder, depth first, using each folder's
  `Ready` or previous snapshot, and produces `List<Row(path, depth, entry, state)>`. Computed with
  `derivedStateOf`.
- One `LazyColumn(rows, key = { it.path })`. Only visible rows are composed.
- The pane subscribes (via `collectDir`) to `rootPath` and every path in `expanded` whose parent chain is
  expanded. Subscriptions belong to the pane, not to rows.
- A symlink to a folder is expandable; a real path that already appears among its own ancestors is not
  expanded again (loop guard).
- On `Gone`: remove the path and its descendants from `expanded`.
- Scroll anchoring: when rows are inserted above the first visible row, keep the first visible key in place.

### 5.3 Row and interactions

- Row: indent guides, chevron (spinner after 150 ms of `Loading(null)`), file-type icon from extension,
  name (monospace), git letter (files) or dot (folders, `"*"`), ignored entries at 45% opacity, active
  editor file in bold.
- Sticky parent row: the nearest expanded ancestor of the first visible row stays pinned at the top.
- Error row under a folder in `Failed`; tapping the folder retries.
- Reveal active file (toggle, on by default): when the focused editor file changes, expand its ancestors
  under `rootPath`, select it, scroll it into view.
- Desktop keyboard: ↑/↓ move, ← collapse or go to parent, → expand or go to first child, Enter open,
  F2 rename, Delete/⌫ delete (confirm), letters jump to the next matching name.
- Phone: tap opens, long-press opens the menu, 44 dp rows in the compact layout.
- Menu (right-click / long-press): New file, New folder, Rename, Delete, Copy path, Copy relative path,
  Attach to chat.
- Header: breadcrumbs, Collapse all (clears `expanded`), Refresh (re-subscribes the visible folders
  without `since`).
- Search field (⌘P on desktop): 120 ms debounce, cancels the previous request, shows up to 50 results with
  matched characters highlighted; empty query shows recently opened files of this pane.

### 5.4 Other consumers moved onto the service

| Consumer | Change |
|---|---|
| Editor "changed on disk" banner | subscribes to the open file's parent folder and compares the entry's `mtime`/`size` with the loaded copy; `editor_open`/`editor_close` stop driving watches |
| New-project path picker | browses with `list()`/`stat()` |
| Tapping a file path in chat | `stat()` first; missing file → a toast instead of an empty editor |

### 5.5 Removed

`FileTree.kt`'s recursive `TreeNodeRow`, `ExplorerState`, `TreeNode`, `loadAndExpand`, and
`EditorPanel`'s embedded sidebar tree are replaced. The test tags `editor_explorer_pane` and `editor_tree`
are kept on the new pane.

## 6. Limits (starting values)

| Knob | Value |
|---|---|
| Subscriptions per socket | 500 |
| Entries per snapshot | 5,000 |
| Broker `DirCache` | 2,000 folders, subscribed pinned |
| App cache | 1,000 folders desktop, 300 phone |
| Watch debounce | 100 ms trailing, 1 s max |
| Unsubscribe grace | 10 s, both sides |
| `lstat` concurrency | 32 |
| Search index | 200,000 paths, rebuilt after 30 s, evicted after 10 min idle |
| Search results | 50 default, 200 max |
| Git | `Bun.spawn`, 5 s timeout, 500 ms debounce per repo |

All are constants in one place per side so they can be tuned.

## 7. Testing

**Broker (bun test, temp folders, real git repos):**
- `paths`: relative, `..`, NUL and trailing-slash cases.
- `DirCache`: single-flight (one `readdir` for N concurrent loads), unchanged reload keeps the version,
  truncation at the limit, symlink `target` and broken links.
- `DirWatchers`: create/delete/rename in a folder produces one flush after debounce; a storm flushes at
  most once per second; deleting the folder emits `gone`; forced `fs.watch` failure falls back to polling.
- `SubscriptionRegistry`: ref-counting across sockets and paths, grace teardown, `dropSocket`, the
  500 cap, `since` → `unchanged`.
- `RepoInfo`: ignored flags (including a parent folder ignored), status letters and folder `"*"`, refresh
  after `git add`, worktree `.git` file, no repo.
- `SearchIndex`: ranking order for a fixed fixture, `hits` positions, skip list outside git.
- `FileOps`: each op and its error codes; delete goes to the trash folder.
- Event-loop test: listing a 10,000-entry folder never blocks a concurrent timer for more than 50 ms.
- Routes: existing `workspace-fs.test.ts` unchanged and green; new `/fs/*` route tests bind port 0; proxy
  cookie rejected on `/fs/*`.

**App (commonTest / jvmTest):**
- `FileSystemService`: 0→1 sends one `fs_sub`, 1→2 sends nothing, 1→0 then re-subscribe within grace
  sends nothing, grace expiry sends `fs_unsub`, reconnect re-sends with `since`, `fs_gone` → `Gone`,
  `Loading(previous)` keeps rows, cache eviction skips subscribed paths.
- `flatten`: order, depth, loop guard, collapsed folders omitted, `Gone` pruning.
- Compose UI tests: the tree renders only visible rows for 5,000 entries; expanding shows the spinner only
  after 150 ms; the test tags still resolve.

**Manual:** desktop, Android and iOS against this repository with `node_modules` present: expand speed,
live update when an agent creates a file, reconnect after sleep.

## 8. Rollout

**Sub-project A**
1. **A1 Broker core:** `paths`, `DirCache`, `RepoInfo`, `FileOps`, `SearchIndex`; rebuild the existing
   `/workspaces/:id/fs*` and `/sessions/:id/fs*` routes on them. No protocol change. Result: the current
   app stops blocking the broker and search improves.
2. **A2 Broker subscriptions:** `DirWatchers`, `SubscriptionRegistry`, `fs_sub`/`fs_unsub`/`fs_dir`/
   `fs_gone`/`fs_err`, the `/fs/*` routes, `dropSocket` on close, the `editor_open` callback leak fixed.
3. **A3 App service:** frames, `FileSystemService` in `HostStore`, `collectDir`.

**Sub-project B**
4. **B1 Tree:** `TreeViewState`, `flatten`, the new lazy tree with live updates, breadcrumbs, reveal
   active file; old tree code removed.
5. **B2 Polish and consumers:** icons, git badges, sticky parents, keyboard, menu with file ops, ⌘P search
   UI; stale banner, path picker and chat path taps moved to the service; `FsWatcher` and `fs_changed`
   removed.

Each step is testable on a preview broker (`mux:preview-broker`) before merging.

## 9. Decisions on the former open questions

- **View state sync:** not synced; per device, in memory (§5.1).
- **Delete:** OS trash with confirmation; across filesystems a second, explicit "Delete permanently?" confirm (§4.3).
- **Git status:** data in A1; badges in B2.
- **Browsing above the workspace root:** allowed through breadcrumbs, with a chip back to the root (§5.1).
