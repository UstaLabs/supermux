# macOS desktop client — native SwiftUI + KMP — Design (2026-07-02)

- **Date:** 2026-07-02
- **Status:** Approved (decisions confirmed with user: hybrid desktop stack, macOS first, full-parity v1 including dictation + display streaming)
- **Area:** `apps/iosApp` (new macOS target sharing SwiftUI sources), `apps/shared` (new `macosArm64` target). Zero broker/server changes.
- **Goal:** A first-class **native macOS app** for supermux, at full feature parity with the iOS app. This is the first of the desktop clients; Windows/Linux follow later on a different UI stack (see Decision 1).

## Context

- supermux already ships three clients: web PWA (`src/web-app`, Vue 3), iOS (`apps/iosApp`, SwiftUI + KMP `Shared.framework` via SKIE, App Store v1.1 live), Android (`apps/android`, Compose + KMP). The web PWA is installable in desktop browsers, but the user's explicit goal is a **native** desktop experience — a real app with dock presence and platform look/feel, not a browser shell.
- The KMP shared module (`apps/shared/build.gradle.kts`) already declares `jvm()`, `androidTarget()`, and Apple targets (`iosArm64`, `iosSimulatorArm64`, `watchosArm64`, `watchosSimulatorArm64`) with a common `appleMain` source set (Darwin ktor client). The shared brain — `BrokerApi`, WebSocket transport, agent-state models, `PredictiveEcho.kt`, path detector, `TerminalScroll.kt`, `VncClient` — is exactly what a desktop client needs, and most of it is already exercised by JVM tests on Linux.
- The iOS app is SwiftUI-first and structured into focused groups (`App`, `Broker`, `Chat`, `DesignSystem`, `Display`, `Editor`+`EditorWeb`, `Pairing`, `Push`, `Sessions`, `Shell`, `Terminal`, `Watch`). Key portability facts, verified in source:
  - Terminal = **SwiftTerm 1.13.0** via SwiftPM (`project.yml`) — SwiftTerm ships both a UIKit and an AppKit `TerminalView`.
  - Editor = **WKWebView + bundled CodeMirror 6** (`Supermux/EditorWeb` folder resource) — WKWebView exists on macOS.
  - Dictation = **`SFSpeechRecognizer` + `AVAudioEngine`** (`Chat/SpeechDictation.swift`) — both available on macOS.
  - Display streaming = VNC over Metal (`Display/VncMetalView.swift`, `VncSession.swift`) + scrcpy video (`ScrcpySession.swift`) — Metal is native to macOS; the `UIViewRepresentable` wrappers in `DisplayPane.swift` need `NSViewRepresentable` splits.
  - Pairing = token/URL-based (`Pairing/PairingView.swift`, `PairToken.swift`, `KeychainStore.swift`) — no QR/camera dependency; carries to macOS nearly as-is.
- Build/release muscle that already exists and gets reused: XcodeGen project (`.xcodeproj` gitignored), the gradle pre-build phase `:shared:embedAndSignAppleFrameworkForXcode`, the remote-Mac headless build pipeline (rsync whole `apps/`, per the 2026-06-28 build rule), paid team `57L7J9XA89`, TestFlight/App Store releases at v1.2/build 52.

## Decisions

1. **Desktop = two sub-projects; this spec is macOS only.** The chosen desktop strategy (confirmed with user) is a hybrid mirroring the mobile split: **native SwiftUI + KMP on macOS; Compose Multiplatform Desktop + KMP on Windows/Linux**. macOS ships first (user's call). The Windows/Linux Compose client gets its own spec when macOS is done — one spec per implementation plan.
2. **Rejected alternatives** (discussed with user): fully-native UI on all three platforms (WinUI/GTK can't consume KMP sensibly → the brain gets re-implemented, 3× UI maintenance); Compose Desktop everywhere (weakest native feel exactly where expectations are highest — macOS); Tauri/Electron wrapping the existing PWA (it *is* the web UI in a frame — the thing the user wants to move past); Avalonia/Qt/Flutter (mature, but custom-drawn too **and** a parallel stack with zero reuse of the KMP brain or existing team skills).
3. **A true native macOS app target — not Mac Catalyst, not "Designed for iPad on Apple Silicon."** The app is SwiftUI-first, so AppKit-backed SwiftUI with shared sources is both the most native and the cheapest honest option. Catalyst gives an iPad-in-a-window feel and is a design dead end for "most native experience."
4. **Full-parity v1 scope** (user explicitly widened this): sessions list + chat (MarkdownView incl. GFM tables, tappable file paths), terminal with predictive echo, CodeMirror editor + file tree, new-session launcher with draft persistence, Finish flow, archived sessions (+ project filter), usage panel, **dictation**, **display/VNC streaming**, pairing/auth, notifications. **Excluded from v1:** the watch companion (not a desktop concept), menu-bar-extra quick panel, deeper OS integration (global hotkeys etc. — the user's "capability" wishlist, deliberately deferred), Windows/Linux.
5. **KMP: add `macosArm64()` to `apps/shared`**, producing the same `Shared.framework` via the existing SKIE + `embedAndSignAppleFrameworkForXcode` flow. Apple-Silicon-only (no `macosX64`): macOS 26 runs on almost no Intel Macs, and the app targets macOS 26 for Liquid Glass design parity (matches iOS 26.0 deployment target). Darwin ktor client and `appleMain` actuals apply as-is; audit the few `iosMain`-only actuals and hoist anything macOS needs into `appleMain`.
6. **New XcodeGen target `SupermuxMac`** (`type: application, platform: macOS`) in the same `project.yml`, sharing the `Supermux/` source tree (the project already shares sources across targets — e.g. watch relay files into the iOS app). Platform divergence via `#if os(macOS)` conditionals and target-level source excludes (`Watch/**`, iOS-push-NSE specifics). The iOS target must keep building unchanged — parity of sources, not a fork.
7. **Distribution: Mac App Store + TestFlight for macOS** on team `57L7J9XA89` — reuses the entire existing release flow. The app is sandbox-friendly (outbound WS/HTTP client, WKWebView, mic; it never spawns local shells). Notarized direct DMG + Sparkle/Homebrew stays a documented later option if the post-v1 "deeper OS integration" phase outgrows the sandbox; nothing in v1 may depend on being outside it.
8. **Zero broker changes.** Same WS + REST protocol, same device-pairing claim flow, same stateless push relay; the Mac registers as one more device. If a client-platform tag exists in device registration it gets set to `macos`; no new endpoints or frame types.

## Architecture

```
apps/shared      + macosArm64() target  ──►  Shared.framework (SKIE)   [same gradle embed flow]
apps/iosApp/project.yml
  Supermux       (iOS app, unchanged)
  SupermuxMac    (NEW macOS app target)  — shares Supermux/ sources, excludes Watch/**
  SupermuxTests  (+ run key logic tests against the mac target where practical)
```

- **Source sharing model:** one SwiftUI codebase, two app targets. UIKit-touching spots get `#if os(macOS)` splits. Known concrete split points (from source audit): SwiftTerm wrapper in `Terminal/` (UIKit vs AppKit `TerminalView`), the three `UIViewRepresentable` wrappers in `Display/DisplayPane.swift` → `NSViewRepresentable`, pasteboard/haptics/share-sheet helpers in `DesignSystem`/`Chat`, keyboard-avoidance logic (unneeded on macOS). The first implementation task is a **compile audit**: build `SupermuxMac` with stubs and enumerate every UIKit diagnostic — that list, not guesswork, drives the porting work.
- **Workspace layout:** the iPad multi-pane workspace adapts to a `NavigationSplitView` — sessions sidebar, chat center, terminal/editor/display panes. Mac-native additions: open-session-in-new-window (`WindowGroup` scenes; windows on the same session share the session store — no duplicate fetching), a real menu bar (⌘N new session, ⌘W close window, pane-focus and send shortcuts), resizable splits with persisted sizes.
- **Design language:** macOS 26 shares Liquid Glass with iOS 26, so the existing `DesignSystem` carries visually; per-control differences (hover states, pointer cursors, focus rings, `.contextMenu` affordances) are handled where they arise, not via a parallel design system.
- **Build pipeline:** unchanged remote-Mac SSH flow — rsync the whole `apps/` directory, `xcodegen generate`, `xcodebuild -scheme SupermuxMac`. A Mac app is simpler than iOS here: products run directly on the build Mac (no simulator boot, no device install step). Release signing mirrors the iOS Release config (manual, Apple Distribution) with macOS provisioning added.

## Components (iOS → macOS deltas)

- **Sessions + Chat:** SwiftUI views and `MarkdownView.swift` (incl. GFM tables) carry over; shared-KMP path detector keeps powering tappable file paths → `cmRevealLine` in the editor. Add pointer hover/context-menu affordances.
- **Terminal:** SwiftTerm AppKit `TerminalView`; the predictive-echo SwiftTerm adapter (renders engine ops via `tv.feed` ANSI) and scroll logic are view-independent and carry over; trackpad/scroll-wheel is native AppKit behavior (the touch-drag→wheel-bytes bridge becomes iOS-only).
- **Editor:** WKWebView + bundled CodeMirror (`EditorWeb` folder resource, same folder-reference build phase) unchanged; file tree as a persistent sidebar column (iPad pattern). Sandbox note: WKWebView loads only bundled assets + broker content — no entitlement surprises.
- **Launcher:** same launcher + draft persistence (`UserDefaults`, `cmux:launcher-prefs` / `cmux:launcher-draft`, per the 2026-07-01 spec) — UserDefaults semantics identical on macOS.
- **Dictation:** `SpeechDictation.swift` (SFSpeechRecognizer + AVAudioEngine) works on macOS; needs the mic + speech-recognition usage descriptions and the sandbox **audio-input entitlement**; then the same `POST /transcribe` cleanup flow.
- **Display/VNC:** shared-KMP `VncClient` + `VncSession` unchanged; `VncMetalView` (Metal) and scrcpy video rendering port behind `NSViewRepresentable`; `DisplayInput` mappings extend from touch to mouse/keyboard where the pane already supports input.
- **Notifications:** `UserNotifications` framework on macOS — local notifications from WS frames while the app runs; **APNs remote push** through the existing stateless relay with the Mac's own device token (`aps-environment` entitlement on the mac target). If the iOS `SupermuxPushNSE` rich-push behavior is needed on macOS it ports as a mac NSE later; v1 requires only banner + badge fidelity.
- **Pairing/auth:** `PairingView` flow as-is with paste-URL/pairing-link instead of any mobile-specific affordance; `KeychainStore` uses the macOS Keychain (add the app's Keychain access group / sandbox keychain entitlement as required).

## Data flow

Byte-for-byte the mobile flow: one WS to the broker (`:9898` direct or via tunnel/mesh) for the snapshot + live frames; `agent_state` frames rendered verbatim (broker-computed, no client inference); REST for history; `ensureMessagesLoaded` → `GET /sessions/:id/messages` on chat-open when the buffer is empty (the resume-from-archive gotcha). No new endpoints, no new frame types.

## Error handling

- **Reconnect:** existing mobile backoff + connection banner, plus Mac-specific **sleep/wake**: observe `NSWorkspace` wake notifications and force an immediate reconnect cycle instead of waiting out a backoff timer.
- **Dead sessions:** build the **"Not responding" (dead-state) view into the shared SwiftUI code** — web + Android have it, iOS never got it (known gap). Doing it in shared views closes iOS for free. Render strictly from the broker's `agent_state`; no client-side optimism.
- **Multi-window:** windows on one session share the same store/stream; closing one window never tears down the session's WS state.
- **Pairing/Keychain failures** land in an explicit re-pair screen (never a silently dead app); token refresh/401 handling identical to iOS.

## Testing

- **KMP shared:** existing common + JVM tests keep running on Linux; `macosArm64` test task joins the Apple-target lane on the remote Mac. New/changed shared code follows the existing parity-test pattern (e.g. predictive echo's 23 web↔Kotlin parity tests).
- **macOS app:** XCTest via headless `xcodebuild test -scheme SupermuxMac` on the remote Mac — runs natively, no simulator. Existing `SupermuxTests` (104 green on iOS) must stay green on the iOS target throughout; shared-logic tests get compiled into the mac test bundle where they're platform-neutral.
- **UI smoke:** a small XCUITest pack for the critical path — launch → pair (against a disposable local broker on the Mac) → open session → send a message → terminal echo → open editor via a tapped file path.
- **Human feel-testing:** TestFlight **for macOS** — same beta loop as iOS. Feel-pass checklist: menu bar, shortcuts, hover states, window restore, sleep/wake reconnect, notification banners.
- **Broker verify gate:** untouched (`.mux/verify.sh` covers the TS broker; mac app testing lives in the remote-Mac lane like iOS today).

## Distribution

Mac App Store + TestFlight on team `57L7J9XA89`, sandboxed, with entitlements: outbound network client, audio input (dictation), keychain. Version/build numbering joins the existing marketing-version scheme. Notarized DMG + Sparkle (or Homebrew cask) is explicitly out of scope for v1 but documented as the escape hatch if a later capability phase outgrows the sandbox.

## Sequencing & out of scope

- **After macOS v1 ships:** a separate spec for the **Windows/Linux Compose Multiplatform Desktop** client (full KMP reuse on `jvm()`, sharing Compose UI with `apps/android` where practical; JediTerm-class JVM terminal and the editor-webview question get decided there, not here).
- Out of scope for this spec: watch companion on desktop, menu-bar-extra quick panel, global hotkeys/deeper OS integration (post-v1 capability phase), any broker/protocol changes, dictation-engine swaps, Intel (`macosX64`) support.

## Risks

- **UIKit bleed-through is the main unknown.** Mitigation: the compile audit is implementation task #1, and its diagnostic list re-scopes the porting estimate before deep work starts.
- **SwiftTerm's AppKit view is less exercised in this codebase than its UIKit twin** (selection, key handling, IME). Mitigation: the predictive-echo adapter is view-independent; budget a hardening pass on mac-specific terminal input.
- **macOS 26 minimum** excludes older-OS Macs. Accepted: it buys design-language parity (Liquid Glass) and matches the arm64-only call; revisit only if real users are stuck on macOS 15.
- **Sandbox + App Review** for a "remote agent control" app: the app is a pure client (no local execution), which is the safe side of review; the DMG path exists as the escape hatch.

## Open questions

None outstanding — stack (hybrid), platform order (macOS first), v1 scope (full parity incl. dictation + display streaming), and distribution (MAS + TestFlight) were confirmed with the user in the brainstorming conversation before this spec was written.
