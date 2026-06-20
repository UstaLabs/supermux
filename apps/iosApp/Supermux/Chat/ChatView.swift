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
    @State private var dictation = SpeechDictation()
    @State private var transcribing = false
    @State private var micDenied = false
    @FocusState private var composing: Bool

    enum PaneTab: Hashable { case chat, native, terminal, editor, display }
    @State private var tab: PaneTab = .chat
    private var agentViewAvailable: Bool { (session.agent ?? "claude") == "claude" }
    /// Asset-catalog logo for the session's agent — used as the Native tab's icon so it
    /// shows the relevant brand (Claude/Codex/…), not a generic glyph. nil → fallback.
    private var agentAssetName: String? {
        switch session.agent.lowercased() {
        case "claude": return "claude"
        case "codex": return "codex"
        case "cursor": return "cursor"
        case "opencode": return "opencode"
        default: return nil
        }
    }
    /// The agent logo, pre-rendered to a tab-bar-sized padded image — the raw vector asset
    /// renders far too large as a tab icon, and SwiftUI frame modifiers don't constrain it
    /// in a Tab label. A UIImage's point size IS respected. Cached per agent.
    private static var tabIconCache: [String: Image] = [:]
    private var agentTabIcon: Image {
        guard let asset = agentAssetName else { return Image(systemName: "cube.transparent") }
        if let cached = Self.tabIconCache[asset] { return cached }
        guard let ui = UIImage(named: asset) else { return Image(systemName: "cube.transparent") }
        let canvas = CGSize(width: 26, height: 26)
        let inset: CGFloat = 4
        let rendered = UIGraphicsImageRenderer(size: canvas).image { _ in
            ui.draw(in: CGRect(x: inset, y: inset, width: canvas.width - 2 * inset, height: canvas.height - 2 * inset))
        }
        let image = Image(uiImage: rendered.withRenderingMode(.alwaysOriginal))
        Self.tabIconCache[asset] = image
        return image
    }

    /// Composer is expanded (full controls) when focused, when there's a draft or a
    /// staged attachment, or while recording; otherwise it rests as a slim glass pill.
    private var composerExpanded: Bool { composing || !draft.isEmpty || !pending.isEmpty || recorder.isRecording || dictation.isListening || transcribing }

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
        tab = .chat
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
        // Native iOS 26 TabView → the system draws the floating Liquid Glass bar and its
        // selection (no hand-rolled chrome). Editor/Display are placeholders for now.
        TabView(selection: $tab) {
            Tab("Chat", systemImage: "bubble.left", value: PaneTab.chat) { chatPane }
            if agentViewAvailable {
                Tab(value: PaneTab.native) {
                    nativePane
                } label: {
                    Label { Text("Native") } icon: { agentTabIcon }
                }
            }
            Tab("Terminal", systemImage: "terminal", value: PaneTab.terminal) {
                TerminalPanel(broker: broker, session: session)
            }
            Tab("Editor", systemImage: "chevron.left.forwardslash.chevron.right", value: PaneTab.editor) {
                EditorPane(broker: broker, session: session)
            }
            Tab("Display", systemImage: "display", value: PaneTab.display) {
                DisplayPane(broker: broker, session: session)
            }
        }
        .navigationTitle(session.name)
        .navigationSubtitle(navSubtitle)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                AgentLogo(agent: session.agent, size: 20)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let g = git, g.isRepo {
                    Button { runFinish() } label: { Label("Finish", systemImage: "arrow.triangle.merge") }
                        .tint(Theme.teal)
                }
                if (git?.isRepo ?? false) || !sessionLinks.isEmpty { navMenu }
            }
        }
        .toolbarTitleDisplayMode(.inline)
        .task { if draft.isEmpty, let d = ProcessInfo.processInfo.environment["SM_DRAFT"] { draft = d } }
        // Load per-session state on EVERY appearance — `.task(id:)` doesn't re-fire when
        // re-opening the *same* session (id unchanged), which left git/branch unloaded.
        // onAppear covers first-open + reopen; onChange covers switching (reused view).
        .onAppear { if loadedSessionId != session.id { loadSession() } }
        .onDisappear { dictation.cancel(); recorder.cancel() }
        .onChange(of: session.id) { _, _ in dictation.cancel(); recorder.cancel(); loadSession() }
        .onChange(of: draft) { _, new in UserDefaults.standard.set(new, forKey: draftKey) }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_TERMINAL"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .terminal
            }
        }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_NATIVE"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .native
            }
        }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_EDITOR"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .editor
            }
        }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_DISPLAY"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .display
            }
        }
        .task(id: session.id) {
            // Debug: raise the keyboard (focus composer) to repro the keyboard-relayout blank.
            guard ProcessInfo.processInfo.environment["SM_FOCUS"] == "1" else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            composing = true
        }
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

    // The Chat tab: the transcript (or the raw agent terminal when "Native" is on), with
    // the composer + Chat/Native toggle pinned above the system glass tab bar.
    private var chatPane: some View {
        transcript
            .safeAreaInset(edge: .bottom, spacing: 0) { chatBottomCluster }
    }

    // The raw agent terminal — the "Native" view of a (claude) session, now its own tab.
    private var nativePane: some View {
        TerminalPane(broker: broker, session: session, kind: "agent", terminalId: nil,
                     onExit: { tab = .chat })
    }


    // Composer + Chat/Native toggle as one morphing Liquid Glass cluster. The pane
    // switcher itself is now the native TabView bar below this, not hand-drawn chrome.
    private var chatBottomCluster: some View {
        VStack(spacing: 8) {
            if let banner { bannerView(banner) }
            dock
        }
        .padding(.bottom, 4)
    }

    // Placeholder for panes that aren't built yet (Editor, Display).
    private struct ComingSoonPane: View {
        let title: String
        let systemImage: String
        var body: some View {
            ContentUnavailableView {
                Label(title, systemImage: systemImage)
            } description: {
                Text("Coming soon")
            }
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

    /// Subtitle under the inline title: branch + sync status when in a repo, else the workdir.
    /// Kept off the title row so a long session name can't crowd it (it truncates on its own line).
    private var navSubtitle: String {
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }

    /// Overflow menu (•••): git actions (when a repo) + session links. Folded out of the
    /// title row so the bar stays one tidy line regardless of session-name length.
    @ViewBuilder private var navMenu: some View {
        Menu {
            if let g = git, g.isRepo {
                Button { gitAction { await broker.gitFetch(session.id) } } label: { Label("Fetch", systemImage: "arrow.down") }
                Button { gitAction { await broker.gitPush(session.id) } } label: { Label("Push", systemImage: "arrow.up") }
                Button { gitAction { await broker.gitPull(session.id) } } label: { Label("Pull", systemImage: "arrow.down.to.line") }
                if g.upstream == nil {
                    Button { gitAction { await broker.gitPublish(session.id) } } label: { Label("Publish", systemImage: "arrow.up.to.line") }
                }
            }
            if !sessionLinks.isEmpty {
                if git?.isRepo ?? false { Divider() }
                ForEach(sessionLinks, id: \.domain) { p in
                    if let u = linkURL(p) { Link(destination: u) { Label(proxyDisplayUrl(proxy: p), systemImage: "link") } }
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
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
            List {
                if blocks.isEmpty {
                    starterPrompts
                        .frame(maxWidth: .infinity)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(blocks) { block in
                        Group {
                            switch block {
                            case .message(let m): MessageRow(entry: m, broker: broker)
                            case .tools(let rows):
                                VStack(alignment: .leading, spacing: 4) {
                                    ForEach(rows) { ToolRowView(row: $0) }
                                }
                            }
                        }
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                        .listRowBackground(Color.clear)
                    }
                    if working {
                        workingIndicator
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                            .listRowBackground(Color.clear)
                    }
                    Color.clear.frame(height: 1).id("__bottom__")
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            // List (collection-view-backed) re-displays its rows correctly on every
            // relayout — including the keyboard-avoidance shrink — instead of blanking
            // like ScrollView+LazyVStack did (blank-on-keyboard, blank-on-open). Keyed
            // per session so each open builds fresh and defaultScrollAnchor lands at bottom.
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            // Tap anywhere on the transcript to dismiss the keyboard ("tap outside").
            // simultaneousGesture fires alongside scrolling + row/link taps without blocking
            // them, and is scoped to the transcript so composer controls are unaffected.
            .simultaneousGesture(TapGesture().onEnded { if composing { composing = false } })
            .scrollEdgeEffectStyle(.soft, for: .top)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .onChange(of: log.count) { _, _ in scrollToBottom(proxy) }
            .task(id: session.id) {
                // List needs an explicit initial scroll to the bottom — it doesn't honor
                // defaultScrollAnchor for first positioning the way ScrollView does, and
                // onChange(log.count) doesn't fire on open (count unchanged). List's
                // scroll-to-row is reliable, so this can't race/blank like LazyVStack did.
                try? await Task.sleep(nanoseconds: 100_000_000)
                scrollToBottom(proxy, animated: false)
            }
        }
        .id(session.id)
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

    // Chat/Native is now a tab in the bottom bar (see `body`), not an in-view control.

    private var dock: some View {
        VStack(spacing: 8) {
            if composerExpanded {
                if !slashMatches.isEmpty { slashMenu }
            }
            composerField
        }
        .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 2)
        .animation(.smooth(duration: 0.28), value: composerExpanded)
        .onChange(of: photoItems) { _, items in loadPhotos(items) }
        .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .images)
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            handleFiles(result)
        }
        .fullScreenCover(isPresented: $showCamera) { CameraPicker { addCameraImage($0) } }
    }

    // ONE glass card with an always-present TextField: tapping it focuses natively, so
    // there's no button→field handoff and no focus race (the earlier first-tap bug). The
    // extra controls fade in around the field when it's active (focused / non-empty / recording),
    // and the card morphs (corner radius + padding) between the slim resting pill and the card.
    private var composerField: some View {
        VStack(spacing: 8) {
            if composerExpanded {
                if !pending.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { ForEach(pending) { attachmentChip($0) } }
                    }
                }
                if dictation.isListening {
                    RecordingBar(elapsed: dictation.elapsed) { dictation.cancel() }
                } else if recorder.isRecording {
                    RecordingBar(elapsed: recorder.elapsed) { recorder.cancel() }
                } else if transcribing {
                    transcribingBar
                }
            }
            HStack(alignment: .center, spacing: 10) {
                if !composerExpanded {
                    Image(systemName: "plus").font(.body.weight(.medium)).foregroundStyle(.secondary)
                }
                TextField("Message \(session.name)…", text: $draft, axis: .vertical)
                    .lineLimit(composerExpanded ? (1...12) : (1...1))
                    .focused($composing)
                if !composerExpanded {
                    micButton
                }
            }
            if composerExpanded {
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
        }
        .padding(.horizontal, composerExpanded ? 12 : 16)
        .padding(.vertical, composerExpanded ? 12 : 10)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: composerExpanded ? 20 : 24, style: .continuous))
    }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespaces).isEmpty || !pending.isEmpty }

    // Mic → on-device dictation. Tap to start listening (RecordingBar), tap to stop →
    // POST the recognized draft to /transcribe → drop the cleaned text into the composer
    // (appended, never auto-sent). Falls back to audio recording + multipart /transcribe
    // when the device locale can't recognize on-device.
    private var micActive: Bool { dictation.isListening || recorder.isRecording }
    private var micButton: some View {
        Button {
            Task { await toggleMic() }
        } label: {
            Image(systemName: micActive ? "stop.circle.fill" : "mic")
                .font(.body.weight(.medium))
                .foregroundStyle(micActive ? .red : .secondary)
        }
        .disabled(transcribing)
    }

    private var transcribingBar: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text("Transcribing…").font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color(.tertiarySystemFill), in: Capsule())
    }

    /// Drive the mic: stop→transcribe if already capturing, otherwise start dictation
    /// (with audio-upload fallback). Always sets the composer text; never sends.
    private func toggleMic() async {
        if recorder.isRecording {
            // Fallback path was active — finish the clip and transcribe via multipart.
            guard let (data, name) = recorder.stop() else { return }
            await runTranscription { try await broker.transcribeAudio(sessionId: session.id, data: data, filename: name) }
            return
        }
        if dictation.isListening {
            let (text, _) = await dictation.stop()
            guard !text.isEmpty else { showBanner("Didn't catch that"); return }
            await runTranscription { try await broker.transcribeDraft(sessionId: session.id, draft: text) }
            return
        }
        // Not capturing yet → start on-device dictation, or fall back to recording.
        switch await dictation.start() {
        case .started:
            break
        case .denied:
            micDenied = true
        case .unavailable, .failed:
            // On-device recognition isn't available for this locale (or engine failed to
            // start) — fall back to recording audio for a server-side (multipart) transcribe.
            if case .denied = await recorder.start() { micDenied = true }
        }
    }

    /// Run a transcribe call with the "Transcribing…" state, then append the cleaned text
    /// to the composer (a space joins it to any existing draft). Errors → banner.
    private func runTranscription(_ call: @escaping () async throws -> String) async {
        transcribing = true
        defer { transcribing = false }
        do {
            let text = try await call()
            let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !cleaned.isEmpty else { return }
            let existing = draft.trimmingCharacters(in: .whitespacesAndNewlines)
            draft = existing.isEmpty ? cleaned : existing + " " + cleaned
            composing = true
        } catch {
            showBanner("Transcription failed")
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

    // Pane switching (incl. Chat/Native) is the native TabView bar (see `body`).
    // The old hand-drawn paneBar / paneCluster / segmented pill were removed.
}

// MessageRow, AttachmentView, Lightbox, ShareSheet, CameraPicker → ChatMessages.swift

// OptionSwitchSheet → OptionSwitchSheet.swift

// ChatBlock, ToolRow, ToolStatus, tsMs, buildChatBlocks, ToolRowView → ChatActivity.swift
