# Apple Watch Companion App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a simple, native watchOS companion to the Supermux iOS app that lists active sessions, lets you read a session's history (incl. photos), and lets you talk to the agent by voice.

**Architecture:** New `SupermuxWatch` watchOS app target (XcodeGen) reusing the KMP `Shared.framework` (BrokerClient/BrokerApi/DTOs) for an independent broker connection. Credentials are provisioned phone→watch once via WatchConnectivity, then the watch connects on its own. Pull-only v1 (no push). Voice = watch system dictation → broker LLM cleanup (glossary) → send.

**Tech Stack:** SwiftUI (watchOS 26), Kotlin Multiplatform (+ SKIE, Ktor Darwin), XcodeGen, WatchConnectivity, build/sign on the remote Mac (`ssh mac`, Xcode 26.5) over Tailscale.

Spec: `docs/superpowers/specs/2026-06-22-apple-watch-app-design.md`.

---

## Environment facts (verified 2026-06-22)

- **Mac:** `ssh mac` (Tailscale `100.121.185.86`, user `ahmet`, passwordless, MacBook Air, Darwin arm64). **Xcode 26.5**. **watchOS 26.5 SDK** (`-sdk watchos26.5` / `-sdk watchsimulator26.5`) + **watchOS 26.5 sim runtime** present. Watch sim devices incl. `Apple Watch Series 11 (46mm)`, paired with `iPhone 17 Pro Max`.
- **Every ssh build command MUST `source ~/ios-build-env.sh`** (sets JAVA_HOME/PATH; without it xcodegen/gradle/java are "not found").
- **Sync code to the Mac with tar-over-ssh, NOT rsync** (macOS rsync is openrsync and breaks). Long jobs: `nohup … & ` + poll a log sentinel (macOS has no `setsid`/`timeout`).
- **iOS app:** bundle `dev.supermux.app`, team `57L7J9XA89`, iOS 26.0, XcodeGen. KMP framework built by `./gradlew --no-daemon :shared:embedAndSignAppleFrameworkForXcode` (reads Xcode `$SDK_NAME`/`$ARCHS`). KMP native targets are disabled on Linux → all Apple compiles happen on the Mac.
- **Canonical sim playbook:** skill `mux:ios-simulator-on-remote-mac`. **Device playbook:** `mux:ios-device-on-remote-mac`.
- **Live broker for real-data tests:** this host's tailscale0 = `http://100.84.92.82:9898`; mint a token with `cd /home/ahmet/projects/supermux && bun run pair <name>` (take the `?t=` value).

## File structure (created / modified)

- `apps/shared/build.gradle.kts` — **modify:** add watchOS targets + `appleMain` intermediate source set.
- `apps/shared/src/appleMain/kotlin/dev/supermux/net/DarwinClient.kt` — **move** from `iosMain` (shared by iOS + watchOS).
- `apps/iosApp/project.yml` — **modify:** add `SupermuxWatch` watchOS app target; embed it in `Supermux`.
- `apps/iosApp/SupermuxWatch/` — **create** the watch app:
  - `SupermuxWatchApp.swift` — `@main`, wires WatchBrokerSession + provisioning.
  - `Watch/WatchBrokerSession.swift` — slim observable wrapper over the shared client (sessions, messages, send, transcribe, loadFile).
  - `Watch/WatchKeychain.swift` — store/read `{baseURL, token}` in the watch Keychain.
  - `Watch/WatchProvisioning.swift` — WCSession receiver + env-var fallback for headless tests.
  - `Watch/SessionsListView.swift` — the list screen.
  - `Watch/SessionDetailView.swift` — history (Markdown + inline photos) + mic button.
  - `Watch/VoiceInput.swift` — system dictation → cleanup → send.
  - `Info.plist` (or project.yml info keys) — `WKCompanionAppBundleIdentifier`, usage strings as needed.
- `apps/iosApp/Supermux/Watch/PhoneWatchProvisioner.swift` — **create:** iOS-side WCSession sender of `{baseURL, token}`.
- Wire `PhoneWatchProvisioner` into `apps/iosApp/Supermux/App/SupermuxApp.swift` + on token change in `Pairing/BrokerConfig.swift`.

---

## Task 1: KMP — add watchOS targets + `appleMain` source set

**Files:**
- Modify: `apps/shared/build.gradle.kts`
- Move: `apps/shared/src/iosMain/kotlin/dev/supermux/net/IosClient.kt` → `apps/shared/src/appleMain/kotlin/dev/supermux/net/DarwinClient.kt`

- [ ] **Step 1: Inspect the current iOS Darwin code** so it moves cleanly.

Run: `sed -n '1,80p' apps/shared/src/iosMain/kotlin/dev/supermux/net/IosClient.kt` — note the exported symbol name (`IosClientKt.iosHttpClient()`); keep the same function name so the iOS Swift side keeps compiling (do NOT rename the function, only the file/source-set).

- [ ] **Step 2: Introduce watchOS targets + `appleMain` hierarchy** in `apps/shared/build.gradle.kts`.

Replace the iOS-targets block + relevant sourceSets with:

```kotlin
    // Apple targets. Compile/link tasks run on a Mac; disabled on Linux via
    // kotlin.native.ignoreDisabledTargets=true.
    val appleTargets = listOf(
        iosArm64(), iosSimulatorArm64(),
        watchosArm64(), watchosSimulatorArm64(),
    )
    appleTargets.forEach { t ->
        t.binaries.framework {
            baseName = "Shared"
            isStatic = false
        }
    }

    // … inside sourceSets { } …
    val appleMain by creating { dependsOn(commonMain.get()) }
    iosMain.get().dependsOn(appleMain)
    val watchosMain by getting { dependsOn(appleMain) }
    appleMain.dependencies { implementation(libs.ktor.client.darwin) }
```

Remove the old `iosMain.dependencies { implementation(libs.ktor.client.darwin) }` line (now in `appleMain`). Keep `jvmMain`/`androidMain` as-is.

- [ ] **Step 3: Move the Darwin client into `appleMain`.**

```bash
mkdir -p apps/shared/src/appleMain/kotlin/dev/supermux/net
git mv apps/shared/src/iosMain/kotlin/dev/supermux/net/IosClient.kt \
       apps/shared/src/appleMain/kotlin/dev/supermux/net/DarwinClient.kt
```

(Keep the `package` line and the `iosHttpClient()` function name unchanged — only the file location moves.)

- [ ] **Step 4: Verify the non-native build + tests still pass locally (Linux).**

Run: `cd apps/shared && ../../gradlew :shared:jvmTest`
Expected: `BUILD SUCCESSFUL` (the source-set refactor must not break commonMain logic; native targets stay disabled on Linux).

- [ ] **Step 5: Verify the watchOS framework actually links on the Mac** (the primary risk gate).

Sync the tree to the Mac (see Task 9 helper) then:
```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-watch && ./gradlew --no-daemon :shared:linkDebugFrameworkWatchosSimulatorArm64'
```
Expected: `BUILD SUCCESSFUL` and a `Shared.framework` under `apps/shared/build/bin/watchosSimulatorArm64/`.
**If SKIE or Ktor-Darwin fails for watchOS:** fall back to the spec's thin-Swift-client plan (URLSession WS + REST on the watch) — STOP and re-plan Tasks 4–7 against that fallback before proceeding.

- [ ] **Step 6: Commit.**

```bash
git add apps/shared/build.gradle.kts apps/shared/src
git commit -m "feat(shared): add watchOS KMP targets + appleMain source set"
```

---

## Task 2: XcodeGen — add the `SupermuxWatch` watchOS app target

**Files:**
- Modify: `apps/iosApp/project.yml`
- Create: `apps/iosApp/SupermuxWatch/SupermuxWatchApp.swift` (placeholder), `apps/iosApp/SupermuxWatch/Info.plist`

- [ ] **Step 1: Add the watch target** to `project.yml` (mirror the `Supermux` target's framework search paths + the KMP pre-build phase, but for watchOS). Add under `targets:`:

```yaml
  SupermuxWatch:
    type: application
    platform: watchOS
    deploymentTarget: "26.0"
    sources:
      - path: SupermuxWatch
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.supermux.app.watchkitapp
        INFOPLIST_FILE: SupermuxWatch/Info.plist
        FRAMEWORK_SEARCH_PATHS:
          - $(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
        OTHER_LDFLAGS: [-framework, Shared]
        GENERATE_INFOPLIST_FILE: NO
    preBuildScripts:
      - name: Build Kotlin Shared.framework (watchOS)
        basedOnDependencyAnalysis: false
        script: |
          set -e
          source ~/ios-build-env.sh 2>/dev/null || true
          export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
          cd "$SRCROOT/.."
          ./gradlew --no-daemon :shared:embedAndSignAppleFrameworkForXcode
```

Then add the watch app as an embedded dependency of the iOS app target `Supermux`:
```yaml
    dependencies:
      - package: SwiftTerm
      - target: SupermuxWatch   # embeds the watch app into the iOS bundle
```

- [ ] **Step 2: Create `Info.plist`** at `apps/iosApp/SupermuxWatch/Info.plist` with the companion link:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDisplayName</key><string>Supermux</string>
  <key>WKApplication</key><true/>
  <key>WKCompanionAppBundleIdentifier</key><string>dev.supermux.app</string>
  <key>NSSpeechRecognitionUsageDescription</key><string>Dictate messages to your agents.</string>
</dict>
</plist>
```

- [ ] **Step 3: Placeholder `@main` app** at `apps/iosApp/SupermuxWatch/SupermuxWatchApp.swift`:

```swift
import SwiftUI

@main
struct SupermuxWatchApp: App {
    var body: some Scene {
        WindowGroup { Text("Supermux Watch").font(.headline) }
    }
}
```

- [ ] **Step 4: Generate + build the empty watch app on the sim** (proves the target + KMP pre-build wire up). See Task 9 for the sync+build helper; build scheme `SupermuxWatch` for `-sdk watchsimulator26.5 -destination 'platform=watchOS Simulator,name=Apple Watch Series 11 (46mm)'`.
Expected: `BUILD SUCCEEDED`; install to the watch sim + screenshot shows "Supermux Watch".

- [ ] **Step 5: Commit.**
```bash
git add apps/iosApp/project.yml apps/iosApp/SupermuxWatch
git commit -m "feat(watch): add SupermuxWatch watchOS app target (empty shell)"
```

---

## Task 3: Watch Keychain + provisioning (WatchConnectivity + env fallback)

**Files:** Create `apps/iosApp/SupermuxWatch/Watch/WatchKeychain.swift`, `Watch/WatchProvisioning.swift`

- [ ] **Step 1: `WatchKeychain`** — mirror the iOS `KeychainStore` (service `dev.supermux.app`, account `device_token`, accessible `.afterFirstUnlock`) plus a UserDefaults `broker_base_url`. Provide `save(baseURL:token:)`, `load() -> (baseURL,token)?`, `clear()`.

- [ ] **Step 2: `WatchProvisioning`** — an `@Observable @MainActor` object that:
  - reads `WatchKeychain.load()` on init;
  - is a `WCSessionDelegate`: on `didReceiveApplicationContext` / `didReceiveUserInfo` with keys `baseURL`+`token`, save to Keychain and publish;
  - **headless test fallback:** if `ProcessInfo.processInfo.environment["SM_PAIR_TOKEN"]` and `SM_PAIR_BASE` are set, use those (mirrors the iOS app's `SIMCTL_CHILD_SM_PAIR_*` hooks) so the sim can be provisioned without a live phone handshake.
  - exposes `var creds: (baseURL: String, token: String)?`.

- [ ] **Step 3: Build to confirm it compiles** (Task 9 helper, scheme `SupermuxWatch`). Expected: `BUILD SUCCEEDED`.

- [ ] **Step 4: Commit.** `git commit -m "feat(watch): keychain + WatchConnectivity/env provisioning"`

---

## Task 4: Watch broker session wrapper (reuse shared client)

**Files:** Create `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift`

- [ ] **Step 1:** Write a slim `@Observable @MainActor WatchBrokerSession`, modeled on the iOS `BrokerSession` but ONLY: connect (`BrokerClient` WS subscribe), `sessions: [SessionInfo]`, `messages: [String:[LogEntry]]`, `send(sessionId,text,attachments?)`, `transcribeDraft(sessionId,draft) -> String`, `loadFile(id) -> Data?`. Take `{baseURL, token}` from `WatchProvisioning`. Reuse `IosClientKt.iosHttpClient()` (now in appleMain → available to watchOS) for REST. No terminal/editor/display/git/finish code.

- [ ] **Step 2: Build** (Task 9). Expected: `BUILD SUCCEEDED`. (Bridging gotchas to expect, per infra notes: Kotlin `Boolean?` ↔ `KotlinBoolean?` via the `.kb`/`.boolValue` helpers; suspend→async via SKIE.)

- [ ] **Step 3: Commit.** `git commit -m "feat(watch): slim broker session over shared KMP client"`

---

## Task 5: Sessions list screen

**Files:** Create `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift`; wire into `SupermuxWatchApp.swift`

- [ ] **Step 1:** SwiftUI `List` (Crown-scrollable) of `broker.sessions` sorted by recent activity. Row: name (`.headline`), agent (`.caption`, secondary), a small activity/status dot (`connected`/`status`). States: not-provisioned → "Open Supermux on iPhone to connect"; provisioned-but-connecting → "Connecting…"; empty → "No active sessions". `NavigationStack` → tap pushes `SessionDetailView(session)`. ≥44pt rows, semantic colors, Dynamic Type.

- [ ] **Step 2: Build + screenshot with real data** (Task 9 + provision via `SM_PAIR_*` env to `http://100.84.92.82:9898`). Expected: list shows live sessions (e.g. `dockie`, `travel-assistant`).

- [ ] **Step 3: Commit.** `git commit -m "feat(watch): sessions list screen"`

---

## Task 6: Session detail screen (history + photos)

**Files:** Create `apps/iosApp/SupermuxWatch/Watch/SessionDetailView.swift`

- [ ] **Step 1:** `ScrollView` of `broker.messages[session.id]`. Agent (`direction=="outbound"`) → render `Text(AttributedString(markdown:))` (fall back to plain on parse error); user (`inbound`) → plain text bubble. Inline images: for attachments where `mime` starts `image`/`kind=="photo"`, async `broker.loadFile(file_id)` → `Image(uiImage:)` downscaled to watch width, tap → full-screen `.sheet`. Crown scrolls; auto-scroll to newest on appear/append. Bottom toolbar mic button (wired in Task 7).

- [ ] **Step 2: Build + screenshot** an opened session with history + a photo (use a session known to have an image). Expected: readable history, image renders.

- [ ] **Step 3: Commit.** `git commit -m "feat(watch): session detail with history + inline photos"`

---

## Task 7: Voice input (system dictation → cleanup → send)

**Files:** Create `apps/iosApp/SupermuxWatch/Watch/VoiceInput.swift`; wire mic button in `SessionDetailView`

- [ ] **Step 1:** Mic button presents the watch's native text input (SwiftUI: a `TextField`/`.sheet` using the system input controller, which offers dictation + scribble + emoji). On returned text: optimistic local echo into `messages`, call `broker.transcribeDraft(session.id, draft: text)` (glossary + LLM cleanup), then `broker.send(session.id, cleaned)`. Show a subtle "cleaning…" state; on cleanup failure send the raw draft. Empty result → no-op.

- [ ] **Step 2: Build + verify on sim** — tap mic, type/dictate, confirm the message posts to the live broker (verify it appears in the session on the host, e.g. via the PWA or broker logs). Screenshot the composer + sent message.

- [ ] **Step 3: Commit.** `git commit -m "feat(watch): voice input via system dictation + broker cleanup"`

---

## Task 8: Phone-side WatchConnectivity sender

**Files:** Create `apps/iosApp/Supermux/Watch/PhoneWatchProvisioner.swift`; wire into `App/SupermuxApp.swift` (+ resend on token change)

- [ ] **Step 1:** `PhoneWatchProvisioner` — `WCSessionDelegate` on iOS: on `activate` and when `session.isWatchAppInstalled`, send `{baseURL: BrokerConfig.baseURL, token: BrokerConfig.token}` via `updateApplicationContext` (idempotent latest-wins). Resend when the token changes (call from `BrokerConfig` save) and clear on unpair. Guard `WCSession.isSupported()`.

- [ ] **Step 2:** Activate it from `SupermuxApp.init()` (after BrokerConfig read), alongside the existing pairing flow.

- [ ] **Step 3: Build the iOS app** on the sim (scheme `Supermux`) to confirm no regression. Expected: `BUILD SUCCEEDED`.

- [ ] **Step 4: Commit.** `git commit -m "feat(ios): provision watch credentials via WatchConnectivity"`

---

## Task 9: Build + verify in the watch Simulator (helper + milestone)

This task documents the **sync+build+screenshot helper** referenced above; run the relevant parts at each task's build step.

- [ ] **Sync the working tree to the Mac (tar-over-ssh):**
```bash
cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/e3ce3ac8-dcbe-42b4-892c-d765fbf792a6
tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules -czf - . \
  | ssh mac 'rm -rf ~/supermux-watch && mkdir -p ~/supermux-watch && tar -xzf - -C ~/supermux-watch'
```

- [ ] **Generate + build the watch app (sim):**
```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-watch/apps/iosApp && xcodegen generate && \
  nohup xcodebuild -scheme SupermuxWatch -sdk watchsimulator26.5 \
    -destination "platform=watchOS Simulator,name=Apple Watch Series 11 (46mm)" \
    -derivedDataPath build/dd ARCHS=arm64 EXCLUDED_ARCHS=x86_64 \
    CODE_SIGNING_ALLOWED=NO build > ~/supermux-watch/build.log 2>&1 &'
# poll: ssh mac 'tail -3 ~/supermux-watch/build.log; grep -c "BUILD SUCCEEDED" ~/supermux-watch/build.log'
```

- [ ] **Boot the paired pair, install, provision, launch, screenshot:**
```bash
TOK=$(cd /home/ahmet/projects/supermux && bun run pair watch-sim | grep -oE 't=[^&]+' | cut -d= -f2)
ssh mac "xcrun simctl boot 'Apple Watch Series 11 (46mm)' 2>/dev/null; \
  xcrun simctl install '0B2A2E11-87B8-4507-BCF9-3FB46BD2F2CD' <APP_PATH_FROM_build/dd>; \
  SIMCTL_CHILD_SM_PAIR_BASE=http://100.84.92.82:9898 SIMCTL_CHILD_SM_PAIR_TOKEN=$TOK \
  xcrun simctl launch --terminate-running-process '0B2A2E11-87B8-4507-BCF9-3FB46BD2F2CD' dev.supermux.app.watchkitapp; \
  sleep 11; xcrun simctl io '0B2A2E11-87B8-4507-BCF9-3FB46BD2F2CD' screenshot /tmp/watch.png"
scp mac:/tmp/watch.png /tmp/watch.png   # then Read /tmp/watch.png
```
Expected milestone: screenshots of sessions list + detail + voice, on the watch sim, with live broker data.

---

## Task 10: Physical Apple Watch install + on-device verification

- [ ] **Step 1:** Follow `mux:ios-device-on-remote-mac`. Build `Supermux` (which embeds `SupermuxWatch`) for `iphoneos` Release-signed (team `57L7J9XA89`); the watch app installs with the phone app (paired install). Confirm the watch app appears on the physical watch.
- [ ] **Step 2:** On the watch: open app → open Supermux on iPhone so WatchConnectivity provisions creds → confirm the sessions list loads over the watch's own connection → open a session → dictate + send a message → confirm it lands in the session.
- [ ] **Step 3:** Verify feel on-device (Crown scroll, tap targets, dictation). Capture notes.

---

## Task 11: Version bump + finalize

- [ ] **Step 1:** Bump `CURRENT_PROJECT_VERSION` / marketing version in `project.yml` for both targets (iOS silently keeps the old build otherwise).
- [ ] **Step 2:** Run the broker test suite + `tsc` (no server changes expected, but confirm clean): `cd /home/ahmet/projects/supermux && bun test && bunx tsc --noEmit` (or the repo's configured commands).
- [ ] **Step 3: Commit + report.** Summarize what shipped, the test surface + evidence (sim screenshots, on-device confirmation), and any follow-ups (e.g. native push project, lite WS subscribe).

---

## Self-review notes

- **Spec coverage:** sessions list (T5) · history+photos (T6) · voice (T7) · independent connection via shared client (T1,T4) · WatchConnectivity provisioning (T3 watch + T8 phone) · pull-only/no-push (no push tasks, by design) · build/sign/verify on Mac (T9,T10). All spec sections map to a task.
- **Primary risk gate is T1.S5** (watchOS KMP framework links) with an explicit thin-Swift-client fallback before building UI.
- **No broker/server changes** in v1 — the watch reuses existing `/ws`, `/sessions/{id}/reply`, `/sessions/{id}/transcribe`, `/files/{id}`.
- **Open default:** min watchOS = 26.0 (matches iOS 26 stance).
