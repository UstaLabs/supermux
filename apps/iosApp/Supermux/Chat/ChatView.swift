import SwiftUI
import Shared
import UIKit
import PhotosUI
import UniformTypeIdentifiers

/// A photo staged in the composer, awaiting upload on send.
struct PendingAttachment: Identifiable {
    let id = UUID()
    let data: Data
    let filename: String
    let mime: String
}

/// Document-style transcript + info bar (agent · workdir · model/reasoning pills)
/// + live "Working…" indicator + glass composer with the Chat∣Native pill.
struct ChatView: View {
    let broker: BrokerSession
    let session: SessionInfo
    @State private var draft = ""
    @State private var modelSheet = false
    @State private var reasoningSheet = false
    @State private var reasoning: ReasoningResponse?
    @State private var pending: [PendingAttachment] = []
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showCamera = false
    @State private var proxies: [ProxyDto] = []
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showKillConfirm = false
    @State private var git: GitRemoteStatus?
    @State private var banner: String?
    @State private var noVerifyConfirm = false
    @State private var commitPrompt = false
    @State private var commitMsg = ""
    @State private var loadedSessionId: String?
    @State private var recorder = AudioRecorder()
    @State private var micDenied = false
    @FocusState private var composing: Bool
    @Environment(\.horizontalSizeClass) private var hSize

    private var sessionLinks: [ProxyDto] { proxies.filter { $0.sessionName == session.name } }
    private var draftKey: String { "cmux:draft:\(session.id)" }

    private var log: [LogEntry] { broker.messages[session.id] ?? [] }
    private var phase: String? { broker.agentPhase[session.id] }
    private var working: Bool {
        ["working", "thinking", "running", "tool", "busy", "sending"].contains(phase ?? "")
    }
    private var activityEvents: [ActivityEvent] { broker.activity[session.id] ?? [] }
    /// Messages + tool-call activity, time-merged into blocks (parity with the web ChatView).
    private var blocks: [ChatBlock] { buildChatBlocks(messages: log, activity: activityEvents) }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool = true) {
        if animated { withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("__bottom__", anchor: .bottom) } }
        else { proxy.scrollTo("__bottom__", anchor: .bottom) }
    }

    /// (Re)load everything tied to the current session. Runs on first open, reopen,
    /// and session switch — git status is retried so the branch reliably appears.
    private func loadSession() {
        loadedSessionId = session.id
        draft = UserDefaults.standard.string(forKey: draftKey)
            ?? ProcessInfo.processInfo.environment["SM_DRAFT"] ?? ""
        git = nil
        Task {
            for _ in 0..<8 {
                if let g = await broker.gitStatus(session.id) { git = g; return }
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        }
        Task { reasoning = await broker.reasoning(session.id) }
        Task { proxies = (try? await broker.api.proxies()) ?? [] }
    }

    var body: some View {
        VStack(spacing: 0) {
            infoBar
            Divider().opacity(0.4)
            transcript
            if let banner { bannerView(banner) }
            dock.layoutPriority(1)   // the composer wins height so the field can grow over the keyboard
            if hSize == .compact && !composing { paneBar }   // free space (+ tablet shows it in the header)
        }
        .navigationTitle(session.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let g = git, g.isRepo {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { runFinish() } label: { Label("Finish", systemImage: "arrow.triangle.merge") }
                        .tint(Theme.teal)
                }
            }
            if hSize != .compact {
                ToolbarItem(placement: .topBarTrailing) { paneCluster }
            }
        }
        .task { if draft.isEmpty, let d = ProcessInfo.processInfo.environment["SM_DRAFT"] { draft = d } }
        // Load per-session state on EVERY appearance — `.task(id:)` doesn't re-fire when
        // re-opening the *same* session (id unchanged), which left git/branch unloaded.
        // onAppear covers first-open + reopen; onChange covers switching (reused view).
        .onAppear { if loadedSessionId != session.id { loadSession() } }
        .onChange(of: session.id) { _, _ in loadSession() }
        .onChange(of: draft) { _, new in UserDefaults.standard.set(new, forKey: draftKey) }
        .sheet(isPresented: $modelSheet) {
            OptionSwitchSheet(title: "Model", broker: broker, session: session, kind: .model)
        }
        .sheet(isPresented: $reasoningSheet) {
            OptionSwitchSheet(title: "Reasoning", broker: broker, session: session, kind: .reasoning)
        }
        .alert("Rename session", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { broker.rename(session.id, to: renameText) }
        }
        .confirmationDialog("Kill “\(session.name)”?", isPresented: $showKillConfirm, titleVisibility: .visible) {
            Button("Kill session", role: .destructive) { broker.kill(session.id) }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("No verify script found", isPresented: $noVerifyConfirm, titleVisibility: .visible) {
            Button("Merge without verifying") { runFinish(skipVerify: true) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Uncommitted changes", isPresented: $commitPrompt) {
            TextField("Commit message", text: $commitMsg)
            Button("Cancel", role: .cancel) {}
            Button("Commit & finish") { runFinish(commitFirst: true, commitMessage: commitMsg.isEmpty ? "wip" : commitMsg) }
        } message: { Text("Commit the session's changes, then finish.") }
        .alert("Microphone access needed", isPresented: $micDenied) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Enable microphone access for supermux in Settings to record voice messages.")
        }
    }

    private func bannerView(_ text: String) -> some View {
        Text(text).font(.caption.weight(.medium)).foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(Theme.teal)
    }
    private func showBanner(_ text: String) {
        banner = text
        Task { try? await Task.sleep(nanoseconds: 4_000_000_000); banner = nil }
    }
    private func gitAction(_ op: @escaping () async -> GitOpResult?) {
        Task {
            let r = await op()
            showBanner(gitResultText(r))
            git = await broker.gitStatus(session.id)
        }
    }
    private func gitResultText(_ r: GitOpResult?) -> String {
        guard let r else { return "Failed" }
        switch r.status {
        case "pushed": return "Pushed"
        case "up_to_date": return "Up to date"
        case "clean": return "Pulled"
        case "rejected_non_ff": return "Push rejected — pull first"
        case "conflict": return "Conflict in \(r.files.count) file(s)"
        case "dirty": return "Uncommitted changes block the pull"
        case "auth_failed": return "Auth failed"
        case "error": return r.message ?? "Error"
        default: return r.status
        }
    }
    private func runFinish(skipVerify: Bool? = nil, commitFirst: Bool? = nil, commitMessage: String? = nil) {
        Task {
            guard let r = await broker.finish(session.id, skipVerify: skipVerify,
                                              commitFirst: commitFirst, commitMessage: commitMessage) else {
                showBanner("Finish failed"); return
            }
            switch r.status {
            case "integrated": showBanner("Merged into \(r.base ?? "base")")
            case "nothing_to_do": showBanner("Nothing to merge")
            case "no_verify": noVerifyConfirm = true; return
            case "uncommitted": commitMsg = ""; commitPrompt = true; return
            case "sync_conflict": showBanner("Sync conflict in \(r.files.count) file(s) — resolve via the agent")
            case "tests_failed": showBanner("Verify failed: \(r.command ?? "tests")")
            case "dirty_overlap": showBanner("Dirty overlap in \(r.files.count) file(s)")
            case "non_ff": showBanner("Base moved — retry")
            case "error": showBanner(r.message ?? "Error")
            default: showBanner(r.status)
            }
            git = await broker.gitStatus(session.id)
        }
    }

    private var infoBar: some View {
        HStack(spacing: 8) {
            AgentLogo(agent: session.agent, size: 22)
            if let g = git, g.isRepo, let b = g.branch {
                branchPill(g, b)
            } else {
                Text(formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir)))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            linksButton
        }
        .padding(.horizontal, 14).padding(.vertical, 6)
        .background(.bar)
    }

    private func branchPill(_ g: GitRemoteStatus, _ branch: String) -> some View {
        Menu {
            Button("Fetch") { gitAction { await broker.gitFetch(session.id) } }
            Button("Push") { gitAction { await broker.gitPush(session.id) } }
            Button("Pull") { gitAction { await broker.gitPull(session.id) } }
            if g.upstream == nil { Button("Publish") { gitAction { await broker.gitPublish(session.id) } } }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "arrow.triangle.branch").font(.caption2)
                Text(branch).font(.caption2.weight(.medium)).lineLimit(1)
                if g.upstream == nil {
                    Text("· not published").font(.caption2).foregroundStyle(.tertiary)
                } else {
                    if g.ahead > 0 { Text("↑\(g.ahead)").font(.caption2) }
                    if g.behind > 0 { Text("↓\(g.behind)").font(.caption2) }
                }
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Color(.tertiarySystemFill), in: Capsule())
        }
    }

    @ViewBuilder private var linksButton: some View {
        if sessionLinks.count == 1, let u = linkURL(sessionLinks[0]) {
            Link(destination: u) { Image(systemName: "link").font(.subheadline) }.tint(Theme.teal)
        } else if sessionLinks.count > 1 {
            Menu {
                ForEach(sessionLinks, id: \.domain) { p in
                    if let u = linkURL(p) { Link(proxyDisplayUrl(proxy: p), destination: u) }
                }
            } label: { Image(systemName: "link").font(.subheadline) }.tint(Theme.teal)
        }
    }
    private func linkURL(_ p: ProxyDto) -> URL? { URL(string: proxyUrl(proxy: p)) }

    private func pill(_ text: String, system: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: system).font(.caption2)
                Text(text).font(.caption.weight(.medium)).lineLimit(1)
            }
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(Color(.tertiarySystemFill), in: Capsule())
            .foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                if blocks.isEmpty {
                    starterPrompts
                } else {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(blocks) { block in
                            switch block {
                            case .message(let m): MessageRow(entry: m, broker: broker)
                            case .tools(let rows):
                                VStack(alignment: .leading, spacing: 4) {
                                    ForEach(rows) { ToolRowView(row: $0) }
                                }
                            }
                        }
                        if working { workingIndicator }
                        Color.clear.frame(height: 1).id("__bottom__")
                    }
                    .padding(16)
                }
            }
            // .defaultScrollAnchor keeps the bottom pinned as activity streams; only
            // explicitly scroll on a NEW message (not every tool-activity tick — that
            // constant scrolling during a busy session was blanking the transcript).
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .simultaneousGesture(TapGesture().onEnded { composing = false })   // tap transcript to dismiss keyboard
            .onChange(of: log.count) { _, _ in scrollToBottom(proxy) }
            .task(id: session.id) {
                try? await Task.sleep(nanoseconds: 120_000_000)
                scrollToBottom(proxy, animated: false)
            }
        }
    }

    private var workingIndicator: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let since = broker.agentSince[session.id]
            let elapsed = since.map { max(0, Int64(Date().timeIntervalSince1970 - Double($0) / 1000.0)) }
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(workingLabel + (elapsed.map { " · " + formatDuration(totalSeconds: $0) } ?? ""))
                    .font(.caption).foregroundStyle(.secondary)
                Button { broker.interrupt(session.id) } label: {
                    HStack(spacing: 3) {
                        Image(systemName: "stop.fill").font(.caption2)
                        Text("Stop").font(.caption.weight(.medium))
                    }
                    .foregroundStyle(.red)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.red.opacity(0.12), in: Capsule())
                }
                .buttonStyle(.plain)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    private var workingLabel: String {
        switch phase {
        case "sending": return "Sending…"
        case "thinking": return "Thinking…"
        default: return "Working…"
        }
    }

    private var starterPrompts: some View {
        VStack(spacing: 10) {
            Spacer().frame(height: 36)
            Image(systemName: "sparkles").font(.largeTitle).foregroundStyle(Theme.teal)
            Text("Start the conversation").font(.headline)
            ForEach(["What's the current state?", "Run the tests", "Summarize recent changes"], id: \.self) { p in
                Button { broker.send(session.id, p) } label: {
                    Text(p).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .background(Color(.secondarySystemBackground),
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain).foregroundStyle(.primary)
            }
        }
        .padding(20)
    }

    private var dock: some View {
        VStack(spacing: 8) {
            if !slashMatches.isEmpty { slashMenu }
            HStack(spacing: 3) {
                pillSeg("Chat", system: "bubble.left", on: true)
                pillSeg("Native", system: "terminal", on: false).opacity(0.5)
            }
            .padding(3).background(.quaternary, in: Capsule())

            VStack(spacing: 8) {
                if !pending.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { ForEach(pending) { attachmentChip($0) } }
                    }
                }
                if recorder.isRecording {
                    RecordingBar(elapsed: recorder.elapsed) { recorder.cancel() }
                }
                TextField("Message \(session.name)…", text: $draft, axis: .vertical)
                    .lineLimit(1...12)
                    .focused($composing)
                HStack(spacing: 12) {
                    Menu {
                        Button { showPhotos = true } label: { Label("Photos", systemImage: "photo") }
                        Button { showFiles = true } label: { Label("Files", systemImage: "folder") }
                        Button { showCamera = true } label: { Label("Camera", systemImage: "camera") }
                    } label: {
                        Image(systemName: "plus").font(.body.weight(.medium)).foregroundStyle(.secondary)
                    }
                    micButton
                    if let m = session.model, !m.isEmpty { pill(m, system: "cpu") { modelSheet = true } }
                    if reasoning?.visible ?? false {
                        pill(reasoning?.current ?? "reasoning", system: "brain") { reasoningSheet = true }
                    }
                    Spacer()
                    Button { sendMessage() } label: {
                        Image(systemName: "arrow.up")
                            .font(.headline.weight(.bold)).foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(canSend ? Theme.teal : Color.gray.opacity(0.4), in: Circle())
                    }
                    .disabled(!canSend)
                }
            }
            .padding(12).glassSurface(cornerRadius: 20)
            .onChange(of: photoItems) { _, items in loadPhotos(items) }
            .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .images)
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                handleFiles(result)
            }
            .fullScreenCover(isPresented: $showCamera) { CameraPicker { addCameraImage($0) } }
        }
        .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 6).background(.bar)
    }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespaces).isEmpty || !pending.isEmpty }

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

    private func loadPhotos(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        Task {
            for (i, item) in items.enumerated() {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    pending.append(PendingAttachment(data: data, filename: "image-\(pending.count + i + 1).jpg", mime: "image/jpeg"))
                }
            }
            photoItems = []
        }
    }
    private func handleFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                pending.append(PendingAttachment(data: data, filename: url.lastPathComponent, mime: mime))
            }
        }
    }
    private func addCameraImage(_ img: UIImage) {
        if let data = img.jpegData(compressionQuality: 0.85) {
            pending.append(PendingAttachment(data: data, filename: "photo-\(pending.count + 1).jpg", mime: "image/jpeg"))
        }
    }

    private func sendMessage() {
        let text = draft
        let toUpload = pending
        draft = ""; pending = []
        Task {
            var ids: [String] = []
            for p in toUpload {
                let kind = p.mime.hasPrefix("audio") ? "voice" : nil
                if let id = await broker.upload(session.id, data: p.data, filename: p.filename, mime: p.mime, kind: kind) {
                    ids.append(id)
                }
            }
            broker.send(session.id, text, attachments: ids.isEmpty ? nil : ids)
        }
    }
    private func pillSeg(_ t: String, system: String, on: Bool) -> some View {
        Label(t, systemImage: system).font(.caption.weight(.semibold))
            .padding(.horizontal, 14).padding(.vertical, 6)
            .foregroundStyle(on ? .white : .secondary)
            .background(on ? Theme.teal : .clear, in: Capsule())
    }

    // Active `/command` token at the end of the draft (cursor assumed at the end),
    // starting at the beginning or after whitespace — mirrors web activeSlashToken.
    private var slashQuery: String? {
        guard let r = draft.range(of: #"(?:^|\s)(/[^\s]*)$"#, options: .regularExpression) else { return nil }
        let token = draft[r].drop(while: { $0 == " " || $0 == "\n" || $0 == "\t" })
        return String(token.dropFirst()).lowercased()
    }
    private var slashMatches: [SlashCommand] {
        guard let q = slashQuery else { return [] }
        return Array((broker.commands[session.id] ?? [])
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
                        if cmd.action != nil {
                            Image(systemName: "bolt.fill").font(.caption2).foregroundStyle(.tertiary)
                        }
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
        if let action = cmd.action {
            clearSlashToken()
            switch action.kind {
            case "model": modelSheet = true
            case "rename": renameText = session.name; showRename = true
            case "mute": broker.toggleMute(session)
            case "stop": broker.interrupt(session.id)
            case "kill": showKillConfirm = true
            default: break   // spawn needs navigation we don't have from the chat
            }
            return
        }
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

    // Always-present pane bar (Chat active; Terminal/Editor/Display are later phases).
    private var paneBar: some View {
        HStack(spacing: 0) {
            paneTab("Chat", "bubble.left", on: true, enabled: true)
            paneTab("Terminal", "terminal", on: false, enabled: false)
            paneTab("Editor", "chevron.left.forwardslash.chevron.right", on: false, enabled: false)
            paneTab("Display", "display", on: false, enabled: false)
        }
        .padding(.top, 6).padding(.bottom, 2)
        .background(.bar)
    }
    private func paneTab(_ t: String, _ icon: String, on: Bool, enabled: Bool) -> some View {
        VStack(spacing: 3) {
            Image(systemName: icon).font(.system(size: 18))
            Text(t).font(.system(size: 9.5, weight: .medium))
        }
        .frame(maxWidth: .infinity)
        .foregroundStyle(on ? Theme.teal : .secondary)
        .opacity(enabled ? 1 : 0.4)
    }

    // Tablet/desktop: the pane switcher sits in the header as a segmented cluster.
    private var paneCluster: some View {
        HStack(spacing: 0) {
            paneIcon("bubble.left", on: true, enabled: true)
            paneIcon("terminal", on: false, enabled: false)
            paneIcon("chevron.left.forwardslash.chevron.right", on: false, enabled: false)
            paneIcon("display", on: false, enabled: false)
        }
        .padding(2).background(.quaternary, in: Capsule())
    }
    private func paneIcon(_ icon: String, on: Bool, enabled: Bool) -> some View {
        Image(systemName: icon).font(.system(size: 13))
            .frame(width: 32, height: 24)
            .foregroundStyle(on ? .white : .secondary)
            .background(on ? Theme.teal : Color.clear, in: Capsule())
            .opacity(enabled ? 1 : 0.45)
    }
}

// MessageRow, AttachmentView, Lightbox, ShareSheet, CameraPicker → ChatMessages.swift

/// Model / reasoning-level switcher sheet.
struct OptionSwitchSheet: View {
    enum Kind { case model, reasoning }
    let title: String
    let broker: BrokerSession
    let session: SessionInfo
    let kind: Kind
    @Environment(\.dismiss) private var dismiss
    @State private var options: [Opt] = []
    @State private var current: String?
    @State private var loading = true

    struct Opt: Identifiable { let id: String; let label: String }

    var body: some View {
        NavigationStack {
            List {
                if loading { HStack { Spacer(); ProgressView(); Spacer() } }
                ForEach(options) { o in
                    Button {
                        if kind == .model { broker.switchModel(session.id, o.id) }
                        else { broker.switchReasoning(session.id, o.id) }
                        dismiss()
                    } label: {
                        HStack {
                            Text(o.label)
                            Spacer()
                            if o.id == current { Image(systemName: "checkmark").foregroundStyle(Theme.teal) }
                        }
                    }
                    .foregroundStyle(.primary)
                }
            }
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task { await load() }
        }
        .tint(Theme.teal)
        .presentationDetents([.medium, .large])
    }

    private func load() async {
        switch kind {
        case .model:
            if let r = await broker.models(session.id) {
                options = r.models.map { Opt(id: $0.id, label: $0.displayName) }
                current = r.current
            }
        case .reasoning:
            if let r = await broker.reasoning(session.id) {
                options = r.levels.map { Opt(id: $0.id, label: $0.id.capitalized) }
                current = r.current
            }
        }
        loading = false
    }
}

// MARK: - Tool/activity blocks (parity with the web ChatView)

enum ChatBlock: Identifiable {
    case message(LogEntry)
    case tools([ToolRow])
    var id: String {
        switch self {
        case .message(let m): return "m:\(m.id)"
        case .tools(let rows): return "act:\(rows.first?.id ?? "")"
        }
    }
}

struct ToolRow: Identifiable {
    let id: String
    let toolName: String
    let summary: String?
    let input: String?
    let output: String?
    let status: ToolStatus
}

enum ToolStatus { case running, done, error }

private func tsMs(_ s: String) -> Double {
    if let d = Double(s) { return d > 1_000_000_000_000 ? d : d * 1000 }
    let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = f.date(from: s) ?? ISO8601DateFormatter().date(from: s) {
        return date.timeIntervalSince1970 * 1000
    }
    return 0
}

/// Merge messages + "tool" activity into time-ordered blocks; consecutive tool
/// rows cluster together. tool_result events are folded into their tool row.
func buildChatBlocks(messages: [LogEntry], activity: [ActivityEvent]) -> [ChatBlock] {
    var resultByCall: [String: ActivityEvent] = [:]
    for e in activity where e.kind == "tool_result" {
        if let c = e.callId { resultByCall[c] = e }
    }
    enum Payload { case message(LogEntry); case tool(ToolRow) }
    var rows: [(ts: Double, rank: Int, payload: Payload)] = []
    for m in messages { rows.append((tsMs(m.ts), 1, .message(m))) }
    for e in activity where e.kind == "tool" {
        let res = e.callId.flatMap { resultByCall[$0] }
        let status: ToolStatus = res == nil ? .running : (res?.title == "error" ? .error : .done)
        let title = e.title ?? ""
        let prefix = "\(e.tool ?? ""): "
        let summary = (e.tool != nil && title.hasPrefix(prefix)) ? String(title.dropFirst(prefix.count)) : title
        let id = e.seq.map { "a:\($0.intValue)" } ?? "a:\(e.ts):\(e.tool ?? "")"
        let row = ToolRow(id: id, toolName: e.tool ?? "tool",
                          summary: summary.isEmpty ? nil : summary,
                          input: e.detail, output: res?.detail, status: status)
        rows.append((tsMs(e.ts), 0, .tool(row)))
    }
    rows.sort { $0.ts != $1.ts ? $0.ts < $1.ts : $0.rank < $1.rank }
    var result: [ChatBlock] = []
    var cluster: [ToolRow] = []
    func flush() { if !cluster.isEmpty { result.append(.tools(cluster)); cluster = [] } }
    for r in rows {
        switch r.payload {
        case .message(let m): flush(); result.append(.message(m))
        case .tool(let t): cluster.append(t)
        }
    }
    flush()
    return result
}

/// One tool-call card: icon · label · summary · status dot, expandable input/output.
struct ToolRowView: View {
    let row: ToolRow
    @State private var open = false
    private var hasContent: Bool { !(row.input ?? "").isEmpty || !(row.output ?? "").isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { if hasContent { open.toggle() } } label: {
                HStack(spacing: 8) {
                    Image(systemName: icon).font(.caption).foregroundStyle(.secondary).frame(width: 16)
                    Text(label).font(.caption.weight(.semibold)).foregroundStyle(.primary)
                    if let s = row.summary {
                        Text(s).font(.caption2.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Circle().fill(statusColor).frame(width: 6, height: 6)
                    if hasContent {
                        Image(systemName: open ? "chevron.down" : "chevron.right")
                            .font(.caption2).foregroundStyle(.tertiary)
                    }
                }
                .padding(.horizontal, 10).padding(.vertical, 7).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open, hasContent {
                VStack(alignment: .leading, spacing: 8) {
                    if let i = row.input, !i.isEmpty { ioBlock("Input", i, error: false) }
                    if let o = row.output, !o.isEmpty { ioBlock("Output", o, error: row.status == .error) }
                }
                .padding(.horizontal, 10).padding(.bottom, 8)
            }
        }
        .background(Color(.secondarySystemBackground).opacity(0.7),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Theme.hairline, lineWidth: 0.5))
    }

    private var icon: String {
        switch row.toolName {
        case "Bash": return "terminal"
        case "Read": return "doc.text"
        case "Edit", "Write": return "square.and.pencil"
        case "Grep": return "magnifyingglass"
        case "Glob": return "folder"
        case "Task", "Agent": return "sparkles"
        case "Skill": return "book"
        case "WebFetch", "WebSearch": return "globe"
        default: return "wrench.and.screwdriver"
        }
    }
    private var label: String {
        row.toolName.hasPrefix("mcp__")
            ? (row.toolName.components(separatedBy: "__").last ?? row.toolName)
            : row.toolName
    }
    private var statusColor: Color {
        switch row.status {
        case .running: return Theme.teal
        case .done: return Theme.teal.opacity(0.55)
        case .error: return .red
        }
    }
    private func ioBlock(_ label: String, _ value: String, error: Bool) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            ScrollView(.vertical, showsIndicators: false) {
                Text(value).font(.caption2.monospaced())
                    .foregroundStyle(error ? .red : .secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: 200)
            .padding(8)
            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 6))
        }
    }
}
