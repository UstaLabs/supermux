import SwiftUI
import Shared
import PhotosUI
import UniformTypeIdentifiers

#if os(macOS)
private struct MacProjectPickerAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>?
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}
#endif

/// Compose-first launcher — mirrors the web SessionLauncherView: centered
/// "Let's build", a project dropdown, and a compose card (agent picker + the
/// first message) that spawns the session and sends that message on ↑.
struct NewSessionView: View {
    /// The host-global connection the launcher spawns on — the fleet's ACTIVE host, resolved and
    /// passed by `RootView`. Picking a different host in the host pill (multi-host) updates
    /// `fleet`'s active record, and `RootView` re-passes the new host's broker.
    let broker: BrokerSession
    let fleet: Fleet
    var onSpawned: (String) -> Void

    /// Broker-known project paths (unordered). UI order is derived via recency below.
    @State private var knownProjects: [String] = []
    @State private var workdir: String
    /// User explicitly chose a path (or restored a draft with one) — freezes recency follow.
    @State private var workdirTouched: Bool
    @State private var agent: String
    @State private var model: String?
    @State private var models: [ModelInfo] = []
    // Thinking-level picker (web LauncherEffortPicker parity) — levels come from the broker's
    // session-less /reasoning-levels, refetched on agent/model change; hidden when the agent
    // offers no real choice. `reasoningLevel` is what spawn() sends.
    @State private var reasoningLevels: [ReasoningLevel] = []
    @State private var reasoningLevel: String?
    @State private var reasoningVisible = false
    @State private var projectSearch = false
    @State private var launcherCommands: [SlashCommand] = []
    @State private var spawning = false
    @State private var spawnFailed = false
    // Worktree / base-branch (web LauncherWorktreePicker parity) — shown only when
    // the selected project is an eligible git repo.
    @State private var repoInfo: RepoInfo?
    @State private var useWorktree: Bool
    @State private var baseBranch: String
    @State private var worktreeSheet = false
    @State private var worktreeFetching = false
    @State private var fetchedRepos: Set<String> = []
    // New Session draft persistence (survives navigation + relaunch). `agent`/`workdir`/
    // `useWorktree`/`baseBranch`/the composer's draft are seeded synchronously in `init` below,
    // not in a later `.task { }` — an on-device repro (see git history for this file) proved that
    // restoring inside an async `.task` leaves a real window where a plain default value is
    // visible to other effects before the restore lands, and no runtime flag reliably closed it:
    // `.task(id:)` was observed to spin up a *second* instance for the exact same settled id
    // milliseconds after the first (not just an intermediate different id), which a one-shot
    // "have I run once" flag can't tell apart from a genuine later change. Seeding in `init`
    // removes the default-value window entirely — there is no render at which `agent`/`workdir`
    // ever hold anything but the already-resolved value. `lastSeenAgent`/`lastSeenWorkdir` then
    // only need to answer "did this actually change to something *different*", which is safe
    // under a duplicate same-id re-invocation regardless of ordering (see the two guarded
    // `.task(id:)` blocks below).
    @State private var launcherState: LauncherStateStore
    @State private var lastSeenAgent: String?
    @State private var lastSeenWorkdir: String?
    @State private var lastSeenBrokerURL: String?
    @State private var lastRepoBrokerURL: String?
    @FocusState private var composing: Bool
    // Wired in `.task` (below) once `broker` is in scope — the real glossary/transcribe closures
    // need it, which isn't available yet here in `init`.
    @State private var composer: ComposerModel
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showCamera = false
    @State private var showVideoCamera = false
    @State private var photoItems: [PhotosPickerItem] = []
    /// Transient composer status ("Didn't catch that" / "Transcription failed") — chat has a
    /// teal banner; the launcher uses a short-lived line above the compose card.
    @State private var composerStatus: String?
    // Installed agent kinds for the ACTIVE host (GET /agents/status) — replaces the hardcoded four
    // so the picker only offers what THAT host actually has (spec §5). Reloaded on host switch; the
    // literal list is the fallback until the fetch lands / when the host doesn't answer.
    @State private var hostAgents: [String] = []

    private static let agents = ["claude", "codex", "cursor", "opencode", "grok"]
    private var agents: [String] { hostAgents.isEmpty ? Self.agents : hostAgents }
    /// The active host as the picker renders it (dot + name), or nil in single-host mode.
    private var activeHost: HostView? { fleet.hostViews.first { $0.recordId == fleet.activeRecordId } }

    /// When non-nil, launcher reopens this draft (web /new?draft= parity).
    private let reopenDraft: SessionInfo?

    init(broker: BrokerSession, fleet: Fleet, draft: SessionInfo? = nil, onSpawned: @escaping (String) -> Void) {
        self.broker = broker
        self.fleet = fleet
        self.onSpawned = onSpawned
        self.reopenDraft = draft
        // Seed every persisted field synchronously, at construction — before the very first
        // render — so there is never a moment where `agent`/`workdir`/etc hold a plain default
        // that a guarded `.task(id:)` effect downstream could see and act on. See the note above
        // the @State declarations for why an async restore inside `.task { }` couldn't guarantee
        // that on its own.
        let store = LauncherStateStore()
        let restoredAgent = store.prefs.agent
        // Validate against the known agent list — web's loadPrefs() does the same
        // (SessionLauncherView.vue:126) — so a future agent type added after this prefs blob was
        // written can't leave `agent` holding a value the Menu below has no matching row for.
        var resolvedAgent = Self.agents.contains(restoredAgent) ? restoredAgent : "claude"
        if let da = draft?.agent, Self.agents.contains(da) { resolvedAgent = da }
        _launcherState = State(initialValue: store)
        _agent = State(initialValue: resolvedAgent)
        _model = State(initialValue: draft?.model ?? store.prefs.models[resolvedAgent])
        _reasoningLevel = State(initialValue: draft?.reasoningLevel ?? store.prefs.reasoningLevels[resolvedAgent])
        let restoredWorkdir = draft?.workdir ?? store.draft.workdir ?? ""
        _workdir = State(initialValue: restoredWorkdir)
        // Web/Android: restoring a draft path freezes recency so we don't overwrite it.
        _workdirTouched = State(initialValue: !restoredWorkdir.isEmpty)
        _useWorktree = State(initialValue: store.draft.useWorktree)
        _baseBranch = State(initialValue: store.draft.baseBranch)
        // The real glossary/transcribe closures need `broker`, wired in `.task` once the view
        // has appeared; this placeholder context only needs to seed the restored draft text.
        // Server draft_payload wins when reopening a task-list draft.
        _composer = State(initialValue: ComposerModel(
            context: ComposerContext(glossary: { [] }, cleanupTranscript: nil, audioFallbackTranscribe: nil),
            initialDraft: draft?.draftPayload?.text ?? store.draft.text
        ))
    }

    /// Distinct project paths from sessions, most-recently-active first (message timestamp).
    /// Shared KMP `DefaultProject` — same order web/Android use for the launcher default.
    private var recentProjectPaths: [String] {
        let sorted = sessionsByRecency(sessions: broker.sessions) { s in
            broker.messages[s.id]?.last?.ts ?? ""
        }
        return recentWorkdirs(sessionsNewestFirst: sorted)
    }

    /// Picker list: recently-active projects first, then remaining known projects.
    private var projects: [String] {
        orderProjectsByRecency(recent: recentProjectPaths, known: knownProjects)
    }

    /// True once the user is typing / attaching — freezes recency follow (web composeStarted).
    private var isComposing: Bool {
        !composer.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !composer.pending.isEmpty
    }

    /// Fingerprint of recency order so SwiftUI can re-run default selection as messages hydrate.
    private var recencyKey: String {
        recentProjectPaths.joined(separator: "\u{1e}")
    }

    private func applyDefaultProject() {
        workdir = chooseDefaultProject(
            current: workdir,
            recent: recentProjectPaths,
            picked: workdirTouched,
            composing: isComposing
        )
    }

    private func pickWorkdir(_ path: String) {
        workdirTouched = true
        workdir = path
    }

    var body: some View {
        Group {
            #if os(macOS)
            GeometryReader { proxy in
                ScrollView {
                    launcherContent
                        .frame(minHeight: proxy.size.height, alignment: .center)
                }
            }
            #else
            ScrollView { launcherContent }
            #endif
        }
        .navigationTitle("New session").smInlineNavigationTitle()
        .tint(Theme.teal)
        // Keyed on the active host (baseURL) so a host switch re-wires the composer context and
        // reloads that host's projects + installed agents (spec §5). Single-host: runs once on appear.
        .task(id: broker.baseURL) {
            let switchedHost = lastSeenBrokerURL != nil && lastSeenBrokerURL != broker.baseURL
            lastSeenBrokerURL = broker.baseURL
            if switchedHost {
                model = nil
            }
            // Never show or submit host A's cached choices while host B is loading.
            knownProjects = []
            hostAgents = []
            models = []
            reasoningLevels = []
            reasoningVisible = false
            launcherCommands = []
            repoInfo = nil
            fetchedRepos = []
            // No session yet (pre-spawn launcher): the broker's id-less /transcribe cleans the
            // draft off the global glossary/engine/model — the same AI correction the chat
            // composer gets, just without prior-message context.
            composer.setContext(ComposerContext(
                glossary: { (try? await broker.fetchGlossary()) ?? [] },
                cleanupTranscript: { try await broker.transcribeDraft(sessionId: nil, draft: $0) },
                audioFallbackTranscribe: { try await broker.transcribeAudio(sessionId: nil, data: $0, filename: $1) }
            ))
            await composer.loadGlossary()
            let loadedProjects = await broker.projects()
            knownProjects = loadedProjects
            // The selected host's installed agents drive the agent menu; keep `agent` valid for it.
            let installed = (await broker.agentStatuses()).filter { $0.installed }.map { $0.kind }
            hostAgents = installed
            if !installed.isEmpty, !installed.contains(agent) { agent = installed.first ?? agent }
            // Debug: force the initial project for headless screenshots (e.g. an eligible repo).
            if let forced = ProcessInfo.processInfo.environment["SM_WORKDIR"], !forced.isEmpty {
                workdir = forced
                workdirTouched = true
            } else {
                // Invalid host-local paths (e.g. draft workdir from another host) are corrected
                // without freezing — only an explicit picker choice sets workdirTouched.
                let known = Set(projects)
                if workdir.isEmpty || (workdir != "~" && !known.contains(workdir) && !recentProjectPaths.contains(workdir)) {
                    workdir = recentProjectPaths.first ?? knownProjects.first ?? "~"
                }
                applyDefaultProject()
            }
        }
        // Follow most-recently-used project as session/message data hydrates; freeze once the
        // user engages (picked a path or started composing) — web/Android chooseDefaultProject.
        .onChange(of: recencyKey) { _, _ in applyDefaultProject() }
        .onChange(of: isComposing) { _, _ in applyDefaultProject() }
        .task {
            // Debug: auto-open a launcher picker for headless screenshots.
            guard let which = ProcessInfo.processInfo.environment["SM_OPEN_PICKER"] else { return }
            if which == "worktree" {
                for _ in 0..<40 where repoInfo == nil { try? await Task.sleep(nanoseconds: 150_000_000) }
                worktreeSheet = true
            } else if which == "project" {
                projectSearch = true
            }
        }
        .task(id: "\(broker.baseURL)|\(agent)") {
            let loadedModels = await broker.listModels(agent)
            models = loadedModels
            if let selected = model, !loadedModels.contains(where: { $0.id == selected }) {
                model = nil
            }
            // Reset only on a genuine switch to a *different* agent than the last one this task
            // actually observed — never on the very first run (whatever agent `init` seeded), and
            // never on a same-agent re-invocation. `.task(id:)` was observed on-device spinning up
            // a second instance for the same settled id shortly after the first (see the note by
            // the @State declarations above) — comparing against the last *value* rather than a
            // one-shot "have I run" flag is safe under that regardless of which instance's write
            // lands first, because it never fires unless `agent` truly differs from what was last
            // recorded.
            if let last = lastSeenAgent, last != agent {
                model = nil
            }
            lastSeenAgent = agent
        }
        // Thinking levels depend on the agent and (for Codex) the chosen model. Idempotent — it
        // resolves from the sticky per-agent choice each run, so a duplicate .task(id:) re-invocation
        // is harmless (nothing to reset, unlike the model task above).
        .task(id: "\(broker.baseURL)|\(agent)|\(model ?? "")") {
            let resp = await broker.reasoningLevels(agent, model)
            let levels = resp?.levels ?? []
            reasoningLevels = levels
            reasoningVisible = (resp?.visible ?? false) && ReasoningLevelsKt.showReasoningPicker(levels: levels)
            reasoningLevel = reasoningVisible
                ? ReasoningLevelsKt.resolveReasoningLevel(levels: levels, stored: launcherState.prefs.reasoningLevels[agent])
                : nil
        }
        // Agent slash commands depend on both the agent and the chosen project.
        .task(id: "\(broker.baseURL)|\(agent)|\(workdir)") {
            launcherCommands = workdir.isEmpty ? [] : await broker.previewCommands(agent, workdir)
        }
        // Git repo info for the worktree picker — refreshes whenever the project changes.
        .task(id: "\(broker.baseURL)|\(workdir)") {
            guard !workdir.isEmpty else { repoInfo = nil; return }
            let info = await broker.repoInfo(workdir)
            repoInfo = info
            let switchedRepoHost = lastRepoBrokerURL != nil && lastRepoBrokerURL != broker.baseURL
            lastRepoBrokerURL = broker.baseURL
            // Same last-observed-value comparison as the agent/model task above. A genuine switch
            // to a different workdir always follows its current branch; the first run (or a
            // same-workdir re-invocation) only fills in a currently-empty baseBranch, preserving
            // whatever `init` seeded from the draft.
            if switchedRepoHost || (lastSeenWorkdir != nil && lastSeenWorkdir != workdir) {
                baseBranch = info?.currentBranch ?? ""
            } else if baseBranch.isEmpty {
                baseBranch = info?.currentBranch ?? ""
            }
            lastSeenWorkdir = workdir
        }
        #if os(macOS)
        .overlayPreferenceValue(MacProjectPickerAnchorKey.self) { anchor in
            GeometryReader { proxy in
                if projectSearch, let anchor {
                    MacProjectPickerOverlay(
                        anchor: proxy[anchor],
                        container: proxy.size,
                        broker: broker,
                        projects: projects,
                        current: workdir,
                        onPick: { pickWorkdir($0) },
                        onClose: { projectSearch = false }
                    )
                }
            }
        }
        #else
        .sheet(isPresented: $projectSearch) {
            ProjectPickerSheet(
                broker: broker,
                projects: projects,
                current: workdir,
                onPick: { pickWorkdir($0) },
                onClose: { projectSearch = false }
            )
        }
        #endif
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            Task { await composer.loadPhotos(items); photoItems = [] }
        }
        .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .any(of: [.images, .videos]))
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { composer.handleFiles($0) }
        #if os(iOS)
        .smFullScreenCover(isPresented: $showCamera) { CameraPicker(mode: .photo, onImage: { composer.addCameraImage($0) }) }
        .smFullScreenCover(isPresented: $showVideoCamera) { CameraPicker(mode: .video, onVideo: { composer.addCameraVideo($0) }) }
        #endif
        .onChange(of: composer.refocusToken) { _, _ in composing = true }
        .onChange(of: composer.status) { _, s in
            guard let s else { return }
            composerStatus = s
            composer.status = nil
            Task {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if composerStatus == s { composerStatus = nil }
            }
        }
        .onChange(of: workdir) { _, new in
            launcherState.draft.workdir = new.isEmpty ? nil : new
        }
        .onChange(of: useWorktree) { _, new in
            launcherState.draft.useWorktree = new
        }
        .onChange(of: baseBranch) { _, new in
            launcherState.draft.baseBranch = new
        }
        .onChange(of: composer.draft) { _, new in
            launcherState.draft.text = new
        }
        .alert("Microphone access needed", isPresented: $composer.micDenied) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Enable microphone access for supermux in Settings to record voice messages.")
        }
        .alert("Couldn’t start session", isPresented: $spawnFailed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Make sure the selected agent is installed and signed in on this host, then try again.")
        }
    }

    private var launcherContent: some View {
        VStack(spacing: 16) {
            #if os(iOS)
            Spacer().frame(height: 28)
            #endif
            Image(systemName: "cube.fill").font(.system(size: 38)).foregroundStyle(.primary)
            Text("Let's build").font(.largeTitle.bold())
            projectPicker
            if fleet.multiHost, let h = activeHost { hostPickerPill(h) }
            if repoInfo?.eligible == true { worktreePill }
            if let composerStatus {
                Text(composerStatus)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Theme.teal, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .transition(.opacity)
            }
            composeCard
            if !workdir.isEmpty {
                Label(workdirLabel, systemImage: "folder")
                    .font(.caption.monospaced()).foregroundStyle(.secondary).padding(.top, 2)
            }
        }
        #if os(macOS)
        .padding(.vertical, 20)
        .padding(.horizontal, 48)
        .frame(maxWidth: 900)
        #else
        .padding(20)
        #endif
        .frame(maxWidth: .infinity)
    }

    private var workdirLabel: String {
        formatWorkdir(workdir: workdir, home: inferHomeDir(workdir: workdir))
    }

    private var projectPicker: some View {
        Button { projectSearch = true } label: {
            HStack(spacing: 6) {
                Text(workdir.isEmpty ? "Select project" : workdirLabel)
                    .font(.title2.weight(.semibold)).foregroundStyle(.secondary)
                Image(systemName: "chevron.down").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
            }
        }
        .smMacPlainButton()
        #if os(macOS)
        .anchorPreference(key: MacProjectPickerAnchorKey.self, value: .bounds) { $0 }
        #endif
    }
    private var modelLabel: String {
        guard let model else { return "Default" }
        return models.first { $0.id == model }?.displayName ?? model
    }

    // MARK: - Worktree / base branch (web LauncherWorktreePicker parity)

    private var worktreeLabel: String {
        guard useWorktree else { return "No worktree" }
        if !baseBranch.isEmpty { return baseBranch }
        return repoInfo?.currentBranch ?? "HEAD"
    }

    // MARK: - Host picker (Android HostPickerPill parity)

    /// "Spawn on which host" — a bordered pill in the hero, under the project dropdown, exactly
    /// where Android's launcher puts it (multi-host only). Picking a host updates the fleet's
    /// active record; `RootView` re-passes the new host's broker, which retargets the
    /// projects/agents/models/commands loads above.
    private func hostPickerPill(_ h: HostView) -> some View {
        Menu {
            ForEach(fleet.hostViews, id: \.recordId) { hv in
                Button {
                    fleet.setActive(hv.recordId)
                } label: {
                    let title = hv.online ? hv.displayLabel : "\(hv.displayLabel) (offline)"
                    if hv.recordId == fleet.activeRecordId {
                        Label(title, systemImage: "checkmark")
                    } else {
                        Text(title)
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                HostDot(colorIndex: h.colorIndex, size: 9)
                Text(h.shortLabel).font(.caption.weight(.medium)).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold)).opacity(0.6)
            }
            .foregroundStyle(.primary)
            .padding(.horizontal, 11).padding(.vertical, 5)
            .background(Color.smSecondaryBackground, in: Capsule())
            .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))
        }
        .smMacBorderlessMenu()
        .accessibilityIdentifier("launcher_host_picker")
    }

    private var worktreePill: some View {
        Button { worktreeSheet = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "arrow.triangle.branch")
                    .font(.system(size: 11, weight: .semibold))
                    .opacity(useWorktree ? 1 : 0.55)
                Text(worktreeLabel)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .bold))
                    .opacity(0.55)
            }
            .foregroundStyle(useWorktree ? AnyShapeStyle(Theme.teal) : AnyShapeStyle(.secondary))
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(
                useWorktree ? Theme.teal.opacity(0.10) : Color.smSecondaryBackground,
                in: Capsule()
            )
            .overlay(
                Capsule().strokeBorder(
                    useWorktree ? Theme.teal.opacity(0.28) : Theme.hairline,
                    lineWidth: 1
                )
            )
        }
        .smMacPlainButton()
        // Mac: popover anchored to the pill (default sheet is tiny / unreadable).
        // iOS: detented sheet via smOptionPicker.
        .smOptionPicker(isPresented: $worktreeSheet) {
            WorktreeSheet(
                useWorktree: $useWorktree, baseBranch: $baseBranch,
                branches: repoInfo?.branches, currentBranch: repoInfo?.currentBranch,
                loading: worktreeFetching, onAppearRefresh: onWorktreeRefresh
            )
        }
    }

    /// Re-list branches whenever the worktree sheet opens. Network `git fetch` is
    /// once per repo (fetchedRepos); we always re-read local + remote-tracking refs
    /// so newly created branches show up (web SessionLauncherView parity).
    private func onWorktreeRefresh() async {
        guard !workdir.isEmpty else { return }
        let root = repoInfo?.repoRoot
        let shouldFetch = root.map { !fetchedRepos.contains($0) } ?? false
        worktreeFetching = shouldFetch
        if let fresh = await broker.repoInfo(workdir, fetch: shouldFetch) {
            repoInfo = fresh
            if baseBranch.isEmpty { baseBranch = fresh.currentBranch ?? "" }
            if shouldFetch, let r = fresh.repoRoot { fetchedRepos.insert(r) }
        }
        worktreeFetching = false
    }

    private var composeCard: some View {
        let cmds = launcherCommands
        let matches = composer.slashMatches(in: cmds)
        return VStack(alignment: .leading, spacing: 12) {
            if !composer.pending.isEmpty {
                AttachmentTray(pending: composer.pending, onRemove: { composer.removeAttachment($0) })
            }
            if composer.dictation.isListening || composer.recorder.isRecording {
                if composer.dictation.isListening && !composer.dictation.transcript.isEmpty {
                    ScrollView {
                        Text(composer.dictation.transcript)
                            .font(.callout)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 100)
                }
                RecordingBar(elapsed: composer.dictation.isListening ? composer.dictation.elapsed : composer.recorder.elapsed,
                             onStop: { Task { await composer.toggleMic() } },
                             onCancel: { composer.cancelMic() })
            } else if composer.transcribing {
                // After STOP, the clip is POSTed to /transcribe (often 10–30s). Chat already
                // showed this pill; the launcher was silent and looked frozen on macOS.
                ComposerBusyBar(label: "Transcribing…")
            } else if composer.micStarting {
                ComposerBusyBar(label: "Preparing speech…")
            }
            TextField("What should the agent do?", text: $composer.draft, axis: .vertical)
                // Plain style: the card is the field's chrome — without this, macOS wraps it
                // in a bezel + blue focus ring (iOS already renders it plain here).
                .textFieldStyle(.plain)
                .lineLimit(3...8).focused($composing)
                .composerHardwareKeyboardSubmit(canSubmit: canSpawn && !spawning) { spawn() }
            if !matches.isEmpty {
                SlashMenu(matches: matches, showsActionGlyph: false) { composer.applyCommand($0) }
            }
            // Pickers get their own row so "Claude" / a long model name never get squeezed
            // into vertical letter-columns (the action buttons used to share this row and
            // overflow it on a narrow iPhone). Soft filter-pill chrome matches chat composer.
            HStack(spacing: 8) {
                #if os(macOS)
                // The logo sits OUTSIDE the Menu label on the Mac: AppKit flattens custom
                // menu-button labels and draws asset images at intrinsic size — a giant
                // unscaled logo. iOS below keeps the logo inside the tap target, unchanged.
                HStack(spacing: 6) {
                    AgentLogo(agent: agent, size: 18)
                    Menu {
                        ForEach(agents, id: \.self) { a in
                            Button(a.capitalized) { agent = a; launcherState.prefs.agent = a }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(agent.capitalized).font(.caption.weight(.semibold)).lineLimit(1)
                            Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.5)
                        }
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Color.smTertiaryFill, in: Capsule())
                        .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))
                    }
                    .smMacBorderlessMenu()
                }
                #else
                Menu {
                    ForEach(agents, id: \.self) { a in
                        Button(a.capitalized) { agent = a; launcherState.prefs.agent = a }
                    }
                } label: {
                    HStack(spacing: 5) {
                        AgentLogo(agent: agent, size: 18)
                        Text(agent.capitalized).font(.caption.weight(.semibold)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.5)
                    }
                    .foregroundStyle(.primary)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(Color.smTertiaryFill, in: Capsule())
                    .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))
                }
                #endif
                Menu {
                    Button("Default") {
                        model = nil
                        launcherState.prefs.models.removeValue(forKey: agent)
                    }
                    ForEach(models, id: \.id) { m in
                        Button(m.displayName) {
                            model = m.id
                            launcherState.prefs.models[agent] = m.id
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "cpu").font(.system(size: 10, weight: .semibold))
                        Text(modelLabel).font(.caption.weight(.semibold)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.5)
                    }
                    .foregroundStyle(model != nil ? Theme.teal : Color.secondary)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(model != nil ? Theme.teal.opacity(0.10) : Color.smTertiaryFill, in: Capsule())
                    .overlay(Capsule().strokeBorder(model != nil ? Theme.teal.opacity(0.28) : Theme.hairline, lineWidth: 1))
                }
                .smMacBorderlessMenu()
                if reasoningVisible {
                    Menu {
                        ForEach(reasoningLevels, id: \.id) { l in
                            Button(l.id.capitalized) {
                                reasoningLevel = l.id
                                launcherState.prefs.reasoningLevels[agent] = l.id
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "brain").font(.system(size: 10, weight: .semibold))
                            Text((reasoningLevel ?? "").capitalized).font(.caption.weight(.semibold)).lineLimit(1)
                            Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.5)
                        }
                        .foregroundStyle(Theme.teal)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Theme.teal.opacity(0.10), in: Capsule())
                        .overlay(Capsule().strokeBorder(Theme.teal.opacity(0.28), lineWidth: 1))
                    }
                    .smMacBorderlessMenu()
                }
                Spacer(minLength: 0)
            }
            // Action row — attach · mic · save draft · send.
            HStack(spacing: 10) {
                AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                           showVideoCamera: $showVideoCamera)
                if !composer.recorder.isRecording && !composer.dictation.isListening {
                    MicButton(model: composer)
                }
                Spacer(minLength: 0)
                OutlinePillButton(title: "Save draft", enabled: canSaveDraft && !spawning) { saveAsDraft() }
                SendCircleButton(enabled: canSpawn, spinning: spawning) { spawn() }
            }
        }
        .padding(16)
        .smCardSurface(cornerRadius: 20)
    }

    private var canSpawn: Bool { !workdir.isEmpty }
    private var canSaveDraft: Bool {
        !workdir.isEmpty && !composer.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func saveAsDraft() {
        let text = composer.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canSaveDraft else { return }
        spawning = true
        Task {
            if let old = reopenDraft?.id {
                // Replace on re-save (web saveAsDraft parity) — no update endpoint.
                broker.kill(old)
            }
            let id = await broker.createDraft(
                workdir: workdir,
                agent: agent,
                model: model,
                text: text,
                name: nil,
                reasoningLevel: reasoningLevel
            )
            await MainActor.run {
                spawning = false
                if id != nil {
                    launcherState.clearDraft()
                    onSpawned(id ?? "")
                } else {
                    spawnFailed = true
                }
            }
        }
    }

    private func spawn() {
        spawning = true
        let (raw, toUpload) = composer.consume()
        let firstMsg = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let eligible = repoInfo?.eligible == true
        let wantsWorktree = eligible ? useWorktree : false
        let base = (eligible && useWorktree && !baseBranch.isEmpty) ? baseBranch : nil
        Task {
            // Starting a reopened draft: discard it first so the name is free (web parity).
            if let old = reopenDraft?.id {
                broker.kill(old)
            }
            let id = await broker.spawn(workdir: workdir, agent: agent, name: nil, model: model,
                                        worktree: wantsWorktree, baseBranch: base, reasoningLevel: reasoningLevel)
            if let id, !id.isEmpty {
                // Attachments need a session id, so upload after spawn (like the first message).
                var ids: [String] = []
                for p in toUpload {
                    // Audio clips → "voice"; images and videos stay nil so the broker infers the kind
                    // from the MIME (video/* → "video" server-side). Never mislabel a video as audio.
                    let kind = p.mime.hasPrefix("audio") ? "voice" : nil
                    let fid: String?
                    if let url = p.fileURL {
                        // Video/large: stream from the file URL (chunked, bounded RAM).
                        fid = await broker.uploadResumable(id, source: NSFileHandleChunkSource(path: url.path),
                            filename: p.filename, mime: p.mime, kind: kind) { _, _ in }
                    } else if let data = p.data {
                        fid = await broker.upload(id, data: data, filename: p.filename, mime: p.mime, kind: kind)
                    } else {
                        fid = nil
                    }
                    if let fid { ids.append(fid) }
                }
                if !firstMsg.isEmpty || !ids.isEmpty {
                    broker.send(id, firstMsg, attachments: ids.isEmpty ? nil : ids)
                }
                launcherState.clearDraft()
                onSpawned(id)
            } else {
                if composer.draft.isEmpty {
                    composer.draft = raw
                } else if !raw.isEmpty {
                    composer.draft = raw + "\n" + composer.draft
                }
                composer.pending = toUpload + composer.pending
                spawnFailed = true
            }
            spawning = false
        }
    }
}

/// Forge-aware project picker — mirrors the web ProjectPathPicker omnibox:
/// pick a known project, type an arbitrary path, clone a repo from a connected
/// GitHub/GitLab account, or create a new repo (locally or on a forge).
private struct ProjectPickerSheet: View {
    let broker: BrokerSession
    let projects: [String]
    let current: String
    var onPick: (String) -> Void
    var onClose: () -> Void

    @State private var search = ""
    @State private var connections: [ForgeConnection] = []
    @State private var cloudRepos: [RemoteRepo] = []
    @State private var searching = false
    @State private var resolving = false
    @FocusState private var searchFocused: Bool

    private var query: String { search.trimmingCharacters(in: .whitespaces) }

    private func label(_ path: String) -> String {
        formatWorkdir(workdir: path, home: inferHomeDir(workdir: path))
    }
    private func basename(_ path: String) -> String {
        path.split(separator: "/").last.map(String.init) ?? path
    }

    private var filteredProjects: [String] {
        let q = query.lowercased()
        guard !q.isEmpty else { return projects }
        return projects.filter { $0.lowercased().contains(q) || label($0).lowercased().contains(q) }
    }
    // "Use this path" — shown when the typed text isn't an exact known project path.
    private var showTypedPath: Bool { !query.isEmpty && !projects.contains(query) }

    private var cloudGroups: [(conn: ForgeConnection, repos: [RemoteRepo])] {
        connections.compactMap { c in
            let repos = cloudRepos.filter { $0.connectionId == c.id }
            return repos.isEmpty ? nil : (c, repos)
        }
    }

    private var isValidName: Bool {
        query.range(of: "^[A-Za-z0-9][A-Za-z0-9._-]*$", options: .regularExpression) != nil
    }
    private var exactMatch: Bool {
        guard !query.isEmpty else { return false }
        let ql = query.lowercased()
        if projects.contains(where: { basename($0).lowercased() == ql }) { return true }
        if cloudRepos.contains(where: { $0.name.lowercased() == ql || $0.fullName.lowercased() == ql }) { return true }
        return false
    }
    private var showCreate: Bool { !query.isEmpty && isValidName && !exactMatch }

    var body: some View {
        Group {
            #if os(macOS)
            macPicker
            #else
            NavigationStack {
                pickerList
                    .searchable(text: $search, placement: .smNavDrawerAlways,
                                prompt: "Search projects, repos, or type a path")
                    .navigationTitle("Project").smInlineNavigationTitle()
                    .toolbar {
                        ToolbarItem(placement: .smTopTrailing) { Button("Cancel", action: onClose) }
                    }
                }
            #endif
        }
        .tint(Theme.teal)
        .overlay {
            if resolving {
                ZStack {
                    Color.black.opacity(0.08)
                    VStack(spacing: 10) {
                        ProgressView().controlSize(.small)
                        Text("Cloning / creating…").font(.caption.weight(.medium)).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 20).padding(.vertical, 16)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.hairline))
                    .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
                }
            }
        }
        .onAppear {
            #if os(macOS)
            DispatchQueue.main.async { searchFocused = true }
            #endif
        }
        .task {
            connections = await broker.forges()
            // Debug: prefill the search for headless screenshots (surfaces cloud/create options).
            if let q = ProcessInfo.processInfo.environment["SM_PICKER_QUERY"], !q.isEmpty { search = q }
        }
        // Debounced forge search (≥2 chars, only with connections) — web useForgeOmnibox parity.
        .task(id: query) {
            guard connections.count > 0, query.count >= 2 else { cloudRepos = []; return }
            try? await Task.sleep(for: .milliseconds(250))
            if Task.isCancelled { return }
            searching = true
            cloudRepos = await broker.searchForge(query)
            searching = false
        }
    }

    #if os(macOS)
    /// A desktop-native command palette: the search field stays fixed while results scroll,
    /// paths remain readable at wide Mac sizes, and every operation has one consistent row.
    private var macPicker: some View {
        VStack(spacing: 0) {
            VStack(spacing: 10) {
                HStack(spacing: 9) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(searchFocused ? Theme.teal : Color.primary.opacity(0.48))
                    TextField("Search projects, repositories, or enter a path", text: $search)
                        .textFieldStyle(.plain)
                        .font(.body)
                        .focused($searchFocused)
                        .onSubmit(submitMacSearch)
                    if searching {
                        ProgressView().controlSize(.mini)
                    } else if !search.isEmpty {
                        Button { search = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear search")
                    }
                }
                .padding(.horizontal, 12)
                .frame(height: 40)
                .background(Color.smTertiaryBackground.opacity(0.72), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(searchFocused ? Theme.teal.opacity(0.65) : Theme.hairline,
                                      lineWidth: searchFocused ? 1.5 : 1)
                }

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 5) {
                        if showTypedPath {
                            macSectionHeader("OPEN PATH", detail: nil)
                            MacProjectPickerRow(
                                icon: "arrow.turn.down.left",
                                title: "Open this path",
                                subtitle: query,
                                trailing: "Open",
                                accent: Theme.teal,
                                selected: false
                            ) {
                                onPick(query); onClose()
                            }
                            .padding(.bottom, 6)
                        }

                        if !filteredProjects.isEmpty {
                            macSectionHeader(query.isEmpty ? "PROJECTS" : "LOCAL RESULTS",
                                             detail: "\(filteredProjects.count)")
                            ForEach(filteredProjects, id: \.self) { p in
                                MacProjectPickerRow(
                                    icon: "folder.fill",
                                    title: basename(p),
                                    subtitle: label(p),
                                    trailing: p == current ? "Current" : nil,
                                    accent: Theme.teal,
                                    selected: p == current
                                ) {
                                    onPick(p); onClose()
                                }
                            }
                            .padding(.bottom, 6)
                        }

                        ForEach(cloudGroups, id: \.conn.id) { group in
                            macSectionHeader(group.conn.host.uppercased(),
                                             detail: "@\(group.conn.account.login)")
                            ForEach(group.repos, id: \.fullName) { r in
                                MacProjectPickerRow(
                                    icon: "icloud.and.arrow.down.fill",
                                    title: r.name,
                                    subtitle: r.fullName,
                                    trailing: "Clone",
                                    accent: Theme.teal,
                                    selected: false
                                ) {
                                    resolveCloud(r)
                                }
                                .disabled(resolving)
                            }
                            .padding(.bottom, 6)
                        }

                        if searching && cloudGroups.isEmpty {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.mini)
                                Text("Searching connected repositories…")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(.horizontal, 10).padding(.vertical, 12)
                        }

                        if showCreate {
                            macSectionHeader("CREATE NEW", detail: nil)
                            MacProjectPickerRow(
                                icon: "plus",
                                title: query,
                                subtitle: "New local Git repository",
                                trailing: "Create",
                                accent: Theme.teal,
                                selected: false
                            ) {
                                resolveCreateLocal()
                            }
                            .disabled(resolving)
                            ForEach(connections, id: \.id) { c in
                                MacProjectPickerRow(
                                    icon: "plus",
                                    title: "\(c.account.login)/\(query)",
                                    subtitle: "Create on \(c.host)",
                                    trailing: "Create",
                                    accent: Theme.teal,
                                    selected: false
                                ) {
                                    resolveCreateForge(c.id)
                                }
                                .disabled(resolving)
                            }
                        }

                        if query.isEmpty && projects.isEmpty {
                            VStack(spacing: 8) {
                                Image(systemName: "folder.badge.plus")
                                    .font(.system(size: 26, weight: .light)).foregroundStyle(.tertiary)
                                Text("No projects found").font(.callout.weight(.semibold))
                                Text("Enter a local path above, or search a connected repository.")
                                    .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 30)
                        }
                    }
                    .padding(8)
                }
                .background(Color.smTertiaryBackground.opacity(0.34), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.hairline))
            }
            .padding(12)
        }
    }

    @ViewBuilder
    private func macSectionHeader(_ title: String, detail: String?) -> some View {
        HStack(spacing: 6) {
            Text(title)
            Spacer()
            if let detail { Text(detail) }
        }
        .font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
        .padding(.horizontal, 8).padding(.top, 4).padding(.bottom, 2)
    }

    private func submitMacSearch() {
        guard !query.isEmpty else { return }
        if let exact = projects.first(where: {
            $0.caseInsensitiveCompare(query) == .orderedSame ||
            basename($0).caseInsensitiveCompare(query) == .orderedSame
        }) {
            onPick(exact); onClose()
        } else if filteredProjects.count == 1, let only = filteredProjects.first {
            onPick(only); onClose()
        } else if showTypedPath {
            onPick(query); onClose()
        }
    }
    #endif

    private var pickerList: some View {
        List {
            if showTypedPath {
                Section {
                    Button { onPick(query); onClose() } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "arrow.turn.down.left").foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 1) {
                                Text("Use this path").foregroundStyle(.primary)
                                Text(query).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }.smMacPlainButton()
                }
            }
            if !filteredProjects.isEmpty {
                Section("Projects") {
                    ForEach(filteredProjects, id: \.self) { p in
                        Button { onPick(p); onClose() } label: { projectRow(name: basename(p), sub: label(p), checked: p == current) }.smMacPlainButton()
                    }
                }
            }
            ForEach(cloudGroups, id: \.conn.id) { group in
                Section("\(group.conn.host) · @\(group.conn.account.login)") {
                    ForEach(group.repos, id: \.fullName) { r in
                        Button { resolveCloud(r) } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "folder").foregroundStyle(.secondary)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(r.name).foregroundStyle(.primary).lineLimit(1)
                                    Text(r.fullName).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer()
                                Label("Clone", systemImage: "arrow.down.circle")
                                    .labelStyle(.titleAndIcon).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .disabled(resolving).smMacPlainButton()
                    }
                }
            }
            if searching && cloudGroups.isEmpty {
                Section { HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Searching repos…").foregroundStyle(.secondary) } }
            }
            if showCreate {
                Section("Create") {
                    Button { resolveCreateLocal() } label: {
                        Label("Create locally — \(query)", systemImage: "plus.circle")
                    }.disabled(resolving).smMacPlainButton()
                    ForEach(connections, id: \.id) { c in
                        Button { resolveCreateForge(c.id) } label: {
                            Label("Create on \(c.host) — \(c.account.login)/\(query)", systemImage: "plus.circle")
                                .lineLimit(1)
                        }
                        .disabled(resolving).smMacPlainButton()
                    }
                }
            }
        }
    }

    private func projectRow(name: String, sub: String, checked: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "folder").foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(name).foregroundStyle(.primary).lineLimit(1)
                Text(sub).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if checked { Image(systemName: "checkmark").foregroundStyle(Theme.teal) }
        }
    }

    private func resolveCloud(_ r: RemoteRepo) {
        resolving = true
        Task {
            let path = await broker.cloneForge(connectionId: r.connectionId, owner: r.owner, name: r.name)
            resolving = false
            if let path { onPick(path); onClose() }
        }
    }
    private func resolveCreateLocal() {
        let name = query
        resolving = true
        Task {
            let path = await broker.createLocalRepo(name)
            resolving = false
            if let path { onPick(path); onClose() }
        }
    }
    private func resolveCreateForge(_ connectionId: String) {
        let name = query
        resolving = true
        Task {
            let path = await broker.createForge(connectionId: connectionId, name: name)
            resolving = false
            if let path { onPick(path); onClose() }
        }
    }
}

#if os(macOS)
/// One compact, pointer-friendly result in the Mac project popover. A custom row avoids the
/// table chrome and full-width selection bars that made the old iOS-style List feel out of place.
private struct MacProjectPickerRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let trailing: String?
    let accent: Color
    let selected: Bool
    let action: () -> Void

    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(accent.opacity(selected ? 0.17 : 0.09))
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(selected ? accent : Color.primary.opacity(0.58))
                }
                .frame(width: 32, height: 32)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.callout.weight(selected ? .semibold : .medium))
                        .foregroundStyle(.primary).lineLimit(1)
                    Text(subtitle)
                        .font(.caption.monospaced()).foregroundStyle(.secondary)
                        .lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 8)
                if selected {
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark.circle.fill")
                        if let trailing { Text(trailing) }
                    }
                    .font(.caption.weight(.medium)).foregroundStyle(accent)
                } else if let trailing {
                    HStack(spacing: 4) {
                        Text(trailing)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .semibold))
                    }
                    .font(.caption.weight(.medium))
                    .foregroundStyle(hovered ? accent : Color.primary.opacity(0.42))
                }
            }
            .padding(.horizontal, 9).padding(.vertical, 7)
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .background(
                selected ? accent.opacity(0.105) : (hovered ? Color.primary.opacity(0.055) : .clear),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .overlay {
                if selected {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(accent.opacity(0.22))
                }
            }
        }
        .buttonStyle(.plain)
        .onHover { hovered = $0 }
        .accessibilityElement(children: .combine)
    }
}

/// Compact omnibox popover anchored under the project control. It lives inside the detail
/// workspace rather than becoming an AppKit sheet window or a centered modal.
private struct MacProjectPickerOverlay: View {
    let anchor: CGRect
    let container: CGSize
    let broker: BrokerSession
    let projects: [String]
    let current: String
    var onPick: (String) -> Void
    var onClose: () -> Void

    private var cardWidth: CGFloat { min(540, max(320, container.width - 32)) }
    private var cardHeight: CGFloat {
        let visibleRows = CGFloat(min(max(projects.count, 1), 6))
        let desired = max(200, 82 + visibleRows * 53)
        return min(max(160, container.height - 32), desired)
    }
    private var cardX: CGFloat {
        min(max(anchor.midX, cardWidth / 2 + 16), container.width - cardWidth / 2 - 16)
    }
    private var cardY: CGFloat {
        let below = anchor.maxY + 8 + cardHeight / 2
        if below + cardHeight / 2 <= container.height - 16 { return below }
        return max(cardHeight / 2 + 16, anchor.minY - 8 - cardHeight / 2)
    }

    var body: some View {
        ZStack {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(perform: onClose)

            ProjectPickerSheet(
                broker: broker,
                projects: projects,
                current: current,
                onPick: onPick,
                onClose: onClose
            )
            .frame(width: cardWidth, height: cardHeight)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color.smSeparator.opacity(0.65))
            }
            .shadow(color: .black.opacity(0.18), radius: 18, y: 7)
            .position(x: cardX, y: cardY)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .transition(.opacity)
        .onExitCommand(perform: onClose)
        .accessibilityIdentifier("project-picker-overlay")
    }
}
#endif

/// Worktree + base-branch picker — desktop-native menu (matches Mac project picker +
/// web LauncherWorktreePicker): toggle, inline search, hoverable branch rows. No stock
/// List/Form chrome — that looked dated and put search in an unreachable toolbar on Mac.
private struct WorktreeSheet: View {
    @Binding var useWorktree: Bool
    @Binding var baseBranch: String
    let branches: RepoBranches?
    let currentBranch: String?
    let loading: Bool
    var onAppearRefresh: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @FocusState private var searchFocused: Bool

    private struct BranchItem: Identifiable {
        let name: String
        let isRemote: Bool
        var id: String { "\(isRemote ? "r" : "l"):\(name)" }
    }
    private var allBranches: [BranchItem] {
        (branches?.local ?? []).map { BranchItem(name: $0, isRemote: false) }
            + (branches?.remote ?? []).map { BranchItem(name: $0, isRemote: true) }
    }
    private var filtered: [BranchItem] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? allBranches : allBranches.filter { $0.name.lowercased().contains(q) }
    }

    var body: some View {
        VStack(spacing: 0) {
            #if os(iOS)
            HStack {
                Text("Worktree").font(.headline)
                Spacer()
                Button("Done") { dismiss() }.fontWeight(.semibold)
            }
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 8)
            #endif

            VStack(alignment: .leading, spacing: 12) {
                // Toggle row
                Button {
                    useWorktree.toggle()
                    if useWorktree { searchFocused = true }
                } label: {
                    HStack(spacing: 10) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(Theme.teal.opacity(useWorktree ? 0.16 : 0.08))
                            Image(systemName: "arrow.triangle.branch")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(useWorktree ? Theme.teal : Color.primary.opacity(0.55))
                        }
                        .frame(width: 32, height: 32)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Isolated worktree")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.primary)
                            Text("Fresh branch · leaves your working copy alone")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: useWorktree ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 18, weight: .medium))
                            .foregroundStyle(useWorktree ? Theme.teal : Color.primary.opacity(0.22))
                            .symbolRenderingMode(.hierarchical)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 9)
                    .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .background(
                        useWorktree ? Theme.teal.opacity(0.07) : Color.primary.opacity(0.03),
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                    )
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(useWorktree ? Theme.teal.opacity(0.22) : Theme.hairline, lineWidth: 1)
                    }
                }
                .buttonStyle(.plain)

                if useWorktree {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 6) {
                            Text("BASE BRANCH")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(.secondary)
                                .tracking(0.6)
                            Spacer()
                            if loading {
                                ProgressView().controlSize(.mini)
                            }
                        }
                        .padding(.horizontal, 4)

                        // Inline search field (command-palette style)
                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(searchFocused ? Theme.teal : Color.primary.opacity(0.4))
                            TextField("Search branches…", text: $search)
                                .textFieldStyle(.plain)
                                .font(.subheadline)
                                .autocorrectionDisabled()
                                .smNoAutocapitalization()
                                .focused($searchFocused)
                            if !search.isEmpty {
                                Button {
                                    search = ""
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.system(size: 12))
                                        .foregroundStyle(.tertiary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Clear search")
                            }
                        }
                        .padding(.horizontal, 10)
                        .frame(height: 34)
                        .background(
                            Color.smTertiaryBackground.opacity(0.72),
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous)
                        )
                        .overlay {
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .strokeBorder(
                                    searchFocused ? Theme.teal.opacity(0.6) : Theme.hairline,
                                    lineWidth: searchFocused ? 1.5 : 1
                                )
                        }

                        // Branch list
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 2) {
                                if loading && allBranches.isEmpty {
                                    HStack(spacing: 8) {
                                        ProgressView().controlSize(.small)
                                        Text("Fetching branches…")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 20)
                                } else if filtered.isEmpty {
                                    Text(allBranches.isEmpty ? "No branches found" : "No match")
                                        .font(.caption).foregroundStyle(.secondary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 20)
                                } else {
                                    ForEach(filtered) { b in
                                        MenuOptionRow(
                                            title: b.name,
                                            systemImage: b.isRemote ? "cloud" : "arrow.triangle.branch",
                                            monospaced: true,
                                            selected: baseBranch == b.name
                                        ) {
                                            baseBranch = b.name
                                            useWorktree = true
                                            dismiss()
                                        }
                                    }
                                }
                            }
                            .padding(.vertical, 2)
                        }
                        .frame(maxHeight: .infinity)
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
                } else {
                    Text("Turn this on to cut a fresh branch from a base, so the session never touches your working tree.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 4)
                        .padding(.bottom, 4)
                }
            }
            .padding(12)
            .animation(.smooth(duration: 0.22), value: useWorktree)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color.smBackground)
        .tint(Theme.teal)
        .smPresentationDetents([.medium, .large])
        // Compact desktop menu size — closer to web dropdown than a form sheet.
        .smMacFixedFrame(width: 300, height: 400)
        .task {
            await onAppearRefresh()
            #if os(macOS)
            if useWorktree {
                DispatchQueue.main.async { searchFocused = true }
            }
            #endif
        }
    }
}


