# Watch Session List — Phone Parity & Wrist Triage — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Apple Watch session list up to the unified per-session state indicator (working › git › neutral) that already shipped to web/iOS/Android, plus a flat attention-sorted layout and swipe actions — by enriching the watch's REST `/sessions` payload with the signals it can't get over WebSocket.

**Architecture:** The broker enriches the watch-only `GET /sessions` *route response* (not the shared `getSessionsSnapshot()`) with `phase`, last-message preview, and `unread`, reusing opts that already exist (`getSessionAgentState`, `getSessionLog`, `getReads`). The watch decodes those fields, re-derives the tiny git-status rule in pure Swift (it can't link the KMP `GitBadge.kt`), sorts a flat list into needs-you/working/rest buckets, and renders a phone-parity row with swipe actions that reuse existing broker endpoints.

**Tech Stack:** Broker = TypeScript on Bun (`bun test`). Watch = SwiftUI / watchOS, XcodeGen project, XCTest in the `SupermuxTests` target (built on the remote Mac). Design spec: `docs/superpowers/specs/2026-06-27-watch-session-list-design.md`.

---

## Prerequisite (do first)

- [ ] **Locate the remote-Mac SSH host + iOS/watchOS build recipe** used by prior sessions (memory domains `infra` / `claudemux`; the prior watch specs reference it). The broker tasks (1–3) build + test locally with `bun test`; the Swift tasks (4–9) compile and run only on that Mac. Record the working `xcodebuild` scheme + simulator destination before starting the Swift tasks.

## File Structure

**Broker (TypeScript)**
- **Create** `src/channels/web/watch-session-row.ts` — pure `watchRowExtras(state, last, readTs)` helper + `WatchRowExtras` type. One responsibility: derive the watch's extra row fields.
- **Create** `src/channels/web/watch-session-row.test.ts` — `bun test` for the helper.
- **Modify** `src/channels/web/index.ts` — enrich the `GET /sessions` route via the helper; add `POST /sessions/{id}/read`.

**Watch (Swift)**
- **Create** `apps/iosApp/SupermuxWatch/Watch/WatchSessionStatus.swift` — pure logic (no `SessionInfo` dependency): `GitLite`, `sessionStatus`, `isWorking`, `attentionBucket`, `tsValue`. Given **iOS-app target membership** so `SupermuxTests` can reach it.
- **Create** `apps/iosApp/SupermuxTests/WatchSessionStatusTests.swift` — XCTest for the pure logic.
- **Modify** `apps/iosApp/SupermuxWatch/Watch/WatchModels.swift` — decode the new `SessionInfo` fields + `git`/`mute`.
- **Create** `apps/iosApp/SupermuxWatch/Watch/WatchSessionRow.swift` — the parity row view.
- **Modify** `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift` — ordered list, glance header, swipe actions.
- **Modify** `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift` — `orderedSessions`, counts, `setMute`/`interrupt`/`markRead`; call `markRead` on open.
- **Modify** `apps/iosApp/project.yml` — add `WatchSessionStatus.swift` to the `Supermux` app target sources (the watch target globs the whole `SupermuxWatch/` dir, so the other new files need no entry).

**Why route-level, not `getSessionsSnapshot()`:** the snapshot is shared by several callers; the extra fields (esp. a per-session message preview) are watch-only. The `GET /sessions` route already has `getSessionAgentState`, `getSessionLog`, `getReads`, and `markRead` on `this.opts` (`src/channels/web/index.ts:111–185`), so enriching there is contained and needs no shared-type edit. This supersedes the spec's "modify `getSessionsSnapshot()`" line.

---

## Task 1: Broker — `watchRowExtras` pure helper (TDD)

**Files:**
- Create: `src/channels/web/watch-session-row.ts`
- Test: `src/channels/web/watch-session-row.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/channels/web/watch-session-row.test.ts
import { test, expect } from "bun:test"
import { watchRowExtras } from "./watch-session-row"

test("phase and tool pass through", () => {
  const e = watchRowExtras({ phase: "running", tool: "Bash" }, undefined, undefined)
  expect(e.phase).toBe("running")
  expect(e.tool).toBe("Bash")
})

test("unread: true when there's a last message and no read pointer", () => {
  const e = watchRowExtras(undefined, { ts: "2026-06-27T04:00:00Z", direction: "outbound", text: "hi" }, undefined)
  expect(e.unread).toBe(true)
})

test("unread: false when read pointer is at/after the last message", () => {
  const e = watchRowExtras(undefined, { ts: "2026-06-27T04:00:00Z", direction: "outbound", text: "hi" }, "2026-06-27T04:00:00Z")
  expect(e.unread).toBe(false)
})

test("unread: true when last message is newer than the read pointer", () => {
  const e = watchRowExtras(undefined, { ts: "2026-06-27T05:00:00Z", direction: "outbound", text: "hi" }, "2026-06-27T04:00:00Z")
  expect(e.unread).toBe(true)
})

test("lastFrom derives from the direction prefix", () => {
  expect(watchRowExtras(undefined, { ts: "t", direction: "inbound" }, undefined).lastFrom).toBe("in")
  expect(watchRowExtras(undefined, { ts: "t", direction: "outbound" }, undefined).lastFrom).toBe("out")
})

test("lastText truncates to the preview cap with an ellipsis", () => {
  const e = watchRowExtras(undefined, { ts: "t", direction: "outbound", text: "x".repeat(200) }, undefined)
  expect(e.lastText!.length).toBeLessThanOrEqual(120)
  expect(e.lastText!.endsWith("…")).toBe(true)
})

test("no last message → undefined preview and not unread", () => {
  const e = watchRowExtras({ phase: "idle" }, undefined, undefined)
  expect(e.lastText).toBeUndefined()
  expect(e.unread).toBe(false)
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `bun test src/channels/web/watch-session-row.test.ts`
Expected: FAIL — `Cannot find module './watch-session-row'`.

- [ ] **Step 3: Write the minimal implementation**

```ts
// src/channels/web/watch-session-row.ts
// Watch-only enrichment for GET /sessions: the signals the phone gets over WebSocket
// (agent phase, last-message preview, unread) that the lean REST snapshot omits.

export interface WatchRowExtras {
  phase?: string
  tool?: string
  lastText?: string
  lastTs?: string
  lastFrom?: "in" | "out"
  unread: boolean
}

const PREVIEW_MAX = 120

/** Derive the watch's extra row fields from the agent state, the session's last log
 *  entry, and its server-side read pointer. Pure; unit-tested. `unread` uses the same
 *  string-timestamp comparison as the web unread store (src/web-app/src/stores/unread.ts). */
export function watchRowExtras(
  state: { phase?: string; tool?: string } | undefined,
  last: { ts?: string; direction?: string; text?: string } | undefined,
  readTs: string | undefined,
): WatchRowExtras {
  const lastTs = last?.ts
  const text = last?.text
  return {
    phase: state?.phase,
    tool: state?.tool,
    lastText: text
      ? (text.length > PREVIEW_MAX ? text.slice(0, PREVIEW_MAX - 1) + "…" : text)
      : undefined,
    lastTs,
    lastFrom: last?.direction ? (last.direction.startsWith("in") ? "in" : "out") : undefined,
    unread: !!(lastTs && (!readTs || lastTs > readTs)),
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `bun test src/channels/web/watch-session-row.test.ts`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/watch-session-row.ts src/channels/web/watch-session-row.test.ts
git commit -m "feat(watch): pure watchRowExtras helper for enriched /sessions"
```

---

## Task 2: Broker — enrich the `GET /sessions` route

**Files:**
- Modify: `src/channels/web/index.ts` (the `GET /sessions` handler at ~`:1588`)

- [ ] **Step 1: Import the helper**

At the top of `src/channels/web/index.ts`, add to the existing import block:

```ts
import { watchRowExtras } from "./watch-session-row"
```

- [ ] **Step 2: Replace the `GET /sessions` handler body**

Find:

```ts
    if (method === "GET" && path === "/sessions") {
      return this.json(this.opts.getSessionsSnapshot())
    }
```

Replace with:

```ts
    if (method === "GET" && path === "/sessions") {
      // Watch-only enrichment: fold in agent phase, a last-message preview, and unread —
      // the signals the watch can't get over WebSocket. Reuses opts the route already has.
      const reads = this.opts.getReads?.() ?? {}
      const enriched = this.opts.getSessionsSnapshot().map((s) => {
        const key = s.id ?? s.name
        const log = this.opts.getSessionLog(key)
        const extras = watchRowExtras(
          this.opts.getSessionAgentState?.(key) as { phase?: string; tool?: string } | undefined,
          log[log.length - 1],
          reads[key],
        )
        return { ...s, ...extras }
      })
      return this.json(enriched)
    }
```

- [ ] **Step 3: Typecheck**

Run: `bun run -b tsc --noEmit -p tsconfig.json` (or the repo's typecheck script — check `package.json`; e.g. `bun run typecheck`).
Expected: no new type errors from this file. If `getSessionLog`'s entry type lacks `text`/`direction`/`ts`, the helper's structural param still accepts it (all optional); no cast needed beyond the `getSessionAgentState` one shown.

- [ ] **Step 4: Sanity-check the shape (existing web `/sessions` consumers unaffected)**

Run: `bun test` (full broker suite).
Expected: PASS — the added fields are extra keys; no existing test asserts the snapshot is exact-equal. If one does, update it to allow the extra optional keys.

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/index.ts
git commit -m "feat(watch): enrich GET /sessions with phase, preview, unread"
```

---

## Task 3: Broker — `POST /sessions/{id}/read`

**Files:**
- Modify: `src/channels/web/index.ts` (add beside the `/mute` handler at ~`:1595`)

- [ ] **Step 1: Add the route**

Immediately after the `POST /sessions/{id}/mute` handler block, add:

```ts
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/read$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      this.opts.markRead?.(id)   // advances last_read_at + broadcasts session_read (main.ts:1121)
      return this.json({ ok: true })
    }
```

- [ ] **Step 2: Typecheck + test**

Run: `bun run typecheck && bun test`
Expected: PASS. (`markRead?` is already declared on `WebChannelOpts` at `:124` and wired in `main.ts:1121` — no further wiring needed.)

- [ ] **Step 3: Commit**

```bash
git add src/channels/web/index.ts
git commit -m "feat(watch): POST /sessions/:id/read to clear unread"
```

---

## Task 4: Watch — pure status/sort logic (`WatchSessionStatus.swift`)

**Files:**
- Create: `apps/iosApp/SupermuxWatch/Watch/WatchSessionStatus.swift`
- Modify: `apps/iosApp/project.yml` (add iOS-app target membership)

- [ ] **Step 1: Create the pure-logic file**

```swift
// apps/iosApp/SupermuxWatch/Watch/WatchSessionStatus.swift
import Foundation

// Pure-Swift mirror of the shared `GitBadge.kt` session-status rule + the agent
// working-phase set. The watch can't link the KMP Shared.framework (arm64_32), so it
// re-states the tiny rule here. KEEP IN SYNC with
// apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt
// (pinned by WatchSessionStatusTests here and :shared:jvmTest there).
// Deliberately depends on NO watch-only type (no SessionInfo) so it can also compile
// into the iOS app target for unit testing.

struct GitLite: Codable, Equatable {
    var mode: String          // "base" (worktree) | "remote"
    var ahead: Int
    var behind: Int
    var dirty: Int
    var touched: Bool?
    var unpublished: Bool?
}

enum WatchStatusKind { case worktree, remote }
enum WatchStatusLevel { case pristine, done, notDone }

/// Unified per-session git status for the list indicator; nil when none applies (git == nil).
func sessionStatus(_ git: GitLite?) -> (kind: WatchStatusKind, level: WatchStatusLevel)? {
    guard let git else { return nil }
    if git.mode == "base" {
        let level: WatchStatusLevel
        if git.ahead > 0 || git.dirty > 0 { level = .notDone }
        else if git.touched == true { level = .done }
        else { level = .pristine }
        return (.worktree, level)
    } else {
        let synced = git.ahead == 0 && git.behind == 0 && git.dirty == 0 && git.unpublished != true
        return (.remote, synced ? .done : .notDone)
    }
}

/// Agent phases that mean "actively working" — verbatim from the iPhone's SessionRow.
private let workingPhases: Set<String> = ["working", "thinking", "running", "tool", "busy", "sending"]

func isWorking(_ phase: String?) -> Bool {
    guard let phase else { return false }
    return workingPhases.contains(phase)
}

/// Flat-list triage bucket: 0 = needs you (finished + unseen), 1 = working, 2 = the rest.
/// Lower sorts first. A working session stays in the working bucket even if unread.
func attentionBucket(phase: String?, unread: Bool) -> Int {
    if !isWorking(phase) && unread { return 0 }
    if isWorking(phase) { return 1 }
    return 2
}

/// Parse a message timestamp (epoch-ms or ISO-8601) to a comparable Double; 0 when absent.
/// Mirrors the iPhone's ChatActivity.tsMs so watch + phone order identically.
func tsValue(_ s: String?) -> Double {
    guard let s, !s.isEmpty else { return 0 }
    if let d = Double(s) { return d > 1_000_000_000_000 ? d : d * 1000 }
    let iso = ISO8601DateFormatter(); iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = iso.date(from: s) ?? ISO8601DateFormatter().date(from: s) {
        return date.timeIntervalSince1970 * 1000
    }
    return 0
}
```

- [ ] **Step 2: Give it iOS-app target membership**

In `apps/iosApp/project.yml`, under the `Supermux` app target `sources:` (where `SupermuxWatch/Watch/RelayEnvelope.swift` and `BrokerTransport.swift` are already listed for exactly this reason, ~`:58`), add:

```yaml
      - path: SupermuxWatch/Watch/WatchSessionStatus.swift
```

(The watch target's `sources: - path: SupermuxWatch` glob already includes the new file for the watch build — no entry needed there.)

- [ ] **Step 3: Regenerate + build on the remote Mac**

Run (on the Mac, after syncing the tree): `xcodegen generate` then the recipe's app build.
Expected: compiles; `GitLite`/`sessionStatus`/`isWorking`/`attentionBucket`/`tsValue` are visible to both the watch and `Supermux`/`SupermuxTests`.

- [ ] **Step 4: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/WatchSessionStatus.swift apps/iosApp/project.yml
git commit -m "feat(watch): pure session-status + attention-sort logic (KMP-free)"
```

---

## Task 5: Watch — unit tests for the pure logic (TDD-after, runs on Mac)

**Files:**
- Create: `apps/iosApp/SupermuxTests/WatchSessionStatusTests.swift`

- [ ] **Step 1: Write the tests** (mirrors the existing `RoutingTransportTests.swift` style: `import XCTest` + `@testable import Supermux`)

```swift
// apps/iosApp/SupermuxTests/WatchSessionStatusTests.swift
import XCTest
@testable import Supermux

final class WatchSessionStatusTests: XCTestCase {
    func testWorktreeDoneNotDonePristine() {
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 0, behind: 0, dirty: 0, touched: true,  unpublished: nil))?.level, .done)
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 2, behind: 0, dirty: 0, touched: true,  unpublished: nil))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 0, behind: 0, dirty: 3, touched: true,  unpublished: nil))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 0, behind: 0, dirty: 0, touched: false, unpublished: nil))?.level, .pristine)
    }
    func testRemoteSyncedVsNot() {
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 0, behind: 0, dirty: 0, touched: nil, unpublished: false))?.level, .done)
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 0, behind: 0, dirty: 0, touched: nil, unpublished: true ))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 1, behind: 0, dirty: 0, touched: nil, unpublished: false))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 0, behind: 2, dirty: 0, touched: nil, unpublished: false))?.level, .notDone)
    }
    func testNilGit() { XCTAssertNil(sessionStatus(nil)) }
    func testWorkingSet() {
        for p in ["working", "thinking", "running", "tool", "busy", "sending"] { XCTAssertTrue(isWorking(p)) }
        for p in ["idle", "stalled"] { XCTAssertFalse(isWorking(p)) }
        XCTAssertFalse(isWorking(nil))
    }
    func testAttentionBucket() {
        XCTAssertEqual(attentionBucket(phase: "idle",    unread: true),  0)  // needs you
        XCTAssertEqual(attentionBucket(phase: "running", unread: true),  1)  // working wins over unread
        XCTAssertEqual(attentionBucket(phase: "running", unread: false), 1)
        XCTAssertEqual(attentionBucket(phase: "idle",    unread: false), 2)
    }
    func testTsValueOrdersNewerHigher() {
        XCTAssertGreaterThan(tsValue("2026-06-27T05:00:00Z"), tsValue("2026-06-27T04:00:00Z"))
        XCTAssertGreaterThan(tsValue("1782535713328"), 0)
        XCTAssertEqual(tsValue(nil), 0)
    }
}
```

- [ ] **Step 2: Run the tests on the remote Mac**

Run (Mac, exact scheme/destination from the Prerequisite recipe), e.g.:
`xcodebuild test -scheme Supermux -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:SupermuxTests/WatchSessionStatusTests`
Expected: all pass. (If `GitLite`'s memberwise init isn't synthesized because Codable added one — it won't; no custom init is declared — this compiles as-is.)

- [ ] **Step 3: Commit**

```bash
git add apps/iosApp/SupermuxTests/WatchSessionStatusTests.swift
git commit -m "test(watch): pin sessionStatus/attentionBucket to the shared rule"
```

---

## Task 6: Watch — decode the new fields on `SessionInfo`

**Files:**
- Modify: `apps/iosApp/SupermuxWatch/Watch/WatchModels.swift`

- [ ] **Step 1: Replace the `SessionInfo` struct**

Replace the existing `SessionInfo` (lines 7–24) with:

```swift
struct SessionInfo: Decodable, Identifiable {
    let id: String
    let name: String
    var agent: String?
    var status: String?
    var connected: Bool?
    var mute: Bool?
    // Watch session-list enrichment (GET /sessions; see watch-session-row.ts):
    var phase: String?
    var tool: String?
    var lastText: String?
    var lastTs: String?
    var lastFrom: String?
    var unread: Bool?
    var git: GitLite?

    enum CodingKeys: String, CodingKey {
        case id, name, agent, status, connected, mute, phase, tool, lastText, lastTs, lastFrom, unread, git
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.name = try c.decode(String.self, forKey: .name)
        self.id = (try? c.decode(String.self, forKey: .id)) ?? name
        self.agent = try? c.decode(String.self, forKey: .agent)
        self.status = try? c.decode(String.self, forKey: .status)
        self.connected = try? c.decode(Bool.self, forKey: .connected)
        self.mute = try? c.decode(Bool.self, forKey: .mute)
        self.phase = try? c.decode(String.self, forKey: .phase)
        self.tool = try? c.decode(String.self, forKey: .tool)
        self.lastText = try? c.decode(String.self, forKey: .lastText)
        self.lastTs = try? c.decode(String.self, forKey: .lastTs)
        self.lastFrom = try? c.decode(String.self, forKey: .lastFrom)
        self.unread = try? c.decode(Bool.self, forKey: .unread)
        self.git = try? c.decode(GitLite.self, forKey: .git)
    }
}
```

(`GitLite` lives in `WatchSessionStatus.swift`, same watch target — visible here.)

- [ ] **Step 2: Build on the Mac**

Run: the recipe's watch build.
Expected: compiles.

- [ ] **Step 3: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/WatchModels.swift
git commit -m "feat(watch): decode phase/preview/unread/git on SessionInfo"
```

---

## Task 7: Watch — the parity row view (`WatchSessionRow.swift`)

**Files:**
- Create: `apps/iosApp/SupermuxWatch/Watch/WatchSessionRow.swift`

- [ ] **Step 1: Create the row**

```swift
// apps/iosApp/SupermuxWatch/Watch/WatchSessionRow.swift
import SwiftUI

/// One session row on the watch: the unified status indicator (working spinner › git glyph
/// › neutral) + name + last-message preview, mirroring the iPhone's SessionRow/SessionStatusRail.
struct WatchSessionRow: View {
    let session: SessionInfo

    private var working: Bool { isWorking(session.phase) }
    private var unread: Bool { session.unread ?? false }
    private var preview: String { session.lastText ?? session.agent ?? "" }

    var body: some View {
        HStack(spacing: 8) {
            statusRail.frame(width: 14, alignment: .center)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    if unread { Circle().fill(Color.accentColor).frame(width: 6, height: 6) }
                    Text(session.name)
                        .font(.headline).fontWeight(unread ? .bold : .semibold).lineLimit(1)
                    if session.mute ?? false {
                        Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary)
                    }
                    Spacer(minLength: 0)
                }
                if !preview.isEmpty {
                    Text(preview).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder private var statusRail: some View {
        if working {
            ProgressView().controlSize(.mini)
        } else if let st = sessionStatus(session.git) {
            switch (st.kind, st.level) {
            case (.worktree, .done):     glyph("checkmark", .green)
            case (.worktree, .notDone):  glyph("arrow.triangle.branch", .orange)
            case (.worktree, .pristine): neutralDot
            case (.remote, .done):       glyph("checkmark.icloud", .green)
            case (.remote, .notDone):    glyph("icloud", .orange)
            }
        } else {
            neutralDot
        }
    }
    private func glyph(_ name: String, _ color: Color) -> some View {
        Image(systemName: name).font(.caption2.weight(.semibold)).foregroundStyle(color)
    }
    private var neutralDot: some View {
        Circle().fill(Color.secondary.opacity(0.3)).frame(width: 6, height: 6)
    }
}
```

- [ ] **Step 2: Build on the Mac**

Run: the recipe's watch build. Expected: compiles (the file is auto-included via the `SupermuxWatch/` source glob).

- [ ] **Step 3: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/WatchSessionRow.swift
git commit -m "feat(watch): phone-parity session row with unified status rail"
```

---

## Task 8: Watch — broker-session sort, counts, and actions

**Files:**
- Modify: `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift`

- [ ] **Step 1: Replace `orderedSessions` and add counts + actions**

Find:

```swift
    /// Active sessions in the broker's order.
    var orderedSessions: [SessionInfo] { sessions }
```

Replace with:

```swift
    /// Flat triage order: needs-you (finished+unseen) → working → rest; recency within each.
    var orderedSessions: [SessionInfo] {
        sessions.sorted { a, b in
            let ba = attentionBucket(phase: a.phase, unread: a.unread ?? false)
            let bb = attentionBucket(phase: b.phase, unread: b.unread ?? false)
            if ba != bb { return ba < bb }
            return tsValue(a.lastTs) > tsValue(b.lastTs)
        }
    }

    /// Glance-header counts.
    var needsYouCount: Int { sessions.filter { !isWorking($0.phase) && ($0.unread ?? false) }.count }
    var workingCount: Int { sessions.filter { isWorking($0.phase) }.count }

    /// Mute/unmute a session (POST /sessions/{id}/mute).
    func setMute(_ id: String, _ muted: Bool) {
        Task { [transport] in
            let body = try? JSONSerialization.data(withJSONObject: ["muted": muted])
            _ = try? await transport.request(method: "POST", path: "/sessions/\(id)/mute",
                                             body: body, contentType: "application/json")
        }
    }

    /// Interrupt a running agent (POST /sessions/{id}/interrupt).
    func interrupt(_ id: String) {
        Task { [transport] in
            _ = try? await transport.request(method: "POST", path: "/sessions/\(id)/interrupt",
                                             body: nil, contentType: nil)
        }
    }

    /// Clear unread on the server (POST /sessions/{id}/read).
    func markRead(_ id: String) {
        Task { [transport] in
            _ = try? await transport.request(method: "POST", path: "/sessions/\(id)/read",
                                             body: nil, contentType: nil)
        }
    }
```

- [ ] **Step 2: Call `markRead` when a session opens**

In `openSession(_ id:)`, after `activeSession = id`, add `markRead(id)`:

```swift
    func openSession(_ id: String) {
        activeSession = id
        markRead(id)
        Task {
            if let log = try? await get("/sessions/\(id)/messages", [LogEntry].self) {
                messages[id] = merge(server: log, sessionId: id)
            }
        }
    }
```

(The swipe "Continue" action reuses the existing `send(_:_:)` — no new method.)

- [ ] **Step 3: Build on the Mac**

Run: the recipe's watch build. Expected: compiles.

- [ ] **Step 4: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift
git commit -m "feat(watch): attention sort, glance counts, mute/interrupt/markRead"
```

---

## Task 9: Watch — rewire the list view (row, header, swipe)

**Files:**
- Modify: `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift`

- [ ] **Step 1: Replace the populated-list branch**

Find:

```swift
            } else {
                List(broker.orderedSessions, id: \.id) { session in
                    NavigationLink(value: session.id) { SessionRow(session: session) }
                }
            }
```

Replace with:

```swift
            } else {
                List {
                    if broker.needsYouCount + broker.workingCount > 0 {
                        Text(glanceText)
                            .font(.caption2).foregroundStyle(.secondary)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(broker.orderedSessions, id: \.id) { session in
                        NavigationLink(value: session.id) { WatchSessionRow(session: session) }
                            .swipeActions(edge: .trailing) {
                                Button { broker.send(session.id, "continue") } label: {
                                    Label("Continue", systemImage: "arrowshape.right.fill")
                                }.tint(.green)
                                Button { broker.interrupt(session.id) } label: {
                                    Label("Stop", systemImage: "stop.fill")
                                }.tint(.orange)
                                Button { broker.setMute(session.id, !(session.mute ?? false)) } label: {
                                    Label((session.mute ?? false) ? "Unmute" : "Mute",
                                          systemImage: (session.mute ?? false) ? "bell.slash" : "bell")
                                }.tint(.gray)
                            }
                    }
                }
            }
```

- [ ] **Step 2: Add the `glanceText` helper + delete the obsolete `SessionRow`**

Add this computed property inside `SessionsListView` (e.g. after `body`):

```swift
    /// "2 need you · 1 working", omitting zero parts.
    private var glanceText: String {
        var parts: [String] = []
        if broker.needsYouCount > 0 { parts.append("\(broker.needsYouCount) need you") }
        if broker.workingCount > 0 { parts.append("\(broker.workingCount) working") }
        return parts.joined(separator: " · ")
    }
```

Then delete the now-unused `private struct SessionRow { … }` at the bottom of the file (replaced by `WatchSessionRow`).

- [ ] **Step 3: Build on the Mac**

Run: the recipe's watch build. Expected: compiles; no reference to the deleted `SessionRow` remains.

- [ ] **Step 4: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift
git commit -m "feat(watch): triage list — ordered rows, glance header, swipe actions"
```

---

## Task 10: Verify end-to-end

- [ ] **Step 1: Broker suite green**

Run: `bun test`
Expected: PASS, including `watch-session-row.test.ts`.

- [ ] **Step 2: Swift unit tests green (Mac)**

Run: `xcodebuild test … -only-testing:SupermuxTests/WatchSessionStatusTests`
Expected: PASS.

- [ ] **Step 3: watchOS Simulator screenshots (Mac)**

Build + run the watch scheme in the watchOS Simulator against the dev broker. Capture the list with a mix of states. Verify:
- a working session shows the spinner and sits in the **working** band;
- an idle session with a fresh agent reply shows the **bold name + dot** and sits at the **top** (needs-you);
- the **glance header** reads e.g. "1 need you · 1 working";
- each row shows the **last-message preview**;
- muted shows the bell-slash; git states show ✓ / ⎇ / ☁︎ as applicable.

- [ ] **Step 4: On-device pass (source of truth)**

OTA/dev-install to the physical Apple Watch (bump build). Confirm against the live broker: order updates as agents start/stop; opening a session **clears** its unread (it leaves the needs-you band); swipe → **Continue** nudges the agent; **Stop** interrupts; **Mute** toggles. Confirm the phone-relay path (`via iPhone`) still serves the enriched payload.

- [ ] **Step 5: Final commit (version bump if the recipe requires one for install)**

```bash
git add -A
git commit -m "chore(watch): bump build for session-list triage"
```

---

## Self-Review (completed during authoring)

**Spec coverage:** enrich snapshot → Tasks 1–2; mark-read route → Task 3; pure git rule (KMP-free) + working set → Task 4–5; decode fields → Task 6; parity row (status rail, preview, unread bold+dot, drops connected dot) → Task 7; flat attention sort + counts → Task 8; glance header + swipe (Continue/Mute/Interrupt) → Task 9; tests + simulator + device → Tasks 5,10. Non-goals (complication, permission-approvals, grouping) intentionally absent. **Scope-cut lever** (drop the git glyph for a leaner v1): omit Tasks 4–5's `sessionStatus`/`GitLite` and the `statusRail`'s git branch in Task 7 — the triage core (phase + unread + recency) is unaffected.

**Placeholder scan:** none — every code/test step is complete; the only deferral is the remote-Mac recipe (its own Prerequisite task), consistent with the spec.

**Type consistency:** `watchRowExtras(state,last,readTs)` (Task 1) is called with exactly those args (Task 2); `WatchRowExtras` keys = the `SessionInfo` fields decoded in Task 6 = the broker's `{...s, ...extras}` output (Task 2). Swift `sessionStatus`/`isWorking`/`attentionBucket`/`tsValue` signatures (Task 4) match every call site (Tasks 5,7,8). `setMute(_:_:)`/`interrupt(_:)`/`markRead(_:)`/`send(_:_:)` names match between Task 8 (definitions) and Task 9 (calls).
