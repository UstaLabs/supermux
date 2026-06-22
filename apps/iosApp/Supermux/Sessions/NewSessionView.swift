import SwiftUI
import Shared

/// Compose-first launcher — mirrors the web SessionLauncherView: centered
/// "Let's build", a project dropdown, and a compose card (agent picker + the
/// first message) that spawns the session and sends that message on ↑.
struct NewSessionView: View {
    let broker: BrokerSession
    var onSpawned: (String) -> Void

    @State private var projects: [String] = []
    @State private var workdir = ""
    @State private var agent = "claude"
    @State private var model: String?
    @State private var models: [ModelInfo] = []
    @State private var projectSearch = false
    @State private var draft = ""
    @State private var launcherCommands: [SlashCommand] = []
    @State private var pending: [PendingAttachment] = []
    @State private var recorder = AudioRecorder()
    @State private var micDenied = false
    @State private var spawning = false
    // Worktree / base-branch (web LauncherWorktreePicker parity) — shown only when
    // the selected project is an eligible git repo.
    @State private var repoInfo: RepoInfo?
    @State private var useWorktree = true
    @State private var baseBranch = ""
    @State private var worktreeSheet = false
    @State private var worktreeFetching = false
    @State private var fetchedRepos: Set<String> = []
    @FocusState private var composing: Bool

    private let agents = ["claude", "codex", "cursor", "opencode"]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer().frame(height: 28)
                Image(systemName: "cube.fill").font(.system(size: 38)).foregroundStyle(.primary)
                Text("Let's build").font(.largeTitle.bold())
                projectPicker
                if repoInfo?.eligible == true { worktreePill }
                composeCard
                if !workdir.isEmpty {
                    Label(workdirLabel, systemImage: "folder")
                        .font(.caption.monospaced()).foregroundStyle(.secondary).padding(.top, 2)
                }
            }
            .padding(20).frame(maxWidth: .infinity)
        }
        .navigationTitle("New session").navigationBarTitleDisplayMode(.inline)
        .tint(Theme.teal)
        .task {
            projects = await broker.projects()
            // Debug: force the initial project for headless screenshots (e.g. an eligible repo).
            if let forced = ProcessInfo.processInfo.environment["SM_WORKDIR"], !forced.isEmpty {
                workdir = forced
            } else if workdir.isEmpty {
                workdir = projects.first ?? "~"
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
        .task(id: agent) {
            models = await broker.listModels(agent)
            model = nil
        }
        // Agent slash commands depend on both the agent and the chosen project.
        .task(id: "\(agent)|\(workdir)") {
            launcherCommands = workdir.isEmpty ? [] : await broker.previewCommands(agent, workdir)
        }
        // Git repo info for the worktree picker — refreshes whenever the project changes.
        .task(id: workdir) {
            guard !workdir.isEmpty else { repoInfo = nil; return }
            let info = await broker.repoInfo(workdir)
            repoInfo = info
            baseBranch = info?.currentBranch ?? ""
        }
        .sheet(isPresented: $projectSearch) {
            ProjectPickerSheet(broker: broker, projects: projects, current: workdir) { workdir = $0 }
        }
        .sheet(isPresented: $worktreeSheet) {
            WorktreeSheet(
                useWorktree: $useWorktree, baseBranch: $baseBranch,
                branches: repoInfo?.branches, currentBranch: repoInfo?.currentBranch,
                loading: worktreeFetching, onAppearRefresh: onWorktreeRefresh
            )
            .presentationDetents([.medium, .large])
        }
        .alert("Microphone access needed", isPresented: $micDenied) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Enable microphone access for supermux in Settings to record voice messages.")
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
            .background(Color(.secondarySystemBackground), in: Capsule())
        }
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
        VStack(alignment: .leading, spacing: 12) {
            if !pending.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) { ForEach(pending) { attachmentChip($0) } }
                }
            }
            if recorder.isRecording {
                RecordingBar(elapsed: recorder.elapsed,
                             onStop: {
                                 if let (data, name) = recorder.stop() {
                                     pending.append(PendingAttachment(data: data, filename: name, mime: "audio/mp4"))
                                 }
                             },
                             onCancel: { recorder.cancel() })
            }
            TextField("What should the agent do?", text: $draft, axis: .vertical)
                .lineLimit(3...8).focused($composing)
            if !slashMatches.isEmpty { slashMenu }
            HStack(spacing: 16) {
                Menu {
                    ForEach(agents, id: \.self) { a in Button(a.capitalized) { agent = a } }
                } label: {
                    HStack(spacing: 5) {
                        AgentLogo(agent: agent, size: 18)
                        Text(agent.capitalized).font(.subheadline.weight(.medium))
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.primary)
                }
                if !models.isEmpty {
                    Menu {
                        Button("Default") { model = nil }
                        ForEach(models, id: \.id) { m in Button(m.displayName) { model = m.id } }
                    } label: {
                        HStack(spacing: 4) {
                            Text(modelLabel).font(.subheadline.weight(.medium))
                            Image(systemName: "chevron.down").font(.caption2)
                        }.foregroundStyle(.secondary)
                    }
                }
                if !recorder.isRecording { micButton }
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
                .disabled(!canSpawn || spawning)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var canSpawn: Bool { !workdir.isEmpty }

    private func spawn() {
        spawning = true
        let firstMsg = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let toUpload = pending
        let eligible = repoInfo?.eligible == true
        let wantsWorktree = eligible ? useWorktree : false
        let base = (eligible && useWorktree && !baseBranch.isEmpty) ? baseBranch : nil
        Task {
            let id = await broker.spawn(workdir: workdir, agent: agent, name: nil, model: model,
                                        worktree: wantsWorktree, baseBranch: base)
            if let id, !id.isEmpty {
                // Attachments need a session id, so upload after spawn (like the first message).
                var ids: [String] = []
                for p in toUpload {
                    let kind = p.mime.hasPrefix("audio") ? "voice" : nil
                    if let fid = await broker.upload(id, data: p.data, filename: p.filename, mime: p.mime, kind: kind) {
                        ids.append(fid)
                    }
                }
                if !firstMsg.isEmpty || !ids.isEmpty {
                    broker.send(id, firstMsg, attachments: ids.isEmpty ? nil : ids)
                }
                onSpawned(id)
            }
            spawning = false
        }
    }

    private var micButton: some View {
        Button {
            if recorder.isRecording {
                if let (data, name) = recorder.stop() {
                    pending.append(PendingAttachment(data: data, filename: name, mime: "audio/mp4"))
                }
            } else {
                Task { if case .denied = await recorder.start() { micDenied = true } }
            }
        } label: {
            Image(systemName: recorder.isRecording ? "stop.circle.fill" : "mic")
                .font(.body.weight(.medium))
                .foregroundStyle(recorder.isRecording ? .red : .secondary)
        }
    }

    private func attachmentChip(_ p: PendingAttachment) -> some View {
        HStack(spacing: 5) {
            Image(systemName: p.mime.hasPrefix("audio") ? "waveform" : "photo").font(.caption2)
            Text(p.filename).font(.caption2).lineLimit(1)
            Button { pending.removeAll { $0.id == p.id } } label: {
                Image(systemName: "xmark.circle.fill").font(.caption2)
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .background(Color(.tertiarySystemFill), in: Capsule())
        .foregroundStyle(.secondary)
    }

    // MARK: - Slash commands (mirror ChatView, against launcher preview commands)

    // Active `/command` token at the end of the draft (cursor assumed at the end).
    private var slashQuery: String? {
        guard let r = draft.range(of: #"(?:^|\s)(/[^\s]*)$"#, options: .regularExpression) else { return nil }
        let token = draft[r].drop(while: { $0 == " " || $0 == "\n" || $0 == "\t" })
        return String(token.dropFirst()).lowercased()
    }
    private var slashMatches: [SlashCommand] {
        guard let q = slashQuery else { return [] }
        return Array(launcherCommands
            .filter { q.isEmpty || $0.name.lowercased().contains(q) || $0.family.lowercased().contains(q) }
            .prefix(8))
    }
    private var slashMenu: some View {
        VStack(spacing: 0) {
            ForEach(slashMatches, id: \.id) { cmd in
                Button { applyCommand(cmd) } label: {
                    HStack(spacing: 8) {
                        Text(cmd.sigil + cmd.name).font(.callout.weight(.semibold)).foregroundStyle(Theme.teal)
                        Text(cmd.family).font(.caption2).foregroundStyle(.tertiary)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 14).padding(.vertical, 9).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if cmd.id != slashMatches.last?.id { Divider() }
            }
        }
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.hairline, lineWidth: 1))
    }
    private func applyCommand(_ cmd: SlashCommand) {
        // Launcher preview commands are insert-only — control actions need a live session.
        guard cmd.action == nil else { clearSlashToken(); return }
        replaceSlashToken(with: (cmd.insertText.flatMap { $0.isEmpty ? nil : $0 }) ?? (cmd.sigil + cmd.name + " "))
    }
    private func replaceSlashToken(with insert: String) {
        if let r = draft.range(of: #"(?:^|\s)/[^\s]*$"#, options: .regularExpression) {
            let lead = draft[r].prefix(while: { $0 == " " || $0 == "\n" || $0 == "\t" })
            let prefixEnd = draft.index(r.lowerBound, offsetBy: lead.count)
            draft = String(draft[draft.startIndex..<prefixEnd]) + insert
        } else {
            draft = insert
        }
    }
    private func clearSlashToken() { replaceSlashToken(with: "") }
}

/// Forge-aware project picker — mirrors the web ProjectPathPicker omnibox:
/// pick a known project, type an arbitrary path, clone a repo from a connected
/// GitHub/GitLab account, or create a new repo (locally or on a forge).
private struct ProjectPickerSheet: View {
    let broker: BrokerSession
    let projects: [String]
    let current: String
    var onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

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
                        Button { onPick(query); dismiss() } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "arrow.turn.down.left").foregroundStyle(.secondary)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text("Use this path").foregroundStyle(.primary)
                                    Text(query).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                        }
                    }
                }
                if !filteredProjects.isEmpty {
                    Section("Projects") {
                        ForEach(filteredProjects, id: \.self) { p in
                            Button { onPick(p); dismiss() } label: { projectRow(name: basename(p), sub: label(p), checked: p == current) }
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
                            }.disabled(resolving)
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
                        }.disabled(resolving)
                        ForEach(connections, id: \.id) { c in
                            Button { resolveCreateForge(c.id) } label: {
                                Label("Create on \(c.host) — \(c.account.login)/\(query)", systemImage: "plus.circle")
                                    .lineLimit(1)
                            }.disabled(resolving)
                        }
                    }
                }
            }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Search projects, repos, or type a path")
            .navigationTitle("Project").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Cancel") { dismiss() } } }
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
            if let path { onPick(path); dismiss() }
        }
    }
    private func resolveCreateLocal() {
        let name = query
        resolving = true
        Task {
            let path = await broker.createLocalRepo(name)
            resolving = false
            if let path { onPick(path); dismiss() }
        }
    }
    private func resolveCreateForge(_ connectionId: String) {
        let name = query
        resolving = true
        Task {
            let path = await broker.createForge(connectionId: connectionId, name: name)
            resolving = false
            if let path { onPick(path); dismiss() }
        }
    }
}

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
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search branches")
            .navigationTitle("Worktree").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .tint(Theme.teal)
        .task { await onAppearRefresh() }
    }
}
