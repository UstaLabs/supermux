import SwiftUI
import Shared
import PhotosUI
import UniformTypeIdentifiers

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

    @State private var projects: [String] = []
    @State private var workdir: String
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
    // Installed agent kinds for the ACTIVE host (GET /agents/status) — replaces the hardcoded four
    // so the picker only offers what THAT host actually has (spec §5). Reloaded on host switch; the
    // literal list is the fallback until the fetch lands / when the host doesn't answer.
    @State private var hostAgents: [String] = []

    private static let agents = ["claude", "codex", "cursor", "opencode"]
    private var agents: [String] { hostAgents.isEmpty ? Self.agents : hostAgents }
    /// The active host as the picker renders it (dot + name), or nil in single-host mode.
    private var activeHost: HostView? { fleet.hostViews.first { $0.recordId == fleet.activeRecordId } }

    init(broker: BrokerSession, fleet: Fleet, onSpawned: @escaping (String) -> Void) {
        self.broker = broker
        self.fleet = fleet
        self.onSpawned = onSpawned
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
        let resolvedAgent = Self.agents.contains(restoredAgent) ? restoredAgent : "claude"
        _launcherState = State(initialValue: store)
        _agent = State(initialValue: resolvedAgent)
        _model = State(initialValue: store.prefs.models[resolvedAgent])
        _reasoningLevel = State(initialValue: store.prefs.reasoningLevels[resolvedAgent])
        _workdir = State(initialValue: store.draft.workdir ?? "")
        _useWorktree = State(initialValue: store.draft.useWorktree)
        _baseBranch = State(initialValue: store.draft.baseBranch)
        // The real glossary/transcribe closures need `broker`, wired in `.task` once the view
        // has appeared; this placeholder context only needs to seed the restored draft text.
        _composer = State(initialValue: ComposerModel(
            context: ComposerContext(glossary: { [] }, cleanupTranscript: nil, audioFallbackTranscribe: nil),
            initialDraft: store.draft.text
        ))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer().frame(height: 28)
                Image(systemName: "cube.fill").font(.system(size: 38)).foregroundStyle(.primary)
                Text("Let's build").font(.largeTitle.bold())
                projectPicker
                if fleet.multiHost, let h = activeHost { hostPickerPill(h) }
                if repoInfo?.eligible == true { worktreePill }
                composeCard
                if !workdir.isEmpty {
                    Label(workdirLabel, systemImage: "folder")
                        .font(.caption.monospaced()).foregroundStyle(.secondary).padding(.top, 2)
                }
            }
            .padding(20).frame(maxWidth: .infinity)
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
            projects = []
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
            projects = loadedProjects
            // The selected host's installed agents drive the agent menu; keep `agent` valid for it.
            let installed = (await broker.agentStatuses()).filter { $0.installed }.map { $0.kind }
            hostAgents = installed
            if !installed.isEmpty, !installed.contains(agent) { agent = installed.first ?? agent }
            // Debug: force the initial project for headless screenshots (e.g. an eligible repo).
            if let forced = ProcessInfo.processInfo.environment["SM_WORKDIR"], !forced.isEmpty {
                workdir = forced
            } else if workdir.isEmpty || (workdir != "~" && !loadedProjects.contains(workdir)) {
                // A persisted project from another host must never be spawned on this one.
                workdir = loadedProjects.first ?? "~"
            }
        }
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
        .overlay {
            if projectSearch {
                MacProjectPickerOverlay(
                    broker: broker,
                    projects: projects,
                    current: workdir,
                    onPick: { workdir = $0 },
                    onClose: { projectSearch = false }
                )
            }
        }
        #else
        .sheet(isPresented: $projectSearch) {
            ProjectPickerSheet(
                broker: broker,
                projects: projects,
                current: workdir,
                onPick: { workdir = $0 },
                onClose: { projectSearch = false }
            )
        }
        #endif
        .sheet(isPresented: $worktreeSheet) {
            WorktreeSheet(
                useWorktree: $useWorktree, baseBranch: $baseBranch,
                branches: repoInfo?.branches, currentBranch: repoInfo?.currentBranch,
                loading: worktreeFetching, onAppearRefresh: onWorktreeRefresh
            )
            .smPresentationDetents([.medium, .large])
        }
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
            HStack(spacing: 5) {
                Image(systemName: "arrow.triangle.branch")
                    .font(.caption2).opacity(useWorktree ? 0.9 : 0.5)
                Text(worktreeLabel).font(.caption.weight(.medium)).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold)).opacity(0.6)
            }
            .foregroundStyle(useWorktree ? AnyShapeStyle(Theme.teal) : AnyShapeStyle(.secondary))
            .padding(.horizontal, 11).padding(.vertical, 5)
            .background(Color.smSecondaryBackground, in: Capsule())
        }
        .smMacPlainButton()
    }

    /// Fetch origin once per repo when the worktree sheet opens, so the branch
    /// list reflects what's been pushed since the last local fetch (web parity).
    private func onWorktreeRefresh() async {
        guard let root = repoInfo?.repoRoot, !workdir.isEmpty, !fetchedRepos.contains(root) else { return }
        worktreeFetching = true
        if let fresh = await broker.repoInfo(workdir, fetch: true) {
            repoInfo = fresh
            if baseBranch.isEmpty { baseBranch = fresh.currentBranch ?? "" }
            fetchedRepos.insert(root)
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
            // overflow it on a narrow iPhone).
            HStack(spacing: 12) {
                #if os(macOS)
                // The logo sits OUTSIDE the Menu label on the Mac: AppKit flattens custom
                // menu-button labels and draws asset images at intrinsic size — a giant
                // unscaled logo. iOS below keeps the logo inside the tap target, unchanged.
                HStack(spacing: 5) {
                    AgentLogo(agent: agent, size: 18)
                    Menu {
                        ForEach(agents, id: \.self) { a in
                            Button(a.capitalized) { agent = a; launcherState.prefs.agent = a }
                        }
                    } label: {
                        HStack(spacing: 5) {
                            Text(agent.capitalized).font(.subheadline.weight(.medium)).lineLimit(1)
                            Image(systemName: "chevron.down").font(.caption2)
                        }.foregroundStyle(.primary)
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
                        Text(agent.capitalized).font(.subheadline.weight(.medium)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.primary)
                }
                #endif
                // Always show the model menu (web LauncherModelPicker parity). Hiding it
                // when the list is empty made cursor/opencode look model-less after a
                // transient /models miss or before the cache warmed.
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
                        Text(modelLabel).font(.subheadline.weight(.medium)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.secondary)
                }
                .smMacBorderlessMenu()
                // Thinking-level menu (web LauncherEffortPicker parity) — only when the agent offers
                // a real choice. Mirrors the model menu's style.
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
                            Image(systemName: "brain").font(.caption2)
                            Text((reasoningLevel ?? "").capitalized).font(.subheadline.weight(.medium)).lineLimit(1)
                            Image(systemName: "chevron.down").font(.caption2)
                        }.foregroundStyle(.secondary)
                    }
                    .smMacBorderlessMenu()
                }
                Spacer(minLength: 0)
            }
            // Action row — attach · mic · send.
            HStack(spacing: 16) {
                AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                           showVideoCamera: $showVideoCamera)
                // Hidden while recording/dictating (the RecordingBar above owns stop/cancel) —
                // parity with the original launcher + the chat composer.
                if !composer.recorder.isRecording && !composer.dictation.isListening {
                    MicButton(model: composer)
                }
                Spacer()
                Button(action: spawn) {
                    if spawning {
                        ProgressView().tint(.white).frame(width: 40, height: 40)
                    } else {
                        Image(systemName: "arrow.up").font(.headline.weight(.bold)).foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(canSpawn ? Theme.teal : Color.gray.opacity(0.5), in: Circle())
                    }
                }
                .smMacPlainButton()
                .disabled(!canSpawn || spawning)
            }
        }
        .padding(16)
        .smCardSurface(cornerRadius: 20)
    }

    private var canSpawn: Bool { !workdir.isEmpty }

    private func spawn() {
        spawning = true
        let (raw, toUpload) = composer.consume()
        let firstMsg = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let eligible = repoInfo?.eligible == true
        let wantsWorktree = eligible ? useWorktree : false
        let base = (eligible && useWorktree && !baseBranch.isEmpty) ? baseBranch : nil
        Task {
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
        NavigationStack {
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
                            }.disabled(resolving).smMacPlainButton()
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
                            }.disabled(resolving).smMacPlainButton()
                        }
                    }
                }
            }
            #if os(macOS)
            .navigationTitle("")
            #else
            .searchable(text: $search, placement: .smNavDrawerAlways,
                        prompt: "Search projects, repos, or type a path")
            .navigationTitle("Project").smInlineNavigationTitle()
            #endif
            .toolbar {
                #if os(macOS)
                ToolbarItem(placement: .navigation) {
                    HStack(spacing: 6) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Search projects, repos, or type a path", text: $search)
                            .textFieldStyle(.plain)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 6))
                    .frame(width: 280)
                }
                #endif
                ToolbarItem(placement: .smTopTrailing) { Button("Cancel") { onClose() } }
            }
            .overlay {
                if resolving {
                    ZStack {
                        Color.black.opacity(0.06).ignoresSafeArea()
                        VStack(spacing: 10) {
                            ProgressView()
                            Text("Cloning / creating…").font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(22).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 560)
        #endif
        .tint(Theme.teal)
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
/// iPad presents the project picker as a card over the launcher. AppKit backs SwiftUI `.sheet`
/// with another window, so the Mac recreates that card inside the existing window instead.
private struct MacProjectPickerOverlay: View {
    let broker: BrokerSession
    let projects: [String]
    let current: String
    var onPick: (String) -> Void
    var onClose: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.2)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture(perform: onClose)

            ProjectPickerSheet(
                broker: broker,
                projects: projects,
                current: current,
                onPick: onPick,
                onClose: onClose
            )
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.smSeparator.opacity(0.7))
            }
            .shadow(color: .black.opacity(0.25), radius: 24, y: 10)
            .padding(36)
        }
        .transition(.opacity)
        .onExitCommand(perform: onClose)
        .accessibilityIdentifier("project-picker-overlay")
    }
}
#endif

/// Worktree + base-branch picker — native take on the web LauncherWorktreePicker:
/// a toggle for the isolated worktree plus a searchable list of local/remote
/// branches to cut it from. Fetches origin once when it opens (via onAppearRefresh).
private struct WorktreeSheet: View {
    @Binding var useWorktree: Bool
    @Binding var baseBranch: String
    let branches: RepoBranches?
    let currentBranch: String?
    let loading: Bool
    var onAppearRefresh: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    private var allBranches: [String] { (branches?.local ?? []) + (branches?.remote ?? []) }
    private var filtered: [String] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? allBranches : allBranches.filter { $0.lowercased().contains(q) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle(isOn: $useWorktree) {
                        Label("Run in isolated worktree", systemImage: "arrow.triangle.branch")
                    }.tint(Theme.teal)
                } footer: {
                    Text("Runs the session on a fresh branch cut from the base below, so your working copy stays untouched.")
                }
                if useWorktree {
                    Section {
                        if loading && allBranches.isEmpty {
                            HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Fetching…").foregroundStyle(.secondary) }
                        } else if filtered.isEmpty {
                            Text(allBranches.isEmpty ? "No branches" : "No match").foregroundStyle(.secondary)
                        }
                        ForEach(filtered, id: \.self) { b in
                            Button { baseBranch = b; useWorktree = true; dismiss() } label: {
                                HStack {
                                    Text(b).font(.callout.monospaced()).foregroundStyle(.primary).lineLimit(1)
                                    Spacer()
                                    if baseBranch == b { Image(systemName: "checkmark").foregroundStyle(Theme.teal) }
                                }
                            }
                        }
                    } header: {
                        HStack {
                            Text("Base branch")
                            if loading && !allBranches.isEmpty { Spacer(); ProgressView().controlSize(.small) }
                        }
                    }
                }
            }
            .searchable(text: $search, placement: .smNavDrawerAlways, prompt: "Search branches")
            .navigationTitle("Worktree").smInlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .smTopTrailing) { Button("Done") { dismiss() } } }
        }
        .tint(Theme.teal)
        .task { await onAppearRefresh() }
    }
}
