import Foundation
import Shared
#if os(macOS)
import AppKit   // NSWorkspace wake notification (sleep/wake reconnect)
#endif

/// Observable wrapper over the shared `BrokerClient` — mirrors the Android
/// `AppViewModel`: collect `client.frames` into UI state, run the socket loop,
/// and expose `BrokerApi` for REST actions. SKIE gives us `async` + typed enums.
@MainActor
@Observable
final class BrokerSession {
    let baseURL: String
    private let token: String
    let api: BrokerApi
    private let client: BrokerClient
    /// The socket run-loop task (`client.run()`) and the frames collector, retained so
    /// `stop()` can cancel them (and, on macOS, so `wakeKick()` can cancel the in-flight
    /// reconnect backoff and redial immediately on wake). Without the explicit cancel the
    /// run loop would keep the socket dialing/reconnecting for the whole process lifetime —
    /// the frames collector iterates a hot SharedFlow that never completes on its own.
    @ObservationIgnored private var runTask: Task<Void, Never>?
    @ObservationIgnored private var framesTask: Task<Void, Never>?
    #if os(macOS)
    @ObservationIgnored private var wakeObserver: (any NSObjectProtocol)?
    #endif

    // Viewing presence — tells the broker which chat is foreground (parity with the web
    // `useViewing` composable) so it suppresses a push for a chat you're already looking at.
    // The shell (RootView) pushes state in on selection/scene changes; a 60s heartbeat keeps
    // the broker's 5-min viewing TTL fresh while you sit on a long, quiet turn.
    @ObservationIgnored private var viewingSession: String?
    @ObservationIgnored private var viewingVisible = false
    @ObservationIgnored private var lastSentViewing: (session: String?, visible: Bool)?
    @ObservationIgnored private var viewingHeartbeat: Task<Void, Never>?
    private static let viewingHeartbeatNs: UInt64 = 60_000_000_000   // 60s, well under the 5-min TTL

    private(set) var sessions: [SessionInfo] = []
    private(set) var messages: [String: [LogEntry]] = [:]
    private(set) var activity: [String: [ActivityEvent]] = [:]
    private(set) var agentPhase: [String: String] = [:]
    private(set) var agentSince: [String: Int64] = [:]
    private(set) var agentWorking: [String: Bool] = [:]
    private(set) var agentDead: [String: Bool] = [:]           // derived: state == "dead"
    private(set) var agentState: [String: String] = [:]       // idle | working | dead
    private(set) var agentDetail: [String: String] = [:]      // thinking | running
    private(set) var agentWorkingSince: [String: Int64] = [:]
    private(set) var agentTool: [String: String] = [:]        // running tool name (e.g. Bash)
    private(set) var agentWaiting: [String: Bool] = [:]       // idle but background tasks still open
    private(set) var agentBgOpen: [String: Int] = [:]         // open background-task count
    private(set) var bgTasks: [String: [ServerFrameBgTask]] = [:]
    private(set) var pendingSend: Set<String> = []            // client-local "Sending…"
    private(set) var commands: [String: [SlashCommand]] = [:]
    private(set) var displays: [DisplayStream] = []
    private(set) var finishJobs: [String: FinishJobDto] = [:]
    private(set) var synced = false

    /// Per-host reachability for the merged fleet list (spec §5): true once this host's control WS
    /// is up, false when it drops. Fed by the shared `BrokerClient`'s `onConnectionChange`. Distinct
    /// from `synced`, which latches true on the first snapshot ("do we have state yet") and never
    /// flips back — `online` tracks the live socket so `Fleet` can grey an offline host. `Fleet`
    /// sets `onConnectionChanged` to learn each transition (record last-seen / backfill identity).
    private(set) var online = false
    @ObservationIgnored var onConnectionChanged: ((Bool) -> Void)?
    // Indirection so the BrokerClient's connection callback captures a plain relay object at
    // construction — NOT `self` (which isn't fully initialized while `self.client` is being set).
    // Its target is wired to `self` at the end of init, once every stored property exists.
    @ObservationIgnored private let connFlag = ConnectionFlag()

    private func setOnline(_ up: Bool) {
        guard online != up else { return }
        online = up
        onConnectionChanged?(up)
    }

    /// Drop a session's finish job (FinishSheet Dismiss/Done) so the sheet returns to the
    /// readiness menu. The broker only ever ADDS/updates jobs over the WS `finish_job` frame —
    /// it never broadcasts a cleared state — so the client clears its own copy. (web parity:
    /// `finishJob.clear(sessionId)`.)
    func clearFinishJob(_ id: String) { finishJobs.removeValue(forKey: id) }

    init(baseURL: String, token: String) {
        self.baseURL = baseURL
        self.token = token
        let http = IosClientKt.iosHttpClient()
        self.api = BrokerApi(baseUrl: baseURL, token: token, http: http)
        self.client = BrokerClient(baseUrl: baseURL, token: token, http: http,
                                   policy: ReconnectPolicy(baseMs: 500, maxMs: 8000),
                                   onConnectionChange: { [connFlag] connected in
                                       // Kotlin invokes this off the main actor. `connected` is the
                                       // boxed Kotlin Boolean (K/N leaves primitive lambda params
                                       // boxed — same as the KotlinLong `onProgress` callbacks). The
                                       // relay (not self) is captured, then hops to main below.
                                       connFlag.report(connected.boolValue)
                                   })
        // self is fully initialized now (every stored property has a value), so it's safe to point
        // the connection relay at it — off-main callbacks hop to the main actor to touch @Observable.
        connFlag.onChange = { [weak self] up in
            Task { @MainActor in self?.setOnline(up) }
        }
        #if os(macOS)
        // Macs sleep with the lid: the WS drops and the run-loop enters its backoff delay.
        // On wake, don't sit out that timer — kick the loop immediately so the workspace is
        // live on lid-open. `queue: .main` guarantees the callback lands on the main thread,
        // so `assumeIsolated` is sound (this class is @MainActor).
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.wakeKick() }
        }
        #endif
    }

    // Belt to stop()'s suspenders, for a release WITHOUT a prior stop() (any SwiftUI path
    // where onDisappear doesn't fire). Dropping a Task handle does NOT cancel it — the run
    // loop holds the captured Kotlin client, not self, so it would keep reconnecting forever
    // after this object died. Task.cancel() is Sendable, so it's legal in deinit.
    deinit {
        framesTask?.cancel()
        runTask?.cancel()
        viewingHeartbeat?.cancel()
        #if os(macOS)
        if let wakeObserver { NSWorkspace.shared.notificationCenter.removeObserver(wakeObserver) }
        #endif
    }

    /// Build a terminal WS client for a session. Centralized here so the device
    /// token stays private (mirrors how `api`/`client` are constructed).
    func terminalClient(sessionId: String, kind: String, terminalId: String?) -> TerminalClient {
        TerminalClient(
            baseUrl: baseURL,
            token: token,
            http: IosClientKt.iosHttpClient(),
            sessionId: sessionId,
            kind: kind,
            terminalId: terminalId
        )
    }

    /// Build a VNC WS client for a display stream (token stays private, mirrors `terminalClient`).
    func vncClient(streamId: String) -> VncClient {
        VncClient(baseUrl: baseURL, token: token, http: IosClientKt.iosHttpClient(), streamId: streamId)
    }

    /// Build a scrcpy (H.264) WS client for a display stream (mirrors `terminalClient`).
    func scrcpyClient(streamId: String) -> ScrcpyClient {
        ScrcpyClient(baseUrl: baseURL, token: token, http: IosClientKt.iosHttpClient(), streamId: streamId)
    }

    func start() {
        // Already armed → no-op (symmetry with idempotent stop(): a double-start would
        // orphan the previous task pair with no handle left to cancel them by).
        guard framesTask == nil else { return }
        // Neither long-lived task may hold `self` strongly across a suspension point: even
        // after stop() cancels them (verified: SKIE propagates the cancel and both bodies
        // exit promptly), Kotlin/Native can keep a task's async frame reachable until its
        // tracing GC next runs — so a strong `self` captured in the frame (e.g. an up-front
        // `guard let self`) would tie the session's release to K/N GC timing (observed
        // empirically via BrokerSessionTeardownTests). Capturing `client` (Kotlin, safe to
        // linger) plus a per-frame weak rebind keeps teardown deterministic.
        framesTask = Task { [weak self, client] in
            for await frame in client.frames {
                guard let self else { break }
                self.reduce(frame)
            }
        }
        runTask = Task { [client] in try? await client.run() }
        // Seed the display list (REST); the two frame cases below keep it live. Same rule as
        // above: fetch via the captured `api`, rebind self only after the await ends (an
        // `await self?.refreshDisplays()` optional-chain would pin a strong temp across it).
        Task { [weak self, api] in
            let seeded = (try? await api.listDisplays()) ?? []
            self?.displays = seeded
        }
    }

    /// Tear down the connection. Cancels the run loop — SKIE propagates the cancellation
    /// into the Kotlin coroutine, closing the WS (or unwinding the backoff `delay()`) — and
    /// the frames collector, whose cancellation ends the `for await` (without this the
    /// session's socket keeps dialing/reconnecting for the whole process lifetime). On macOS
    /// also drops the wake observer so a stopped session can't be resurrected by a lid-open.
    /// Idempotent; called when the owning view disappears (a closed SessionWindow on macOS;
    /// RootView's unpair/re-pair recreation on both platforms). `start()` re-arms everything
    /// except the wake observer (init-registered), which is fine: the stop/start pairs in
    /// play recreate the whole session anyway. Deterministic release of the session object
    /// itself is guaranteed by start()'s no-strong-self capture rule + this cancel — see
    /// BrokerSessionTeardownTests.
    func stop() {
        framesTask?.cancel()
        framesTask = nil
        runTask?.cancel()
        runTask = nil
        viewingHeartbeat?.cancel()
        viewingHeartbeat = nil
        #if os(macOS)
        if let wakeObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(wakeObserver)
            self.wakeObserver = nil
        }
        #endif
    }

    #if os(macOS)
    /// macOS wake: skip the reconnect backoff. When the socket dropped during sleep the
    /// run-loop is sitting in its Kotlin `delay()` backoff (so `client.sync.synced` is false);
    /// cancel that task — SKIE propagates the cancellation into the coroutine, unwinding the
    /// `delay()` — and start a fresh `run()` that redials at once. No-op while still connected,
    /// so a healthy WS is never torn down (idempotent, safe to call on every wake). The frames
    /// collector is a separate, durable subscriber on the same client, so it keeps delivering
    /// across the restart.
    func wakeKick() {
        guard !client.sync.synced else { return }
        runTask?.cancel()
        runTask = Task { [client] in try? await client.run() }   // no self (see start())
    }
    #endif

    // Not `private`: SupermuxTests drives this directly (no fake-server seam exists on
    // `client`/`BrokerClient`, so the unit tests for the `agent_state` reduction — see
    // `AgentDeadStateTests` — call this the same way the `start()` frame loop does).
    func reduce(_ frame: ServerFrame) {
        switch onEnum(of: frame) {
        case .snapshot(let s):
            sessions = s.sessions
            messages = s.logs
            activity = s.activity
            agentPhase = s.agentState.mapValues { $0.phase }
            agentSince = s.agentState.compactMapValues { $0.since?.int64Value }
            agentWorking = s.agentState.mapValues { $0.working }
            agentDead = s.agentState.mapValues { $0.state == "dead" }
            agentState = s.agentState.mapValues { $0.state }
            agentDetail = s.agentState.compactMapValues { $0.detail }
            agentWorkingSince = s.agentState.compactMapValues { $0.workingSince?.int64Value }
            agentTool = s.agentState.compactMapValues { $0.tool }
            agentWaiting = s.agentState.mapValues { $0.waiting }
            agentBgOpen = s.agentState.mapValues { Int($0.bgOpen) }
            bgTasks = s.bgTasks
            commands = s.commands
            finishJobs = Dictionary(uniqueKeysWithValues: s.sessions.compactMap { sess in sess.finish_job.map { (sess.id, $0) } })
            synced = true
            // A (re)connect always begins with a snapshot; re-assert viewing presence so the
            // broker's per-device tracker is current after a reconnect (we may have expired out
            // of it). Clearing lastSentViewing forces the send even when the value is unchanged.
            lastSentViewing = nil
            sendViewingIfChanged()
        case .sessionAdded(let a):
            // The broker re-broadcasts session_added for the SAME session (an early add right
            // after spawn, then the authoritative post-register add that carries repo_root /
            // session_branch). Dedup by id and backfill — keep existing values where the incoming
            // frame omits them, never clobber with nil — instead of appending a duplicate row.
            // (web parity: src/web-app/src/stores/sessions.ts `add()`.)
            let incoming = a.session
            if let idx = sessions.firstIndex(where: { $0.id == incoming.id }) {
                let old = sessions[idx]
                sessions[idx] = incoming.doCopy(
                    id: incoming.id, name: incoming.name, workdir: incoming.workdir,
                    agent: incoming.agent, status: incoming.status ?? old.status,
                    mute: incoming.mute ?? old.mute, connected: incoming.connected ?? old.connected,
                    model: incoming.model ?? old.model,
                    reasoningLevel: incoming.reasoningLevel ?? old.reasoningLevel,
                    repo_root: incoming.repo_root ?? old.repo_root,
                    role: incoming.role ?? old.role, session_branch: incoming.session_branch ?? old.session_branch,
                    git: incoming.git ?? old.git, finish_job: incoming.finish_job ?? old.finish_job,
                    userStatus: incoming.userStatus ?? old.userStatus,
                    sortOrder: incoming.sortOrder != 0 ? incoming.sortOrder : old.sortOrder,
                    draftPayload: incoming.draftPayload ?? old.draftPayload)
            } else {
                sessions.append(incoming)
            }
            if let job = incoming.finish_job { finishJobs[incoming.id] = job }
        case .sessionRemoved(let r):
            // Resolve the name BEFORE dropping the session — display hosts are keyed by it.
            let removedName = sessions.first { $0.id == r.id }?.name
            sessions.removeAll { $0.id == r.id }
            // Clear the whole per-session agent-state family: an archived session RESUMES with
            // the SAME id, and over a continuous WS the resume's session_added carries no agent
            // state — so a stale entry (e.g. agentDead=true from the kill) would misreport the
            // healthy resumed session ("Not responding") until its first real agent_state frame.
            agentPhase.removeValue(forKey: r.id)
            agentSince.removeValue(forKey: r.id)
            agentWorking.removeValue(forKey: r.id)
            agentDead.removeValue(forKey: r.id)
            agentState.removeValue(forKey: r.id)
            agentDetail.removeValue(forKey: r.id)
            agentWorkingSince.removeValue(forKey: r.id)
            agentTool.removeValue(forKey: r.id)
            agentWaiting.removeValue(forKey: r.id)
            agentBgOpen.removeValue(forKey: r.id)
            bgTasks.removeValue(forKey: r.id)
            evictTerminalHosts(sessionId: r.id)   // session killed → tear down its live terminals
            dropEditorHost(sessionId: r.id)       // …its editor webview (stop() breaks the bridge cycle)
            if let removedName { evictDisplayHosts(sessionName: removedName) }  // …and its displays
        case .sessionRenamed(let r):
            if let idx = sessions.firstIndex(where: { $0.id == r.id }) {
                let old = sessions[idx]
                sessions[idx] = old.doCopy(
                    id: old.id, name: r.newName, workdir: old.workdir, agent: old.agent,
                    status: old.status, mute: old.mute, connected: old.connected,
                    model: old.model, reasoningLevel: old.reasoningLevel,
                    repo_root: old.repo_root, role: old.role, session_branch: old.session_branch,
                    git: old.git, finish_job: old.finish_job,
                    userStatus: old.userStatus, sortOrder: old.sortOrder, draftPayload: old.draftPayload)
            }
        case .sessionState(let st):
            // Per-session patch (model/effort switch, mute, shim connect): merge only the
            // fields present (web parity: ws.ts updateState). Natives dropped this frame
            // pre-2026-07-11 → model/effort pills stayed stale until app restart.
            if let idx = sessions.firstIndex(where: { $0.id == st.session }) {
                let old = sessions[idx]
                sessions[idx] = old.doCopy(
                    id: old.id, name: old.name, workdir: old.workdir, agent: old.agent,
                    status: old.status,
                    mute: st.mute ?? old.mute, connected: st.connected ?? old.connected,
                    model: st.model ?? old.model,
                    reasoningLevel: st.reasoningLevel ?? old.reasoningLevel,
                    repo_root: old.repo_root, role: old.role, session_branch: old.session_branch,
                    git: old.git, finish_job: old.finish_job,
                    userStatus: old.userStatus, sortOrder: old.sortOrder, draftPayload: old.draftPayload)
            }
        case .messageAppend(let m):
            // Drop the optimistic local echo when the real inbound message arrives.
            if m.entry.direction.hasPrefix("in") {
                messages[m.session]?.removeAll { $0.id.hasPrefix("local-") && $0.text == m.entry.text }
            }
            messages[m.session, default: []].append(m.entry)
        case .activityAppend(let a): activity[a.session, default: []].append(a.event)
        case .bgTasks(let f): bgTasks[f.session] = f.tasks
        case .agentState(let st):
            agentPhase[st.session] = st.phase
            agentSince[st.session] = (st.since ?? st.workingSince)?.int64Value
            agentWorking[st.session] = st.working
            agentDead[st.session] = st.state == "dead"
            agentState[st.session] = st.state
            if let d = st.detail { agentDetail[st.session] = d } else { agentDetail[st.session] = nil }
            agentWorkingSince[st.session] = st.workingSince?.int64Value
            if let t = st.tool { agentTool[st.session] = t } else { agentTool[st.session] = nil }
            agentWaiting[st.session] = st.waiting
            agentBgOpen[st.session] = Int(st.bgOpen)
            pendingSend.remove(st.session)   // first real state clears the client-local Sending…
        case .commandsChanged(let c): commands[c.session] = c.commands
        case .fsChanged(let f): editorStates[f.session]?.markChanged(f.paths)
        case .displayAdded(let f):
            displays.removeAll { $0.id == f.display.id }
            displays.append(f.display)
        case .displayRemoved(let f):
            displays.removeAll { $0.id == f.id }
            dropDisplayHost(streamId: f.id)   // stream stopped → tear down its live host
        case .lspStatus(let f): lspBridges[f.session ?? ""]?.handleStatus(f)
        case .lspReady(let f): lspBridges[f.session]?.handleReady(f.serverId)
        case .lspError(let f): lspBridges[f.session ?? ""]?.handleError(f.serverId)
        case .lspRpcIn(let f): lspBridges[f.session]?.handleRpcIn(f.serverId, f.message)
        case .lspExit: break
        case .lspInstallProgress: break
        case .lspInstallDone: break
        case .agentError: break
        case .finishJobFrame(let f):
            if let job = f.job { finishJobs[f.session] = job } else { finishJobs.removeValue(forKey: f.session) }
        case .sessionGit(let g):
            if let idx = sessions.firstIndex(where: { $0.id == g.session }) {
                // SKIE exposes the Kotlin data-class copy as doCopy(...) with the
                // original snake_case property names as argument labels.
                sessions[idx] = sessions[idx].doCopy(
                    id: sessions[idx].id, name: sessions[idx].name, workdir: sessions[idx].workdir,
                    agent: sessions[idx].agent, status: sessions[idx].status, mute: sessions[idx].mute,
                    connected: sessions[idx].connected, model: sessions[idx].model,
                    reasoningLevel: sessions[idx].reasoningLevel,
                    repo_root: sessions[idx].repo_root, role: sessions[idx].role,
                    session_branch: sessions[idx].session_branch, git: g.git,
                    finish_job: sessions[idx].finish_job,
                    userStatus: sessions[idx].userStatus, sortOrder: sessions[idx].sortOrder,
                    draftPayload: sessions[idx].draftPayload)
            }
        }
    }

    func send(_ sessionId: String, _ text: String, attachments: [String]? = nil) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let atts = attachments ?? []
        guard !t.isEmpty || !atts.isEmpty else { return }
        // Optimistic local echo so the message appears instantly (web parity).
        // The broker's real inbound echo replaces it (deduped in reduce()).
        if !t.isEmpty {
            let optimistic = LogEntry(
                id: "local-\(messages[sessionId]?.count ?? 0)-\(abs(t.hashValue))",
                ts: ISO8601DateFormatter().string(from: Date()),
                direction: "inbound", text: t,
                op: nil, channel: nil, chat_id: nil, message_id: nil, attachments: nil
            )
            messages[sessionId, default: []].append(optimistic)
        }
        let frame = ClientFrameSend(session: sessionId, op: "reply",
                                    args: SendArgs(text: t, attachments: atts.isEmpty ? nil : atts))
        Task { [client] in try? await client.send(frame: frame) }
        pendingSend.insert(sessionId)   // client-local "Sending…" until the next agent_state
    }

    // MARK: - Viewing presence (push suppression for the chat you're looking at)

    /// Report the foreground chat (`nil` = the session list) + whether the app is visible.
    /// Called by the shell on selection / scene-phase changes. Deduped, and it (lazily) starts
    /// the keep-alive heartbeat. Mirrors the web `useViewing` composable.
    func updateViewing(session: String?, visible: Bool) {
        viewingSession = session
        viewingVisible = visible
        sendViewingIfChanged()
        ensureViewingHeartbeat()
    }

    /// Emit a `viewing` frame only when the (session, visible) pair actually changed — the
    /// broker re-reads it into its per-device tracker. Captures only `client` across the SKIE
    /// suspend (never `self` — see `start()`'s GC-pinning note).
    private func sendViewingIfChanged() {
        if let last = lastSentViewing, last.session == viewingSession, last.visible == viewingVisible { return }
        lastSentViewing = (viewingSession, viewingVisible)
        let frame = ClientFrameViewing(session: viewingSession, visible: viewingVisible)
        Task { [client] in try? await client.send(frame: frame) }
    }

    /// Re-assert the viewing frame every 60s so the broker's 5-min TTL never lapses while the
    /// user reads a long, quiet turn (the exact bug the web heartbeat fixed). Only refreshes
    /// while visible — a backgrounded app has no presence to keep alive. Idempotent; torn down
    /// in `stop()`/`deinit`.
    private func ensureViewingHeartbeat() {
        guard viewingHeartbeat == nil else { return }
        viewingHeartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.viewingHeartbeatNs)
                guard let self else { return }
                guard self.viewingVisible else { continue }
                // Read state synchronously on the main actor, then send capturing only `client`.
                let frame = ClientFrameViewing(session: self.viewingSession, visible: true)
                let client = self.client
                Task { [client] in try? await client.send(frame: frame) }
            }
        }
    }

    /// Upload bytes (base64 over the wire) → file id, for composing attachments.
    func upload(_ sessionId: String, data: Data, filename: String, mime: String, kind: String? = nil) async -> String? {
        let b64 = data.base64EncodedString()
        return (try? await api.uploadBase64(session: sessionId, base64: b64, filename: filename, mime: mime, kind: kind))?.file_id
    }

    /// Resumable/chunked upload from a `ChunkSource` (bounded RAM — never buffers the whole
    /// file), reporting absolute progress `(bytesSent, total)`. Returns the finalized file id,
    /// or nil on failure so the caller can surface a failed chip instead of a silent drop.
    func uploadResumable(_ sessionId: String, source: ChunkSource, filename: String, mime: String,
                         kind: String? = nil, onProgress: @escaping (Int64, Int64) -> Void) async -> String? {
        (try? await api.uploadResumable(
            session: sessionId, source: source, filename: filename, mime: mime, kind: kind,
            onProgress: { sent, total in onProgress(sent.int64Value, total.int64Value) }
        ))?.file_id
    }

    /// PWA-identical grouping (Personal Assistants + per-workdir) via the shared helper.
        func groups() -> [SessionGroup] {
        let home = inferHomeDir(workdir: sessions.first?.workdir) ?? ""
        return groupSessions(sessions: sessions, home: home, lastTs: { [messages] s in
            messages[s.id]?.last?.ts ?? ""
        }, archived: [])
    }

    // MARK: - Displays (live via display_added/removed frames; seeded by listDisplays)

    /// The newest running display bound to a session name (newest `createdAt` wins; falls
    /// back to the last-appended when timestamps are missing). Drives `DisplayPane`.
    func runningDisplay(for name: String) -> DisplayStream? {
        displays
            .filter { $0.sessionName == name && $0.status == "running" }
            .max { a, b in (a.createdAt ?? "") < (b.createdAt ?? "") }
    }

    /// Re-seed the display list from REST (called on `start()` and by management views).
    func refreshDisplays() async {
        displays = (try? await api.listDisplays()) ?? []
    }

    // MARK: - Session actions (mirror the web SessionListView)
    func toggleMute(_ s: SessionInfo) {
        let next = !(s.mute?.boolValue ?? false)
        Task { [api] in try? await api.setMute(id: s.id, muted: next) }
    }
    func rename(_ id: String, to name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        Task { [api] in try? await api.rename(id: id, name: n) }
    }
    func kill(_ id: String) {
        Task { [api] in try? await api.kill(id: id) }
    }
    func interrupt(_ id: String) {
        Task { [api] in try? await api.interrupt(id: id) }
    }

    // Git status + finish (chat header).
    func gitStatus(_ id: String) async -> GitRemoteStatus? { try? await api.gitStatus(id: id) }
    func gitFetch(_ id: String) async -> GitOpResult? { try? await api.gitFetch(id: id) }
    func gitPublish(_ id: String) async -> GitOpResult? { try? await api.gitPublish(id: id) }
    func gitPush(_ id: String) async -> GitOpResult? { try? await api.gitPush(id: id) }
    func gitPull(_ id: String) async -> GitOpResult? { try? await api.gitPull(id: id) }
    func finish(_ id: String, action: String? = nil, skipVerify: Bool? = nil, commitFirst: Bool? = nil,
                commitMessage: String? = nil, prTitle: String? = nil, prBody: String? = nil,
                draft: Bool? = nil, prRequiresGreen: Bool? = nil) async -> FinishResult? {
        try? await api.finish(id: id, action: action, skipVerify: skipVerify?.kb,
                              commitFirst: commitFirst?.kb, commitMessage: commitMessage,
                              prTitle: prTitle, prBody: prBody,
                              draft: draft?.kb, prRequiresGreen: prRequiresGreen?.kb)
    }
    func finishReadiness(_ id: String) async -> FinishReadiness? { try? await api.finishReadiness(id: id) }
    func verifySuggest(_ id: String) async -> VerifySuggestResult? { try? await api.verifySuggest(id: id) }
    func verifySave(_ id: String, content: String) async -> VerifySaveResult? { try? await api.verifySave(id: id, content: content) }
    func sendMessage(_ id: String, _ text: String) { Task { [api] in try? await api.sendMessage(id: id, text: text) } }
    func projects() async -> [String] { (try? await api.listProjects()) ?? [] }
    func spawn(workdir: String, agent: String?, name: String?, model: String? = nil,
               worktree: Bool? = nil, baseBranch: String? = nil, reasoningLevel: String? = nil) async -> String? {
        // Resolve ~ to an absolute path so the worktree is cut from the real repo root (web parity).
        let resolved = (try? await api.validatePath(path: workdir)).flatMap { $0.ok ? $0.path : nil } ?? workdir
        let req = SpawnRequest(workdir: resolved, name: name, agent: agent, model: model,
                               worktree: worktree?.kb, baseBranch: baseBranch, reasoningLevel: reasoningLevel,
                               userStatus: nil, draftPayload: nil)
        return (try? await api.spawn(req: req))?.id
    }

    // MARK: - Launcher: worktree + git-hosting parity with the web SessionLauncherView

    /// Validate (resolve ~) then load git repo info — drives the launcher's worktree picker.
    /// Returns nil when the path isn't a resolvable directory (→ no worktree option, like web).
    func repoInfo(_ path: String, fetch: Bool = false) async -> RepoInfo? {
        guard let resolved = (try? await api.validatePath(path: path)).flatMap({ $0.ok ? $0.path : nil })
        else { return nil }
        return try? await api.getRepoInfo(path: resolved, fetch: fetch)
    }

    /// Configured GitHub/GitLab connections (for clone/create in the project picker).
    func forges() async -> [ForgeConnection] { (try? await api.listForges())?.connections ?? [] }
    /// Matching remote repos across all connections (caller debounces).
    func searchForge(_ query: String) async -> [RemoteRepo] { (try? await api.searchForge(query: query))?.repos ?? [] }
    /// Clone a remote repo → local checkout path.
    func cloneForge(connectionId: String, owner: String, name: String) async -> String? {
        (try? await api.cloneForge(connectionId: connectionId, owner: owner, name: name))?.localPath
    }
    /// Create a remote repo on a forge (always private, web parity) then clone → local path.
    func createForge(connectionId: String, name: String) async -> String? {
        (try? await api.createForge(connectionId: connectionId, name: name, isPrivate: true))?.localPath
    }
    /// `git init` a fresh local repo under the projects root → local path.
    func createLocalRepo(_ name: String) async -> String? {
        (try? await api.createLocalRepo(name: name))?.localPath
    }
    func listModels(_ agent: String) async -> [ModelInfo] {
        (try? await api.listModels(agent: agent))?.models ?? []
    }
    /// Agent slash commands for the new-session launcher (no live session yet).
    func previewCommands(_ agent: String, _ workdir: String) async -> [SlashCommand] {
        (try? await api.previewCommands(agent: agent, workdir: workdir))?.commands ?? []
    }

    func models(_ id: String) async -> ModelsResponse? { try? await api.models(id: id) }
    func switchModel(_ id: String, _ model: String) {
        Task { [api] in try? await api.switchModel(id: id, model: model) }
    }
    func reasoning(_ id: String) async -> ReasoningResponse? { try? await api.reasoningLevels(id: id) }
    /// GET /reasoning-levels?agent=&model= — thinking levels for the launcher (no session yet).
    func reasoningLevels(_ agent: String, _ model: String?) async -> ReasoningResponse? {
        try? await api.getReasoningLevels(agent: agent, model: model)
    }
    func switchReasoning(_ id: String, _ level: String) {
        Task { [api] in try? await api.switchReasoning(id: id, level: level) }
    }

    func archived() async -> [ArchivedDto] { (try? await api.archived()) ?? [] }
    func resume(_ id: String) { Task { [api] in try? await api.resume(id: id) } }
    func reorderSessions(_ orderedIds: [String]) {
        // Optimistic sort_order so List.onMove doesn't snap back (PATCH has no WS broadcast).
        let order = Dictionary(uniqueKeysWithValues: orderedIds.enumerated().map { ($1, $0) })
        sessions = sessions.map { s in
            guard let i = order[s.id] else { return s }
            return s.doCopy(
                id: s.id, name: s.name, workdir: s.workdir, agent: s.agent,
                status: s.status, mute: s.mute, connected: s.connected,
                model: s.model, reasoningLevel: s.reasoningLevel,
                repo_root: s.repo_root, role: s.role, session_branch: s.session_branch,
                git: s.git, finish_job: s.finish_job,
                userStatus: s.userStatus, sortOrder: Int32(i), draftPayload: s.draftPayload)
        }
        Task { [api] in try? await api.reorderSessions(orderedIds: orderedIds) }
    }
    func createDraft(
        workdir: String,
        agent: String,
        model: String?,
        text: String,
        name: String? = nil,
        reasoningLevel: String? = nil
    ) async -> String? {
        let payload = DraftPayloadDto(text: text, attachments: nil)
        let req = SpawnRequest(
            workdir: workdir,
            name: name,
            agent: agent,
            model: model,
            worktree: nil,
            baseBranch: nil,
            reasoningLevel: reasoningLevel,
            userStatus: "draft",
            draftPayload: payload
        )
        return try? await api.spawn(req: req).id
    }
    func archivedLogs(_ id: String) async -> [LogEntry] { (try? await api.archivedLogs(sessionId: id)) ?? [] }

    /// Lazily fetch a session's transcript when we don't already have it. The WS `snapshot`
    /// (sent on connect) seeds `messages` for every session live at connect time, and
    /// `message_append` keeps them current — but a session resumed from archive arrives via a
    /// `session_added` frame, which carries NO history, so its transcript stays empty until the
    /// next reconnect/snapshot (e.g. an app restart). Calling this on chat-open closes that gap.
    /// Web parity: `ChatView.vue` `loadMessages()` fetches GET /sessions/:id/messages when its
    /// store has nothing for the session — and `archivedLogs` hits that same endpoint, which
    /// serves any session, live or archived.
    func ensureMessagesLoaded(_ sessionId: String) async {
        guard messages[sessionId]?.isEmpty ?? true else { return }
        let fetched = await archivedLogs(sessionId)
        // Re-check after the await: a live `message_append`, an optimistic send, or a fresh
        // snapshot may have populated the buffer while the fetch was in flight — don't clobber
        // newer state with the historical fetch.
        guard messages[sessionId]?.isEmpty ?? true else { return }
        if !fetched.isEmpty { messages[sessionId] = fetched }
    }

    // Usage (typed), device mint/revoke, proxy privacy — mirror the web pages.
    func usage() async -> UsageResponse? { try? await api.usage() }
    func redeemCodexReset() async -> CodexResetResult? { try? await api.redeemCodexReset() }
    func addDevice(_ name: String) async -> AddDeviceResponse? { try? await api.addDevice(name: name) }
    func revokeDevice(_ name: String) async { try? await api.revokeDevice(name: name) }
    func setProxyPublic(_ domain: String, _ isPublic: Bool) async { try? await api.setProxyPublic(domain: domain, isPublic: isPublic) }
    func removeProxy(_ domain: String) async { try? await api.removeProxy(domain: domain) }

    // Personal Assistants.
    func pas() async -> [PADto] { (try? await api.listPAs()) ?? [] }
    func createPA(name: String, agent: String?, model: String?, focusText: String?) async {
        try? await api.createPA(name: name, agent: agent, model: model, focusText: focusText)
    }

    func config() async -> AppConfigDto? { try? await api.getConfig() }
    func setPAName(_ name: String) { Task { [api] in try? await api.putConfig(paName: name) } }

    /// PUT /settings/config — partial patch. All fields are optional; pass nil to leave
    /// unchanged. An empty-string `voiceCleanupModel` ("") resets the model to the
    /// engine's default (the broker's reset sentinel).
    func saveConfig(onboarded: Bool? = nil, paName: String? = nil,
                    voiceSttEngine: String? = nil,
                    voiceCleanupModel: String? = nil,
                    voiceCleanupEngine: String? = nil,
                    claudeOauthToken: String? = nil, anthropicApiKey: String? = nil,
                    codexApiKey: String? = nil, cursorApiKey: String? = nil) async {
        try? await api.saveConfig(onboarded: onboarded?.kb,
                                  paName: paName,
                                  voiceSttEngine: voiceSttEngine,
                                  voiceCleanupModel: voiceCleanupModel,
                                  voiceCleanupEngine: voiceCleanupEngine,
                                  claudeOauthToken: claudeOauthToken, anthropicApiKey: anthropicApiKey,
                                  codexApiKey: codexApiKey, cursorApiKey: cursorApiKey)
    }

    // Soul (system prompt / persona markdown).
    func getSoul() async -> String { (try? await api.getSoul()) ?? "" }
    func putSoul(text: String) async -> Bool { (try? await api.putSoul(text: text))?.boolValue ?? false }

    // Agent install status + login flow.
    func agentStatuses() async -> [AgentInstallStatus] { (try? await api.agentStatuses()) ?? [] }
    /// Preserve transport failure separately from a legitimate empty result. Navigation can
    /// cancel a settings request; callers use nil to avoid turning that cancellation into a
    /// misleading host error when the user moves back through onboarding.
    func loadAgentStatuses() async -> [AgentInstallStatus]? { try? await api.agentStatuses() }
    func saveAgentCredential(kind: String, value: String) async -> Bool {
        do {
            switch kind {
            case "claude":
                // KMP → Swift does not surface Kotlin default args; pass voiceSttEngine explicitly.
                try await api.saveConfig(onboarded: nil, paName: nil,
                                         voiceSttEngine: nil,
                                         voiceCleanupModel: nil, voiceCleanupEngine: nil,
                                         claudeOauthToken: value, anthropicApiKey: nil,
                                         codexApiKey: nil, cursorApiKey: nil)
            case "codex":
                try await api.saveConfig(onboarded: nil, paName: nil,
                                         voiceSttEngine: nil,
                                         voiceCleanupModel: nil, voiceCleanupEngine: nil,
                                         claudeOauthToken: nil, anthropicApiKey: nil,
                                         codexApiKey: value, cursorApiKey: nil)
            case "cursor":
                try await api.saveConfig(onboarded: nil, paName: nil,
                                         voiceSttEngine: nil,
                                         voiceCleanupModel: nil, voiceCleanupEngine: nil,
                                         claudeOauthToken: nil, anthropicApiKey: nil,
                                         codexApiKey: nil, cursorApiKey: value)
            default:
                return false
            }
            let statuses = try await api.agentStatuses()
            return statuses.contains { $0.kind == kind && $0.authed }
        } catch {
            return false
        }
    }
    func startAgentInstall(kind: String) async -> AgentInstallJob? { try? await api.startAgentInstall(kind: kind) }
    func agentInstallState(kind: String) async -> AgentInstallJob? { try? await api.agentInstallState(kind: kind) }
    func startAgentLogin(kind: String) async -> AgentLoginState? { try? await api.startAgentLogin(kind: kind) }
    func agentLoginState(kind: String) async -> AgentLoginState? { try? await api.agentLoginState(kind: kind) }
    func sendAgentLoginCode(kind: String, code: String) { Task { [api] in try? await api.sendAgentLoginCode(kind: kind, code: code) } }
    func cancelAgentLogin(kind: String) { Task { [api] in try? await api.cancelAgentLogin(kind: kind) } }

    // opencode providers.
    func openCodeProviders() async -> [OpenCodeProvider] { (try? await api.openCodeProviders()) ?? [] }
    func loadOpenCodeProviders() async -> [OpenCodeProvider]? { try? await api.openCodeProviders() }
    func saveOpenCodeKey(providerId: String, key: String) async -> Bool {
        do {
            try await api.setOpenCodeKey(providerId: providerId, key: key)
            return true
        } catch {
            return false
        }
    }
    func startOpenCodeOAuth(providerId: String, method: Int) async -> OpenCodeOAuthStart? {
        try? await api.startOpenCodeOAuth(providerId: providerId, method: Int32(method))
    }
    func finishOpenCodeAuthorization(providerId: String, method: Int, code: String) async -> Bool {
        do {
            try await api.finishOpenCodeOAuth(providerId: providerId, method: Int32(method), code: code)
            return true
        } catch {
            return false
        }
    }

    // Editor / LSP settings.
    func getEditorSettings() async -> EditorSettingsResponse? { try? await api.getEditorSettings() }
    func setLspEnabled(_ id: String, enabled: Bool) async -> EditorSettingsResponse? {
        try? await api.setLspEnabled(id: id, enabled: enabled)
    }
    func installEditorLsp(_ id: String) async -> LspInstallResult? { try? await api.installEditorLsp(id: id) }
    func addCustomEditorLsp(id: String, label: String, command: String, extensions: [String],
                            args: [String] = [], languageId: String? = nil, installCmd: String? = nil) async -> LspMutationResult? {
        try? await api.addCustomEditorLsp(id: id, label: label, command: command,
                                          extensions: extensions, args: args,
                                          languageId: languageId, installCmd: installCmd)
    }
    func removeCustomEditorLsp(_ id: String) async -> LspMutationResult? { try? await api.removeCustomEditorLsp(id: id) }

    // Forge connections.
    func addForge(kind: String, token: String, host: String? = nil, transport: String) async -> ForgeConnection? {
        try? await api.addForge(kind: kind, token: token, host: host, transport: transport)
    }
    func importForge(kind: String, transport: String) async -> ForgeConnection? {
        try? await api.importForge(kind: kind, transport: transport)
    }
    func removeForge(_ id: String) { Task { [api] in try? await api.removeForge(id: id) } }

    // System.
    func restartBroker() { Task { [api] in try? await api.restartBroker() } }
    func updateStatus() async -> UpdateStatus? { try? await api.updateStatus() }
    /// Trigger the broker's self-update (binary mode). Returns the broker's verdict:
    /// `started` (poll updateStatus for progress), or `error`/`instruction` when the
    /// install can't self-update (source/docker/disabled) or is already busy.
    func runUpdate() async -> RunUpdateResult? { try? await api.runUpdate() }

    func curatorSettings() async -> CuratorSettingsResponse? { try? await api.getCuratorSettings() }
    func saveCurator(enabled: Bool, hour: Int, minute: Int) {
        Task { [api] in _ = try? await api.saveCuratorSettings(enabled: enabled, hour: Int32(hour), minute: Int32(minute)) }
    }
    func runCuratorNow() { Task { [api] in try? await api.runCuratorNow() } }

    /// Load an attachment's bytes (Bearer-authed) — used for inline images.
    func loadFile(_ id: String) async -> Data? {
        guard let url = URL(string: "\(baseURL)/files/\(id)") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try? await URLSession.shared.data(for: req).0
    }

    /// Stream an attachment to a temp file with progress; throws on HTTP/transport failure
    /// (unlike `loadFile`, which silently returns nil). Returns the local file URL. Used by
    /// the file-row download UI so large files report progress and surface errors.
    func downloadFile(_ id: String, name: String,
                      onProgress: @escaping (Double) -> Void) async throws -> URL {
        guard let url = URL(string: "\(baseURL)/files/\(id)") else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(id, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(name)
        return try await FileDownloader(dest: dest, onProgress: onProgress).download(req)
    }

    // MARK: - Dictation transcribe (POST /sessions/:id/transcribe → { text, degraded? })
    // Bearer-authed direct HTTP (same shape as `loadFile`), since there's no shared
    // Kotlin helper for this endpoint. The on-device path POSTs the recognized draft as
    // JSON; the fallback POSTs the recorded clip as multipart (field "audio").

    private struct TranscribeResponse: Decodable { let text: String; let degraded: Bool? }

    /// Cleanup endpoint URL — id-less `/transcribe` when `sessionId` is nil/empty (the session
    /// only enriches cleanup context server-side; it isn't required), else `/sessions/<id>/transcribe`.
    private func transcribeURL(_ sessionId: String?) -> URL? {
        if let id = sessionId, !id.isEmpty {
            return URL(string: "\(baseURL)/sessions/\(id)/transcribe")
        }
        return URL(string: "\(baseURL)/transcribe")
    }

    /// JSON `{ draft }` → cleaned `text`. Used for the on-device-recognition result.
    /// `sessionId` is optional — `nil` (pre-spawn launcher) hits the id-less `/transcribe`.
    func transcribeDraft(sessionId: String?, draft: String) async throws -> String {
        guard let url = transcribeURL(sessionId) else {
            throw URLError(.badURL)
        }
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

    /// Multipart (field "audio") → cleaned `text`. Fallback when on-device recognition
    /// isn't available for the device locale; the broker stores + transcribes the clip.
    /// `sessionId` is optional — `nil` (pre-spawn launcher) hits the id-less `/transcribe`.
    func transcribeAudio(sessionId: String?, data audioData: Data, filename: String) async throws -> String {
        guard let url = transcribeURL(sessionId) else {
            throw URLError(.badURL)
        }
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        let dashes = "--\(boundary)\r\n"
        body.append(dashes.data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"audio\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/mp4\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        let (respData, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(TranscribeResponse.self, from: respData).text
    }

    // MARK: - Voice-cleanup glossary (GET/PUT /config/voice-glossary → { glossary: [String] })
    // Bearer-authed direct HTTP (same shape as transcribeDraft / loadFile) — the glossary of
    // project/technical terms shared across devices: fed broker-side into the codex cleanup
    // prompt AND, on-device, into the recognizer as contextual hints (see SpeechDictation).

    private struct GlossaryResponse: Decodable { let glossary: [String] }

    /// GET the current glossary. Returns the broker's list (default-seeded server-side).
    func fetchGlossary() async throws -> [String] {
        guard let url = URL(string: "\(baseURL)/config/voice-glossary") else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(GlossaryResponse.self, from: data).glossary
    }

    /// PUT the full glossary (body `{ glossary: [...] }`) — persisted broker-side.
    func updateGlossary(_ terms: [String]) async throws {
        guard let url = URL(string: "\(baseURL)/config/voice-glossary") else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["glossary": terms])
        let (_, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
    }

    // MARK: - Editor filesystem (workdir-relative paths)
    func fsList(_ id: String, _ path: String) async -> [FsEntry] { (try? await api.fsList(sessionId: id, path: path)) ?? [] }
    func fsRead(_ id: String, _ path: String) async throws -> String { try await api.fsRead(sessionId: id, path: path) }
    func fsWrite(_ id: String, _ path: String, _ content: String) async -> Bool { (try? await api.fsWrite(sessionId: id, path: path, content: content))?.boolValue ?? false }
    func fsSearch(_ id: String, _ q: String) async -> [FsSearchResult] { (try? await api.fsSearch(sessionId: id, q: q)) ?? [] }

    // MARK: - Editor diff + code review (workdir-relative paths)
    /// `base` is the adjustable diff base spec (session-start | head | commit:<sha> | branch:<name>);
    /// nil = the broker's default (session-start). Target is always the working tree (web parity).
    func fsDiff(_ id: String, base: String? = nil) async -> FsDiffResult? { try? await api.fsDiff(sessionId: id, base: base) }
    /// Branches + recent commits per repo, for the diff base selector (web parity).
    func fsRefs(_ id: String) async -> FsRefsResult? { try? await api.fsRefs(sessionId: id) }
    func reviewAddComment(_ id: String, _ body: AddCommentBody) async { _ = try? await api.reviewAddComment(sessionId: id, body: body) }
    func reviewResolve(_ id: String, _ commentId: String) async {
        _ = try? await api.reviewUpdateComment(sessionId: id, commentId: commentId,
                                               patch: UpdateCommentBody(status: "resolved", body: nil, resolvedBy: "user"))
    }
    func reviewSubmit(_ id: String) async -> ReviewSubmitResult? { try? await api.reviewSubmit(sessionId: id) }

    /// Subscribe / unsubscribe the broker's filesystem watcher for a session's workdir,
    /// driving the editor's "changed on disk" banner via fs_changed frames.
    func editorOpen(_ id: String) { Task { [client] in try? await client.send(frame: ClientFrameEditorOpen(session: id)) } }
    func editorClose(_ id: String) { Task { [client] in try? await client.send(frame: ClientFrameEditorClose(session: id)) } }

    // MARK: - Open a tapped file path (chat message → editor)
    /// A chat-initiated request to bring a session's editor to the front. The nonce makes
    /// each tap a distinct value so `.onChange` observers fire even on a repeat of the same id.
    struct EditorFocusRequest: Equatable { let sessionId: String; let nonce: Int }
    /// Set when a path is tapped → observed by the chat container to surface the editor.
    var editorFocus: EditorFocusRequest?
    /// Transient "couldn't open" message surfaced by the chat container as a banner.
    var editorOpenError: String?

    /// Resolve a tapped path against the session workdir, open it in that session's editor at
    /// the cited line, and ask the UI to bring the editor forward. A path that resolves outside
    /// the workdir surfaces a transient error instead (parity with Android's toast / the web).
    func openFileFromMessage(sessionId: String, workdir: String, ref: FilePathRef) {
        let home = inferHomeDir(workdir: workdir)
        guard let rel = toWorkdirRelativePath(path: ref.path, workdir: workdir, homeDir: home) else {
            editorOpenError = "File is outside this session's project"
            return
        }
        // `ref.line`/`endLine` bridge as boxed `KotlinInt?`; `.intValue` is `Int32`, so map to Swift Int.
        editorState(for: sessionId).openFileAtLine(rel,
                                                   line: ref.line.map { Int($0.intValue) },
                                                   endLine: ref.endLine.map { Int($0.intValue) })
        editorFocus = EditorFocusRequest(sessionId: sessionId, nonce: (editorFocus?.nonce ?? 0) + 1)
    }

    // MARK: - Editor state (one per session, cached so open tabs / tree expansion /
    // scroll survive pane AND session switches — full state preservation is required).
    @ObservationIgnored private var editorStates: [String: EditorState] = [:]
    func editorState(for sessionId: String) -> EditorState {
        if let existing = editorStates[sessionId] { return existing }
        let state = EditorState(
            sessionId: sessionId,
            fsRead: { [weak self] path in
                guard let self else { return "" }
                return try await self.fsRead(sessionId, path)
            },
            fsWrite: { [weak self] path, content in
                guard let self else { return false }
                return await self.fsWrite(sessionId, path, content)
            },
            fsDiff: { [weak self] base in
                guard let self else { return nil }
                return await self.fsDiff(sessionId, base: base)
            },
            fsRefs: { [weak self] in
                guard let self else { return nil }
                return await self.fsRefs(sessionId)
            }
        )
        editorStates[sessionId] = state
        return state
    }

    // MARK: - LSP bridge (one per session; the Swift control-plane + relay for the
    // webview's CodeMirror LSP client — the broker is a dumb JSON-RPC pipe).
    @ObservationIgnored private var lspBridges: [String: LspBridge] = [:]
    func lspBridge(for sessionId: String) -> LspBridge {
        if let existing = lspBridges[sessionId] { return existing }
        let bridge = LspBridge(sessionId: sessionId, send: { [client] frame in
            Task { try? await client.send(frame: frame) }
        })
        lspBridges[sessionId] = bridge
        return bridge
    }

    // MARK: - Terminal hosts (one per (session, kind, terminalId); the live websocket +
    // SwiftTerm emulator view, cached so the connection AND on-screen scrollback survive
    // SwiftUI remounts / pane toggles. Sessions are bounded, so no LRU — just don't leak
    // on kill (sessionRemoved) or shell exit (dropTerminalHost).
    @ObservationIgnored private var terminalHosts: [String: TerminalHost] = [:]
    private func terminalHostKey(_ sessionId: String, _ kind: String, _ terminalId: String?) -> String {
        "\(sessionId)|\(kind)|\(terminalId ?? "agent")"
    }
    func terminalHost(sessionId: String, kind: String, terminalId: String?) -> TerminalHost {
        let key = terminalHostKey(sessionId, kind, terminalId)
        if let existing = terminalHosts[key] { return existing }
        let host = TerminalHost(broker: self, sessionId: sessionId, kind: kind, terminalId: terminalId)
        terminalHosts[key] = host
        return host
    }
    /// Drop a single cached host (shell exited → a new tab with a new id should build fresh).
    func dropTerminalHost(sessionId: String, kind: String, terminalId: String?) {
        let key = terminalHostKey(sessionId, kind, terminalId)
        terminalHosts.removeValue(forKey: key)?.stop()
    }
    /// Tear down ALL of a session's cached terminals (its hosts are keyed "\(id)|…").
    private func evictTerminalHosts(sessionId: String) {
        let prefix = "\(sessionId)|"
        for key in terminalHosts.keys.filter({ $0.hasPrefix(prefix) }) {
            terminalHosts.removeValue(forKey: key)?.stop()
        }
    }

    // MARK: - Editor hosts (one per session.id; the live CodeMirror WKWebView + its bridge
    // coordinator, cached so the loaded page AND the open document survive SwiftUI remounts /
    // editor-pane toggles — that's what removes the reload/white-flash. Bounded by sessions,
    // so no LRU — just don't leak on kill (sessionRemoved → stop() removes the script-message
    // handlers, breaking the webView↔coordinator retain cycle).
    @ObservationIgnored private var editorHosts: [String: EditorHost] = [:]
    func editorHost(for sessionId: String) -> EditorHost {
        if let existing = editorHosts[sessionId] { return existing }
        let host = EditorHost()
        editorHosts[sessionId] = host
        return host
    }
    /// Drop a session's cached editor host and tear down its bridge (stop() removes the
    /// script-message handlers so the webview↔coordinator cycle doesn't leak).
    private func dropEditorHost(sessionId: String) {
        editorHosts.removeValue(forKey: sessionId)?.stop()
    }

    // MARK: - Display hosts (one per stream.id; the live VNC/scrcpy websocket + the native
    // surface — framebuffer texture / video decoder — cached so the connection AND the
    // rendered picture survive Display-pane toggles / session switches / management remounts.
    // The cached `sessionName` lets us evict a killed session's streams even after they've
    // dropped out of `displays`. Bounded by running streams, so no LRU — just don't leak on
    // display stop (displayRemoved) or session kill (sessionRemoved).
    @ObservationIgnored private var displayHosts: [String: (host: DisplayHost, sessionName: String)] = [:]
    /// Lazily build + cache the host for a stream (transport picked inside `DisplayHost.make`,
    /// matching `DisplayStreamView`'s branch). Keyed by `stream.id`, so the chat Display tab and
    /// the management viewer share one warm stream.
    func displayHost(for stream: DisplayStream) -> DisplayHost {
        if let existing = displayHosts[stream.id] { return existing.host }
        let host = DisplayHost.make(broker: self, stream: stream)
        displayHosts[stream.id] = (host, stream.sessionName)
        return host
    }
    /// Drop a single cached display host (e.g. its stream errored/stopped → rebuild fresh).
    func dropDisplayHost(streamId: String) {
        displayHosts.removeValue(forKey: streamId)?.host.stop()
    }
    /// Tear down ALL cached display hosts bound to a session name (its streams die with it).
    private func evictDisplayHosts(sessionName: String) {
        let ids = displayHosts.filter { $0.value.sessionName == sessionName }.map(\.key)
        for id in ids {
            displayHosts.removeValue(forKey: id)?.host.stop()
        }
    }
}

/// Tiny reference relay so `BrokerSession.init` can hand the shared `BrokerClient` a connection
/// callback that captures NO `self` (self isn't fully initialized while its `client` stored property
/// is being set). `onChange` is wired to the session at the end of init; the Kotlin client invokes
/// `report` off the main actor, which forwards to `onChange` (which hops back to main).
private final class ConnectionFlag {
    var onChange: ((Bool) -> Void)?
    func report(_ up: Bool) { onChange?(up) }
}

private extension Bool {
    /// Box for SKIE-bridged Kotlin `Boolean?` parameters.
    var kb: KotlinBoolean { KotlinBoolean(bool: self) }
}
