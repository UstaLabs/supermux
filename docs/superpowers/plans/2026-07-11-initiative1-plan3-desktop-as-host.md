# Initiative 1 · Plan 3 — Desktop-as-Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the macOS + Linux desktop apps embed the broker so the machine is itself a host — install → first-run QR wizard → phone scans → agents from anywhere — with a supervised broker sidecar (adopt-don't-duplicate), bundled static tmux + frpc, and login keep-alive. Windows desktop stays client-only with a "Host from this PC — preview" card.

**Architecture:** A `BrokerSidecar` supervisor (JVM) spawns or **adopts** a broker on :9898 (probe `GET /host`; never kill a broker it didn't start), exposes health, and its identity feeds a first-run **HostWizard** that mints a one-time `claimSecret` from the local broker and renders it as a pairing QR (reusing Plan 2's `PairingPayload`). Bundled `tmux`/`frpc` binaries ship in the app image; a login agent (launchd/systemd-user) keeps the host alive when the window closes.

**Tech Stack:** Compose Desktop (JVM), `apps/desktop`; the Bun broker bundled as a runtime asset; static `tmux` (Linux/macOS) + `frpc` binaries; launchd plist / systemd `--user` unit.

**Spec:** `docs/superpowers/specs/2026-07-11-desktop-host-multihost-design.md` §6, D6, D7, D11. **Depends on** Plan 1 (host identity, claim, GET /host) + Plan 2 (`PairingPayload`, `PairedHostStore`).

**Verifiability note:** Task 1 (`BrokerSidecar` adopt/health logic) is JVM-unit-testable on this host. Tasks 2–6 (wizard UI, QR, keep-alive units, packaging with bundled binaries, Windows preview card) build against Compose Desktop and REQUIRE a desktop display (Xvfb + `SKIKO_RENDER_API=SOFTWARE` for headless build/smoke) and real OS integration to verify; macOS launchd + the .app bundle need a Mac. Build-green + spec-conformance is the bar; runtime is display/OS-gated.

---

## File Structure

**Create:**
- `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/BrokerSidecar.kt` — spawn/adopt/health/stop.
- `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/HostProbe.kt` — the pure adopt-decision logic.
- `apps/desktop/src/test/kotlin/dev/supermux/desktop/host/HostProbeTest.kt`
- `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/HostWizard.kt` — first-run QR wizard.
- `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/QrCode.kt` — QR bitmap from a string (bundled encoder, no network).
- `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/KeepAlive.kt` — install/remove launchd/systemd unit.

**Modify:** `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt` (start the sidecar, gate the wizard on first run); the packaging Gradle config (bundle tmux/frpc/broker); the Windows build to show the preview card.

---

## Task 1: `HostProbe` — the adopt-or-spawn decision (pure, JVM-testable)

**Files:** Create `HostProbe.kt` + `HostProbeTest.kt`.

The decision spec §6: probe `GET /host` on :9898 → valid supermux host ⇒ ADOPT (external); a 404-but-legacy fingerprint ⇒ "upgrade required"; a foreign process ⇒ conflict (use an alternate port); nothing ⇒ SPAWN our own (managed).

- [ ] **Step 1: Failing test** — `HostProbeTest.kt`:

```kotlin
package dev.supermux.desktop.host

import kotlin.test.Test
import kotlin.test.assertEquals

class HostProbeTest {
    @Test fun validHostAdopts() {
        assertEquals(HostDecision.AdoptExternal,
            decideHost(HostProbeResult.SupermuxHost(hostId = "habc")))
    }
    @Test fun legacyNeedsUpgrade() {
        assertEquals(HostDecision.UpgradeRequired,
            decideHost(HostProbeResult.LegacySupermux))
    }
    @Test fun foreignProcessConflicts() {
        assertEquals(HostDecision.PortConflict,
            decideHost(HostProbeResult.ForeignProcess))
    }
    @Test fun nothingSpawnsManaged() {
        assertEquals(HostDecision.SpawnManaged,
            decideHost(HostProbeResult.PortFree))
    }
}
```

- [ ] **Step 2: Run — expect FAIL** — `cd apps && ./gradlew :desktop:test --tests "dev.supermux.desktop.host.*" -Dorg.gradle.jvmargs=-Xmx2048m`
- [ ] **Step 3: Implement** — `HostProbe.kt`:

```kotlin
package dev.supermux.desktop.host

sealed interface HostProbeResult {
    data class SupermuxHost(val hostId: String) : HostProbeResult
    object LegacySupermux : HostProbeResult   // responds but no GET /host (pre-Plan-1)
    object ForeignProcess : HostProbeResult   // :9898 held by something else
    object PortFree : HostProbeResult
}

enum class HostDecision { AdoptExternal, UpgradeRequired, PortConflict, SpawnManaged }

/** Pure policy — spec §6 adopt-don't-duplicate. Never returns "kill". */
fun decideHost(probe: HostProbeResult): HostDecision = when (probe) {
    is HostProbeResult.SupermuxHost -> HostDecision.AdoptExternal
    HostProbeResult.LegacySupermux -> HostDecision.UpgradeRequired
    HostProbeResult.ForeignProcess -> HostDecision.PortConflict
    HostProbeResult.PortFree -> HostDecision.SpawnManaged
}
```

- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit.**

---

## Task 2: `BrokerSidecar` — spawn / adopt / health / stop

**Files:** Create `BrokerSidecar.kt`.

- [ ] **Step 1:** Implement a supervisor that: acquires a per-user file lock in `~/.mux/state` (shared with the keep-alive unit); runs `HostProbe` against :9898; on `SpawnManaged` launches the bundled Bun broker (`ProcessBuilder`, env `MUX_WEB_PORT`, `MUX_RELAY_DOMAIN` when hosting-remote is on) and marks ownership `managed`; on `AdoptExternal` marks `external` and never stops it; on `PortConflict` picks a persisted alternate port. Health = poll `GET /host` until 200.
- [ ] **Step 2:** Expose `hostId`, `state (starting/online/adopted/conflict)`, `ownership`, and a `stop()` that only stops a `managed` broker.
- [ ] **Step 3:** Build `:desktop` green (compileKotlinJvm); commit.

> Runtime verification (headless): sidecar spawns the broker and `GET /host` returns an id. Needs the bundled broker asset (Task 5) — until then test against a source `bun src/main.ts`. Display-gated for the full app.

---

## Task 3: `HostWizard` + `QrCode` — first-run pairing screen (D6 choice A)

**Files:** `HostWizard.kt`, `QrCode.kt`; modify `Main.kt` to show the wizard on first run.

- [ ] **Step 1:** `QrCode.kt` — encode a string to a Compose `ImageBitmap` using a bundled QR encoder (ZXing-core is already a common choice; add the dep to `:desktop` — no network). Pure function `qrBitmap(text: String): ImageBitmap`.
- [ ] **Step 2:** `HostWizard.kt` — on first run: call the local broker to mint a `claimSecret` (new broker endpoint `POST /pair/mint-claim`, authed by the desktop's own local token — add it in the broker, tiny), build a `PairingPayload` (v1, hostId from the sidecar, relayUrl when hosting-remote), `Json.encodeToString` it, render the QR + "This computer is ready to host your agents. Scan with your phone." + a checked "Keep this computer available when the app is closed and after I sign in" box + the relay-disclosure line (spec §6 copy).
- [ ] **Step 3:** Wire into `Main.kt` — first run shows the wizard; the desktop auto-pairs to its own local host (loopback token) so "This computer" appears in the fleet (Plan 2 store).
- [ ] **Step 4:** Build green; commit.

> The broker `POST /pair/mint-claim` (authed → `claimStore.mint()` → returns `{claimSecret}`) is a ~10-line addition to `src/channels/web/index.ts` behind the auth gate; include it here since the wizard depends on it.

---

## Task 4: `KeepAlive` — login autostart unit

**Files:** `KeepAlive.kt`.

- [ ] **Step 1:** `install()` writes a launchd LaunchAgent plist (`~/Library/LaunchAgents/dev.supermux.host.plist`, macOS) or a systemd `--user` unit + `systemctl --user enable` (Linux; XDG-autostart fallback), launching a stable host-launcher. `remove()` tears it down. Unchecking the wizard box calls neither. Uses the same file lock as the sidecar.
- [ ] **Step 2:** Guard every `getuid`/`launchctl`/`systemctl` behind platform checks (they no-op on the wrong OS). Build green; commit.

> OS-integration verification needs a real Mac/Linux desktop session — build + unit-test the plist/unit string generation here; runtime install is OS-gated.

---

## Task 5: Packaging — bundle broker + static tmux + frpc

**Files:** `apps/desktop` Gradle packaging config (jpackage / the existing `.deb`/`.msi` flow).

- [ ] **Step 1:** Add the Bun broker build, a static `tmux` (Linux/macOS), and `frpc` (all platforms, ~13 MB) as bundled runtime resources; resolve their paths at runtime (dev = repo/`$PATH`, packaged = app resources). Materialize with exec permission on first use (mirror the broker's `runtime-assets.ts` pattern).
- [ ] **Step 2:** Extend the existing GitHub Actions `.deb`/`.msi` job to include the bundled binaries; Windows omits tmux (client-only) but includes frpc for when it later hosts.
- [ ] **Step 3:** Build the Linux `.deb`; smoke-test that the bundled broker launches from the installed layout. Commit.

> Packaging is the heaviest-to-verify task — needs the full jpackage/.deb build (OOM-prone here; run solo with capped heap) and ideally a clean-container install smoke. macOS `.app` + notarization needs a Mac.

---

## Task 6: Windows preview card + finish

**Files:** the desktop fleet list (Plan 2 Task 12) — Windows build branch.

- [ ] **Step 1:** On the Windows build, the fleet list shows an enabled "Host from this PC — native hosting is coming next. Join the preview." card → opens an explainer + a preview-signup action (POST a counter to supermux.dev or a local log) + the documented advanced WSL-host path (pairs as a separate `This PC — Ubuntu (WSL)` host).
- [ ] **Step 2:** macOS/Linux hide the card (they host natively). Build all three desktop targets green; commit.

## Task 7: Finish

- [ ] `:desktop:test` green (HostProbe + any unit tests); `:desktop` build green on all targets.
- [ ] Manual/headless smoke where possible; clearly flag device/OS-gated items as unverified-in-session.

## Self-review (coverage)
Spec §6 adopt-don't-duplicate → Tasks 1, 2. First-run QR wizard + keep-alive default-on (D6) → Tasks 3, 4. Bundled tmux/frpc (D7/D11) → Task 5. Windows client + preview card → Task 6. The broker `POST /pair/mint-claim` dependency is folded into Task 3. macOS/launchd + packaging runtime = OS/display-gated, flagged.
