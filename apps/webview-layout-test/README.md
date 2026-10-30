# webview-layout-test: where WKWebView goes in a Compose Desktop window (macOS)

**Throwaway.** The macOS engine plan moves `native/macos` into `apps/webview-compose/native/macos` and deletes this
module. Spec: `docs/superpowers/specs/2026-10-28-webview-compose-design.md` §7. Plan:
`docs/superpowers/plans/2026-10-29-webview-macos-layout-test.md` (both local only).

## The answer: layout A, page under Compose

Task 17 Step 2, rule 3: neither layout passes, because in both the page trails its slot whenever the slot moves (list
scroll 10–17 px in A and 15–16 px in B; pane drag 25–33 px and 18–29 px; window resize gaps of up to 358 and 386 px).
Every failing or inconclusive row is one both layouts share (that motion sync, the page's frames during a window
resize, the synthetic wheel), so none is left on either side, and the tie goes to A. Everything else passed in both
layouts and both runs: 7 of 7 clicks, every screen including B's control, every keyboard row, the accessibility bridge;
and A keeps what B loses, the shadows of overlays over the page. The manual checklist (Task 15) hasn't run yet: until it
has, this is what the automated rows decide.

## How the page is placed

- skiko 0.150.2 adds its `AWTMetalLayer` (non-opaque) straight to the layer of AWT's content view (`AWTView`,
  layer-hosting). The page's container `NSView` is a subview of that same view, so its layer is a sibling of the Metal
  layer, ordered by `zPosition`: -1 for A, +1 for B. The layer tree each run recorded shows exactly that.
- A: Compose clears the page's slot with `BlendMode.Clear`. B: a `CAShapeLayer` mask (even-odd: the visible rect plus a
  hole per overlay, per-corner radii) and a dim layer per scrim.
- Input, both layouts: `hitTest:` returns nil outside the visible rect, in a hole or under a scrim. ⌘C/X/V/A/Z/⇧Z go to
  the page as actions through a local key monitor, because `AWTView.performKeyEquivalent:` sends every key equivalent to
  Java only. VoiceOver reaches the page through a runtime subclass of the `AWTView` instance, because `AWTView` exposes
  only the Java accessibility tree.
- Until its first paint the page view is transparent (`alphaValue` 0) and takes no input, but it is never hidden: a
  WKWebView with a hidden ancestor never paints (below).

## Results: 2026-10-30, MacBook Air M1 (MacBookAir10,1), macOS 26.6.2 (25G83), the built-in Retina display at 60 Hz and 2× (an MSI MP273U, 60 Hz, also attached)

Two automated runs, 10 minutes apart (04:39 and 04:49, +03:00), each with the window open for about 3 minutes, on the
built-in display. The Mac was **under load**: load averages of 10–20 in run 1 and 21–28 in run 2, so the frame rows
are numbers taken under load. Screenshots went through SSH (`external`: `java` holds no Screen Recording permission,
the SSH session does). The window was 1200 × 765 pt (the display capped the 860 dp asked for); px are window pixels,
2 a point. Run 1's dialog screens read INCONCLUSIVE because the harness looked for the bright beacon under the
dialog's own full-window scrim; by eye they were right, and run 2 checks the dimmed beacon.

| Criterion (spec §7) | A, run 1 / run 2 | B, run 1 / run 2 | Manual (A / B) |
|---|---|---|---|
| Menu and dialog take their own clicks over a live, scrolling page | 7 of 7 PASS / 7 of 7 PASS, among them 3 px inside and 6 px outside the menu's edge and the scrim over the page. The menu stays open after the page takes a click (INFO) | 7 of 7 PASS / 7 of 7 PASS; the same INFO | 8: not run yet |
| Overlays show over the page (screens) | rest, menu PASS / PASS; dialog INCONCLUSIVE (the beacon under the scrim, right by eye) / PASS. The menu's shadow falls on the page | rest, menu, and the control (the menu hidden by a page with no cut-outs) PASS / PASS; dialog as in A. The page hides the menu's shadow; the dim layer matches Compose's scrim pixel for pixel | 8: not run yet |
| Wheel routing / trackpad momentum and pinch | both rows INCONCLUSIVE / INCONCLUSIVE: the synthetic wheel event (`eventWithCGEvent`) carries window 0, and AppKit delivered it to neither side | the same | 1, 2: not run yet |
| Turkish and Japanese IME | focus page, type into page, focus Compose, type into Compose: 4 of 4 PASS / 4 of 4 PASS (ASCII, the ABC layout) | 4 of 4 / 4 of 4 | 3, 4: not run yet |
| ⌘C/⌘V/⌘Z | without the monitor ⌘Z leaves the page as it is (AWT keeps the key); with it ⌘Z undoes ('abcdef' to '') and ⌘A selects all: PASS / PASS | the same | 5: not run yet |
| VoiceOver reads the page | the WKWebView isn't reachable without the bridge (58 nodes searched), and is with it: PASS / PASS | the same (the bridge doesn't depend on the layout) | 6: not run yet |
| Live resize and pane drag without flicker | max offset px, list / pane / window: 10 / 25 / INCONCLUSIVE (2 measured, 40 and 54) FAIL; 17 / 33 / INCONCLUSIVE (4 measured, up to 358) FAIL | 16 / 18 / INCONCLUSIVE (1 measured, 16; gaps to 342 by eye) FAIL; 15 / 29 / 386 FAIL | 7: not run yet |
| Frame pacing (p95, display Hz) | Compose, list / pane / window: 17.4 / 17.8 / 13.8 ms and 17.5 / 17.7 / 7.6 ms. Page, list / pane / scrolling itself: 18.0 ms every time; during the window resize 27 and 28 frames in 3 s (p95 137 and 367 ms): INCONCLUSIVE. 60 Hz, under load | Compose 17.6 / 17.6 / 8.6 ms and 17.7 / 17.7 / 10.3 ms. Page 18.0 ms every time; during the window resize 9 and 26 frames in 3 s (p95 303 and 121 ms): INCONCLUSIVE | — |
| 120 fps on ProMotion | NOT RUN: 60 Hz displays only | NOT RUN | 9: not run yet |

## What the engine must carry over

- **Never hide the page before its first paint.** WebKit counts a view with a hidden ancestor as not visible
  (`isViewVisible()` NO in its log), suspends the page's animation frames, and the first paint never comes: the first
  probe hung that way. Keep the view transparent and out of hit testing until then.
- **Route input with `hitTest:`, no event monitor.** It worked in both layouts, in every click, with the page keeping
  native first responder for typing.
- **Close Compose's popups when the page takes a click.** Compose never sees it, so a menu stays open (every run).
- **The ⌘-key monitor and the accessibility bridge.** Without the monitor ⌘Z never reaches the page; without the
  bridge VoiceOver can't reach it. The bridge isa-swizzles AWT's private `AWTView`: pin and test it against JDK updates.
- **Cover the slot while it moves.** The page trails Compose by one to two frames whenever its slot moves, and by far
  more while the window is resized every frame, when the page also gets almost no frames of its own. Through the gap A
  shows AWT's own background (white or grey) and B shows Compose's. Draw the snapshot over the slot while it moves, as
  iOS does, or present Compose's frame and the page's move in one transaction.
- **zPosition** held at -1 and +1 through both runs; the glue re-applies it at every placement anyway.
- **Mark every overlay.** Unmarked, it is hidden under the page in B, and in A it shows but the page takes its clicks.
- **Keep the slot out of offscreen layers in A.** A Compose ancestor with alpha below 1 or an offscreen compositing
  strategy turns the slot's clear into a clear of that layer only (not exercised here): show the snapshot meanwhile.
- **Detach last.** A window's dispose lays out once more after the app is done with the page, so the host checks that
  the page is still attached before each call into the glue (the probe's second fix).

## Run it again

On the Mac's copy of this worktree: `webview-mac-gradle` prints it (`~/work/webview-compose` from the main worktree,
`~/work/webview-compose-HASH` from any other). Screenshots go through SSH (`external`), so `java` needs no permission.

```
webview-mac-gradle :webview-layout-test:test :webview-layout-test:installDist
ssh mac 'bash ~/work/webview-compose/apps/webview-layout-test/native/macos/build.sh'
ssh mac 'mkdir -p ~/work/webview-compose/run/auto/shots; nohup bash ~/work/webview-compose/apps/webview-layout-test/mac/shoot-loop.sh ~/work/webview-compose/run/auto >/dev/null 2>&1 &'
ssh mac 'bash ~/work/webview-compose/apps/webview-layout-test/mac/run.sh auto ~/work/webview-compose/run/auto external'
ssh mac 'bash ~/work/webview-compose/apps/webview-layout-test/mac/cleanup.sh'
```

For the manual checks, `run.sh manual ~/work/webview-compose/run/manual` opens the window with the ⌘-key monitor and the
accessibility bridge on, until it is closed or `run.sh stop`.
