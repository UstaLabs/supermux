# webview-compose: the platform's own web engine, inside Compose

`dev.supermux.webview:webview-compose` is a Compose Multiplatform web view for apps that show **hundreds of web
views**: interactive demos posted into a chat, or a plugin system built on web pages. Each `WebView` is a cheap
handle. A small pool of live engines is shared among them, and a view without an engine shows a snapshot of its page,
or a placeholder.

It is built for each platform's own engine: Android `WebView`, `WKWebView` on iOS and macOS, WebView2 on Windows, WPE
WebKit on Linux, and an `<iframe>` on the web. **The web's engine exists** ([The web engine](#the-web-engine)). The
others don't yet, and until a platform's does, that platform reports `Unavailable(NotImplemented)`. Everything around
the engines is built and tested against the library's fake engine on every platform: the pool, activation,
snapshots, page memory, overlays, the page bridge and the engine contract.

It depends on Compose and kotlinx (coroutines, serialization) only, nothing else of supermux. It targets Android, iOS
(arm64 and simulator arm64), the desktop JVM and wasmJs. It isn't published yet; depend on the project, as
[`webview-sample`](../webview-sample) does: `implementation(project(":webview-compose"))`.

The design, including the goals set for the engines, is in the [design document][design]. This README describes what is
built.

[design]: ../../docs/superpowers/specs/2026-10-28-webview-compose-design.md

## Status: what has actually been run, and where

| platform | engine | what has run | what has not |
|---|---|---|---|
| any | fake engine | `:webview-compose:jvmTest`: **607 tests**: 466 common ones (the pool, activation, overlay geometry, the bridge, the caches and page memory, `WebViewState`, content, URLs and the navigation policy, the engines' event queue, the fake engine) and 141 JVM-only ones (the `WebView` composable, its host and the overlay modifiers over the fake engine, in Compose UI tests). `:webview-sample:jvmTest`: **9 tests**, among them a 200-message chat read and scrolled with every frame checked against the budgets, with no engine leaked | any real engine |
| browser (wasmJs) | iframe (`IframeWebEngine`) | `:webview-compose:wasmJsBrowserTest`: **556 tests** in headless Chrome 156: the 466 common ones and 90 more, covering the bridge shim, the web engine (its shim and port, documents and commits, frames, cut-outs and scrims, scroll chaining, the budget for pages of other sites, reset) and `WebView` on it end to end. Also its feasibility test (Karma and the DevTools protocol), and the sample's browser build driven with trusted clicks and wheels by `web-check/run.mjs`: see [The web engine](#the-web-engine) | Safari and Firefox (planned, by hand), mobile browsers, an installed PWA |
| Android, iOS, macOS, Windows, Linux | not built | the module compiles for Android and iOS (`compileDebugKotlinAndroid`, `compileKotlinIosSimulatorArm64`) | everything else: every platform reports `Unavailable(NotImplemented)` until its engine is built |

All of it ran on 2026-10-29 on an Apple M1 Mac (macOS 26.6), JDK 17. The perf lab's numbers, for the fake engine, are
[below](#the-perf-lab).

## Quick start

```kotlin
@Composable
fun AgentDemo(html: String) {
    // Html hashes its text once: remember the content rather than rebuilding it at every composition.
    val content = remember(html) { WebContent.Html(html) }
    val state = rememberWebViewState(content)
    // Live once it is on screen and has stopped moving: WebView's default activation, made for lists.
    WebView(state, sizing = WebSizing.WrapContentHeight(max = 400.dp))
}
```

In a browser this shows the demo, live once it is on screen and has stopped moving. On the other platforms it shows the
placeholder for now: once the view tries to go live, `state.phase` is `Unavailable(NotImplemented)`.

- **`WebContent`** is what to show: `Url(url, headers)` for http(s) pages, `Html(html)` for in-memory HTML on an origin
  of its own (agent demos), and `Bundle(origin, resources, entry)` for a set of files served on an origin (plugins).
- **`WebViewState`** is the logical page: `content`, `url`, `title`, `loading`, `canGoBack`, `canGoForward`, `phase`
  (`Cold`, `Activating`, `Live`, `Crashed`, `Unavailable`), `error`, `contentSize`, `console`, `bridge`, and a
  `navigator` (`load`, `back`, `forward`, `reload`, `stop`). `state.load(content)` navigates, even to the content
  already shown. Show a state in one `WebView` at a time. Main thread only. The navigator reaches only the engine of the
  view that shows the state; an engine that throws puts the view in `Crashed`, and nothing reaches your code.
- **`rememberWebViewState(content)`** keys the state by `content.key`, and never undoes what you load into it: it loads
  `content` again only when the argument itself changes. It isn't saveable on purpose: HTML bodies would overflow
  Android's saved-state bundle. Page memory brings the page back instead ([below](#snapshots-and-page-memory)).
- **`WebView(state, modifier, settings, client, activation, sizing, placeholder)`**:
  - `settings`: `javaScriptEnabled`, `inspectable`, `darkMode` (`Auto`, `Light`, `Dark`), `transparentBackground`,
    `userAgentSuffix`, `profile` (`Default`, `Named(id)` or `Ephemeral`; unset, `Html` gets `Ephemeral` and the rest
    `Default`), `mediaPlaybackRequiresGesture` and `bridgeOrigins`.
  - `activation`: when the view goes live; `Activation.WhenVisible()` unless you say otherwise ([Activation](#activation)).
  - `client`: answers the page's requests: navigations, new windows, dialogs, file choosers, permissions and
    downloads. Every default is the safe answer ([Security defaults](#security-defaults)).
  - `placeholder`: covers the view while there's no snapshot. The default is an opaque card in the resolved theme,
    with the page's title once known. A custom one should be opaque too.

## The pool and memory

All web views share one `WebViewRuntime`: the engine pool, the snapshot cache, page memory and the overlay registry.
`WebViewRuntime.Default` serves unless `ProvideWebViewRuntime(runtime) { … }` provides another.

- **Budget.** At most `maxLive` engines are leased or parked at once: 4 on phones, 6 on tablets, 8 on desktop and 12
  iframes on the web (`RuntimeConfig.platformDefault()`). Over budget, a view takes the slot of a parked page first,
  the one parked longest, then evicts the lowest-priority view below its own; with nobody to evict, it keeps its cover
  and waits for capacity. Priority: the focused view (touched last, or holding the keyboard focus), then visible
  `Eager` views, then other visible views, then hidden ones, then parked pages. Ties go to the least recently used,
  where use means a touch or coming into view.
- **Parked pages.** A view that leaves the composition with its page live (an item a `LazyColumn` disposes as it
  scrolls away) saves the page as before, then parks it in the pool instead of giving its engine back: unreset, hidden
  (`setVisible(false)`), with no holder. The next view of the same page, in the same profile and engine config, adopts
  it as it is: no load, no reset, its in-page state intact, and Live as soon as the engine replays its page. Only one
  view adopts a page; a second gets an engine of its own. A parked page holds its slot until a view needs it: then it
  saves what it has (its page state and engine state, bounded as an eviction is) and its engine is reset for that
  view. A page that crashed while parked is released, and a view of it starts fresh.
- **Spares.** Up to `maxIdle` blank engines are kept for reuse: 3, or 2 on iOS, where each idle `WKWebView` may keep
  its own WebContent process. An engine given back is reset and kept as a spare if there's room, or destroyed, so
  there are never more than `maxLive + maxIdle` engines. The exception is an engine whose eviction or reset overran
  its time bound: it is released once that call returns. A spare is created ahead of need only when the pool has none
  of that kind and got none back in the last 10 s, as at cold start; otherwise recycling supplies them.
- **Never lost, never shared.** Every engine the pool creates ends up leased, a spare, parked or released, whatever
  throws or is cancelled; one whose call hangs past its bound is never reused, and is released once that call returns. An engine
  serves one view at a time, and a state reaches only the engine of the view that shows it. An engine changes hands
  only after the view that had it lets go of its surface, waiting up to 500 ms for the composition to do so. Everything
  is bounded in time: a creation 20 s, a view saving its page before an eviction 5 s, a reset 10 s, a snapshot or a save
  2 s. An engine that overruns is destroyed, never reused.
- **Failures.** A failed creation is retried after 2 s, 10 s, then every 60 s. Other `Unavailable` reasons stay until
  `navigator.reload()`. An engine that throws, or whose renderer dies, puts its view in `Crashed`, covered by its last
  snapshot or its placeholder. A crashed view on screen goes live again by itself a second later, at most once a minute
  (on Android every WebView shares one renderer, so one page's death crashes every live view): one that crashes again
  within the minute stays `Crashed`, and one off screen waits until it is shown. `navigator.reload()` still brings a
  crashed view back at any time. `runtime.onError` sees what the guards catch.
- **Memory pressure.** Call `runtime.onMemoryPressure()` when the OS says memory is short (Android's `onTrimMemory`, an
  iOS memory warning, the macOS memory-pressure source); the library doesn't listen for these itself yet. It releases
  the spares and every parked page (each saves itself first), evicts live views down to half the budget, hidden ones
  first and never the focused one, and gives back half of what the snapshot cache holds. Until
  `runtime.onMemoryRecovered()`, or 30 s after the latest call, the budget stays at half, no spares are kept, nothing
  is parked, and the snapshot cache keeps to that half, even as the evicted views snapshot their pages on the way out.

## Activation

```kotlin
Activation.WhenVisible(settleMillis = 150)  // the default, for lists: live once visible and still for 150 ms
Activation.Eager                            // panes: live whenever visible, even while moving
Activation.OnInteraction                    // very long lists: a snapshot until tapped
```

- `WebView` uses `WhenVisible()` unless told otherwise, because lists are the main use. Give a pane `Eager`.
- A cold view never activates while it moves (a scroll, an animation) unless it is `Eager`, so `Eager` is for panes,
  not list items. A live view stays live while it moves.
- A view is visible while at least 1×1 px of it is in the window, after the clipping of its scroll containers.
- A view that leaves the screen but stays composed (a plain `Column`, a tab not shown) keeps its engine, whatever its
  activation, told it is hidden and ranked lowest: scrolled back, its page is as it was, with nothing reloaded. A
  visible view that needs the slot takes it at once (the hidden page saves itself first), memory pressure takes hidden
  pages first, and a view hidden for `hiddenReleaseMillis` (10 min) gives its engine back by itself.
- A view that leaves the composition saves its page and parks it ([parked pages](#the-pool-and-memory)). In a
  `LazyColumn` that is every item that scrolls out of the composed window: an item scrolled back is a new view, which
  shows its page's snapshot at its height at once ([page memory](#snapshots-and-page-memory)) and adopts the parked
  page, live again without a load. Once the pool has reclaimed the page for other views, the item starts it again
  under its snapshot, restoring what was saved. So within the budget, scrolling away and back keeps pages alive in a
  `LazyColumn` as in a `Column`.
- Compose keeps a few items that scrolled out of a `LazyColumn` composed for reuse, and deactivates them, which
  disposes them, between frames on Android, or when another item takes their slot. Until then such an item keeps its
  engine, and comes back as it was if scrolled back first.
- A new view of a page whose save is still under way (an item that has just scrolled away, a state moved to another
  view) waits up to 1 s for that save before its first document, so it starts from what it keeps; then it decides
  again whether to go live, in case it left the screen meanwhile.

## Sizing

- **`WebSizing.Fill`**, the default: the view takes the size its modifier gives it.
- **`WebSizing.WrapContentHeight(max, initial = 160.dp)`**: full width, and the page's content height, capped at
  `max` and followed with a spring. A taller page scrolls inside the view. The height comes from the bridge shim's
  `ResizeObserver` (so only pages that get the shim, see [The bridge](#the-bridge)), or from an engine that measures
  natively.
  - **Choose `initial` close to your typical content.** Until pages report, every view in a list is `initial` tall,
    and that first layout decides which views are on screen and go live; real heights then push some of them off
    screen again.
  - Page memory keeps each page's last height, so a view recreated by a lazy list shows at its real height at once.
  - Reported sizes are the page's to choose, so they're checked: non-finite, zero and negative sizes are ignored,
    both sides are clamped to 32,000 CSS px, and the view is never laid out taller than 262,142 px, the most Compose's
    `Constraints` can hold. A page that grows with its own viewport (`100vh` plus a margin, up to `400vh`) twice in a
    row is caught, and stops growing the view.

## Snapshots and page memory

- **Covers.** A view without a painted page shows its last snapshot, or its placeholder when it has none. The cover
  stays until the engine paints the document the view started, so, with engines that keep their contract, a page
  never shows a white or blank frame. Navigations the page makes itself, reloads and history navigations keep the
  previous page on screen until the next one paints: engines hold paint, by contract.
- **When.** Snapshots are taken only while the page is composed and laid out: 500 ms after its first paint, 500 ms
  after the user's last interaction, and when a deactivation or an eviction takes the engine, before the view lets go
  of it. A view that leaves the composition takes none, and keeps its last good snapshot.
- **The page, never the app.** On Android a snapshot is the WebView drawn by itself into a bitmap at the compact size
  below, on the main thread: nothing the app draws over the page (its header, a menu, a dialog, the runtime's cover)
  can end up in it, and a page half off screen still gets one. A draw that takes longer than 32 ms is skipped, and one
  that comes out all one colour (video and WebGL, which that path can't draw) is discarded. Only then does a copy of the
  window (`PixelCopy`) stand in, and only with the view wholly on screen and nothing the app marked over it: the
  runtime tells the engine before each snapshot (`overlaysAtSnapshot`). On the phone (Galaxy Z Fold7, WebView 156) a
  demo page filling the screen drew itself at 392 × 916 in 5.5 to 6.0 ms (p50), 12.3 ms at most, over two runs of 20
  snapshots; a WebGL page's draw came out blank, and was discarded. The web takes no snapshots (cross-origin pixels
  can't be read).
- **Compact.** A snapshot is a stand-in for the page for the half second it takes to wake, drawn scaled to the view:
  the runtime keeps it at one pixel per dp of the view at most, and 360,000 pixels at most (about 600 × 600), with its
  aspect ratio, and in 16-bit pixels (`Rgb565`) unless the page asks for a transparent background. A 411 × 300 dp
  embed costs about 0.25 MB instead of 2 to 4 MB, so a phone's 32 MB keeps a picture of a hundred pages and more.
  This happens in one place, after `engine.snapshot()`, for every engine.
- **Where.** In an LRU cache bounded by pixel bytes (`snapshotCacheBytes`: 32 MB on phones and tablets, 128 MB on
  desktop, 64 MB on the web), keyed by profile, content, pixel size, theme and density. A view never shows a snapshot of
  other content, of another profile or of the other theme. Only views on screen hold their cover's pixels, so covers
  take about a screenful, and no placeholder is composed under a cover.
- **Page memory.** A lazy list disposes the items that scroll away, and the item that scrolls back is a new view with a
  new state. So when an engine goes, or a page is parked, the runtime remembers, by profile and content key, the page's
  snapshot, height, engine state (history and scroll, where the engine can save them) and page state; a parked page
  that is reclaimed saves its engine and page state again. A new view of the same content in the same profile shows
  its snapshot at its real height from its first frame. Then it adopts the page if it is still parked, and restores
  nothing into it, since the live page is newer; or it restores the rest when it goes live on another engine. It keeps
  512 pages and 16 MB at most, least recently used out first.
- **Profiles are a boundary.** A profile is a user's cookies and storage, so nothing of a page shown in one profile
  reaches another: a view that shows the same content in another profile (a settings change, or a state moved to a view
  with other settings) starts fresh, with no restore and no cover.
- **Logout.** Users who share a profile, by logging in with cookies in `WebProfile.Default`, share the keys too, and a
  plugin's `Bundle` has the same key for every user. Call `runtime.clear()` at logout: it forgets every page and
  snapshot, no view shows a snapshot from before it, states you still hold drop what they remembered from before it
  when next shown, parked pages are given back unsaved so no view adopts the last user's page, and pages live across
  it save nothing more (reload or remove them). Engines keep their profile's cookies and storage: give each user a
  `WebProfile.Named` of their own, or clear that data through the platform. `runtime.forget(content)` forgets one
  content in every profile, its parked pages included, for content that is gone.
- **Page state.** A page keeps what it needs across engines through the shim. The page has 300 ms to answer when its
  engine goes. What it saved is pinned to the origin of the document that saved it, and given back only to a document
  of that origin:

  ```js
  hostBridge.onSaveState(() => ({ draft: input.value }));
  hostBridge.onRestoreState(saved => { input.value = saved.draft; });
  ```

## The bridge

The page side is `window.hostBridge`, the same on every engine; the app side is `state.bridge`. Messages are JSON.

```kotlin
// Register once on the state: handlers and listeners outlive engines, and stay registered across state.load().
state.bridge.handle("sum") { params -> JsonPrimitive(params.jsonArray.sumOf { it.jsonPrimitive.int }) }
state.bridge.on("clicked") { data -> println("clicked: $data") }

// While the view is live:
val title = state.bridge.call("title")              // suspends; BridgeTimeoutException after 10 s
val sent = state.bridge.post("theme", JsonPrimitive("dark"))
```

```js
hostBridge.handle('title', () => document.title);
hostBridge.on('theme', theme => { document.body.dataset.theme = theme; });
const sum = await hostBridge.call('sum', [1, 2, 3]);
hostBridge.post('clicked', { at: Date.now() });
```

- **Who gets the shim.** With JavaScript on, documents of the content's own origin (`Html`, `Bundle`) and of the
  origins in `WebSettings.bridgeOrigins`. A `Url` page gets it only if its origin is listed there, for example
  `WebSettings(bridgeOrigins = setOf("https://app.example.com"))`. There is no wildcard. Each is an origin as browsers
  write it, `scheme://host[:port]` with no path, no trailing slash and no default port; anything else is refused.
- **While the view has no engine,** `call` throws `BridgeException` and `post` returns false. Until the page's shim
  has announced itself, calls wait within their timeout, and posts are queued, the newest 64.
- **After `state.load(…)`** the bridge leaves the old document at once: what you send waits for the new content's
  document, even while the view moves to another engine for it, and never reaches the page being replaced. What was
  waiting for the old content fails, or is dropped.
- **Handlers and listeners stay** across `load()`. When one state shows one party's content after another's (plugins),
  remove what you registered for the last one (`on` and `handle` return the function that does), or give each party a
  state of its own.
- **The page is untrusted.**
  - Each document announces itself with a random nonce, and every message is addressed to, and signed by, one
    document. Nothing meant for one document reaches the next, even on the same origin, and a new document fails the
    old one's pending calls.
  - Messages from other origins are dropped. Messages are size-capped (64 KiB, replies 256 KiB), depth-checked, strict
    JSON, and rate-limited (200 a second, bursts of 400). At most 16 page calls run at once, 10 s each.
  - A handler's exception reaches the page only as "handler failed", unless it is a `BridgeException` meant for the
    page; `bridge.onError` sees the real one.
  - Names starting with `$` are the library's. `bridge.droppedMessages` counts what was dropped.
- A page's own `call` times out after 30 s by default.

## Overlays and scrims

With the engines as designed, Compose draws over the page on Android, iOS and the frame-mode desktops (Windows,
Linux), so menus, dialogs and tooltips simply cover it. A page that sits *above* the Compose canvas can't be covered:
the web's `<iframe>`, and macOS in one of the layouts its engine may use. Such a page cuts the overlays out of itself,
and dims under the scrims, so mark them:

```kotlin
val shape = MenuDefaults.shape
DropdownMenu(expanded, onDismissRequest, modifier = Modifier.webViewOverlay(shape), shape = shape) { … }
```

- **`Modifier.webViewOverlay(shape)`** goes on a menu, popup, tooltip or dialog surface. Give it the shape the surface
  is drawn with; each corner keeps its own radius. Put it after the modifiers that move, size or transform the
  surface.
- **`Modifier.webViewScrim(color)`** goes on a modal scrim, with the colour the scrim is drawn with. Stacked scrims
  compound in the order they appeared.
- **Several windows.** A multi-window app wraps each window's content in `ProvideWebViewOverlays { … }`, so an
  overlay in one window never cuts a hole in another window's page.
- **On the web, mark everything you draw over a page**, buttons and labels as well as menus and dialogs. Whatever is
  unmarked stays under the page's iframe.
- The geometry is common code, and unit-tested: each live page gets its visible area minus the overlays as an
  even-odd path, whose holes are disjoint and cover exactly the overlays. An isolated overlay keeps its per-corner
  radii, and so does one that contains the others away from its corners. Applying it, with the scrims, is the
  engines' part (`applyOverlays`). The web engine cuts the holes with CSS `clip-path`, which clips clicks too, and
  dims under each scrim, where the page takes no clicks. macOS will use a layer mask.

**Material dialogs.** Material's dialog draws its scrim in a layer of its own, which no modifier reaches, so a page
above the canvas would stay bright under it. Turn that scrim off, and draw a full-window one behind the dialog's
content:

```kotlin
val scrim = Color.Black.copy(alpha = 0.32f)
val shape = AlertDialogDefaults.shape
Dialog(
    onDismissRequest = onDismiss,
    // The content may fill the window, and the dialog's own scrim is off.
    properties = DialogProperties(usePlatformDefaultWidth = false, scrimColor = Color.Transparent),
) {
    Box(Modifier.fillMaxSize().background(scrim).webViewScrim(scrim), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.webViewOverlay(shape),
            shape = shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            // the title, the text and the buttons
        }
    }
}
```

- The scrim is now the dialog's own content, so a tap on it no longer counts as outside the dialog: give it a click
  handler if a tap outside should dismiss.
- `scrimColor` belongs to Compose Multiplatform's `DialogProperties` on desktop, iOS and the web. Android's has none,
  and Android needs no recipe: Compose draws over the page there.

## Security defaults

The library enforces these itself, and tests them:

- **Local content.** Unless given an origin, `Html` is served on `https://c<hash>.invalid`, where the hash is the
  first 130 bits of the HTML's SHA-256. Each demo is then an origin and a site of its own, which no other content can
  forge. `.invalid` never resolves, so a request an engine fails to intercept fails closed. `Html` runs in the
  `Ephemeral` profile unless the settings name another.
- **Navigation.** The default policy (`defaultNavigationDecision`, used by `WebClient.Default`) parses URLs the way
  browsers do.
  - Local content stays on its own origin, its own `blob:` URLs and `about:blank` or `srcdoc`. Network pages can't
    navigate into local origins.
  - `javascript:`, `data:`, `file:` and unknown schemes are blocked.
  - Leaving the app needs a user gesture, and only http(s), `mailto:`, `tel:` and `sms:` URLs ever reach the OS. The
    runtime checks the scheme again before opening one, whatever a custom client decided.
- **Page requests.** By default, dialogs are dismissed (`beforeunload` leaves the page, so a page can't trap the user),
  file choosers cancelled, permissions and downloads denied, and new windows blocked. `onNavigation` and `onNewWindow`
  may run off the main thread: keep them fast, pure and thread-safe.
- **The bridge,** as [above](#the-bridge): listed origins only, a nonce per document, and limits on everything.
- **`WebContent.Url`** takes only http(s) URLs with a host. Its `toString` shows no userinfo, query, fragment or header
  values, and its key carries a fingerprint of its headers, so the same URL sent with other credentials is another page.
- **Users.** Snapshots and page memory are kept per profile and content, so a page shown in another profile never gets
  another profile's snapshot or saved state. Users who share a profile are told apart only by `runtime.clear()` at
  logout ([Snapshots and page memory](#snapshots-and-page-memory)).
- **History.** A switch to or from local content (`Html`, `Bundle`) starts the new document on a fresh lease, an engine
  the pool has reset or a new one, so no history crosses into or out of local content. Network pages loaded one after
  another in a view keep their history, as in a browser tab.

The rest is the engines' part. The web engine's share is described [below](#the-web-engine). For the other engines,
which don't exist yet, these are goals, set by the design and the engine contract:
- no `file://` access;
- mixed content blocked;
- pop-ups denied;
- certificate errors never bypassed;
- inspectors only when `inspectable = true`;
- the shim run in an isolated world where the engine has one;
- every message checked against the receiving document's origin when it arrives.

## The runtime

```kotlin
// One per app, created once, for the life of the process.
val webViews = WebViewRuntime(config = RuntimeConfig.platformDefault().copy(maxLive = 6))

ProvideWebViewRuntime(webViews) { App() }
```

- `RuntimeConfig(maxLive, maxIdle = 3, snapshotCacheBytes, hiddenReleaseMillis = 600_000, pageMemoryEntries = 512)`.
  `hiddenReleaseMillis` is how long a view off screen keeps its engine when no visible view needs it.
- `WebViewRuntime(factory, config, scope, clock)`: `factory` creates the engines, the platform's by default. `scope`
  runs the pool's and the views' work, on `Dispatchers.Main` by default; on desktop the library brings
  `kotlinx-coroutines-swing` for it.
- Control it through the runtime: `onMemoryPressure()`, `onMemoryRecovered()`, `onError`, `clear()` and
  `forget(content)`. The pool's own controls are internal, so the cache halving, the error forwarding and the budget
  (`RuntimeConfig.maxLive`) stay consistent.
- For monitoring, as the perf lab does: `pool.liveCount`, `parkedCount`, `idleCount`, `budget` and `maxLive`; `snapshots.sizeBytes`,
  `count` and `maxBytes`; `pages.count` and `sizeBytes`; `coverBytes` and `coverCount`.
- Main thread only: the runtime, the states and the views.

## For engine authors: the engine SPI

An engine implements `WebEngine`, and a `WebEngineFactory` creates it; give the factory to `WebViewRuntime`. The full
contract is the KDoc of [`engine/WebEngine.kt`](src/commonMain/kotlin/dev/supermux/webview/engine/WebEngine.kt). In
short:

- **Creation.** `create(config)` may suspend, and never throws: failures are `WebEngineResult.Unavailable(reason,
  detail)`. `EngineConfig` holds only what can't change after construction, the profile and the media playback
  policy, and engines are pooled by it. Everything else comes per lease through `configure(PageSetup)`, before every
  `load` or `restoreState`.
- **Threads and events.** The runtime calls every member on the main thread. Events may come from any thread; the
  engine buffers them, and never drops a lifecycle event or a page's `$ready`, which it recognizes as the bridge does
  (`WebBridge.READY_PREFIX`, `READY_MAX_CHARS`). Once `load`, `reload` or `restoreState` returns, no event caused by an
  earlier document is delivered, even one still buffered: engines filter by navigation.
- **No white frame.** After creation or a reset, the surface stays invisible until `FirstPaint`, or a main-frame
  `Error`. Between documents, the engine keeps showing the previous one until the next paints.
- **Reuse.** `reset()` returns true only if nothing of the last lease can come back: no history, no late event, no
  pending request, no per-lease script or grant, and an `Ephemeral` profile's data wiped. Otherwise it returns false,
  and the pool destroys the engine. `isUsable` turns false for good after a crash or a release, and after `release()`
  every member does nothing.
- **Snapshots and state.** `snapshot()` is null until the current document has painted, and unless `Surface` is
  composed and laid out at a non-zero size. It should be the page as the engine draws it, not a copy of the screen,
  which would take in the app's UI over the page; an engine that can only copy the screen does so only when
  `overlaysAtSnapshot(cutouts)`, called just before each snapshot for engines Compose draws over, says nothing is over
  the page (`cutouts.isNone`). `restoreState(state, content)` returns false when it can't restore that
  content's state, and the runtime loads the content instead.
- **Parking and replay.** A page whose view left the composition may be kept live, unbound, with no client, no
  subscriber and no `Surface`, told it is hidden: the engine keeps its document as it is. A view that adopts it
  subscribes anew and calls `replayState()`, with no `configure` unless the setup changed and no `load`: the engine
  tells it what the page is now, as events (`UrlChanged`, `HistoryChanged`, `TitleChanged`, the latest
  `ContentSize`, where it knows them) and `FirstPaint` once the current document is on screen again. Pages answer
  the bridge's `$hello` with their `$ready`, and re-report their size.
- **The bridge.** Inject `BridgeShim.source(sendExpression)` at document start into the main frame of documents whose
  origin is in `PageSetup.bridgeOrigins`, with JavaScript on, and deliver host messages by calling
  `window.__hostBridgeReceive(json)`. `postMessage(json, targetOrigin)` delivers only if the main frame's document
  has that origin when the message arrives. Page messages come from the main frame only, with the origin the engine
  sees.
- **The view.** `Surface(modifier)` hosts the native view or the frames. When an engine changes hands, the runtime
  waits for the view that had it to let go of its surface before the next one composes it, for 500 ms at most.
  `applyOverlays(cutouts)` cuts overlays out of a page above the canvas; an engine that Compose draws over returns
  false from `needsCutouts`, and the runtime computes none for it. `requestFocus` is optional.
- **Hidden pages.** `setVisible(false)` comes for a view off screen and for a parked page; engines may throttle the page
  but must keep its document. What each one does:
  - Android: `WebView.onPause()`, which stops the page's animations, geolocation and media and tells it it is hidden.
    Its JavaScript keeps running (`pauseTimers()` would pause every WebView in the process). A parked page's WebView is
    also out of any window; adopted, it stays invisible until it has drawn the page in its new place.
  - The web: the frame stays laid out in its layer, where it last was, with `visibility: hidden`. The iframe never
    leaves the page, which would reload it, and keeps its viewport size, so the page doesn't reflow; it takes no
    clicks, and the browser throttles a hidden cross-origin frame. A page of another site over the web's budget for
    such pages may give up its slot off screen, and loads afresh when shown.
  - The fake records it.

## Testing with the fake engine

`dev.supermux.webview.testing.FakeWebEngine` follows the engine contract with no browser behind it, so an app can test
its web views wherever Compose UI tests run:

```kotlin
@Test fun theDemoGoesLive() = runComposeUiTest {
    val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 240f }))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val runtime = WebViewRuntime(
        factory = engines,
        config = RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20),
        scope = scope,
        clock = { mainClock.currentTime }, // hidden and settle times on the test's clock
    )
    val state = WebViewState(WebContent.Url("https://example.com/"))
    setContent { ProvideWebViewRuntime(runtime) { WebView(state, sizing = WebSizing.WrapContentHeight(max = 400.dp)) } }
    waitUntil { state.phase == WebViewPhase.Live }
    assertEquals(240.dp, state.contentSize?.height)
    // The pool made a spare at cold start too: every engine was used as the contract says.
    engines.created.forEach { assertEquals(emptyList(), it.violations) }
    scope.cancel()
}
```

- **`FakeEngineBehavior`**: `paintsOnLoad`, `contentHeight` or `contentHeightInViewport` (a page sized by its
  viewport), `pageState` (what the page's save provider returns), `resetSucceeds`, `needsCutouts` and `color`. Unless
  `color` is set, each content paints a colour of its own, derived from its key (`colorOf(content)`), so a screenshot
  shows which snapshot covers which view.
- **The factory** can be held open (`createGate`), made to fail (`failCreate`), or report a platform without engines
  (`unavailable`); `created` keeps every engine it made.
- **An engine** can be driven (`emit` any event, `postFromPage` as the page's `hostBridge.post`, `resetGate`,
  `saveStateGate`) and inspected: `calls`, `cutouts`, `lastSetup`, `config`, `released`, and `documentsStarted`, which
  tells a page kept alive from one started again. Like a real one, it delivers no event of a document after the next
  navigation, and `replayState()` repeats the current document's last commit, history, title and size, and its first
  paint once it has painted.
- **`violations`** lists every break of the contract by its caller: a load without `configure`, lease calls during or
  after a failed reset, anything after `release`, a surface composed twice. Assert it stays empty.

## The perf lab

[`webview-sample`](../webview-sample) is the sample app and the perf lab: on the fake engine on desktop, and on the
iframe engine in a browser:

```
./gradlew :webview-sample:run       # the desktop window: the perf lab and the overlay demo
./gradlew :webview-sample:jvmTest   # the lab's scripted runs, every frame checked; screens to build/sample-screens/
./gradlew :webview-sample:wasmJsBrowserDevelopmentRun   # the same in a browser, served on localhost only
```

The lab is a chat of 50 to 1,000 messages, 200 by default. Each message embeds a `WhenVisible`, `WrapContentHeight`
web view: an agent demo as `Html`, every fifth a `Url` and every tenth a plugin `Bundle`, whose button tells the app
over the bridge (`hostBridge.post('clicked', …)`), and the message shows it. The list is a `LazyColumn` or
a plain `Column`, or shows still images instead, as the baseline. Its scripted runs, from the top to the end and back
without stopping (Scroll test), a screen at a time (Reading test), or one fling, each print a `perf-lab` line with the
frames, the engine churn, the peaks (live and parked pages among them) and the memory held. The numbers panel shows
live, parked and spare engines as they change. Buttons send memory pressure and recovery.

These numbers are for the **fake engine**: its pages are solid colours. They come from `:webview-sample:jvmTest` on
2026-10-29 (Apple M1, JDK 17), in a 1,024 × 768 window at density 1, over 200 messages, with hidden views keeping
their engines, lazy items parking their pages, and compact snapshots:

| run | budget: live + spares | engines created / destroyed | peak live | peak parked | peak engines in existence | pages started | snapshots | peak covers | snapshot cache at the end | page memory at the end |
|---|---|---|---|---|---|---|---|---|---|---|
| Reading test, `LazyColumn` | 2 + 3 | 4 / 0 | 2 | 2 | 4 | 126 | 126 | 1.9 MB, 4 views | 32.0 of 32.0 MB, 72 kept | 126 pages, 35 KB |
| Reading test, `Column`, all 200 composed | 3 + 3 | 5 / 0 | 3 | 0 | 5 | 202 | 202 | 1.9 MB, 4 views | 31.6 of 32.0 MB, 74 kept | 196 pages, 55 KB |
| Scroll test, `LazyColumn` | 4 + 3 | 5 / 0 | 3 | 3 | 5 | 3 | 2 | 922 KB, 2 views | 922 KB of 32.0 MB, 2 kept | 3 pages, 855 B |

Before parking, the same runs created and destroyed 8 / 5, 6 / 3 and 5 / 2 engines. At this window's density of 1 the
embeds are about 990 px wide, within the pixel cap, so compaction only halves them (16-bit): the cache keeps about
twice the pictures it did. On a phone, at one pixel a dp, it keeps several times more. A 60-message reading run keeps
all 37 pictures it took, in 15.6 MB.

- In these runs, checked at every frame, no page started while the list moved, live engines never exceeded the
  budget, engines in existence never exceeded `maxLive + maxIdle`, and covers stayed within a screenful.
- **Memory pressure** (2 live + 3 spares, 32 MB of snapshots): live engines 2 → 1, engines in existence 2 → 1, the
  snapshot cache 922 KB (2 snapshots) → 399 KB (1), and still 399 KB 5 s later. After recovery: 2 live, 922 KB.
- **Frames.** The tests step a virtual clock one 16 ms frame at a time, so the frame intervals in the `perf-lab`
  lines (all 16.0 ms, none dropped) say nothing about smoothness. What the harness measures is the wall-clock cost of
  each frame the list moves: the scroll, composition, layout and drawing, and the runtime's queued work. In the last
  run's Scroll test that was p50 0.9 ms and p95 2.5 ms with web views, against p50 3.3 ms and p95 7.2 ms with still
  images. Over three runs, p50 ranged from 0.9 to 2.7 ms against 3.2 to 5.9 ms, and p95 from 2.5 to 9.1 ms against
  4.5 to 12.7 ms. With solid-colour pages, that is the list's cost and the runtime's, not an engine's.

Two of the design's targets hold on the fake engine: over a 200-demo list, live engines stay within the budget and the
snapshot cache within its bound. The others are goals for the engines, to be measured on each platform once its engine
exists: no more dropped frames with web views than with images, first paint of a settled view within 100 ms on
desktop and 200 ms on mobile from a warm spare, a bridge round trip within 2 ms on desktop and 5 ms on mobile (p50),
60 fps for frame-mode engines, and cut-outs that follow a menu's open animation in the same frame.

**In a browser, on the iframe engine.** These numbers come from `web-check/run.mjs lab` in headless Chrome 156 with the
GPU, on 2026-10-29 (Apple M1, its load 5 to 14 on 8 cores, shared with other work):
- Reading test: `Reading test, LazyColumn · 200 web views: 2315 frames, p50 16.7 · p95 16.7 · p99 16.8 · max 33.4 ms,
  2 dropped; engines created 38, destroyed 40, peak live 5/12, peak in existence 6/15; pages started 201, snapshots 0;
  covers up to 0 B (0 views); cache 0 B of 64.0 MB; page memory 200 pages, 45 KB`. At rest: `engines live 0 of 12 ·
  spares 3 · in existence 3 · created 43 · destroyed 40 · pages started 204 · snapshots 0`, and Chrome's process tree
  at 1601 MB of RSS, which counts the framework's shared pages once in every process.
- Scroll test: `Scroll test, LazyColumn · 200 web views: 1766 frames, p50 16.7 · p95 16.7 · p99 16.8 · max 16.8 ms,
  0 dropped; engines created 0, destroyed 2, peak live 3/12, peak in existence 5/15; pages started 0, snapshots 0;
  covers up to 0 B (0 views); cache 0 B of 64.0 MB; page memory 3 pages, 688 B`. At rest: `engines live 0 of 12 ·
  spares 3 · in existence 3 · created 5 · destroyed 2 · pages started 3 · snapshots 0`, and 1616 MB.
- From showing a view to its page's first paint, cold: 200.8 ms, with no spare yet and the first web view in the page
  (spec §10 asks ≤ 100 ms on desktop from a warm spare, which this doesn't measure).
- The bridge's round trip through the whole library: p50 0.1 ms, p95 0.4 ms, and 0.3 / 0.7 ms in another run (spec
  §10 asks ≤ 2 ms on desktop).

## The web engine

On the web (wasmJs), each live page is an `<iframe>` (`IframeWebEngine`). It was built after a feasibility test that
measured the risky parts first. The numbers are below.

- **Where it sits.** Compose for Web draws on a canvas, and an iframe can't be drawn into one, so the page sits above
  the canvas.
  - It sits in a layer of the library's own, next to Compose's interop container.
  - A page is placed in the same layout pass that moves its view, and clipped to what its scroll container shows.
  - It is never moved to another parent, because an iframe that moves reloads. Compose's `HtmlElementView`
    re-inserts its element when Compose stops placing it, and that reloaded pages in the test.
- **Overlays.** Compose can't draw over the page, so the page cuts out what is drawn over it.
  - Every `Modifier.webViewOverlay` becomes a hole. It uses `clip-path`, which clips clicks too.
  - A `Modifier.webViewScrim` dims the page, and the page takes no clicks under it.
  - **Mark everything you draw over a page**, buttons and labels included. Anything unmarked is under the iframe.
- **Documents.** Every document the app starts gets a fresh iframe. So the engine adds nothing to the browser's history,
  and a page from an earlier view can't come back.
  - `Html` content is a `srcdoc` in `sandbox="allow-scripts"`: an origin of its own, with no storage, no popups and no
    top-level navigation.
  - `Url` pages keep their own origin (`allow-scripts allow-same-origin allow-forms`). Use them only for pages you trust
    to frame.
- **The bridge** runs over a `MessagePort`.
  - Each document creates its own port, and hands it to the app with its first message, together with a secret the
    engine gave it.
  - Messages for a document go only to its port, and only if it has the origin they are for.
  - A link the user clicks in a demo goes through `WebClient.onNavigation`, so by default it opens in a new tab.
- **Scrolling.** A wheel or a touch drag over a live page goes to the page alone: Compose never sees it. So the page's
  shim sends on what the page couldn't scroll itself, and the view hands it to Compose's nested scroll.
  - A wheel over a demo scrolls the demo while it can, then the list around it.
  - A drag that ends while it is chaining flings the list.
  - Only input the browser made counts, at most 400 CSS px a frame.
- **Budget.** 12 live iframes (`RuntimeConfig.platformDefault()`), from the memory measured below.
  - The measurement was of demos, which share a renderer process. A page of another site (`Url`) runs in a process of
    its own, so only 4 of them are live at once.
  - Past that, one whose view is off screen gives up its page, which loads afresh when the view is back. With none to
    give, a page waits behind its cover.

| Measured on 2026-10-29 (Apple M1, Chrome 156) | Result |
|---|---|
| Reloads of live pages while a list scrolls, gets items inserted above, reorders, or doesn't place a page for 5 frames | None, in all four, in two runs. Compose's own `HtmlElementView`, for comparison: 1 reload, when the list didn't place it for 5 frames |
| Clicks through a cut-out reach Compose, and elsewhere reach the page: 6 points, rounded corners, synthetic and trusted clicks | All 6 points, and the page under a scrim takes none, in Karma and with trusted clicks. This needs the frame's outer element to take no clicks itself: as first written, it filled every hole |
| Placing about 12 live pages each frame while their list scrolls (JS time) | With the runtime and 12 live pages: p50 0.0 ms, p95 0.3–0.4 ms, mean 0.09–0.10 ms (the page's clock steps in 0.1 ms). The first test created 18 iframes as they scrolled in and measured p95 0.5–1.3 ms, mostly that creation, which `WhenVisible` doesn't do mid-scroll. A moving menu's hole was late in 0 of 32 of its frames |
| Chrome's memory per live demo page, and after 200 pages came and went | Too little to tell from Chrome's own drift on a busy Mac. Physical footprint over 48 pages gave −1.07 to −0.74 MB a page, and a per-process breakdown about 2.4 MB. Summed RSS counts the framework's shared pages in every process and can't measure it at all. After 200 cycles: +15 and −16 MB, within about ±40 MB of drift |
| Bridge round trip, p50 / p95 | Raw port: 0.0–0.1 / 0.1–0.2 ms. `WebBridge` over the port: 0.1–0.2 / 0.2–0.3 ms. Through `WebView`: 0.1–0.3 / 0.4–0.7 ms |
| Browser history | The engine's documents add no entries, and Back never navigated a new page's iframe. Chrome keeps a removed iframe's own entries (those its page made), and Back over them does nothing |
| The browser sample, with trusted input (`web-check/run.mjs overlay` and `wheel`) | A menu and a dialog over a live page take real clicks, and the page under them doesn't. A link the user clicks in the page opens outside the app. A wheel of 300 px over a live demo moves the list 300 px |

**What the web can't do.** The engine says so instead of pretending.
- **No snapshots.** A cross-origin page's pixels can't be read, so a cold view shows its placeholder.
- **No `evaluate`, no back or forward, no stop.** A page's own navigations still add entries to the browser's
  history, as any iframe's do: links it follows itself, `pushState`, and fragment links. Chrome keeps those entries
  after the page's view is gone. Back then steps over them, one press each, and they navigate nothing.
- **Dialogs, file choosers, permissions and downloads** are denied by the sandbox, and `WebClient` never hears of them.
- **`userAgentSuffix` and `inspectable` do nothing.** The browser's DevTools always work.
- **Some content is refused.**
  - `WebContent.Bundle`, `Url` with headers, and `Url` in an `Ephemeral` profile report `UnsupportedOnPlatform`.
  - A `Url` on the app's own origin, or `http:` in an `https:` app, reports `BlockedByPolicy`.
  - Named profiles share the browser's one storage.
- **Most sites refuse to be framed.** 36 of 42 top sites refuse, and logins inside third-party iframes fail, so `Url`
  is for pages you serve or proxy yourself.
  - Serve such a preview on an origin of its own (a subdomain), never under a path of the app's origin.
  - Serve the app's own page with `Content-Security-Policy: frame-ancestors 'none'`, so no page can load the app inside
    itself.
- **Scrolling is chained from demos only.** A `Url` page runs no shim, so a wheel or a drag over one scrolls that page
  and never the list around it.

To rerun the measurements, run these from `apps/` on a Mac with Chrome (elsewhere, set `CHROME_BIN`):

    ./gradlew :webview-compose:wasmJsBrowserTest -Pwebview.spike=true --tests 'dev.supermux.webview.engine.web.spike.*'
    node webview-sample/web-check/run.mjs memory webview-sample/web-check/probe
    node webview-sample/web-check/run.mjs input webview-sample/web-check/probe
    ./gradlew :webview-sample:wasmJsBrowserDistribution
    node webview-sample/web-check/run.mjs overlay webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs wheel webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs lab webview-sample/build/dist/wasmJs/productionExecutable

## Building and testing

```
./gradlew :webview-compose:jvmTest              # common and JVM tests; Compose UI tests on the fake engine
./gradlew :webview-compose:wasmJsBrowserTest    # common tests, the bridge shim and the web engine, in headless Chrome
./gradlew :webview-compose:compileDebugKotlinAndroid :webview-compose:compileKotlinIosSimulatorArm64
./gradlew :webview-sample:jvmTest               # the perf lab's scripted runs
./gradlew :webview-sample:wasmJsBrowserDevelopmentRun   # the sample in a browser on the iframe engine (localhost)
```

The Apple targets build on a Mac only; elsewhere their tasks are skipped. A library test task that runs past 10 minutes,
or a sample one past 15, fails instead of hanging.

## Not built yet

- The engines for Android, iOS, macOS, Windows and Linux, each with its platform's memory-pressure hook. Until each one
  exists, its platform reports `Unavailable`. Browsers have no memory-pressure signal to hook.
- On the web:
  - `WebContent.Bundle`. Each bundle needs an origin of its own, which the app's server has to provide.
  - A page's own back and forward.
  - A packaged shim for servers that put the bridge into `Url` pages. Until one exists, `Url` pages don't chain scrolls
    to the list around them either.
- A disk cache for snapshots.
- Batching bridge messages per frame, if real engines' numbers show it is needed.
- Publishing: the module isn't published to a repository yet.
