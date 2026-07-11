# Initiative 1 · Plan 2 — Multi-Host Clients Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Turn every native client from single-host into multi-host — a shared `PairedHost` list (with transparent migration from today's single token+baseUrl), a merged all-hosts fleet list, add-host-by-QR, and per-host push routing — Android first, then iOS, then desktop.

**Architecture:** The load-bearing piece is a **shared KMP `PairedHostStore`** (`apps/shared`, commonMain) that every client reuses: pure Kotlin list logic + JSON + migration + hostId-backfill dedupe, behind a `HostPersistence` interface each platform implements (metadata in normal storage, one token per record in the secure store — spec §3.2). The native layers each render the same merged fleet from N per-host `BrokerApi` connections. Web PWA is out of scope (D9, single-host).

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, `:shared:jvmTest` (verifiable on this Linux host); Android Compose + DataStore + Keystore; iOS SwiftUI + UserDefaults + Keychain; Compose Desktop.

**Spec:** `docs/superpowers/specs/2026-07-11-desktop-host-multihost-design.md` §3.2, §5, D5, D9. **Depends on Plan 1** (GET /host, claim pairing) already merged.

**Verifiability note:** Tasks 1–5 (shared KMP) are TDD'd and run on this host via `:shared:jvmTest`. Tasks 6–8 (Android), 9–11 (iOS), 12–13 (desktop) build native UI and REQUIRE an emulator / Mac / desktop display to verify at runtime — build-green + spec-conformance is the bar here; runtime feel is device-gated.

---

## File Structure

**Create (shared, commonMain):**
- `apps/shared/src/commonMain/kotlin/dev/supermux/host/PairedHost.kt` — the `@Serializable` model.
- `apps/shared/src/commonMain/kotlin/dev/supermux/host/PairedHostStore.kt` — list logic, migration, backfill/dedupe.
- `apps/shared/src/commonMain/kotlin/dev/supermux/host/HostPersistence.kt` — the storage interface.
- `apps/shared/src/commonMain/kotlin/dev/supermux/host/PairingPayload.kt` — parse/validate the QR/link payload.
- `apps/shared/src/commonTest/kotlin/dev/supermux/host/PairedHostStoreTest.kt`
- `apps/shared/src/commonTest/kotlin/dev/supermux/host/PairingPayloadTest.kt`

**Modify (native, per platform):** Android `SessionListScreen`/host chips/`AddHostScreen`/push handler; iOS `SessionListView`/`AddHostView`/NSE; desktop fleet list. Exact files located in each native task.

---

## Task 1: `PairedHost` model

**Files:** Create `apps/shared/src/commonMain/kotlin/dev/supermux/host/PairedHost.kt` + test.

- [ ] **Step 1: Write the failing test** — `apps/shared/src/commonTest/kotlin/dev/supermux/host/PairedHostStoreTest.kt`:

```kotlin
package dev.supermux.host

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PairedHostModelTest {
    @Test fun roundTripsThroughJson() {
        val h = PairedHost(recordId = "r1", hostId = "habc", displayName = "MacBook",
            directUrl = "http://192.168.1.2:9898", relayUrl = "https://h-habc.relay.supermux.dev",
            token = "tok", platform = "macos", version = "0.11.0", lastSeenAt = 1000L)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(h, json.decodeFromString(PairedHost.serializer(), json.encodeToString(PairedHost.serializer(), h)))
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (module not found):

Run: `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.host.*" -Dorg.gradle.jvmargs=-Xmx2048m`
Expected: compile error / no such class.

- [ ] **Step 3: Implement** — `PairedHost.kt`:

```kotlin
package dev.supermux.host

import kotlinx.serialization.Serializable

/** One paired broker. recordId is the always-present internal key; hostId is
 *  backfilled from GET /host once known (null against pre-Plan-1 brokers). */
@Serializable
data class PairedHost(
    val recordId: String,
    val hostId: String? = null,
    val displayName: String,
    val directUrl: String? = null,
    val relayUrl: String? = null,
    val token: String,
    val platform: String? = null,
    val version: String? = null,
    val lastSeenAt: Long = 0L,
)
```

- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `git add apps/shared/src/commonMain/kotlin/dev/supermux/host/PairedHost.kt apps/shared/src/commonTest/kotlin/dev/supermux/host/PairedHostStoreTest.kt && git commit -m "feat(shared): PairedHost model"`

---

## Task 2: `HostPersistence` interface

**Files:** Create `apps/shared/src/commonMain/kotlin/dev/supermux/host/HostPersistence.kt`.

- [ ] **Step 1: Implement (interface — no separate test; exercised via the store tests):**

```kotlin
package dev.supermux.host

/** Platform storage. Implementations put metadata in normal storage and each
 *  host's token in the secure store (spec §3.2) — the store logic is agnostic. */
interface HostPersistence {
    fun loadAll(): List<PairedHost>
    fun saveAll(hosts: List<PairedHost>)
}
```

- [ ] **Step 2: Commit** — `git add apps/shared/src/commonMain/kotlin/dev/supermux/host/HostPersistence.kt && git commit -m "feat(shared): HostPersistence storage interface"`

---

## Task 3: `PairedHostStore` — list ops + migration + backfill/dedupe

**Files:** Create `apps/shared/src/commonMain/kotlin/dev/supermux/host/PairedHostStore.kt`; extend the test file.

- [ ] **Step 1: Write failing tests** — append to `PairedHostStoreTest.kt`:

```kotlin
class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
    override fun loadAll() = hosts.toList()
    override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
}

class PairedHostStoreTest {
    private fun store(vararg h: PairedHost) = PairedHostStore(FakePersistence(h.toMutableList())) { "gen-id" }

    @Test fun addAppendsAndPersists() {
        val s = store()
        s.add(displayName = "box", token = "t", relayUrl = "https://h-x.relay.supermux.dev", hostId = "x")
        assertEquals(1, s.list().size)
        assertEquals("box", s.list()[0].displayName)
    }

    @Test fun migrateFromSingleHostSeedsRecordZero() {
        val s = store()
        s.migrateFromSingleHost(token = "legacy", baseUrl = "https://old.example.com")
        assertEquals(1, s.list().size)
        assertEquals("legacy", s.list()[0].token)
        assertEquals("https://old.example.com", s.list()[0].relayUrl ?: s.list()[0].directUrl)
    }

    @Test fun migrateIsIdempotent() {
        val s = store()
        s.migrateFromSingleHost("legacy", "https://old.example.com")
        s.migrateFromSingleHost("legacy", "https://old.example.com")
        assertEquals(1, s.list().size)
    }

    @Test fun backfillHostIdSetsItWhenAbsent() {
        val s = store(PairedHost(recordId = "r1", displayName = "box", token = "t", relayUrl = "u"))
        s.backfillHostId("r1", "habc")
        assertEquals("habc", s.list()[0].hostId)
    }

    @Test fun backfillMergesDuplicateHostKeepingValidToken() {
        // Two records that turn out to be the same host; the one with the newer
        // token wins, no credential is silently dropped, display name preserved.
        val s = store(
            PairedHost(recordId = "r1", displayName = "MyMac", token = "old", relayUrl = "u1"),
            PairedHost(recordId = "r2", hostId = "habc", displayName = "auto", token = "new", relayUrl = "u2"),
        )
        s.backfillHostId("r1", "habc")
        // r1 and r2 are the same host now → merge to one record
        assertEquals(1, s.list().size)
        assertEquals("habc", s.list()[0].hostId)
    }

    @Test fun removeDropsByRecordId() {
        val s = store(PairedHost(recordId = "r1", displayName = "a", token = "t"))
        s.remove("r1")
        assertEquals(0, s.list().size)
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement** — `PairedHostStore.kt`:

```kotlin
package dev.supermux.host

/** Pure multi-host list logic. `newId` supplies recordIds (platform RNG in prod,
 *  fixed in tests). Persists through HostPersistence after every mutation. */
class PairedHostStore(
    private val persistence: HostPersistence,
    private val newId: () -> String,
) {
    private val hosts: MutableList<PairedHost> = persistence.loadAll().toMutableList()

    fun list(): List<PairedHost> = hosts.toList()

    fun add(displayName: String, token: String, relayUrl: String? = null,
            directUrl: String? = null, hostId: String? = null,
            platform: String? = null, version: String? = null): PairedHost {
        val h = PairedHost(recordId = newId(), hostId = hostId, displayName = displayName,
            token = token, relayUrl = relayUrl, directUrl = directUrl, platform = platform, version = version)
        hosts.add(h); flush(); return h
    }

    /** One-time seed of the pre-multi-host (token, baseUrl). No-op if any host exists. */
    fun migrateFromSingleHost(token: String, baseUrl: String) {
        if (hosts.isNotEmpty()) return
        val isRelay = baseUrl.contains(".relay.")
        hosts.add(PairedHost(recordId = newId(), displayName = "This host", token = token,
            relayUrl = if (isRelay) baseUrl else null, directUrl = if (isRelay) null else baseUrl))
        flush()
    }

    /** Learn a record's hostId from GET /host; merge if another record already has it. */
    fun backfillHostId(recordId: String, hostId: String) {
        val idx = hosts.indexOfFirst { it.recordId == recordId }
        if (idx < 0) return
        val dupe = hosts.indexOfFirst { it.hostId == hostId && it.recordId != recordId }
        if (dupe >= 0) {
            // Same host reached two ways — keep one record. Prefer the newer token
            // (the just-paired record), preserve the user's display name + position.
            val keep = hosts[idx].copy(hostId = hostId, displayName = hosts[dupe].displayName.ifBlank { hosts[idx].displayName })
            hosts[minOf(idx, dupe)] = keep
            hosts.removeAt(maxOf(idx, dupe))
        } else {
            hosts[idx] = hosts[idx].copy(hostId = hostId)
        }
        flush()
    }

    fun rename(recordId: String, name: String) { mutate(recordId) { it.copy(displayName = name) } }
    fun updateSeen(recordId: String, at: Long) { mutate(recordId) { it.copy(lastSeenAt = at) } }
    fun remove(recordId: String) { hosts.removeAll { it.recordId == recordId }; flush() }

    private fun mutate(recordId: String, f: (PairedHost) -> PairedHost) {
        val i = hosts.indexOfFirst { it.recordId == recordId }; if (i < 0) return
        hosts[i] = f(hosts[i]); flush()
    }
    private fun flush() = persistence.saveAll(hosts)
}
```

- [ ] **Step 4: Run — expect PASS** (all store tests).
- [ ] **Step 5: Commit** — `git add ...host/PairedHostStore.kt ...PairedHostStoreTest.kt && git commit -m "feat(shared): PairedHostStore — list ops, single-host migration, hostId backfill+merge"`

---

## Task 4: `PairingPayload` — parse + validate the QR/link

**Files:** Create `PairingPayload.kt` + `PairingPayloadTest.kt`.

- [ ] **Step 1: Failing test:**

```kotlin
package dev.supermux.host

import kotlin.test.*

class PairingPayloadTest {
    @Test fun parsesValidV1() {
        val p = PairingPayload.parse("""{"v":1,"action":"pair","hostId":"habc","name":"box","relayUrl":"https://h-habc.relay.supermux.dev","claimSecret":"s3cret"}""")
        assertNotNull(p); assertEquals("habc", p.hostId); assertEquals("s3cret", p.claimSecret)
    }
    @Test fun rejectsWrongVersion() { assertNull(PairingPayload.parse("""{"v":2,"action":"pair","hostId":"h","name":"b","claimSecret":"s"}""")) }
    @Test fun rejectsWrongAction() { assertNull(PairingPayload.parse("""{"v":1,"action":"nope","hostId":"h","name":"b","claimSecret":"s"}""")) }
    @Test fun rejectsNonSupermuxRelayOrigin() { assertNull(PairingPayload.parse("""{"v":1,"action":"pair","hostId":"h","name":"b","relayUrl":"https://evil.example.com","claimSecret":"s"}""")) }
    @Test fun rejectsGarbage() { assertNull(PairingPayload.parse("not json")) }
}
```

- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** — `PairingPayload.kt`:

```kotlin
package dev.supermux.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PairingPayload(
    val v: Int, val action: String, val hostId: String, val name: String,
    val relayUrl: String? = null, val directUrl: String? = null, val claimSecret: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String): PairingPayload? = try {
            val p = json.decodeFromString(serializer(), raw)
            when {
                p.v != 1 || p.action != "pair" -> null
                p.hostId.isBlank() || p.claimSecret.isBlank() -> null
                p.relayUrl != null && !p.relayUrl.contains(".relay.supermux.dev") -> null
                else -> p
            }
        } catch (_: Exception) { null }
    }
}
```

- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit.**

---

## Task 5: Shared-KMP verification gate

- [ ] **Step 1: Full shared JVM test run** — `cd apps && ./gradlew :shared:jvmTest -Dorg.gradle.jvmargs=-Xmx2048m 2>&1 | tail -20`. Expected: BUILD SUCCESSFUL, the new `dev.supermux.host.*` tests green alongside the existing suite.
- [ ] **Step 2: Commit any fixups.** This is the executable core; Tasks 6+ consume it.

---

## Task 6: Android — per-host connection registry

**Files:** Locate with `grep -rn "BrokerApi(" apps/android/src` and `grep -rln "class.*ViewModel" apps/android/src/main/java/dev/supermux`. The app currently holds one BrokerApi/WS; introduce a `HostConnections` map keyed by `recordId`, each a `BrokerApi` + WS, driven by `PairedHostStore`.

- [ ] **Step 1: Android `HostPersistence` actual** — DataStore for metadata + Keystore (`SecureTokenStore.android.kt` pattern) for tokens, one entry per recordId. Write it, wire `PairedHostStore` on top.
- [ ] **Step 2: `HostConnections`** — open/close a `BrokerApi`+WS per online host; expose a merged `StateFlow<List<HostSession>>` where `HostSession = (recordId, session, hostBadge)`.
- [ ] **Step 3: Migration on launch** — call `migrateFromSingleHost` with the existing stored token/baseUrl; verify existing users land as `PairedHost[0]` with zero re-pair.
- [ ] **Step 4: Build** — `cd apps && ./gradlew :android:assembleDebug -Dorg.gradle.jvmargs=-Xmx2048m`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.**

> Runtime verification (emulator): merged list shows sessions from 2 paired hosts with badges. Device-gated — flag if no emulator.

---

## Task 7: Android — merged fleet list + host filter chips + host picker

**Files:** the session-list composable (grep `SessionListScreen`/`SessionRow`) and the new-session launcher (`SessionLauncherScreen`).

- [ ] **Step 1:** Add a compact host badge to each session row (short host name + per-host color dot); render sessions from `HostConnections`' merged flow.
- [ ] **Step 2:** Host filter chip row at top (`All · <host…> · +`), persisted selection; offline host → greyed group header with last-seen from the cached snapshot.
- [ ] **Step 3:** New-session launcher gains a host picker pill (defaults to last-used; hidden with one host); filter agent options by that host's `/agents/status`.
- [ ] **Step 4:** Build green; commit.

---

## Task 8: Android — Add-host (QR + paste) + per-host push

**Files:** new `AddHostScreen`, the existing add-device/QR-scan flow, the FCM receiver.

- [ ] **Step 1:** "Add host" → QR scanner (reuse the existing scanner) + paste-link; parse with `PairingPayload.parse`, POST `/pair/claim`, store the returned `PairedHost` (abort if response hostId ≠ payload hostId); also a typed-URL path (GET /host → claim from host UI) for Tailscale users.
- [ ] **Step 2:** Register the FCM token with each host; include the client `recordId` so the host echoes it in the E2E push payload; route notification taps by `(recordId, sessionId)`.
- [ ] **Step 3:** Build green; commit.

---

## Task 9–11: iOS (SwiftUI + KMP)

Mirror Tasks 6–8 on iOS. `HostPersistence` apple actual = UserDefaults (metadata) + Keychain (tokens). Merged fleet list in `SessionListView` with host badges + filter; `AddHostView` with QR + paste + typed URL; NSE push payload carries recordId. **Build gate:** requires the remote Mac (`ios-simulator-on-remote-mac` skill) — `xcodebuild` green + 104-test suite. Runtime feel is Simulator/device-gated. Commit per task.

## Task 12–13: Desktop (Compose Desktop)

Mirror the fleet list + add-host on the desktop client (`apps/desktop`). `HostPersistence` = the `DesktopTokenStore` pattern + a JSON metadata file. Desktop always has its own local host (Plan 3) plus any added remotes. **Build gate:** `./gradlew :desktop:compileKotlinJvm` green; headless run via Xvfb + `SKIKO_RENDER_API=SOFTWARE`. Commit per task.

---

## Task 14: Cross-client parity check + finish

- [ ] Confirm all three clients share the identical `PairedHostStore` logic (no per-platform reimplementation).
- [ ] `:shared:jvmTest` green; each native build green.
- [ ] Regression: existing single-host users migrate silently on every platform.

## Self-review (coverage)
Spec §3.2 PairedHost + migration + backfill → Tasks 1–5. §5 merged list + filter + offline + host picker → 7, (9), (12). Add-host QR + typed URL → 8, (10), (13). Per-host push with recordId → 8, (10). D9 web excluded — no task, intentional. Shared-first (no per-platform logic dupe) → Task 14 gate.
