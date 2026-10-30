# webview-compose: the platform's own web engine, inside Compose

`dev.supermux.webview:webview-compose` is a Compose Multiplatform web view for apps that show **hundreds of web
views**: interactive demos posted into a chat, or a plugin system built on web pages. Each `WebView` is a cheap
handle. A small pool of live engines is shared among them, and a view without an engine shows a snapshot of its page,
or a placeholder.

It is built for each platform's own engine: Android `WebView`, `WKWebView` on iOS and macOS, WebView2 on Windows, WPE
WebKit on Linux, and an `<iframe>` on the web. **The web's, iOS's, macOS's and Linux's engines exist** ([The web
engine](#the-web-engine), [iOS](#ios), [macOS](#macos), [Linux](#linux)); a platform whose engine isn't built yet
reports `Unavailable(NotImplemented)`. Everything around the engines is built and tested against the library's fake
engine on every platform: the pool, activation, snapshots, page memory, overlays, the page bridge and the engine
contract.

It depends on Compose and kotlinx (coroutines, serialization) only, nothing else of supermux. It targets Android, iOS
(arm64 and simulator arm64), the desktop JVM and wasmJs. It isn't published yet; depend on the project, as
[`webview-sample`](../webview-sample) does: `implementation(project(":webview-compose"))`.

The design, including the goals set for the engines, is in the [design document][design]. This README describes what is
built.

[design]: ../../docs/superpowers/specs/2026-10-28-webview-compose-design.md

## Status: what has actually been run, and where

| platform | engine | what has run | what has not |
|---|---|---|---|
| any | fake engine | `:webview-compose:jvmTest`: **856 tests**: 545 common ones (the pool, activation, overlay geometry, the bridge, the caches and page memory, `WebViewState`, content, URLs and the navigation policy, the engines' event queue, the WebKit engines' URLs, navigation rules, relay and saved state, the fake engine), 158 JVM-only ones (the `WebView` composable, its host and the overlay modifiers over the fake engine, in Compose UI tests), the macOS engine's 60 and the Linux engine's 93 (in their rows). `:webview-sample:jvmTest`: **13 tests**, among them a 200-message chat read and scrolled with every frame checked against the budgets, with no engine leaked | any real engine |
| browser (wasmJs) | iframe (`IframeWebEngine`) | `:webview-compose:wasmJsBrowserTest`: **650 tests** in headless Chrome 156: the 545 common ones and 105 more, covering the bridge shim, the WebKit relay, the web engine (its shim and port, documents and commits, frames, cut-outs and scrims, scroll chaining, the budget for pages of other sites, reset) and `WebView` on it end to end. Also its feasibility test (Karma and the DevTools protocol), and the sample's browser build driven with trusted clicks and wheels by `web-check/run.mjs`: see [The web engine](#the-web-engine) | Safari and Firefox (planned, by hand), mobile browsers, an installed PWA |
| iOS | `WKWebView` | `:webview-compose:iosSimulatorArm64Test` on the iOS 26.5 and 16.4 simulators, **591 tests**: the 545 common ones, the snapshot codec's among them, and 46 iOS ones (the engine driven through its WebKit callbacks, its replay to an adopting view, its handoff from the cover, the scheme handler, snapshot pixels at the screen's scale, script results' JSON, memory warnings, the view that holds the page, and the tests' own wait for the main queue, which a loaded Mac can't cut short). The engine's **31 conformance checks**, in the sample app with real WebKit pages (`webview-sample/device-checks/ios-engine-checks.sh`): 31 of 31 on the iOS 26.5 and 16.4 simulators, among them a parked page adopted live, unreloaded, with its value, scroll position, URL, history, title and bridge as they were, and its first paint only once on screen; a cover, stored and decoded, against the screen (mean difference 0.96 of 255, p99 9); a first paint that waits for the page's content, and not for good; and the handoff from the cover, every display tick against the page, five rounds in a row ([below](#snapshots-and-page-memory)). The sample's UI flows with Maestro (`ios-sim.sh`): live embeds, a drag on an embed scrolls the chat, a plugin's button reaches the app, menus and dialogs over a live page, typing. Simulator numbers: first paint from a warm engine p50 1012 ms (to its commit 883 ms; from its commit 133 ms, where it took 67 ms before the handoff rule's wait for content and two display ticks), the bridge round trip p50 0.2–0.3 ms, a snapshot at the screen's 3× (960 × 1440) p50 33 ms | a physical iPhone or iPad; VoiceOver; IME composition (kana to kanji); a WebContent process killed by the OS; real memory warnings; media suspended while hidden (this simulator played no generated audio, hidden or not); the 200 ms first-paint target |
| macOS | `WKWebView` under the Compose canvas, over JNI | `:webview-compose:jvmTest`, now **856** tests, among them the macOS engine driven through a fake of its glue (epochs, the handoff, the motion cover, reset, release, the bundle's scheme), its surface in Compose UI tests, and, with the real glue loaded, its ABI, its JSON and its hit test's region. The engine's **41 checks** in the desktop sample's window on a MacBook Air M1 (macOS 26.6.2, 2×, 60 Hz): the 31 shared with iOS, 7 of the Mac's own (presses around marked overlays, a menu closed by the page, ⌘A and ⌘Z, focus, VoiceOver's reach, memory pressure, wheel routing) and 3 for motion: `CHECKS DONE passed=41 failed=0`. Motion, the page's content trailing inside its view without the cover → with it: pane 52/179 → 0/181, window 43/180 → 0/187, widest 10 px; a list scroll, which the cover leaves live, 0/180, with the cover never drawn; the window's backing in none of them. The handoff, five rounds in a row: 420 frames judged, 0 differ from the page. First paint from a warm engine p50 90.1 ms (after the commit 39.9 ms), the bridge round trip p50 0.2 ms, a snapshot at 2× (640 × 960) p50 3.0 ms; the perf lab over 200 messages, web views against still images: Reading 1–3 vs 1–5 dropped, Scroll 1–5 vs 1–3 dropped, peak live 4 of 8; load average 3–18 at the runs | trackpad momentum and pinch, Turkish and Japanese IME, ⌘C/⌘V, VoiceOver reading the page, a real live resize, menus and dialogs by hand (the manual checklist); a ProMotion display; an Intel Mac (the x86_64 slice is built, never run); an app packaged with Compose's mac packaging |
| Linux | WPE WebKit 2.54.2 (`LinuxWebEngine`), its frames drawn by Compose | `:webview-compose:jvmTest`: **93 Linux tests**: the engine on a fake native view (leases, documents, the bridge, local content, the surface's frames and input, the handoff from the cover, reset, parked pages, the page's requests). The engine's **40 conformance checks**, in the sample app with real WebKit pages, in Ubuntu 24.04 with no WPE of its own, on the runtime this module builds (190.5 MB, 57.9 MB zstd; its libavif decodes only, with dav1d), software rendering (`webview-sample/device-checks/linux-engine-checks.sh`): 40 of 40 on that runtime, and the first 39 three runs in a row on the one before it (208.2 MB, 63.7 MB zstd, Ubuntu's libavif with its AV1 encoders). Among them the page on screen at the moment of its first paint, Turkish typed (through the X server, but for its Latin-1 capitals, which go in as AWT key events: xdotool lowercases them), a subframe asked about as one, a parked page adopted live, and an AVIF image decoded. The sample on WPE engines, 200 web views in a LazyColumn: the scroll test 1888 frames, p50 15.9 ms, p99 16.1 ms, 1 dropped, peak live 4 of 6; the reading test 2418 frames, p50 15.9 ms, p99 16.0 ms, 1 dropped, 49 engines created, peak live 5 of 6 and 8 of 8 in existence. The feasibility test's numbers: 1080p copy p95 3.48 ms, bridge round trip p50 0.22 ms, 141.7 MB per view. GPU gate, WebKit and the upload on an AMD GPU (Mesa), in a container, **accepted as measured** (not a ship blocker), on a weak, loaded integrated GPU (the dev box's Radeon Vega Mobile, the container capped at 2 CPUs, the box's load 15 to 18): a full-frame 1080p animation 20.5 fps (4.6× its software rate), but copy + upload p95 10.4 ms (the bar is 4) and a warm spare's first paint 127 ms (the bar is 100); with the GPU a view costs 70.5 MB ([Linux](#linux)) | Compose drawing on a GPU (a real desktop session); the GPU gate on a desktop's GPU; NVIDIA's proprietary driver (a risk, [Linux](#linux)); IME composition; `<select>` popups; arm64; Wayland without XWayland; the bubblewrap sandbox in a real session (the checks run with it off, in Docker) |
| Android, Windows | not built | the module compiles for Android (`compileDebugKotlinAndroid`) | everything else: they report `Unavailable(NotImplemented)` until their engine is built |

All of it ran on 2026-10-29 and 30 on an Apple M1 Mac (macOS 26.6), JDK 17, Xcode 26.5. The perf lab's numbers, for the fake
engine, are [below](#the-perf-lab).

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

In a browser and on iOS this shows the demo, live once it is on screen and has stopped moving. On the other platforms it
shows the placeholder for now: once the view tries to go live, `state.phase` is `Unavailable(NotImplemented)`.

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

- **Budget.** At most `maxLive` engines are leased or parked at once: 4 on phones, 6 on tablets, 8 on desktop (6 on
  Linux, with 2 spares instead of 3: every WPE engine is a web process of its own, about 142 MB) and 12 iframes on the
  web (`RuntimeConfig.platformDefault()`). Over budget, a view takes the slot of a parked page first,
  the one parked longest, then evicts the lowest-priority view below its own; with nobody to evict, it keeps its cover
  and waits for capacity. Priority: the focused view (touched last, or holding the keyboard focus), then visible
  `Eager` views, then other visible views, then hidden ones, then parked pages. Ties go to the least recently used,
  where use means a touch or coming into view.
- **Parked pages.** A view that leaves the composition with its page live (an item a `LazyColumn` disposes as it
  scrolls away) saves the page as before, then parks it in the pool instead of giving its engine back: unreset, hidden
  (`setVisible(false)`), with no holder. The next view of the same page, in the same profile and engine config, adopts
  it as it is: no load, no reset, its in-page state intact, and Live as soon as the engine replays its page. Only one
  view adopts a page; a second gets an engine of its own. A parked page holds its slot until a view needs it, or for
  `hiddenReleaseMillis` (10 min, as a hidden view) if no view adopts it: then it saves what it has (its page state and
  engine state, bounded as an eviction is) and its engine is reset, for that view or as a spare. A page that crashed
  while parked is released, and a view of it starts fresh.
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
  iOS memory warning, the macOS memory-pressure source); on Android, iOS and macOS the library listens for these itself
  while any of the runtime's web views is shown, elsewhere it doesn't yet. It releases the spares and every parked page
  (each saves itself first), evicts live views down to half the budget, hidden ones first and never the focused one, and
  the snapshot cache lets go of every decoded snapshot and half of what it stores. Until `runtime.onMemoryRecovered()`,
  or 30 s after the latest call, the budget stays at half, no spares are kept, nothing is parked, and the snapshot cache
  keeps nothing decoded and stores within that half, even as the evicted views snapshot their pages on the way out.
  Views on screen still get their covers decoded, and hold them themselves.

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
- Compose keeps items that scrolled out of a `LazyColumn` composed for a while without placing them: those it measured
  on its way to the new first item stay so until it measures without them, which a list at rest may not do for a long
  time, and those kept for reuse until they are deactivated, which disposes them. An item composed but no longer
  placed counts as hidden, as in a `Column`: told so and ranked lowest, so a visible view that needs a slot takes its
  engine first. Scrolled back first, it comes back as it was.
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
  stays until the engine has drawn the document the view started in its place, with its content, for a frame
  (`FirstPaint`): the page draws under the cover, unseen, so the first frame without the cover is one the page drew,
  and with engines that keep their contract, a page never shows a white, blank or bare-background frame. Navigations
  the page makes itself, reloads and history navigations keep the previous page on screen until the next one paints:
  engines hold paint, by contract.
- **The handoff, measured.** On the phone (Galaxy Z Fold7, WebView 156) every frame around the cover's lift is
  copied and compared with the page (`HandoffFlashTest`), for a cold activation from the cover, a parked page adopted
  and a page hidden then shown, light and dark. Before, a cold activation showed one frame of the page's bare
  background, without its text: the cover lifted on the visual state of the document's commit. Android now waits for
  the document's first contentful paint (or its load, or 3 s), then a visual state callback and two frames drawn in
  place, about 30 ms in all after the commit; five runs in a row showed no frame that differs from the page. On the
  web the covered iframe draws in its place at `opacity: 0` (a frame in a hidden box draws nothing at all), and is
  shown a frame after its load and commit. On the iOS simulators (26.5, and 16.4 on an iPhone 14) the sample app runs
  the same check (`HandoffChecks`): the window copied at every display tick around the lift, after making sure its
  copies show the Compose canvas over a page. On 26.5, before any change, one frame in 30 runs differed: all grey, an
  adopted view's place before its first frame showed, a copy a frame behind the canvas (which presents late while no
  native view is in it), so a view now counts as in place after three ticks there. On 16.4 a cold activation showed
  its cover as the page shrunk into a quarter of its place, on white (light: mean 19.4, p99 219; dark: mean 200.7, p99
  237), in every round: a new `WKWebView` had no size when its first page committed, WebKit zoomed that page out to
  its least scale, 0.25, and iOS 16 kept that scale once the view had its size, so its snapshot did too. A web view
  made at the screen's size laid the page out at the screen's width instead, in 7 of 10 cold activations: the view's
  size, changed while the page loaded, didn't reach the page. So a new `WKWebView` starts at the screen's size, and a
  page that started loading at another size is told the view's size again when it commits. iOS waits as Android does,
  too: a document's first paint is its first contentful paint (or its load, or 3 s), two animation frames, then two
  display ticks with the web view in its window, never hidden under the cover; an adopted page waits two display ticks
  once it has drawn again. After: five rounds in a row on each simulator, all six cases, and no frame differs from the
  page.
- **When.** Snapshots are taken only while the page is composed and laid out: 500 ms after its first paint, 500 ms
  after the user's last interaction, and when a deactivation or an eviction takes the engine, before the view lets go
  of it. A view that leaves the composition takes none, and keeps its last good snapshot.
- **Sharp.** A cover looks like the page: the runtime keeps every engine's snapshot at the screen's own density, up to
  3 pixels a dp (with 4,000,000 pixels as a safety cap only), with its aspect ratio, and in full colour, never 16-bit.
  This happens in one place, after `engine.snapshot()`, for every engine. On the phone (Galaxy Z Fold7, cover screen
  1080 × 2520 at density 2.625, WebView 156), a 480 dp embed's cover, stored and decoded as below, against the live
  page as the screen showed it: an agent demo differed by 0.63 on average per colour channel (of 255), and by 7 at the
  99th percentile; a text-heavy article by 0.48, and 7. The page's own draw alone differed from the screen by 0.01. On
  the iOS simulator (iOS 26.5, an iPhone at 3×), a 320 × 480 pt article embed: the page's own snapshot was the screen
  pixel for pixel, and its cover, stored and decoded, differed by 0.96 on average and 9 at the 99th percentile.
- **The page, never the app.** On Android a snapshot is the WebView drawn by itself into a bitmap, on the main thread:
  nothing the app draws over the page (its header, a menu, a dialog, the runtime's cover) can end up in it, and a page
  half off screen still gets one. One that comes out all one colour (video and WebGL, which that path can't draw) is
  discarded. Only then does a copy of the window (`PixelCopy`) stand in, and only with the view wholly on screen and
  nothing the app marked over it: the runtime tells the engine before each snapshot (`overlaysAtSnapshot`). On iOS a
  snapshot is `WKWebView.takeSnapshot`, which WebKit renders from the page itself, so nothing over it can end up in it.
  The web takes no snapshots (cross-origin pixels can't be read).
- **Its cost.** On the phone, at the screen's density, a 480 dp embed draws itself in 13 to 21 ms (p50), and a demo
  filling the screen (1080 × 2520) in 14 to 28 ms (p50 over three runs of 20 draws, 39 ms at most). A page's first
  draw after it loads is cold, 2 to 4 times slower (34 ms for the full-screen demo). The draws should fit 32 ms. One
  that takes longer is kept, since the main thread has waited for it already; unless it was the page's first, it sets
  how many pixels fit (with a 20 % margin), so a view as big is drawn at the largest of 3, 2.5, 2 and 1.5 pixels a dp
  that fits from then on, and smaller views keep the screen's density. A WebGL page's draw came out blank, and was
  discarded. On iOS a snapshot needs no such budget: WebKit renders it off the main thread, and the engine copies its
  pixels into an image off it too. On the simulator (a Mac shared and loaded), a 320 × 480 pt embed at 3×, 960 × 1440,
  took 27 to 37 ms from asking to the image (p50 33 ms over 20), against 16 to 34 ms at 2× (p50 17 ms).
- **Stored encoded.** The cache stores each snapshot encoded, off the main thread: a JPEG at quality 92, or a PNG for a
  page with a transparent background, which keeps its alpha. On the phone the demo embed's cover is 90 KB stored and
  the article's 169 KB, against 5.4 MB of pixels; encoding took 5 ms (p50, 22 ms at most) and decoding 3 ms (8 ms at
  most). On the iOS simulator the article embed's cover is 179 KB against 5.5 MB of pixels; Skia encoded it in 6.4 ms
  (p50, 12 ms at most) and decoded it in 4.3 ms (7 ms at most). Least recently used out first, within
  `snapshotCacheBytes` of stored bytes: 32 MB on phones and tablets, so 150 to 300 sharp pages; 128 MB on desktop,
  64 MB on the web. Keyed by profile, content, pixel size, theme and density: a view never shows a snapshot of other
  content, of another profile or of the other theme.
- **Decoded on demand.** Beside them the cache keeps decoded snapshots, ready to draw, least recently used out first,
  within `decodedSnapshotBytes` of pixels (24 MB, 64 MB on desktop): the ones just taken, and every decode. A view
  asks for its cover's decode as it is composed: a lazy list composes an item just before it scrolls in, so its cover
  is usually ready by the frame it shows. A decode runs off the main thread, once however many views ask; while it
  runs, the view shows its placeholder, and its cover from the frame after. Only views on screen hold their cover's
  pixels, so covers take about a screenful, and no placeholder is composed under a cover.
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

With the engines as designed, Compose draws over the page on Android, iOS, macOS and the frame-mode desktops (Windows,
Linux), so menus, dialogs and tooltips simply cover it. A page that sits *above* the Compose canvas can't be covered:
the web's `<iframe>`. Such a page cuts the overlays out of itself, and dims under the scrims, so mark them:

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
- The geometry is common code, and unit-tested: each live page gets its visible area minus the overlays as an even-odd
  path, whose holes are disjoint and cover exactly the overlays. An isolated overlay keeps its per-corner radii, and so
  does one that contains the others away from its corners. Applying it, with the scrims, is the engines' part
  (`applyOverlays`). The web engine cuts the holes with CSS `clip-path`, which clips clicks too, and dims under each
  scrim, where the page takes no clicks. On macOS Compose draws over the page, but a press goes to whatever is under the
  pointer natively: the page takes no press inside a marked overlay or under a marked scrim, so mark the overlays that
  take clicks there too.

**Material dialogs.** Material's dialog draws its scrim in a layer of its own, which no modifier reaches, so a page
above the canvas would stay bright under it. On macOS the page dims under it, but still takes the clicks there. Turn
that scrim off, and draw a full-window one behind the dialog's content:

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

The rest is the engines' part, set by the design and the engine contract:
- no `file://` access;
- mixed content blocked;
- pop-ups denied;
- certificate errors never bypassed;
- inspectors only when `inspectable = true`;
- the shim's channel in an isolated world where the engine has one;
- every message checked against the receiving document's origin when it arrives.

The web engine's share is described [below](#the-web-engine). The iOS engine does all of it, and its conformance checks
test the bridge's parts with real pages ([iOS](#ios)). For the other engines, which don't exist yet, these are goals.

## The runtime

```kotlin
// One per app, created once, for the life of the process.
val webViews = WebViewRuntime(config = RuntimeConfig.platformDefault().copy(maxLive = 6))

ProvideWebViewRuntime(webViews) { App() }
```

- `RuntimeConfig(maxLive, maxIdle = 3, snapshotCacheBytes, decodedSnapshotBytes = 24L shl 20, hiddenReleaseMillis = 600_000, pageMemoryEntries = 512)`.
  `snapshotCacheBytes` bounds the snapshots as stored, encoded, and `decodedSnapshotBytes` the decoded ones ready to
  draw, in pixel bytes. `hiddenReleaseMillis` is how long a view off screen keeps its engine when no visible view needs
  it, and how long a parked page keeps its own when no view adopts it.
- `WebViewRuntime(factory, config, scope, clock)`: `factory` creates the engines, the platform's by default. `scope`
  runs the pool's and the views' work, on `Dispatchers.Main` by default; on desktop the library brings
  `kotlinx-coroutines-swing` for it.
- Control it through the runtime: `onMemoryPressure()`, `onMemoryRecovered()`, `onError`, `clear()` and
  `forget(content)`. The pool's own controls are internal, so the cache halving, the error forwarding and the budget
  (`RuntimeConfig.maxLive`) stay consistent.
- For monitoring, as the perf lab does: `pool.liveCount`, `parkedCount`, `idleCount`, `budget` and `maxLive`;
  `snapshots.sizeBytes`, `count` and `maxBytes` (stored), `decodedBytes`, `decodedCount` and `decodedMaxBytes`;
  `pages.count` and `sizeBytes`; `coverBytes` and `coverCount`.
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
  `Error`. `FirstPaint` means the document is drawn in its place with its content, under the runtime's cover, for at
  least a frame: not its commit, and not its bare background. Between documents, the engine keeps showing the previous
  one until the next paints.
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
  - iOS: the page's media is suspended (`setAllMediaPlaybackSuspended`): what plays stops, the page can't start it
    again, and it resumes when shown. Its JavaScript keeps running while its view is in a window. A parked page's
    `WKWebView` is out of any window, where WebKit throttles it by itself (its timers, and its animation frames, which
    stop); adopted, its first paint waits until it has drawn the page in its new place, and the view's cover hides it
    until then.
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
their engines, lazy items parking their pages, and sharp snapshots stored encoded:

| run | budget: live + spares | engines created / destroyed | peak live | peak parked | peak engines in existence | pages started | snapshots | peak covers | snapshot cache at the end (stored, kept, decoded) | page memory at the end |
|---|---|---|---|---|---|---|---|---|---|---|
| Reading test, `LazyColumn` | 2 + 3 | 4 / 0 | 2 | 2 | 4 | 132 | 131 | 3.6 MB, 4 views | 280 KB of 32.0 MB, 131 kept, 23.9 MB decoded (29) | 132 pages, 37 KB |
| Reading test, `Column`, all 200 composed | 3 + 3 | 6 / 0 | 3 | 0 | 6 | 200 | 200 | 3.9 MB, 4 views | 415 KB of 32.0 MB, 199 kept, 23.5 MB decoded (28) | 196 pages, 55 KB |
| Scroll test, `LazyColumn` | 4 + 3 | 5 / 0 | 3 | 3 | 5 | 3 | 2 | 1.8 MB, 2 views | 4 KB of 32.0 MB, 2 kept, 1.8 MB decoded (2) | 3 pages, 855 B |

Before parking, the same runs created and destroyed 8 / 5, 6 / 3 and 5 / 2 engines. Every page the runs saw keeps its
picture: the reading run's 131 of 131 (pictures kept as pixels, even 16-bit at one pixel a dp, left 72 in 32 MB), and
a 60-message run's 34 of 34. The fake's solid-colour pages are about 2 KB each as JPEGs; real ones are tens to a couple
of hundred KB (below).

**On the phone, on Android's WebView.** The Reading test on the Galaxy Z Fold7's cover screen (1080 × 2520 at 2.625,
120 Hz), WebView 156, on 2026-10-29: `Reading test, LazyColumn · 200 web views: 4783 frames, p50 8.3 · p95 8.3 · p99
8.5 · max 58.5 ms, 31 dropped; engines created 1, destroyed 0, peak live 4/4, peak parked 3, peak in existence 6/7;
pages started 199, snapshots 197; covers up to 6.8 MB (4 views); cache 4.9 MB of 32.0 MB, 200 snapshots kept, 22.1 MB
decoded (14); page memory 200 pages, 242 KB`. Every message keeps a sharp picture, in 4.9 MB; with full-size pictures
kept as pixels, an earlier run's cache was full at 31.6 MB, and most pages had lost theirs.

- In these runs, checked at every frame, no page started while the list moved, live engines never exceeded the
  budget, engines in existence never exceeded `maxLive + maxIdle`, and covers stayed within a screenful.
- **Memory pressure** (2 live + 3 spares, 32 MB of snapshots): live engines 2 → 1, engines in existence 2 → 1, the
  snapshot cache 4 KB stored (2 snapshots) → 2 KB (1), and still 2 KB 5 s later. After recovery: 2 live, 4 KB (2).
- **Frames.** The tests step a virtual clock one 16 ms frame at a time, so the frame intervals in the `perf-lab`
  lines (all 16.0 ms, none dropped) say nothing about smoothness. What the harness measures is the wall-clock cost of
  each frame the list moves: the scroll, composition, layout and drawing, and the runtime's queued work. In the last
  run's Scroll test that was p50 0.5 ms and p95 1.5 ms with web views, against p50 2.3 ms and p95 3.1 ms with still
  images. Over earlier runs, p50 ranged from 0.9 to 2.7 ms against 3.2 to 5.9 ms, and p95 from 2.5 to 9.1 ms against
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

## iOS

`WKWebView`, in a `UIKitView` under the Compose canvas (spec §7). Compose draws over the page, so menus and dialogs need
no cut-outs.

- **Touches.** A `WrapContentHeight` view is an embed in a list: Compose gets a drag that starts on it first
  (`UIKitInteropInteractionMode.Cooperative`), so the list scrolls, and taps still reach the page. A `Fill` view is a
  pane: the page gets every touch at once (`NonCooperative`), and scrolls itself.
- **Local content.** `Html` loads on `https://c<hash>.invalid`, a secure origin of its own. WebKit can't serve `https`
  from the app, so a `Bundle` is served from `webview-bundle://<host>.invalid/…`, also a secure context: the page sees
  that as its origin (`self.origin`, `location.origin`), while the bridge, the navigation policy and `WebViewState.url`
  use `https://<host>.invalid`. Use relative URLs in a bundle: an absolute `https://<host>.invalid/…` fails, as
  `.invalid` never resolves.
- **The bridge** spans two worlds: `window.hostBridge` lives in the page's own, and its channel to the app in an
  isolated `WKContentWorld` that page scripts can't reach. Messages from the app are checked against the page's origin
  there, when they arrive.
- **The handoff from the cover.** The `WKWebView` is never hidden: composed, it is in its window under the Compose
  canvas and draws there, under the cover. A document says `FirstPaint` once it has content, two animation frames after
  its first contentful paint or its load (the isolated world sees both, with the page's JavaScript off too), or after
  3 s with neither, and then once the web view has been in its window for two display ticks (`CADisplayLink`). A new
  `WKWebView` starts at the screen's size, never none, and a page that started loading at another size is told the
  view's size again at its commit: iOS 16 kept the size a page started loading at.
- **Reuse.** `WKWebView` can't clear its history, so a reset puts back a one-page history that no navigation can reach:
  a page's `history.back()` never reaches the previous lease's pages, and `canGoBack` starts false.
- **What iOS has no public API for:** a file chooser hook (WebKit shows its own picker; `onFileChooser` isn't called),
  `beforeunload` dialogs (pages simply leave), and a user-gesture flag on navigations (a touch in the last second
  counts). A download goes to `onDownload` and is never saved: as on Android, the app gets the URL.
- **Profiles.** `Named` profiles get stores of their own from iOS 17 (`WKWebsiteDataStore(forIdentifier:)`); on iOS
  15–16 they share the default store. `Ephemeral` is a store per engine, wiped when the engine is given back.
- **Page state.** `Html` keeps no engine state (WebKit can't restore an in-memory document): it reloads, and gets its
  page state back through `hostBridge.onSaveState`. `Url` and `Bundle` pages get their history and scroll back.
- **Parked pages** ([above](#the-pool-and-memory)) keep their `WKWebView`, document and scroll position out of any
  window, with their media suspended. A view that adopts one hears its URL, history and title at once, and its first
  paint once the page has drawn in the new view (two animation frames there) and been shown there for two display
  ticks, which lifts the cover: nothing reloads, the page's own state is as it was, and its shim announces itself to
  the new view's bridge.
- **A WebContent process the OS kills** (it does, to apps in the background) puts its views in `Crashed`, as the
  contract says: one on screen goes live again by itself a second later, at most once a minute, and
  `navigator.reload()` brings any of them back. A parked page whose process was killed is released, and a view of it
  starts afresh.
- **Memory warnings** reach `runtime.onMemoryPressure()` by themselves, while any of the runtime's web views is shown.
- **Running it,** on a Mac, from `apps/`: `webview-sample/device-checks/ios-engine-checks.sh` (the conformance checks;
  `DEVICE="iPhone 14"` for iOS 16.4), `webview-sample/device-checks/ios-sim.sh` (the UI flows), and
  `MEASURE=1 webview-sample/device-checks/ios-engine-checks.sh` (the numbers). Simulators only, no signing.

## macOS

`WKWebView`, driven from the JVM through a small Objective-C library over JNI (`native/macos`), in the view AWT gives
the window, **under** skiko's Metal layer: Compose clears a hole where the page shows. A layout test in a real window
chose this over a page above the canvas (its results are below). macOS 14 or later; elsewhere on the desktop the engine
reports `Unavailable(NotImplemented)`.

- **Overlays.** Compose draws over the page, so menus, dialogs and tooltips show, shadows and all, as anywhere else.
  But a press goes to whatever is under the pointer natively: mark every overlay that takes clicks with
  `Modifier.webViewOverlay`, and every modal scrim with `Modifier.webViewScrim`, or the page takes their presses. A
  press on the page closes a Compose menu that is open, as a press outside it would anywhere.
- **Moving pages.** A native view can't move in the same frame as Compose: skiko presents from a thread of its own, and
  AppKit moves views on its main thread. The layout test saw a page trail its slot by a frame or two, with the window's
  own background in the gap. This engine's view keeps up with its slot; what trails is the page's content inside it
  while the view is resized (strips of WKWebView's own background, up to 16 px), until WebKit has drawn the page at its
  new size. So the cover shows only while a page is being resized: while its pane is dragged or the window is resized,
  the page's last snapshot covers the slot, and a page that only moves, in a list that scrolls, stays live. Either way
  the page takes no input until its slot is still and it has been drawn in its new place. A page that changed since its
  last snapshot jumps to its live state when the resize ends.
- **Input.** Typing and input methods are WKWebView's own. ⌘C, ⌘X, ⌘V, ⌘A, ⌘Z and ⇧⌘Z reach the page while it has
  focus (AWT would keep them); other ⌘ shortcuts are the app's. An embed (`WrapContentHeight`) leaves the wheel and
  trackpad gestures to its list, so a chat scrolls over its demos; a pane (`Fill`) scrolls and zooms itself. Trackpad
  swipe navigation is off: a swipe would show WebKit's picture of the page before a lease's first, which no lease may
  reach.
- **VoiceOver** reaches the page through a bridge on AWT's view, which lists each shown page's web view: AWT itself
  exposes only Java's tree. The bridge subclasses AWT's private view class at run time, and a check pins it.
- **The handoff from the cover, local content, the bridge, reuse and profiles** are iOS's (their WebKit rules are
  common code): a document's first paint once its content is drawn in place for two display ticks; `Html` on its own
  `https://<hash>.invalid` origin, `Bundle` on `webview-bundle://`; the bridge's channel in an isolated world; a reset
  back to a one-page history; a data store per named profile.
- **File inputs** ask `WebClient.onFileChooser` (macOS has the hook iOS lacks); paths or `file:` URIs come back.
- **The native library** is in the library's jar, built on a Mac (universal, arm64 and x86_64; the x86_64 half has never
  run) and signed ad hoc. On first use it is extracted to `~/Library/Caches/dev.supermux.webview/<version>/<sha-256>/`,
  checked against the jar's own bytes, so an app packaged by Compose's mac packaging, which re-signs the libraries inside
  jars, still loads it. `-Ddev.supermux.webview.macLibrary=<path>` loads another build of it.
- **Windows and backgrounds.** A page needs a Compose window or dialog (`ComposeWindow`, `ComposeDialog`); a
  `ComposePanel` inside a Swing frame isn't supported yet. A transparent page (`transparentBackground`) shows the window's
  backing under it, not the Compose content behind it.
- **Running it,** on a Mac, from `apps/`: `webview-sample/device-checks/mac-engine-checks.sh [check]` (the checks, in a
  window), `MEASURE=1 webview-sample/device-checks/mac-engine-checks.sh` (the numbers), `LAB=1` with the same script (the
  perf lab's runs), `webview-sample/device-checks/mac-sample.sh manual` (the sample for a person),
  `webview-sample/device-checks/mac-cleanup.sh` (what the runs left). Each starts the app in
  the logged-in session as a transient LaunchAgent, since an SSH session has no window server.

| Measured 2026-10-30, MacBook Air M1, macOS 26.6.2, 2×, 60 Hz | Result |
|---|---|
| The engine's checks in the sample's window | `CHECKS DONE passed=41 failed=0`, load average 7.2–8.6; 41 of 41 in the run before it too (6.8–9.5) |
| A pane dragged, a window resized (3 s each), without the cover | the page's content trailing inside its view (WKWebView's own background in the slot, 6 px in from its edges): pane 52 of 179 copies (a band up to 6 px), window 43 of 180 (up to 10 px). A list scrolled showed none in five runs before the cover left moves alone (0 of 179 in the last). The window's backing: 0 in every run, at load 5–15, so the lag the layout test measured, with the window's own background in the gap, was not reproduced |
| The same with the cover, and a list scrolled as the engine runs it (no cover for a move) | pane 0 of 181 (the cover drawn 178 times), window 0 of 187 (92 times), list 0 of 180, the cover never drawn; the window's backing in none |
| The handoff from the cover, five rounds of three scenarios, light and dark | 420 frames judged (14 around each lift), 0 differ from the page; a copy p50 6.5–12.0 ms, max 19.6 ms (load 6–9) |
| First paint from a warm engine | p50 90.1 / p90 98.9 ms; to the commit p50 51.3 ms, after it p50 39.9 ms (the best of three runs; their medians 90.1–94.9 ms; load 8–13) |
| The bridge's round trip | p50 0.2 / p90 0.5 ms (three runs: p50 0.2–0.3 ms) |
| One snapshot at 2× (640 × 960) | p50 3.0 / max 6.0 ms (three runs: p50 3.0–3.8 ms) |
| The perf lab, 200 messages in a `LazyColumn`: the Reading and Scroll tests with web views, then with still images | three runs, load 3–18. Reading, web views: 2395–2399 frames, p50 16.7 / p95 17.6–17.7 ms, 1–3 dropped; Scroll, web views: 1765–1768 frames, p50 16.7 / p95 17.5–17.7 ms, 1–5 dropped; Reading, images: 3226–3236 frames, p50 16.6–16.7 / p95 18.2–18.4 ms, 1–5 dropped; Scroll, images: 2358–2359 frames, p50 16.7 / p95 18.5–18.6 ms, 1–3 dropped. Peak live 4 of 8 (Reading) and 3 of 8 (Scroll), at most 11 engines in existence of 11; the snapshot cache 4.2 MB of 128 MB at the end |

The layout test (2026-10-30, the same Mac, under load 10–28): with the page under the canvas, 7 of 7 clicks went to the
right side, among them 3 px inside and 6 px outside a menu's edge; typing went to whichever side had focus; ⌘Z reached
the page only through the key monitor, and VoiceOver only through the bridge; the page trailed its moving slot by
10–17 px in a list, 25–33 px dragging a pane, and up to 358 px resizing the window. A page above the canvas, with
cut-outs, passed and failed the same rows and trailed as far (15–16 px, 18–29 px, up to 386 px), but hid the overlays'
shadows, so the tie went to the page under the canvas.

## Linux

WPE WebKit 2.54 in frame mode (spec §7, "Linux: frames"): WebKit renders off screen through WPEPlatform objects of the
engine's own, into shared-memory buffers, and Compose draws the frames. Compose draws over the page, so menus and
dialogs need no cut-outs.

- **The runtime.** WPE isn't in the jar. The app ships `wpe-runtime-2.54.2-linux-x64` (57.9 MB as `.tar.zst`,
  190.5 MB unpacked), built by `runtime/linux/runtime.sh` on Ubuntu 24.04, so it runs on 24.04 and newer; x64 only.
  - **Its libavif decodes only.** It is Ubuntu's (1.0.4, the same source and flags) with dav1d its only codec.
    Ubuntu's own also links three AV1 encoders and a second decoder, which WebKit never uses: with them the runtime
    was 63.7 MB.
  - **Where the engine looks:** `-Ddev.supermux.webview.wpe.runtime=<dir>`, then `$WEBVIEW_WPE_RUNTIME`, then
    Compose Desktop's packaged resources (`wpe-runtime/`), then `~/.local/share/dev.supermux.webview/wpe-runtime`.
  - **What it checks:** the runtime's manifest (version, ABI, architecture, every file's size) and the glue's
    sha256.
  - **Without one,** it reports `Unavailable(NoWpe)` with the reason, never a crash.
- **The sandbox.** WebKit runs pages in bubblewrap's sandbox. Where `bwrap` can't make one (a container without user
  namespaces) the engine is `Unavailable`, unless the app passes `-Ddev.supermux.webview.wpe.sandbox=off`.
- **Frames.**
  - A frame WebKit renders is copied, its damaged rows only, into one Skia bitmap at the start of the next Compose
    frame, and acknowledged then.
  - So a page draws no faster than the window shows it, and a hidden one not at all.
  - 1080p, in software: copy p95 3.48 ms, upload p95 1.46 ms.
- **On a GPU.** The GPU gate measured WebKit's painting and the upload on a GPU once, in a container on the dev box:
  a weak, loaded integrated GPU (Ryzen 5 3500U's Radeon Vega Mobile, Mesa's `radeonsi`), the container capped at
  2 CPUs and 3 GB, the box's load 15 to 18. Software, then the GPU, back to back. It is **accepted as measured**,
  not a ship blocker:
  - a full-frame 1080p animation: 20.5 fps, 4.6× software's 4.4 (the bar is 2×);
  - copy + upload p95, full frame: 10.4 ms (the bar is 4 ms): the copy is the loaded CPU's, and this GPU's upload
    (p95 4.3 ms) is slower than software GL's;
  - a warm spare's first paint: 127 ms (the bar is 100 ms; software 171 ms);
  - a view's memory: 70.5 MB, against 150 MB in software.

  To do: measure again on a real desktop GPU, and make full-frame animations cheaper to upload (a texture kept from
  frame to frame, partial uploads, or a capped frame rate while the whole frame repaints).

  **A risk, unverified: NVIDIA's proprietary driver.** Its EGL has no `EGL_MESA_platform_surfaceless`, the platform
  WebKit's shared-memory path renders through (without that EGL display, WebKit's source says, the web process
  aborts). So there WebKit would paint with Mesa's software rasterizer where Mesa's EGL is installed too, and its web
  process would abort where it isn't. Nothing has run on such a machine; CPU painting is the way out
  ([Not built yet](#not-built-yet)).
- **Input.**
  - Pointer, wheel and keys reach WebKit in its logical pixels, and a press focuses the page.
  - Characters come from AWT, so layouts, dead keys and AltGr work. An input method's composition doesn't reach the
    page yet.
  - In a `WrapContentHeight` view (an embed in a list) the wheel scrolls the list; in a `Fill` view, the page.
- **The bridge** spans two script worlds, as on iOS:
  - `window.hostBridge` lives in the page's own world;
  - its channel lives in a world named `webview`, which page scripts can't reach;
  - messages from the app are checked against the page's origin there, when they arrive.

  WPE's script messages carry no origin, so a page message's origin is its document's committed URL's.
- **The handoff from the cover.** A document says `FirstPaint` once it has content: two animation frames after its
  first contentful paint or its load, or after 3 s with neither. Then it waits for the screen: a surface has drawn
  WebKit's frame of that moment, or a newer one, at the surface's size, and two Compose frames have passed, still at
  that size. So the cover lifts onto the page as drawn, never onto a frame of the view's earlier size.
- **Navigation.** WPE says whether a navigation is the main frame's only at its response.
  - A page's http(s) navigations are asked about there: `isMainFrame` is right, but the request has gone out.
  - Other schemes are asked about at once, as the main frame's.
- **Dark mode** is the process's: the last view to set its theme sets every page's `prefers-color-scheme`.
- **Local content** is served as on iOS:
  - `Html` on `https://c<hash>.invalid`, and `Bundle` on `webview-bundle://<host>.invalid`, with relative URLs;
  - a missing file is an HTTP 404, and a file keeps its provider's headers;
  - an `Html` document has no back/forward entry: WPE keeps `load_html`'s document out of session history, so back
    from a page it navigated to skips it. (`load_alternate_html` adds the entry, but going back to it refetches the
    origin, which doesn't resolve.)
- **The page's requests:** dialogs, permissions, file choosers and new windows reach the client. WPE doesn't say:
  - which frame raised a dialog, so its `url` is the page's;
  - which origin asks for a permission, so it is the page's.

  File choosers take local paths (`file:` URIs). Downloads go to `onDownload` and are never saved. WebKit asks about
  notifications only from a user gesture, and only in a persistent profile: in an `Ephemeral` one (the default for
  `Html`) it denies them without asking.
- **Profiles.** A `Named` profile is a network session of its own, under `~/.local/share/dev.supermux.webview/wpe/`.
  `Ephemeral` is a session per engine, wiped when the engine is given back.
- **Parked pages** keep their document and their last frame.
  - Hidden from WebKit, which stops drawing them, and muted: WPE can't suspend a page's media as iOS does, so a video
    plays on silently.
  - A view that adopts one shows it as it was. Its cover lifts once the view has drawn the page.
- **Running it.**
  - From `apps/`, on the dev box: `webview-sample/device-checks/linux-engine-checks.sh` runs the conformance checks
    on `desktop`, in a plain Ubuntu 24.04. `MODE=lab webview-sample/device-checks/linux-engine-checks.sh` runs the
    sample unattended, with screenshots.
  - On a Linux desktop with the runtime unpacked into `~/.local/share/dev.supermux.webview/wpe-runtime`:
    `./gradlew :webview-sample:run -Dwebview.sample.engine=wpe`.

## Building and testing

```
./gradlew :webview-compose:jvmTest              # common and JVM tests; Compose UI tests on the fake engine
./gradlew :webview-compose:wasmJsBrowserTest    # common tests, the bridge shim and the web engine, in headless Chrome
./gradlew :webview-compose:compileDebugKotlinAndroid :webview-compose:compileKotlinIosSimulatorArm64
./gradlew :webview-sample:jvmTest               # the perf lab's scripted runs
./gradlew :webview-sample:wasmJsBrowserDevelopmentRun   # the sample in a browser on the iframe engine (localhost)
./gradlew :webview-compose:iosSimulatorArm64Test        # on a Mac: the common tests and the iOS engine's, in a simulator
webview-sample/device-checks/ios-engine-checks.sh       # on a Mac: the iOS engine's conformance checks, in the sample app
webview-sample/device-checks/mac-engine-checks.sh       # on a Mac: the macOS engine's checks, in the sample's window
webview-compose/runtime/linux/runtime.sh image && webview-compose/runtime/linux/runtime.sh build   # the WPE runtime, built on desktop (about 3 hours)
webview-sample/device-checks/linux-engine-checks.sh     # from the dev box: the Linux engine's conformance checks, on desktop
```

The Apple targets build on a Mac only; elsewhere their tasks are skipped. A Kotlin/Native test process isn't an app, so
no page loads in it: the iOS tests cover what needs none, and the sample app runs the rest. A library test task that
runs past 10 minutes, or a sample one past 15, fails instead of hanging.

On a Mac, `jvmTest` also builds the macOS glue (it needs the Xcode command-line tools) and runs the tests that load it;
elsewhere the jar has no glue, those tests are skipped, and the macOS engine reports `Unavailable`.

## Not built yet

- The engines for Android and Windows, each with its platform's memory-pressure hook. Until each one exists, its
  platform reports `Unavailable`. Browsers have no memory-pressure signal to hook.
- On Linux:
  - IME composition: Compose's text input can't drive an editing state that lives in WebKit yet.
  - `<select>` popups (WPE's `show-option-menu`): a select changes with the keyboard only.
  - An arm64 runtime.
  - Compose's draw on a GPU, measured in a real desktop session, and the GPU gate measured again on a desktop's GPU.
  - Cheaper uploads for full-frame animations (copy + upload p95 10.4 ms on the gate's integrated GPU): a texture kept
    from frame to frame, partial uploads, or a capped frame rate while the whole frame repaints.
  - CPU painting for machines whose GL is software (NVIDIA's driver, VMs): the glue has the switch (`INIT_CPU`), and no
    setting reaches it yet.
  - Accessibility: the runtime is built without ATK, so screen readers see no page.
  - The system clipboard, unchecked: copy and paste inside a page work through WebKit; between the page and other apps
    nothing has been tried.
- On the web:
  - `WebContent.Bundle`. Each bundle needs an origin of its own, which the app's server has to provide.
  - A page's own back and forward.
  - A packaged shim for servers that put the bridge into `Url` pages. Until one exists, `Url` pages don't chain scrolls
    to the list around them either.
- On macOS: the web engine's scroll chaining for embeds (an embed taller than its `max` can't be scrolled inside with
  the wheel), trackpad swipe navigation, a `ComposePanel` inside a Swing frame, and the manual checks' results.
- A disk cache for snapshots.
- Batching bridge messages per frame, if real engines' numbers show it is needed.
- Publishing: the module isn't published to a repository yet.
