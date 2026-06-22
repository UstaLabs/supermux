# Apple Watch Companion App — Design (2026-06-22)

## Goal

A simple, **native watchOS** companion to the Supermux iOS app. v1 does three things:

1. **List active sessions** — glanceable, Digital-Crown scrollable.
2. **Open a session** to read its history (agent replies + your messages, inline photos).
3. **Talk to the agent by voice** — dictate a message, clean it, send it.

Deliberately minimal. No terminal, editor, displays, git, finish jobs, model switching, or
notifications. (Native push is a separate follow-up project — see Non-Goals.)

## Decisions (and why)

- **Independent connection.** The watch connects to the broker directly (same base URL + token as
  the phone), reusing the KMP shared client — *not* relayed through the phone. Rationale: a watch
  earns its keep when the phone isn't in hand; matches Apple's "design your watchOS app to function
  independently" guidance; fits the project's "shared core, native shells" approach. Works the same
  on cellular or Wi-Fi-only watches (cellular only changes the phone-left-at-home case).
- **Credential provisioning via WatchConnectivity.** The watch is a *separate device*, so a Keychain
  access group does NOT sync phone→watch. The iOS app sends `{baseURL, token}` to the watch once via
  `WCSession`; the watch stores its own copy in the watch Keychain and connects on its own
  thereafter. No QR scan / token entry on the watch.
- **Pull-only v1 (no notifications).** Proactive wrist alerts require push, which does not exist
  natively yet (the broker has only scaffolding: a `device_push_tokens` table + a `native-sender.ts`
  router, but no APNs adapter, not wired to the reply hook, no client registers a token). Deferred to
  its own project so push is built once for both phone and watch (then watchOS auto-mirrors the
  iPhone's alerts to the wrist).
- **Voice = system dictation + broker cleanup.** `SpeechAnalyzer`/`SpeechTranscriber` (the phone's
  iOS 26 on-device engine) is **unsupported on watchOS** (iOS/macOS/tvOS only). Instead: watch system
  dictation → text → `POST /sessions/{id}/transcribe` `{draft}` → the broker's LLM cleanup
  (`voice-cleanup.ts`, Codex w/ cursor-CLI fallback) applies the voice glossary → send. The cleanup is
  capture-agnostic, so quality ≈ the phone's.

## Architecture

- **New watchOS app target** `SupermuxWatch` in XcodeGen (`apps/iosApp/project.yml`), source under
  `apps/iosApp/SupermuxWatch/`. Embedded with the iOS app (paired install).
- **KMP shared module reuse.** Add watchOS targets to `apps/shared/build.gradle.kts` and link
  `Shared.framework` into the watch target. Restructure Apple source sets so iOS + watchOS share the
  Darwin client:
  - Introduce an intermediate **`appleMain`** source set holding today's `iosMain` Darwin code
    (`IosClient.kt`, the Ktor Darwin dependency).
  - `iosMain` and `watchosMain` both depend on `appleMain`.
  - Add targets `watchosArm64` (+ `watchosDeviceArm64` for newest watches) and
    `watchosSimulatorArm64` — **confirm the exact arch set on the Mac.**
- **Connection:** reuse `BrokerClient` (WS `/ws` subscribe → snapshot + live `SessionAdded`/
  `SessionRemoved`/`MessageAppend`) and `BrokerApi` (REST). v1 reuses the existing WS `subscribe`
  snapshot; revisit a lighter "sessions-only" subscribe only if the payload is too heavy on the watch.
- **Send:** reuse the shared send path (`ClientFrame.Send` / `POST /sessions/{id}/reply`).
- **Images:** reuse `GET /files/{id}` for inline photos (load on demand, downscaled for the watch).
- **Models:** reuse `SessionInfo`, `LogEntry`, `Attachment` DTOs unchanged.

## Phone-side change (iOS app)

Add a small WatchConnectivity provider: when a paired + installed watch is present, send
`{baseURL, token}`; resend on (re)pair and on token change; clear on unpair. (`WCSession` on the iOS
side, mirroring the watch receiver.)

## Screens (watchOS-native: NavigationStack, List, Crown scroll, ≥44pt targets, minimal depth)

1. **Sessions** — `List` of active sessions sorted by recent activity. Row: session name, agent, a
   small activity/status indicator. States: "Open Supermux on iPhone to connect" (before
   provisioning), "Reconnecting…" (transient), empty. Tap → detail.
2. **Session detail** — scrollable history: agent replies as lightweight Markdown, your messages
   plain; inline photos (tap → full-screen). Bottom **mic** button. Crown scrolls.
3. **Voice input** — mic → system dictation (native input; scribble/emoji also available) →
   optimistic local echo → broker cleanup → send via shared client. Subtle "cleaning…" state; on
   cleanup failure, fall back to sending the raw draft.

## Non-Goals (v1)

Terminal, code editor, VNC/scrcpy displays, git actions, finish jobs, model/reasoning switching,
multi-account, complications, **and notifications / native push** (separate project).

## Key risks & fallbacks

- **KMP on watchOS (primary risk).** Kotlin/Native + Ktor Darwin + SKIE for watchOS targets, plus the
  source-set restructure. *Mitigation:* verify a watchOS `Shared.framework` builds & links on the Mac
  as the **first** implementation step. *Fallback:* if too costly, write a thin Swift networking
  client on the watch (URLSession WebSocket + REST) re-implementing only the small surface the watch
  needs (subscribe, send, transcribe, file fetch), still reusing the DTO shapes.
- **WS snapshot size on the watch.** Bounded today by the broker's per-session ring buffer;
  acceptable for v1. If heavy, add a sessions-only subscribe mode.
- **WatchConnectivity timing.** The watch may launch before provisioning; handle the
  "not-yet-connected" state and request credentials when the `WCSession` activates.

## Build / deploy

- Build + sign on the **remote Mac over SSH** (paid Apple Developer account). Follow the existing
  Supermux iOS build/sign setup used by prior sessions (located during the plan step).
- **Verify in the watchOS Simulator** (screenshot each screen) — the cheap gate.
- Then **dev/OTA install to the physical Apple Watch** and confirm on-device (source of truth for
  feel + the live broker connection). Bump version per build.

## Open items for the plan step

- Locate the established Mac SSH host + iOS build/sign recipe (memory: `claudemux` / `infra`; prior
  iOS sessions).
- Confirm the exact Kotlin/Native watchOS target arch set + SKIE/Ktor watchOS support.
- Minimum watchOS deployment target: **default to watchOS 26** to match the iPhone app's iOS 26
  stance and maximize SwiftUI/API availability; lower only if the target watch can't run it.
