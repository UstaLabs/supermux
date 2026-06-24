# Watch Phone-Relay Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Apple Watch app reach the broker through the paired iPhone (phone-first, via `WCSession`) when it can't reach the broker directly — fixing Tailscale-hosted brokers (no watchOS Tailscale client) and any no-Wi‑Fi case — while keeping the existing direct REST client as a fallback.

**Architecture:** Introduce a `BrokerTransport` abstraction under `WatchBrokerSession`. A `RoutingTransport` picks the path per request: if `WCSession.isReachable`, relay through the phone (`PhoneRelayTransport` → `WCSession.sendMessage`); otherwise use the watch's own `DirectTransport` (today's REST). On the iPhone, `PhoneWatchProvisioner` answers relay messages via `BrokerRelay`, which runs the request against the broker with the phone's stored creds and downscales photos to fit the low-bandwidth link. A small `RelayEnvelope` defines the wire format, shared across the watch app, the iOS app, and the test bundle.

**Tech Stack:** Swift / SwiftUI, watchOS 10 + iOS 26, WatchConnectivity, URLSession, XcodeGen (`apps/iosApp/project.yml`), XCTest (`SupermuxTests`). Build + test run on the remote Mac (`ssh mac`).

---

## Spec

Source spec: `docs/superpowers/specs/2026-06-24-watch-phone-relay-fallback-design.md`.

## Build & test environment (read first)

iOS/watchOS **cannot** be compiled on this Linux host. All builds/tests run on the remote
Mac over SSH. Every remote command must `source ~/ios-build-env.sh` first.

**Sync this worktree to the Mac** (tar-over-ssh; macOS rsync is broken):
```bash
cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/49bcbaf3-f25d-4ecb-9c87-fac180fb43fb
tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules --exclude 'apps/iosApp/build' -czf - . \
  | ssh mac 'rm -rf ~/supermux && mkdir -p ~/supermux && tar -xzf - -C ~/supermux'
```

**Regenerate the Xcode project** (the `.xcodeproj` is gitignored):
```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux/apps/iosApp && xcodegen generate'
```

**Run the unit tests** (`SupermuxTests`, iOS simulator):
```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux/apps/iosApp && \
  xcodebuild test -scheme Supermux \
    -destination "platform=iOS Simulator,name=iPhone 16" \
    -only-testing:SupermuxTests'
```

**Build the watch app** (compile gate for watch-only code):
```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux/apps/iosApp && \
  xcodebuild build -scheme SupermuxWatch -sdk watchsimulator26.5 \
    -destination "platform=watchOS Simulator,name=Apple Watch Series 11 (46mm)" \
    ARCHS=arm64 CODE_SIGNING_ALLOWED=NO'
```

**Test-execution note:** Because each remote build takes minutes, tasks are written
**test-first** (real assertions, then implementation), but the suite is actually *run* at the
**Task 7 checkpoint** (and re-run after each fix), not per micro-step. Treat Task 7's
red→green loop as the TDD gate. Do not mark Task 7 complete until tests pass on the Mac.

## File structure (decomposition)

**New files**
- `apps/iosApp/SupermuxWatch/Watch/RelayEnvelope.swift` — pure wire-format constants +
  encode/decode helpers + path validation. **Compiled into `SupermuxWatch` AND `Supermux`**
  (used by both the watch transports and the phone handler). Reachable from `SupermuxTests`
  via `@testable import Supermux`.
- `apps/iosApp/SupermuxWatch/Watch/BrokerTransport.swift` — `BrokerTransport` protocol,
  `DirectTransport`, `PhoneRelayTransport`, `RoutingTransport`, `RelayError`, `BrokerRoute`,
  `RelayReachability` + `WCReachability`. **Compiled into `SupermuxWatch` AND `Supermux`**
  (so the failover logic is reachable from `SupermuxTests` via `@testable import Supermux`;
  it is unused-but-harmless in the app, mirroring the existing PushNSE source-sharing).
- `apps/iosApp/Supermux/Watch/BrokerRelay.swift` — phone-side handler (fetch + photo
  shrink + skip). In the `Supermux` target (auto-globbed under `Supermux/`).
- `apps/iosApp/SupermuxTests/RelayEnvelopeTests.swift`
- `apps/iosApp/SupermuxTests/RoutingTransportTests.swift`
- `apps/iosApp/SupermuxTests/BrokerRelayTests.swift`

**Modified files**
- `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift` — route all calls through an
  injected `BrokerTransport`; add `route` for the indicator.
- `apps/iosApp/Supermux/Watch/PhoneWatchProvisioner.swift` — add
  `didReceiveMessage:replyHandler:`.
- `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift` — subtle route indicator.
- `apps/iosApp/project.yml` — add `RelayEnvelope.swift` + `BrokerTransport.swift` to the
  `Supermux` target's `sources`.

**Why this membership avoids duplicate symbols:** `SupermuxTests` *links* `Supermux` and uses
`@testable import Supermux`, so the shared files must live in `Supermux` (NOT also added to
the test target — that would double-compile them). `SupermuxWatch` is a separate product that
doesn't link `Supermux`, so compiling the same files into it is fine (same pattern the
`SupermuxPushNSE` extension uses for `PushCrypto.swift`).

## Execution order (dependencies)

```
Task 1 (RelayEnvelope)
  ├─ Task 2 (BrokerTransport)  ──► Task 5 (WatchBrokerSession) ──► Task 6 (indicator)
  └─ Task 3 (BrokerRelay)      ──► Task 4 (PhoneWatchProvisioner)
Task 7 (Mac verify) — after all of 1–6
```
Task 2 and Task 3 can run in parallel after Task 1. Task 4 and Task 5 can run in parallel
after their respective predecessors.

---

### Task 1: RelayEnvelope (shared wire format)

**Files:**
- Create: `apps/iosApp/SupermuxWatch/Watch/RelayEnvelope.swift`
- Modify: `apps/iosApp/project.yml` (add the file to the `Supermux` target)
- Test: `apps/iosApp/SupermuxTests/RelayEnvelopeTests.swift`

- [ ] **Step 1: Write the failing test**

Create `apps/iosApp/SupermuxTests/RelayEnvelopeTests.swift`:

```swift
import XCTest
@testable import Supermux

/// Unit tests for `RelayEnvelope` — the WCSession wire format for relaying a broker REST
/// request from the watch through the paired iPhone.
final class RelayEnvelopeTests: XCTestCase {
    func testEncodeDecodeRequestRoundTrip() {
        let body = Data("{\"text\":\"hi\"}".utf8)
        let msg = RelayEnvelope.encodeRequest(method: "POST", path: "/sessions/a/message",
                                              body: body, contentType: "application/json")
        let decoded = RelayEnvelope.decodeRequest(msg)
        XCTAssertEqual(decoded?.method, "POST")
        XCTAssertEqual(decoded?.path, "/sessions/a/message")
        XCTAssertEqual(decoded?.body, body)
        XCTAssertEqual(decoded?.contentType, "application/json")
    }

    func testEncodeRequestOmitsNilBodyAndContentType() {
        let msg = RelayEnvelope.encodeRequest(method: "GET", path: "/sessions",
                                              body: nil, contentType: nil)
        XCTAssertNil(msg[RelayEnvelope.kBody])
        XCTAssertNil(msg[RelayEnvelope.kContentType])
        let decoded = RelayEnvelope.decodeRequest(msg)
        XCTAssertEqual(decoded?.method, "GET")
        XCTAssertNil(decoded?.body)
    }

    func testDecodeRequestRejectsMissingFields() {
        XCTAssertNil(RelayEnvelope.decodeRequest([RelayEnvelope.kPath: "/sessions"])) // no method
        XCTAssertNil(RelayEnvelope.decodeRequest([RelayEnvelope.kMethod: "GET"]))     // no path
    }

    func testDecodeRequestRejectsNonRelativePaths() {
        func req(_ p: String) -> [String: Any] { [RelayEnvelope.kMethod: "GET", RelayEnvelope.kPath: p] }
        XCTAssertNil(RelayEnvelope.decodeRequest(req("https://evil.com/x"))) // absolute URL
        XCTAssertNil(RelayEnvelope.decodeRequest(req("//evil.com/x")))       // protocol-relative
        XCTAssertNil(RelayEnvelope.decodeRequest(req("sessions")))           // no leading slash
        XCTAssertNotNil(RelayEnvelope.decodeRequest(req("/sessions")))       // OK
    }

    func testReplyRoundTrip() {
        let body = Data([1, 2, 3])
        let reply = RelayEnvelope.encodeReply(status: 200, body: body)
        let decoded = RelayEnvelope.decodeReply(reply)
        XCTAssertEqual(decoded.status, 200)
        XCTAssertEqual(decoded.body, body)
    }

    func testFailureReplyDecodesToPhoneFailureStatus() {
        let reply = RelayEnvelope.encodeFailure("unpaired")
        let decoded = RelayEnvelope.decodeReply(reply)
        XCTAssertEqual(decoded.status, RelayEnvelope.phoneFailureStatus)
        XCTAssertEqual(reply[RelayEnvelope.kError] as? String, "unpaired")
    }

    func testDecodeReplyDefaultsToFailureWhenStatusMissing() {
        XCTAssertEqual(RelayEnvelope.decodeReply([:]).status, RelayEnvelope.phoneFailureStatus)
    }
}
```

- [ ] **Step 2: Create the implementation file**

Create `apps/iosApp/SupermuxWatch/Watch/RelayEnvelope.swift`:

```swift
import Foundation

/// Wire format for relaying a broker REST request from the watch to the paired iPhone over
/// WatchConnectivity. The watch packs a request into a `[String: Any]` `WCSession` message;
/// the phone runs it and replies with status + bytes. Uses property-list types only (String,
/// Int, Data) so values cross `WCSession` without base64.
///
/// Shared by the watch transports (`BrokerTransport.swift`) and the phone handler
/// (`BrokerRelay.swift`). Compiled into the watch app and the iOS app; reachable from the
/// iOS test bundle via `@testable import Supermux`.
enum RelayEnvelope {
    // Request keys (watch → phone)
    static let kMethod = "m"
    static let kPath = "p"
    static let kBody = "b"
    static let kContentType = "ct"

    // Reply keys (phone → watch)
    static let kStatus = "status"
    static let kReplyBody = "body"
    static let kError = "err"

    /// `status` sentinel: the phone could not complete the request (unpaired, broker
    /// unreachable from the phone, or an oversized file it chose to skip). The watch treats
    /// this as a transport failure and falls back to its own direct connection.
    static let phoneFailureStatus = 0

    /// Pack a request into a WCSession message dictionary.
    static func encodeRequest(method: String, path: String, body: Data?, contentType: String?) -> [String: Any] {
        var msg: [String: Any] = [kMethod: method, kPath: path]
        if let body { msg[kBody] = body }
        if let contentType { msg[kContentType] = contentType }
        return msg
    }

    /// Read a request dictionary on the phone. Returns nil if required fields are missing or
    /// the path is not broker-relative (must start with a single "/", no scheme/host) — so a
    /// malformed message can't redirect the bearer-authed request to another host.
    static func decodeRequest(_ msg: [String: Any]) -> (method: String, path: String, body: Data?, contentType: String?)? {
        guard let method = msg[kMethod] as? String,
              let path = msg[kPath] as? String,
              path.hasPrefix("/"), !path.hasPrefix("//"),
              !path.contains("://") else { return nil }
        return (method, path, msg[kBody] as? Data, msg[kContentType] as? String)
    }

    /// Pack a success reply on the phone.
    static func encodeReply(status: Int, body: Data) -> [String: Any] {
        [kStatus: status, kReplyBody: body]
    }

    /// Pack a phone-side failure reply.
    static func encodeFailure(_ reason: String) -> [String: Any] {
        [kStatus: phoneFailureStatus, kReplyBody: Data(), kError: reason]
    }

    /// Read a reply dictionary on the watch.
    static func decodeReply(_ reply: [String: Any]) -> (status: Int, body: Data) {
        let status = reply[kStatus] as? Int ?? phoneFailureStatus
        let body = reply[kReplyBody] as? Data ?? Data()
        return (status, body)
    }
}
```

- [ ] **Step 3: Add the file to the `Supermux` target in `project.yml`**

In `apps/iosApp/project.yml`, the `Supermux` target's `sources:` currently is:

```yaml
    sources:
      - path: Supermux
        excludes:
          - "EditorWeb/**"
      - path: Supermux/EditorWeb
        type: folder
        buildPhase: resources
```

Add the shared watch file so it compiles into the iOS app too (needed by `BrokerRelay` and
the tests):

```yaml
    sources:
      - path: Supermux
        excludes:
          - "EditorWeb/**"
      - path: Supermux/EditorWeb
        type: folder
        buildPhase: resources
      # Shared with the watch app: relay wire-format + transport logic, compiled into the
      # iOS app so BrokerRelay (phone side) and SupermuxTests can use them.
      - path: SupermuxWatch/Watch/RelayEnvelope.swift
```

(`SupermuxWatch` already globs the whole `SupermuxWatch/` directory, so the file is in the
watch target automatically.)

- [ ] **Step 4: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/RelayEnvelope.swift \
        apps/iosApp/SupermuxTests/RelayEnvelopeTests.swift \
        apps/iosApp/project.yml
git commit -m "feat(watch): relay envelope wire format for phone fallback

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: BrokerTransport (direct / relay / routing)

**Files:**
- Create: `apps/iosApp/SupermuxWatch/Watch/BrokerTransport.swift`
- Modify: `apps/iosApp/project.yml` (add the file to the `Supermux` target)
- Test: `apps/iosApp/SupermuxTests/RoutingTransportTests.swift`

Depends on Task 1 (`RelayEnvelope`).

- [ ] **Step 1: Write the failing test**

Create `apps/iosApp/SupermuxTests/RoutingTransportTests.swift`:

```swift
import XCTest
@testable import Supermux

/// Unit tests for `RoutingTransport`'s phone-first failover decision.
final class RoutingTransportTests: XCTestCase {

    /// Records calls + the reported route across actor hops (test-only).
    final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private var _calls: [String] = []
        private var _route: BrokerRoute?
        func add(_ s: String) { lock.lock(); _calls.append(s); lock.unlock() }
        func setRoute(_ r: BrokerRoute) { lock.lock(); _route = r; lock.unlock() }
        var calls: [String] { lock.lock(); defer { lock.unlock() }; return _calls }
        var route: BrokerRoute? { lock.lock(); defer { lock.unlock() }; return _route }
    }

    /// Configurable fake transport.
    struct FakeTransport: BrokerTransport {
        let name: String
        let recorder: Recorder
        var result: (Data, Int)? = (Data("ok".utf8), 200)
        var error: Error?
        func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
            recorder.add(name)
            if let error { throw error }
            return result!
        }
    }

    struct FixedReachability: RelayReachability { let isReachable: Bool }

    private func makeRouter(reachable: Bool, direct: FakeTransport, relay: FakeTransport,
                            recorder: Recorder) -> RoutingTransport {
        RoutingTransport(direct: direct, relay: relay,
                         reachability: FixedReachability(isReachable: reachable),
                         onRoute: { recorder.setRoute($0) })
    }

    func testPhoneReachableUsesRelayNotDirect() async throws {
        let rec = Recorder()
        let router = makeRouter(reachable: true,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: FakeTransport(name: "relay", recorder: rec),
                                recorder: rec)
        _ = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(rec.calls, ["relay"])
        XCTAssertEqual(rec.route, .phone)
    }

    func testRelayTransportFailureFallsBackToDirect() async throws {
        let rec = Recorder()
        let relay = FakeTransport(name: "relay", recorder: rec, error: RelayError(reason: "unreachable"))
        let router = makeRouter(reachable: true,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: relay, recorder: rec)
        let (_, status) = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(rec.calls, ["relay", "direct"])
        XCTAssertEqual(rec.route, .direct)
        XCTAssertEqual(status, 200)
    }

    func testBrokerErrorStatusIsReturnedWithoutFallback() async throws {
        let rec = Recorder()
        let relay = FakeTransport(name: "relay", recorder: rec, result: (Data(), 500))
        let router = makeRouter(reachable: true,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: relay, recorder: rec)
        let (_, status) = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(status, 500)
        XCTAssertEqual(rec.calls, ["relay"])   // NO fallback on a real broker error
        XCTAssertEqual(rec.route, .phone)
    }

    func testPhoneUnreachableUsesDirectDirectly() async throws {
        let rec = Recorder()
        let router = makeRouter(reachable: false,
                                direct: FakeTransport(name: "direct", recorder: rec),
                                relay: FakeTransport(name: "relay", recorder: rec),
                                recorder: rec)
        _ = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
        XCTAssertEqual(rec.calls, ["direct"])  // relay never attempted
        XCTAssertEqual(rec.route, .direct)
    }

    func testBothFailReportsOfflineAndThrows() async {
        let rec = Recorder()
        let relay = FakeTransport(name: "relay", recorder: rec, error: RelayError(reason: "x"))
        let direct = FakeTransport(name: "direct", recorder: rec, error: URLError(.notConnectedToInternet))
        let router = makeRouter(reachable: true, direct: direct, relay: relay, recorder: rec)
        do {
            _ = try await router.request(method: "GET", path: "/sessions", body: nil, contentType: nil)
            XCTFail("expected throw")
        } catch {
            XCTAssertEqual(rec.calls, ["relay", "direct"])
            XCTAssertEqual(rec.route, .offline)
        }
    }
}
```

- [ ] **Step 2: Create the implementation file**

Create `apps/iosApp/SupermuxWatch/Watch/BrokerTransport.swift`:

```swift
import Foundation
import WatchConnectivity

/// Which path served the watch's broker traffic — surfaced for the UI indicator.
enum BrokerRoute { case direct, phone, offline }

/// Thrown by a relay transport when the request could not be DELIVERED, or the phone could
/// not complete it. NOT thrown for a normal non-2xx broker response (that is returned as
/// `(body, status)`). `RoutingTransport` catches this to fall back to the direct connection.
struct RelayError: Error { let reason: String }

/// Abstracts "make a broker REST request" so `WatchBrokerSession` doesn't care whether the
/// bytes go directly over the watch's own network or get relayed through the paired iPhone.
/// Returns `(responseBody, httpStatus)`; throws ONLY on transport failure.
protocol BrokerTransport {
    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int)
}

/// Reports whether the paired iPhone is reachable right now. Injectable so `RoutingTransport`
/// is testable without `WCSession`.
protocol RelayReachability {
    var isReachable: Bool { get }
}

/// Live reachability backed by `WCSession`.
struct WCReachability: RelayReachability {
    var isReachable: Bool { WCSession.isSupported() && WCSession.default.isReachable }
}

/// Direct REST to the broker over the watch's own network (today's behavior, lifted from
/// `WatchBrokerSession`).
struct DirectTransport: BrokerTransport {
    let baseURL: String
    let token: String

    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
        guard let url = URL(string: baseURL + path) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body
        let (data, resp) = try await URLSession.shared.data(for: req)
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }
}

/// Relays the request through the paired iPhone over `WCSession.sendMessage`. Throws
/// `RelayError` if the phone isn't reachable, doesn't answer, or reports a failure.
struct PhoneRelayTransport: BrokerTransport {
    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
        guard WCSession.isSupported() else { throw RelayError(reason: "WCSession unsupported") }
        let session = WCSession.default
        guard session.isReachable else { throw RelayError(reason: "phone unreachable") }
        let msg = RelayEnvelope.encodeRequest(method: method, path: path, body: body, contentType: contentType)
        let reply: [String: Any] = try await withCheckedThrowingContinuation { cont in
            session.sendMessage(msg,
                                replyHandler: { cont.resume(returning: $0) },
                                errorHandler: { cont.resume(throwing: RelayError(reason: $0.localizedDescription)) })
        }
        let (status, data) = RelayEnvelope.decodeReply(reply)
        if status == RelayEnvelope.phoneFailureStatus {
            throw RelayError(reason: (reply[RelayEnvelope.kError] as? String) ?? "phone-side failure")
        }
        return (data, status)
    }
}

/// Phone-first failover: relay through the iPhone when reachable; fall back to the watch's
/// own direct connection otherwise — or if the relay attempt fails as a transport error.
/// Reports the chosen route via `onRoute`. (A non-2xx broker response is a *success* here —
/// it's returned, not retried — so the route reflects which device reached the broker.)
struct RoutingTransport: BrokerTransport {
    let direct: BrokerTransport
    let relay: BrokerTransport
    let reachability: RelayReachability
    let onRoute: @Sendable (BrokerRoute) -> Void

    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
        if reachability.isReachable {
            do {
                let r = try await relay.request(method: method, path: path, body: body, contentType: contentType)
                onRoute(.phone)
                return r
            } catch is RelayError {
                // fall through to the direct backstop
            }
        }
        do {
            let r = try await direct.request(method: method, path: path, body: body, contentType: contentType)
            onRoute(.direct)
            return r
        } catch {
            onRoute(.offline)
            throw error
        }
    }
}
```

- [ ] **Step 3: Add the file to the `Supermux` target in `project.yml`**

Append to the `Supermux` target `sources:` (right after the `RelayEnvelope.swift` line added
in Task 1):

```yaml
      - path: SupermuxWatch/Watch/BrokerTransport.swift
```

- [ ] **Step 4: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/BrokerTransport.swift \
        apps/iosApp/SupermuxTests/RoutingTransportTests.swift \
        apps/iosApp/project.yml
git commit -m "feat(watch): phone-first BrokerTransport with direct fallback

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: BrokerRelay (phone-side handler)

**Files:**
- Create: `apps/iosApp/Supermux/Watch/BrokerRelay.swift`
- Test: `apps/iosApp/SupermuxTests/BrokerRelayTests.swift`

Depends on Task 1 (`RelayEnvelope`). Independent of Task 2.

- [ ] **Step 1: Write the failing test**

Create `apps/iosApp/SupermuxTests/BrokerRelayTests.swift`:

```swift
import XCTest
import UIKit
@testable import Supermux

/// Unit tests for the pure parts of `BrokerRelay` (the phone-side relay handler):
/// request validation, photo downscaling, and the skip/passthrough body policy.
final class BrokerRelayTests: XCTestCase {

    private func solidImage(_ size: CGSize) -> UIImage {
        UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
        }
    }

    func testHandleRejectsMalformedMessage() async {
        let reply = await BrokerRelay.handle([RelayEnvelope.kMethod: "GET"]) // no path
        XCTAssertEqual(RelayEnvelope.decodeReply(reply).status, RelayEnvelope.phoneFailureStatus)
        XCTAssertEqual(reply[RelayEnvelope.kError] as? String, "bad request")
    }

    func testDownscaleReducesLongestSideToMax() {
        let big = solidImage(CGSize(width: 2000, height: 1000))
        let small = BrokerRelay.downscale(big, maxDimension: 640)
        XCTAssertEqual(max(small.size.width, small.size.height), 640, accuracy: 1)
        XCTAssertEqual(min(small.size.width, small.size.height), 320, accuracy: 1)
    }

    func testDownscaleLeavesAlreadySmallImageUntouched() {
        let small = solidImage(CGSize(width: 100, height: 100))
        let out = BrokerRelay.downscale(small, maxDimension: 640)
        XCTAssertEqual(out.size, CGSize(width: 100, height: 100))
    }

    func testPrepareBodyPassesThroughSmallNonFileBody() {
        let json = Data("[]".utf8)
        XCTAssertEqual(BrokerRelay.prepareBody(path: "/sessions", status: 200, data: json), json)
    }

    func testPrepareBodySkipsOversizedNonImage() {
        let blob = Data(count: BrokerRelay.hardCapBytes + 1)
        XCTAssertNil(BrokerRelay.prepareBody(path: "/files/x", status: 200, data: blob))
    }

    func testPrepareBodyShrinksLargeImageUnderCap() {
        // A large image rendered as PNG; prepareBody must return JPEG bytes under the cap.
        let big = solidImage(CGSize(width: 3000, height: 3000))
        let png = big.pngData()!
        let out = BrokerRelay.prepareBody(path: "/files/photo", status: 200, data: png)
        XCTAssertNotNil(out)
        XCTAssertLessThanOrEqual(out!.count, BrokerRelay.hardCapBytes)
        XCTAssertNotNil(UIImage(data: out!)) // still a valid image
    }
}
```

- [ ] **Step 2: Create the implementation file**

Create `apps/iosApp/Supermux/Watch/BrokerRelay.swift`:

```swift
import Foundation
import UIKit

/// Phone-side handler for a watch relay request: runs the broker REST call with the phone's
/// stored credentials (`BrokerConfig`) and returns the bytes to the watch. Image responses
/// (`GET /files/...`) are downscaled to a watch-sized thumbnail — and oversized/undecodable
/// ones skipped — so they fit the low-bandwidth `WCSession` link. `handle` is the entry
/// point called from `PhoneWatchProvisioner`; `prepareBody`/`downscale` are pure + tested.
enum BrokerRelay {
    static let thumbMaxDimension: CGFloat = 640
    static let jpegQuality: CGFloat = 0.6
    static let hardCapBytes = 256 * 1024
    static let smallEnoughBytes = 64 * 1024

    /// Run a decoded WCSession message against the broker, returning a reply dictionary.
    static func handle(_ message: [String: Any]) async -> [String: Any] {
        guard let req = RelayEnvelope.decodeRequest(message) else {
            return RelayEnvelope.encodeFailure("bad request")
        }
        guard let base = BrokerConfig.baseURL, let token = BrokerConfig.token,
              !base.isEmpty, !token.isEmpty,
              let url = URL(string: base + req.path) else {
            return RelayEnvelope.encodeFailure("unpaired")
        }
        var urlReq = URLRequest(url: url)
        urlReq.timeoutInterval = 20
        urlReq.httpMethod = req.method
        urlReq.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let ct = req.contentType { urlReq.setValue(ct, forHTTPHeaderField: "Content-Type") }
        urlReq.httpBody = req.body
        do {
            let (data, resp) = try await URLSession.shared.data(for: urlReq)
            let status = (resp as? HTTPURLResponse)?.statusCode ?? RelayEnvelope.phoneFailureStatus
            if status == RelayEnvelope.phoneFailureStatus {
                return RelayEnvelope.encodeFailure("no http response")
            }
            guard let payload = prepareBody(path: req.path, status: status, data: data) else {
                return RelayEnvelope.encodeFailure("payload too large to relay")
            }
            return RelayEnvelope.encodeReply(status: status, body: payload)
        } catch {
            return RelayEnvelope.encodeFailure(error.localizedDescription)
        }
    }

    /// Decide what bytes to send back over the link. File images are downscaled; everything
    /// else passes through if it fits the hard cap, otherwise is skipped (returns nil).
    static func prepareBody(path: String, status: Int, data: Data) -> Data? {
        let isFileImage = (200..<300).contains(status) && path.hasPrefix("/files/")
        guard isFileImage else {
            return data.count <= hardCapBytes ? data : nil
        }
        if data.count <= smallEnoughBytes, UIImage(data: data) != nil {
            return data   // already small enough to relay untouched
        }
        guard let image = UIImage(data: data) else {
            return data.count <= hardCapBytes ? data : nil   // not an image → cap-gate
        }
        let shrunk = downscale(image, maxDimension: thumbMaxDimension)
        guard let jpeg = shrunk.jpegData(compressionQuality: jpegQuality),
              jpeg.count <= hardCapBytes else { return nil }   // still too big → skip
        return jpeg
    }

    /// Aspect-preserving downscale so the longest side ≤ `maxDimension`. Smaller images are
    /// returned unchanged.
    static func downscale(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxDimension else { return image }
        let scale = maxDimension / longest
        let newSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return UIGraphicsImageRenderer(size: newSize).image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add apps/iosApp/Supermux/Watch/BrokerRelay.swift \
        apps/iosApp/SupermuxTests/BrokerRelayTests.swift
git commit -m "feat(watch): phone-side BrokerRelay (broker fetch + photo shrink)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Wire the relay handler into PhoneWatchProvisioner

**Files:**
- Modify: `apps/iosApp/Supermux/Watch/PhoneWatchProvisioner.swift`

Depends on Task 3 (`BrokerRelay`).

- [ ] **Step 1: Add the message handler**

In `apps/iosApp/Supermux/Watch/PhoneWatchProvisioner.swift`, inside the `// MARK: WCSessionDelegate`
section (e.g. right after `sessionReachabilityDidChange`), add the relay handler:

```swift
    // MARK: Relay (watch → broker via this phone)

    /// The watch relays a broker request when it can't reach the broker directly (e.g. a
    /// Tailscale-only broker; there is no watchOS Tailscale client). Run it with this
    /// phone's stored creds and reply with the bytes. Delivered even when the app was
    /// suspended — the system wakes it in the background to answer.
    func session(_ session: WCSession,
                 didReceiveMessage message: [String: Any],
                 replyHandler: @escaping ([String: Any]) -> Void) {
        Task { replyHandler(await BrokerRelay.handle(message)) }
    }
```

(No other change — `PhoneWatchProvisioner` is already the activated `WCSessionDelegate`.)

- [ ] **Step 2: Commit**

```bash
git add apps/iosApp/Supermux/Watch/PhoneWatchProvisioner.swift
git commit -m "feat(watch): answer watch broker-relay messages on the phone

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Route WatchBrokerSession through the transport

**Files:**
- Modify: `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift`

Depends on Task 2 (`BrokerTransport`).

- [ ] **Step 1: Replace stored creds with a transport + add `route`**

In `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift`, replace the property block and
`init` (current lines ~11-30):

```swift
    let baseURL: String
    private let token: String

    private(set) var sessions: [SessionInfo] = []
    private(set) var messages: [String: [LogEntry]] = [:]
    private(set) var synced = false
    private(set) var status = ""   // diagnostic: last REST error, empty when healthy

    /// The session whose detail view is open — its messages get polled each tick.
    var activeSession: String?

    private var polling = false
    private var pendingEchoes: [String: [LogEntry]] = [:]   // optimistic sends awaiting server echo

    init(baseURL: String, token: String) {
        var b = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while b.hasSuffix("/") { b.removeLast() }
        self.baseURL = b
        self.token = token
    }
```

with:

```swift
    let baseURL: String
    private let transport: BrokerTransport

    private(set) var sessions: [SessionInfo] = []
    private(set) var messages: [String: [LogEntry]] = [:]
    private(set) var synced = false
    private(set) var status = ""   // diagnostic: last REST error, empty when healthy
    private(set) var route: BrokerRoute = .direct   // which path served the last request

    /// The session whose detail view is open — its messages get polled each tick.
    var activeSession: String?

    private var polling = false
    private var pendingEchoes: [String: [LogEntry]] = [:]   // optimistic sends awaiting server echo

    /// `transport` is injectable for tests; in the app it's a `RoutingTransport` that prefers
    /// the paired iPhone and falls back to a direct connection.
    init(baseURL: String, token: String, transport: BrokerTransport? = nil) {
        var b = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while b.hasSuffix("/") { b.removeLast() }
        self.baseURL = b
        if let transport {
            self.transport = transport
        } else {
            // Placeholder so all stored properties are initialized before we capture self.
            self.transport = DirectTransport(baseURL: b, token: token)
        }
        if transport == nil {
            self.transport = RoutingTransport(
                direct: DirectTransport(baseURL: b, token: token),
                relay: PhoneRelayTransport(),
                reachability: WCReachability(),
                onRoute: { [weak self] r in Task { @MainActor in self?.route = r } }
            )
        }
    }
```

Note: `transport` is declared `private let` above but reassigned in `init` — change it to
`private var` if the compiler requires it for the two-phase assignment. Use:

```swift
    private var transport: BrokerTransport
```

- [ ] **Step 2: Route `get`, `send`, `transcribeDraft`, `loadFile` through the transport**

Replace the `get` helper (current lines ~103-113):

```swift
    private func get<T: Decodable>(_ path: String, _ type: T.Type) async throws -> T {
        guard let url = URL(string: baseURL + path) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
```

with:

```swift
    private func get<T: Decodable>(_ path: String, _ type: T.Type) async throws -> T {
        let (data, status) = try await transport.request(method: "GET", path: path, body: nil, contentType: nil)
        guard (200..<300).contains(status) else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(T.self, from: data)
    }
```

Replace the body of `send`'s `Task` (current lines ~82-90):

```swift
        Task { [baseURL, token] in
            guard let url = URL(string: "\(baseURL)/sessions/\(sessionId)/message") else { return }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: ["text": t])
            _ = try? await URLSession.shared.data(for: req)
        }
```

with:

```swift
        Task { [transport] in
            let body = try? JSONSerialization.data(withJSONObject: ["text": t])
            _ = try? await transport.request(method: "POST", path: "/sessions/\(sessionId)/message",
                                             body: body, contentType: "application/json")
        }
```

Replace `transcribeDraft` (current lines ~123-135):

```swift
    func transcribeDraft(sessionId: String, draft: String) async throws -> String {
        guard let url = URL(string: "\(baseURL)/sessions/\(sessionId)/transcribe") else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["draft": draft])
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(TranscribeResponse.self, from: data).text
    }
```

with:

```swift
    func transcribeDraft(sessionId: String, draft: String) async throws -> String {
        let body = try JSONSerialization.data(withJSONObject: ["draft": draft])
        let (data, status) = try await transport.request(method: "POST",
                                                         path: "/sessions/\(sessionId)/transcribe",
                                                         body: body, contentType: "application/json")
        guard (200..<300).contains(status) else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(TranscribeResponse.self, from: data).text
    }
```

Replace `loadFile` (current lines ~138-143):

```swift
    func loadFile(_ id: String) async -> Data? {
        guard let url = URL(string: "\(baseURL)/files/\(id)") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try? await URLSession.shared.data(for: req).0
    }
```

with:

```swift
    func loadFile(_ id: String) async -> Data? {
        guard let (data, status) = try? await transport.request(method: "GET", path: "/files/\(id)",
                                                                body: nil, contentType: nil),
              (200..<300).contains(status) else { return nil }
        return data
    }
```

- [ ] **Step 3: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift
git commit -m "feat(watch): route broker calls through phone-first transport

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Route indicator in SessionsListView

**Files:**
- Modify: `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift`

Depends on Task 5 (`broker.route`).

- [ ] **Step 1: Add a subtle route badge**

In `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift`, add a `.toolbar` modifier to the
`Group` (right after `.navigationTitle("Supermux")`, before `.navigationDestination`):

```swift
        .navigationTitle("Supermux")
        .toolbar {
            if broker.route == .phone {
                ToolbarItem(placement: .topBarTrailing) {
                    Image(systemName: "iphone")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Connected via iPhone")
                }
            }
        }
```

Then surface an explicit Offline line in the not-yet-synced branch. Replace:

```swift
                    if !broker.status.isEmpty {
                        Text(broker.status)
                            .font(.caption2).foregroundStyle(.orange)
                            .lineLimit(4).multilineTextAlignment(.center)
                    }
```

with:

```swift
                    if broker.route == .offline {
                        Text("Offline").font(.caption).foregroundStyle(.orange)
                    }
                    if !broker.status.isEmpty {
                        Text(broker.status)
                            .font(.caption2).foregroundStyle(.orange)
                            .lineLimit(4).multilineTextAlignment(.center)
                    }
```

- [ ] **Step 2: Commit**

```bash
git add apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift
git commit -m "feat(watch): show 'via iPhone' / Offline route indicator

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Build + test on the remote Mac (verification gate)

**Files:** none (verification only).

This is the real TDD gate (see "Build & test environment"). Loop until green; fix in the
relevant source/test file and re-run.

- [ ] **Step 1: Sync the worktree to the Mac**

```bash
cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/49bcbaf3-f25d-4ecb-9c87-fac180fb43fb
tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules --exclude 'apps/iosApp/build' -czf - . \
  | ssh mac 'rm -rf ~/supermux && mkdir -p ~/supermux && tar -xzf - -C ~/supermux'
```

- [ ] **Step 2: Regenerate the project + run the unit tests**

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux/apps/iosApp && xcodegen generate && \
  xcodebuild test -scheme Supermux \
    -destination "platform=iOS Simulator,name=iPhone 16" \
    -only-testing:SupermuxTests 2>&1 | tail -40'
```
Expected: `** TEST SUCCEEDED **`, with `RelayEnvelopeTests`, `RoutingTransportTests`,
`BrokerRelayTests` all passing.

- [ ] **Step 3: Build the watch app (watch-only code compile gate)**

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux/apps/iosApp && \
  xcodebuild build -scheme SupermuxWatch -sdk watchsimulator26.5 \
    -destination "platform=watchOS Simulator,name=Apple Watch Series 11 (46mm)" \
    ARCHS=arm64 CODE_SIGNING_ALLOWED=NO 2>&1 | tail -20'
```
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 4: Build the iOS app (verifies shared files compile into `Supermux`)**

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux/apps/iosApp && \
  xcodebuild build -scheme Supermux -sdk iphonesimulator \
    -destination "platform=iOS Simulator,name=iPhone 16" \
    ARCHS=arm64 CODE_SIGNING_ALLOWED=NO 2>&1 | tail -20'
```
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 5: If anything failed, fix and re-run from Step 1**

Common issues: `transport` two-phase init (use `private var transport`); a missing
target-membership line in `project.yml` (symbol "cannot find 'RelayEnvelope' in scope" in
`Supermux` → the `RelayEnvelope.swift`/`BrokerTransport.swift` source entries are missing
from the `Supermux` target); `.topBarTrailing` availability (watchOS 10+ — OK at our
deployment target).

- [ ] **Step 6: No commit needed** (verification only). Report results.

---

## Manual device verification (post-merge, requires a real watch + phone)

WCSession messaging is unreliable in the simulator, so the live relay path must be checked on
hardware (build/sign/install per the watch app's existing playbook,
`docs/superpowers/plans/2026-06-22-apple-watch-app.md`):

1. **Tailscale broker, phone in pocket** → watch loads sessions/messages; "via iPhone" badge
   shows. (Direct can't reach the tailnet; relay carries it.)
2. **Phone off / out of range, public broker, watch on Wi‑Fi/cellular** → watch still works
   (direct); no badge.
3. **Tailscale broker, phone away** → Offline.
4. **Open a session with a photo** over the relay → image loads (downscaled); an oversized
   image is skipped without hanging.
5. **Voice-dictate + send** over the relay → optimistic echo appears, cleanup applies,
   reconciles on the next poll.

---

## Self-review (completed by plan author)

- **Spec coverage:** phone-first routing (T2/T5), `isReachable` keying (T2 `WCReachability` +
  `RoutingTransport`), direct fallback (T2), relay-everything generic envelope (T1),
  photo shrink + skip (T3), relay handler in `PhoneWatchProvisioner` (T4), background-wake
  creds (T3 via `BrokerConfig`), broker-error passthrough / no-fallback (T2 test), route
  indicator + Offline (T6), unit + device tests (T1-3, T7, manual). All spec sections map to
  a task.
- **Placeholders:** none — every code/test step has complete content.
- **Type consistency:** `BrokerTransport.request(method:path:body:contentType:) -> (Data, Int)`
  is identical across `DirectTransport`, `PhoneRelayTransport`, `RoutingTransport`, the fakes,
  and all call sites in `WatchBrokerSession`. `RelayEnvelope` keys/`phoneFailureStatus` and
  `RelayError`/`BrokerRoute`/`RelayReachability` names are consistent between producer and
  consumer tasks.
