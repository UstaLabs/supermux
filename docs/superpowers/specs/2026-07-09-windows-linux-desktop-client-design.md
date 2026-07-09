# Windows/Linux desktop client — Compose Multiplatform Desktop + KMP — Design (2026-07-09)

- **Date:** 2026-07-09
- **Status:** Approved (decisions confirmed with user: standalone `apps/desktop` module, full-parity v1, KCEF bundled, jpackage installers with manual updates, Windows feel-test deferred)
- **Area:** new `apps/desktop` Gradle module (Compose Multiplatform Desktop, JVM); `apps/shared` consumed via its existing `jvm()` target. Zero broker/server changes.
- **Goal:** A first-class **native desktop app for Windows and Linux**, at full feature parity with the mobile/mac clients, from one JVM codebase. This is the second desktop sub-project promised by the macOS spec (2026-07-02, Decision 1): SwiftUI+KMP on macOS (shipped to dev), **Compose Desktop + KMP on Windows/Linux** (this spec).

## Context

- supermux ships four clients today: web PWA (`src/web-app`, Vue 3), iOS (SwiftUI + KMP), Android (`apps/android`, Compose + KMP), and the macOS desktop app (`SupermuxMac`, merged to dev 2026-07-07). Windows/Linux users have only the browser PWA.
- The KMP shared module already declares `jvm()` with a CIO ktor client in `jvmMain`, and the shared brain — `BrokerApi`, WS transport, agent-state models, `PredictiveEcho.kt`, `TerminalScroll.kt`, path detector, `formatWorkdir`, `VncClient` — is exercised by `jvmTest` on this Linux box every day. The desktop client's entire logic layer already builds and passes tests on the target JVM.
- The Android app is Compose and already contains a desktop-shaped layout: the tablet/foldable workspace (`apps/android/.../workspace/` — `WorkspaceLayout.kt`, `SessionWorkspaceDetail.kt`, `WorkspaceShortcuts.kt`), i.e. `Sessions │ Chat │ (Editor/Terminal) │ Display` with drag-resizable splits, per-session pane toggles, and Ctrl-shortcuts, activated at width ≥600dp. A desktop window is permanently "≥600dp", so this is the natural base layout, not the phone UI.
- The editor on iOS and Android is a WebView loading the **same** bundled CodeMirror 6 build (`apps/android/codemirror/cm6-entry.mjs` — one edit serves both platforms). The macOS spec deferred two decisions to this spec: the JVM terminal widget and the editor-webview question.

## Decisions (all confirmed with user, 2026-07-09)

1. **Standalone `apps/desktop` module; copy-adapt Android screens (Approach 1).** The alternative — extracting a shared `apps/shared-ui` Compose Multiplatform module consumed by Android + desktop — was weighed and consciously deferred: it refactors a shipping app before any desktop code exists, and dev churns fast. **Extraction is the sanctioned future refactor** if/when a third Compose surface appears or UI divergence starts hurting; until then the divergence debt of copied composables is accepted.
2. **Full-parity v1** (same widened scope as macOS): sessions list + chat (markdown incl. GFM tables, tappable file paths → editor at line), terminal with predictive echo, CodeMirror editor + file tree, new-session launcher (drafts, slash commands, thinking-effort pill), Finish flow, archived sessions + project filter, usage panel, dictation, display/VNC streaming, pairing/auth, notifications, viewing-frames.
3. **KCEF (bundled Chromium) is accepted** (~100–150 MB extra, per-OS natives) to host the shared CodeMirror bundle — full editor parity beats app size on desktop. It also serves as the terminal fallback (xterm.js) if JediTerm disappoints.
4. **Terminal = JediTerm** (JetBrains' JVM terminal, drives IntelliJ's) rendering the raw tmux byte-stream from the existing WS; predictive echo via a JediTerm adapter over the shared Kotlin engine (the Android termlib adapter pattern, minus the reflection — JediTerm's API is public).
5. **Packaging: jpackage via the Compose Desktop Gradle plugin, manual updates.** Linux `.deb` + tar.gz built on this box; Windows `.msi` built on a Windows VM/CI (jpackage cannot cross-build). Served from a downloads page like the Android sideload APKs (`supermux-apk.ustalabs.com` pattern). Windows binaries unsigned for now (SmartScreen warning accepted). Auto-update (Conveyor/Hydraulic) is a documented later upgrade, not v1.
6. **No Windows feel-test hardware.** Cross-platform code + VM smoke tests here; the user feel-tests Windows when a box is available. Linux is the primary proving ground (this machine runs the live broker).
7. **Zero broker changes.** Same WS + REST protocol, same pairing claim flow; the desktop registers as one more device (platform tag `linux`/`windows` if the registration carries one). No new endpoints or frame types.
8. **Rejected alternatives:** shared-ui extraction first (see 1); PWA-in-KCEF shell (rejected in the macOS spec already — it's the thing the user wants to move past); native-per-OS UI (WinUI/GTK — no KMP reuse, 2× UI codebases).

## Architecture

```
apps/shared        jvm() target (exists)  ──►  shared brain on the desktop JVM
apps/desktop       NEW Compose Multiplatform Desktop module
  src/main/kotlin/dev/supermux/desktop/
    app/           entry point, windowing, menu bar, per-window BrokerSession
    workspace/     port of android workspace/ (splits, pane toggles, shortcuts)
    chat/          port of android chat/ (stream, composer, markdown, attachments)
    session/       session list, launcher, finish flow, archived
    terminal/      JediTerm wrapper + PredictiveEcho adapter + TerminalScroll bridge
    editor/        KCEF webview + cm6 bridge + native Compose file tree
    display/       VncClient → Compose canvas renderer + input forwarding
    dictation/     javax.sound.sampled capture → POST /transcribe
    notifications/ OS notifications from WS frames + viewing-frame suppression
    pairing/       token/QR-link pairing, paste-a-token flow, settings storage
    theme/         port of android theme/ (OKLCH teal tokens, Geist/Geist Mono)
```

- **Brain:** everything stateful/protocol-level comes from `apps/shared` `jvm()`. Expected zero new expect/actuals; anything Android-only that desktop needs gets hoisted to `commonMain` (the macOS port did the same with `appleMain`).
- **UI:** screens are ports of the Android composables with desktop affordances added (hover states, context menus, pointer cursors, focus handling). The **workspace layout is the only layout** — there is no phone-width mode.
- **Desktop chrome:** real menu bar (Ctrl-N new session, Ctrl-W close window, pane-focus + send shortcuts — mirroring the mac app's ⌘ set and the existing `WorkspaceShortcuts` map); **multi-window** via the **web-tab model** proven on macOS: "open session in new window" gives that window its own independent `BrokerSession`/WS — no shared cross-window store; closing a window tears down only its own WS.
- **Persistence:** window size/splits/launcher drafts/prefs in a JSON settings file under the platform config dir (`~/.config/supermux-desktop` / `%APPDATA%\supermux-desktop`), mirroring the `cmux:launcher-prefs` / `cmux:launcher-draft` split (prefs forever, draft cleared on create). Paired-device token in the same dir with file permissions locked down (0600) — the JVM has no OS keychain without another native dep; acceptable v1 trade-off, revisit if users object.
- **Pairing:** same claim flow as other natives; primary UX = paste a pairing link/token (no camera assumption); `SM_PAIR_TOKEN`/`SM_PAIR_BASE` env override kept for dev, same as the mac app.

## Components

- **Sessions + chat:** port of the Android session list (lean rows + status rail — the approved list design) and the redesigned chat stream (mono gutter/spine, tool-calls as terminal operations, inline diffs) as they exist on dev. Markdown: port the Android markdown rendering (desktop is the third consumer of the "native markdown ≠ web markdown" reality; GFM tables and fenced-code path-linkification included — closing the known Android gap in the copy is in-scope while porting).
- **Terminal:** JediTerm fed by the raw tmux byte-stream over the existing per-session WS terminal channel. Input (keys, paste, mouse reports) flows back over the same channel. Scroll: wheel → tmux SGR-mouse bytes via shared `TerminalScroll.kt` math (all clients need this bridge; JediTerm's local scrollback is inert under tmux `mouse on`, exactly like SwiftTerm-mac was). **Predictive echo:** desktop adapter for `PredictiveEcho.kt` implementing the engine's op-rendering against JediTerm's buffer/cursor API. Acceptance = the same behavior the 23 web↔Kotlin parity tests lock in. Fallback if JediTerm's API can't support op rendering cleanly: xterm.js inside KCEF (Chromium is bundled anyway); the engine is view-independent so only the adapter changes.
- **Editor:** KCEF browser view loading the shared CodeMirror bundle. The bundle + host HTML move to a shared `apps/codemirror/` location referenced by Android, desktop (and iOS's copy step) — one bundle, three consumers stays literally true. Same JS bridge contract as mobile (`cmRevealLine`, content get/set, dirty state, font-zoom Ctrl+/−/0 with persisted size). File tree = native Compose port of Android's; tappable file paths in chat open the editor at line.
- **Display/VNC:** shared `VncClient` (commonMain, jvm-tested) decodes framebuffers; render via Compose `Canvas`/`ImageBitmap`; mouse + keyboard forwarded as VNC input events. Scrcpy video decoding is out of v1 scope on desktop (needs a native H.264 decoder dependency); VNC is the v1 display parity bar.
- **Dictation:** `javax.sound.sampled` mic capture → WAV → `POST /transcribe` (optionally `?session=`) → cleaned text into the composer; same degrade-gracefully UX as web when the broker's whisper pipeline isn't provisioned. No on-device STT in v1.
- **Notifications:** OS-native notifications driven by WS frames while the app runs — desktop has no push relay leg (relay is FCM/APNs only) and none is added; the app is expected to be running, like the PWA-without-push case. Compose Desktop's tray/notification API first; if fidelity disappoints, Linux `notify-send`/libnotify and Windows toast via a small lib are the upgrade path. **Viewing frames:** send `ClientFrame.Viewing` on chat open/switch/focus with the 60s heartbeat + reconnect re-assert (the iOS/Android pattern) so the open chat never notifies. Serialization gotcha honored: `session` field has no kotlinx default; null-session on the list serializes as `"session":null`.
- **Usage panel / archived list / Finish flow / launcher:** straight ports of the Android implementations, including the launcher thinking-effort pill (`GET /reasoning-levels`, hide-until-deployed behavior) and slash-command menu (keyboard-drivable).

## Data flow

Byte-for-byte the mobile flow: one WS to the broker for snapshot + live frames; `agent_state` frames rendered verbatim (broker-computed, zero client optimism); REST for history; `ensureMessagesLoaded` → `GET /sessions/:id/messages` on chat-open when the buffer is empty (resume-from-archive gotcha). Known shared-code gap inherited, not fixed here: `Frames.kt` still drops `session_state` frames (missing serializer) — desktop inherits the same limitation as iOS/Android until the shared fix lands.

## Error handling

- **Reconnect:** existing shared backoff + connection banner; desktop adds resume-from-sleep detection (on wake/clock-jump, force an immediate reconnect cycle rather than waiting out backoff — the mac app's sleep/wake lesson).
- **Dead sessions:** "Not responding" view ported from Android — render strictly from broker `agent_state`.
- **KCEF lifecycle:** editor pane must degrade to a readable error card if Chromium fails to initialize (missing natives, first-run download interrupted) — never a blank pane; the rest of the app keeps working.
- **Pairing failures / 401:** explicit re-pair screen, same as mobile; never a silently dead app.

## Testing & verification

- **Shared logic:** existing `jvmTest` lane runs on this box, unchanged.
- **Desktop UI:** Compose Desktop supports the same `runComposeUiTest`/test APIs as Android — port the workspace/shortcuts test patterns (`WorkspaceLayoutTest`, `WorkspaceShortcutsTest`) alongside new tests for the terminal adapter (engine-op rendering against a fake JediTerm buffer) and the settings/draft persistence.
- **Predictive echo:** the desktop adapter gets its own unit tests mirroring the Android adapter's; the engine itself stays locked by the 23 parity tests.
- **Milestone verification (user-mandated):** after each milestone the app is **actually run and used like a user** on this box — launch under a virtual display, pair to the live broker, exercise the milestone's surface (send a message, type in the terminal, open a file, watch a session run), screenshot evidence. This is the Linux feel-test; Windows gets VM smoke tests (app launches, pairs, core panes render) per Decision 6.
- **Suites stay green:** Android + shared test suites must remain untouched/green throughout (the module is additive; the only shared-file edits are commonMain hoists and the cm6 bundle relocation, each verified against the Android build).

## Distribution

- **Linux:** `.deb` + tar.gz from `jpackage` on this box, published to the downloads page alongside the sideload APKs.
- **Windows:** `.msi` from `jpackage` on a Windows VM (or CI runner) — packaging is the only step that requires Windows; the codebase and resources are identical. Unsigned v1.
- Versioning joins the Android `0.x` desktop-adjacent line (read off the branch at release time, per the versioning note in the domain digest).

## Sequencing & out of scope

- Build order inside v1 (each a verifiable milestone): app shell + pairing + sessions/chat → terminal → editor (KCEF) → launcher/Finish/archived/usage → display + dictation + notifications → packaging.
- **Out of scope:** shared-ui extraction (future refactor, see Decision 1), auto-update, Windows code-signing, scrcpy video decoding on desktop, on-device STT, tray-resident/menu-bar quick panel, global hotkeys (post-v1 capability phase, same as macOS), any broker/protocol change.

## Risks

1. **KCEF integration** — fiddly Gradle setup, large native download, per-OS natives. Mitigation: it is the standard Compose Desktop webview path; editor pane has an explicit degraded state; the cm6 bundle contract is already proven on two platforms.
2. **JediTerm predictive-echo adapter** — new adapter surface against a less-documented API. Mitigation: engine is view-independent and parity-locked; xterm-in-KCEF fallback is pre-approved and cheap once KCEF is in for the editor.
3. **Windows without hardware** — packaging + feel bugs surface late. Mitigation: VM smoke tests; Windows-specific code is nearly nil (JVM abstracts it); user feel-test explicitly deferred by decision, not oversight.
4. **UI divergence debt** from copied Android composables — accepted consciously (Decision 1) with extraction named as the remedy.
5. **Shared-box build pressure** — Gradle daemons get OOM-killed here; builds run solo, heap capped (`-Xmx2048M`), `TMPDIR` pointed at `~/.cache` (the `/tmp` quota gotcha).

## Open questions

None outstanding — scope (full parity), stack (Compose Desktop + KMP, Approach 1), KCEF, packaging (jpackage/manual updates), and the Windows-hardware reality were all confirmed with the user in the brainstorming conversation before this spec was written.
