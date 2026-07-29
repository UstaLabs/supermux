import Foundation
import Shared

/// The result of an add-host claim (mirrors Android's `AddHostResult`).
enum AddHostResult {
    /// Claimed + persisted — the new fleet member.
    case added(PairedHost)
    /// A typed URL pointed at a real supermux broker that's already set up: it can't be claimed
    /// without a one-time secret, so tell the user to mint a pairing link on the host.
    case needsClaim(HostIdentity)
    /// User-facing failure (bad link, unreachable host, identity mismatch, rejected claim).
    case error(String)
}

/// Multi-host coordinator (spec §5) — the iOS mirror of Android's `HostConnections` + the multi-host
/// `AppViewModel`. Owns ONE `BrokerSession` (a `BrokerApi` + control WS + per-session state) per
/// paired host from the shared `PairedHostStore`, and folds them into a merged, recordId-tagged
/// session list that feeds the fleet list. Session ids are globally unique across hosts, so the
/// per-host state never collides; per-session actions route to the OWNING host's `BrokerSession`
/// (the same object the detail/chat views already drive), host-global actions to the active host.
///
/// The list logic + migration live in the shared `PairedHostStore`; this class only owns the
/// connections + the merge + host-filter/last-used persistence. Single-host (one paired host) is the
/// unchanged path: `multiHost` is false, no badges/filter, the one `BrokerSession` behaves as before.
@MainActor
@Observable
final class Fleet {
    private let store: PairedHostStore

    /// Snapshot of the store's host list, in order (drives chips + row ordering). Re-read on every
    /// store mutation via `reloadHosts()`.
    private(set) var hosts: [PairedHost] = []
    /// One live connection per reachable host, keyed by recordId (insertion follows store order).
    private(set) var brokers: [String: BrokerSession] = [:]
    /// recordId of the host that host-global ops target (spawn/agents/settings) — the "last used"
    /// host, persisted. Also the new-session picker's default.
    private(set) var activeRecordId: String?
    /// Host filter recordId, or nil for "All" (persisted). Only meaningful when `multiHost`.
    private(set) var filter: String?

    /// The (url|token) each broker was opened with, so `sync()` can rebuild one whose creds changed.
    @ObservationIgnored private var connKeys: [String: String] = [:]

    private static let activeKey = "fleet_active_host"
    private static let filterKey = "fleet_host_filter"

    init(store: PairedHostStore = HostStore.shared) {
        self.store = store
        // Self-heal migration: if the store is empty but the app is paired (the user paired THIS
        // launch, after SupermuxApp's app-init migration already ran and found nothing), seed
        // PairedHost[0] from the legacy BrokerConfig now so the fleet has its first host.
        if store.list().isEmpty { HostStore.migrateFromLegacyIfNeeded() }
        hosts = store.list()
        activeRecordId = UserDefaults.standard.string(forKey: Self.activeKey)
            .flatMap { rid in hosts.first { $0.recordId == rid }?.recordId } ?? hosts.first?.recordId
        filter = UserDefaults.standard.string(forKey: Self.filterKey)
            .flatMap { rid in rid.isEmpty ? nil : hosts.first { $0.recordId == rid }?.recordId }
    }

    // MARK: - Derived, merged state (all reactive — read through @Observable brokers)

    var multiHost: Bool { hosts.count >= 2 }

    /// The merged session list across every host, in store host order.
    var sessions: [SessionInfo] { hosts.flatMap { brokers[$0.recordId]?.sessions ?? [] } }

    /// sessionId → owning host recordId (per-row badges + per-session routing).
    var sessionHost: [String: String] {
        var m: [String: String] = [:]
        for h in hosts { for s in brokers[h.recordId]?.sessions ?? [] { m[s.id] = h.recordId } }
        return m
    }

    /// The fleet's hosts as the list renders them (identity + live reachability + color slot),
    /// via the shared `HostView` so badge colors/labels match Android exactly.
    var hostViews: [HostView] {
        hosts.map { h in
            HostView(recordId: h.recordId, hostId: h.hostId, displayName: h.displayName,
                     online: brokers[h.recordId]?.online ?? false, lastSeenAt: h.lastSeenAt)
        }
    }

    /// True once any host has its first snapshot — drives the "Connecting…" overlay.
    var synced: Bool { brokers.values.contains { $0.synced } }

    /// The active host's connection (host-global ops target); falls back to the first/any host.
    var activeBroker: BrokerSession? {
        if let a = activeRecordId, let b = brokers[a] { return b }
        return hosts.first.flatMap { brokers[$0.recordId] } ?? brokers.values.first
    }

    func broker(forRecord recordId: String?) -> BrokerSession? {
        guard let recordId else { return nil }
        return brokers[recordId]
    }

    /// sessionId → newest message timestamp, for every LIVE session, built in ONE pass.
    ///
    /// This is the `lastTs` source for `buildTaskSections` / `groupSessions` (Settled ordering).
    /// Resolving it per session through `broker(for:)` re-scanned every host's session array on
    /// each lookup — and always missed for archived rows, which no host owns — so the sidebar
    /// paid a full fleet scan per lookup to produce an empty string. One pass, then dictionary
    /// hits. Archived sessions are absent by construction and fall back to "" as before.
    func lastMessageTsBySession() -> [String: String] {
        var m: [String: String] = [:]
        for h in hosts {
            guard let b = brokers[h.recordId] else { continue }
            for s in b.sessions {
                if let ts = b.messages[s.id]?.last?.ts { m[s.id] = ts }
            }
        }
        return m
    }

    /// The host that owns a session (the one whose live list contains it).
    func broker(for sessionId: String) -> BrokerSession? {
        for h in hosts {
            if let b = brokers[h.recordId], b.sessions.contains(where: { $0.id == sessionId }) { return b }
        }
        return nil
    }

    /// Sessions after the host filter (spec §5) — the shared `filterSessions`, so the semantics
    /// match Android. Single-host / All → every session.
    var filteredSessions: [SessionInfo] {
        guard multiHost else { return sessions }
        return FleetModelKt.filterSessions(sessions: sessions, sessionHost: sessionHost, filter: filter)
    }

    /// PWA-identical workdir grouping of the ONLINE hosts' filtered sessions (offline hosts render
    /// in their own greyed groups — see `offlineHostGroups`). Single-host: the usual grouping.
    func onlineGroups() -> [SessionGroup] {
        let owner = sessionHost
        let offline = Set(hostViews.filter { !$0.online }.map { $0.recordId })
        let shown = filteredSessions
        let online = multiHost ? shown.filter { !offline.contains(owner[$0.id] ?? "") } : shown
        return group(online)
    }

    /// (host, its cached filtered sessions) for each OFFLINE host passing the current filter — the
    /// greyed "last seen" groups (spec §5). The host's `BrokerSession` retains its last snapshot
    /// across a dropped socket, so its sessions still show (dimmed) until it reconnects.
    func offlineHostGroups() -> [(host: HostView, sessions: [SessionInfo])] {
        guard multiHost else { return [] }
        let owner = sessionHost
        let shown = filteredSessions
        return hostViews
            .filter { !$0.online && (filter == nil || filter == $0.recordId) }
            .map { hv in (hv, shown.filter { owner[$0.id] == hv.recordId }) }
    }

    /// Archived sessions for the active host, folded into Settled on the task list.
    private(set) var archivedForList: [ArchivedDto] = []

    func refreshArchived() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard let b = self.activeBroker else { return }
            self.archivedForList = await b.archived()
        }
    }

    private func group(_ ss: [SessionInfo]) -> [SessionGroup] {
        let home = inferHomeDir(workdir: ss.first?.workdir) ?? ""
        // One pass up front: `groupSessions` calls `buildTaskSections` once PER project group,
        // so a fleet-scanning closure here is paid over and over. See `lastMessageTsBySession`.
        let ts = lastMessageTsBySession()
        return groupSessions(sessions: ss, home: home, lastTs: { ts[$0.id] ?? "" },
                             archived: archivedForList)
    }

    // MARK: - Selection / filter (persisted)

    func setActive(_ recordId: String) {
        guard hosts.contains(where: { $0.recordId == recordId }) else { return }
        activeRecordId = recordId
        UserDefaults.standard.set(recordId, forKey: Self.activeKey)
    }

    func setFilter(_ recordId: String?) {
        filter = recordId
        UserDefaults.standard.set(recordId ?? "", forKey: Self.filterKey)
    }

    // MARK: - Lifecycle

    /// Open + start a connection for every reachable paired host (idempotent).
    func start() { sync(); refreshArchived() }

    /// Tear down every connection (RootView leaves the hierarchy on unpair / re-pair recreation).
    func stop() {
        for (_, b) in brokers { b.onConnectionChanged = nil; b.stop() }
        brokers.removeAll()
        connKeys.removeAll()
    }

    /// Re-read the store and reconcile connections — called after add-host / forget-host.
    func refresh() {
        reloadHosts()
        sync()
    }

    /// Reconcile live `BrokerSession`s against the store's hosts (mirrors Android `HostConnections.sync`):
    /// open one for each newly-added reachable host, close each removed host, rebuild one whose URL or
    /// token changed. Hosts with a blank token or no reachable URL stay in the list but aren't dialed.
    private func sync() {
        reloadHosts()
        let wanted = hosts.compactMap { h -> (PairedHost, String)? in
            guard let url = Self.effectiveUrl(h), !h.token.isEmpty else { return nil }
            return (h, url)
        }
        let wantedIds = Set(wanted.map { $0.0.recordId })
        for rid in brokers.keys where !wantedIds.contains(rid) { close(rid) }
        for (h, url) in wanted {
            let key = "\(url)|\(h.token)"
            if brokers[h.recordId] == nil {
                open(recordId: h.recordId, url: url, token: h.token, key: key)
            } else if connKeys[h.recordId] != key {
                close(h.recordId)
                open(recordId: h.recordId, url: url, token: h.token, key: key)
            }
        }
    }

    private func open(recordId: String, url: String, token: String, key: String) {
        let broker = BrokerSession(baseURL: url, token: token)
        broker.onConnectionChanged = { [weak self, weak broker] up in
            guard let self, let broker else { return }
            if up {
                // Learn the durable hostId + platform/version once reachable (color stability +
                // dedupe), and mark it seen. Best-effort — a pre-Plan-1 broker just stays hostId-less.
                self.store.updateSeen(recordId: recordId, at: Self.nowMs())
                self.backfillIdentity(recordId: recordId, broker: broker)
            } else {
                // Record the drop moment so the offline group shows an accurate "last seen".
                self.store.updateSeen(recordId: recordId, at: Self.nowMs())
                self.reloadHosts()
            }
        }
        brokers[recordId] = broker
        connKeys[recordId] = key
        broker.start()
    }

    private func close(_ recordId: String) {
        if let b = brokers.removeValue(forKey: recordId) {
            b.onConnectionChanged = nil
            b.stop()
        }
        connKeys.removeValue(forKey: recordId)
    }

    private func backfillIdentity(recordId: String, broker: BrokerSession) {
        Task { @MainActor [weak self, weak broker] in
            guard let self, let broker,
                  let identity = try? await broker.api.getHost(), !identity.hostId.isEmpty else { return }
            let current = self.hosts.first(where: { $0.recordId == recordId })
            let displayName = Self.identityDisplayName(identity.name, for: current)
            // Also repair names written by older desktop builds even when hostId is already known.
            if current?.hostId != identity.hostId ||
                FleetModelKt.isLegacyHostDisplayName(displayName: current?.displayName ?? "") {
                self.store.backfillHostIdentity(
                    recordId: recordId,
                    hostId: identity.hostId,
                    displayName: displayName
                )
                self.reloadHosts()
            }
        }
    }

    private func reloadHosts() {
        hosts = store.list()
        // Keep active/filter pointing at hosts that still exist.
        if let a = activeRecordId, !hosts.contains(where: { $0.recordId == a }) {
            activeRecordId = hosts.first?.recordId
        } else if activeRecordId == nil { activeRecordId = hosts.first?.recordId }
        if let f = filter, !hosts.contains(where: { $0.recordId == f }) { setFilter(nil) }
    }

    // MARK: - Viewing presence (routed to the owning host)

    /// Report the foreground chat + visibility to the OWNING host (so it suppresses that chat's
    /// push), and tell the other hosts we're not viewing any of their chats.
    func updateViewing(session: String?, visible: Bool) {
        let owner = session.flatMap { broker(for: $0) }
        for b in brokers.values {
            if b === owner { b.updateViewing(session: session, visible: visible) }
            else { b.updateViewing(session: nil, visible: visible) }
        }
    }

    // MARK: - Add / forget hosts

    /// Accept both current structured claims and pre-multi-host `/pair?t=…` device-token URLs.
    func claim(raw: String, deviceName: String) async -> AddHostResult {
        if let payload = PairingPayload.companion.parse(raw: raw) {
            return await claim(payload: payload, deviceName: deviceName)
        }
        guard let legacy = PairToken.parse(raw) else {
            return .error("That isn't a valid supermux pairing link — scan or paste the complete QR value.")
        }
        return await claim(legacy: legacy)
    }

    /// Current one-time claim path. The returned identity must match the QR exactly.
    private func claim(payload: PairingPayload, deviceName: String) async -> AddHostResult {
        let isRelay = !(payload.relayUrl ?? "").isEmpty
        guard let url = Self.payloadUrl(payload), !url.isEmpty else {
            return .error("That pairing link has no host URL.")
        }
        let api = BrokerApi(baseUrl: url, token: "", http: IosClientKt.iosHttpClient())
        do {
            let result = try await api.pairClaim(claimSecret: payload.claimSecret, deviceName: deviceName)
            // Anti-MITM (spec §3.4): the broker that answered must prove it is the scanned host —
            // require an EXACT, non-empty hostId match (a missing/blank returned id is a failure).
            guard let returned = result.host?.hostId, !returned.isEmpty, returned == payload.hostId else {
                return .error("This link is for a different host than the one that answered — not adding it.")
            }
            guard !result.deviceToken.isEmpty else {
                return .error("The host didn't return a device token.")
            }
            // addOrUpdate: re-adding a host already in the fleet refreshes it in place, never duplicates.
            let added = store.addOrUpdate(
                displayName: payload.name.isEmpty ? "Host" : payload.name,
                token: result.deviceToken,
                relayUrl: isRelay ? url : nil,
                directUrl: isRelay ? nil : url,
                hostId: payload.hostId,
                platform: result.host?.platform,
                version: result.host?.version)
            refresh()
            return .added(added)
        } catch {
            return .error("Couldn't reach the host or the claim was rejected.")
        }
    }

    /// Legacy QR path. Old brokers already minted the bearer embedded in `/pair?t=…` and do not
    /// implement `/pair/claim` (or usually `/host`), so validate with their native auth probes and
    /// store the token directly. Identity enrichment remains best-effort for newer brokers.
    private func claim(legacy: PairToken) async -> AddHostResult {
        let api = BrokerApi(baseUrl: legacy.baseURL, token: legacy.token, http: IosClientKt.iosHttpClient())
        let pairResult = try? await api.pairJson(token: legacy.token)
        var valid = pairResult?.token == legacy.token
        if !valid, let me = try? await api.me() { valid = me.paired }
        guard valid else {
            return .error("The host rejected this pairing token. Check that it is reachable and generate a fresh QR if needed.")
        }

        let identity = try? await api.getHost()
        let identityName = identity?.name ?? ""
        let identityHostId = identity?.hostId ?? ""
        let name = identityName.isEmpty ? Self.legacyHostDisplayName(legacy.baseURL) : identityName
        let relay = Self.isRelayHostUrl(legacy.baseURL)
        let added = store.addOrUpdate(
            displayName: name,
            token: legacy.token,
            relayUrl: relay ? legacy.baseURL : nil,
            directUrl: relay ? nil : legacy.baseURL,
            hostId: identityHostId.isEmpty ? nil : identityHostId,
            platform: identity?.platform,
            version: identity?.version)
        refresh()
        return .added(added)
    }

    /// Typed-URL path for Tailscale/VPN/reverse-proxy users: confirm it's a supermux broker
    /// (GET /host); if it's already set up, tell the user to mint a pairing link on the host.
    func claimByUrl(url rawUrl: String, deviceName: String) async -> AddHostResult {
        let url = Self.normalizeUrl(rawUrl)
        guard !url.isEmpty else { return .error("Enter a host URL.") }
        let api = BrokerApi(baseUrl: url, token: "", http: IosClientKt.iosHttpClient())
        do {
            let identity = try await api.getHost()
            guard !identity.hostId.isEmpty else {
                return .error("That URL didn't answer as a supermux broker.")
            }
            return .needsClaim(identity)
        } catch {
            return .error("Couldn't reach a supermux broker at that URL.")
        }
    }

    /// Drop a host from the fleet (local revoke): stop its connection + remove it from the store.
    func forgetHost(recordId: String) {
        close(recordId)
        store.remove(recordId: recordId)
        refresh()
    }

    // MARK: - Helpers

    private static func effectiveUrl(_ h: PairedHost) -> String? {
        if let r = h.relayUrl, !r.isEmpty { return r }
        if let d = h.directUrl, !d.isEmpty { return d }
        return nil
    }

    private static func payloadUrl(_ p: PairingPayload) -> String? {
        if let r = p.relayUrl, !r.isEmpty { return r }
        if let d = p.directUrl, !d.isEmpty { return d }
        return nil
    }

    private static func normalizeUrl(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t.hasPrefix("http://") || t.hasPrefix("https://") { return t }
        return "https://" + t
    }

    private static func legacyHostDisplayName(_ url: String) -> String {
        guard let host = URL(string: url)?.host, !host.isEmpty else { return "Host" }
        return host
    }

    private static func isRelayHostUrl(_ url: String) -> Bool {
        guard let host = URL(string: url)?.host?.lowercased() else { return false }
        return host == "relay.supermux.dev" || host.hasSuffix(".relay.supermux.dev")
    }

    private static func identityDisplayName(_ advertised: String, for host: PairedHost?) -> String {
        #if os(macOS)
        if let direct = host?.directUrl?.lowercased(),
           direct.hasPrefix("http://127.0.0.1:") || direct.hasPrefix("http://localhost:") {
            return MacBrokerSidecar.localHostDisplayName()
        }
        #endif
        return advertised
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
