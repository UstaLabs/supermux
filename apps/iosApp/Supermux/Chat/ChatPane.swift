import SwiftUI
import Shared
import PhotosUI
import UniformTypeIdentifiers

/// The chat transcript + composer cluster, extracted from `ChatView` so it can be
/// hosted both in the iPhone tab layout and in the future iPad multi-pane layout.
/// Rendered as the `.chat` tab body by `ChatView`; on iPad it will be placed directly
/// in a column alongside other panes.
struct ChatPane: View {
    let broker: BrokerSession
    let session: SessionInfo

    // MARK: - Bindings from ChatView (state that toolbar actions also mutate)
    /// Drives the "Rename session" alert in `ChatView`.
    @Binding var showRename: Bool
    /// Editable name field inside the rename alert.
    @Binding var renameText: String
    /// Drives the kill-confirmation dialog in `ChatView`.
    @Binding var showKillConfirm: Bool
    /// Transient status banner rendered at the top of the bottom cluster.
    @Binding var banner: String?

    // MARK: - Composer (shared engine)
    @State private var composer: ComposerModel

    // MARK: - Picker / sheet state (still owned by the view)
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showCamera = false
    @State private var showVideoCamera = false
    @State private var composing = false

    // MARK: - Model / reasoning sheet state
    @State private var modelSheet = false
    @State private var reasoningSheet = false
    @State private var reasoning: ReasoningResponse?

    // MARK: - Init

    init(broker: BrokerSession, session: SessionInfo,
         showRename: Binding<Bool>, renameText: Binding<String>,
         showKillConfirm: Binding<Bool>, banner: Binding<String?>) {
        self.broker = broker
        self.session = session
        self._showRename = showRename
        self._renameText = renameText
        self._showKillConfirm = showKillConfirm
        self._banner = banner
        self._composer = State(initialValue: ComposerModel(
            context: Self.makeContext(broker: broker, session: session),
            initialDraft: Self.loadDraft(session: session)
        ))
    }

    private static func makeContext(broker: BrokerSession, session: SessionInfo) -> ComposerContext {
        ComposerContext(
            glossary: { (try? await broker.fetchGlossary()) ?? [] },
            cleanupTranscript: { try await broker.transcribeDraft(sessionId: session.id, draft: $0) },
            audioFallbackTranscribe: { try await broker.transcribeAudio(sessionId: session.id, data: $0, filename: $1) }
        )
    }

    private static func loadDraft(session: SessionInfo) -> String {
        UserDefaults.standard.string(forKey: "cmux:draft:\(session.id)")
            ?? ProcessInfo.processInfo.environment["SM_DRAFT"] ?? ""
    }

    // MARK: - Derived computeds

    private var draftKey: String { "cmux:draft:\(session.id)" }

    /// True when the clipboard holds something we can stage as an attachment (an image, or a PDF) —
    /// gates the composer's "+ → Paste" item. Type-presence check only (privacy-safe; no banner).
    private var pasteboardHasAttachment: Bool {
        SMPasteboard.hasImages || SMPasteboard.contains(.pdf)
    }

    /// Composer is expanded (full controls) when focused, when there's a draft or a
    /// staged attachment, or while recording; otherwise it rests as a slim glass pill.
    private var composerExpanded: Bool {
        composing || composer.hasContent || composer.isBusy
    }

    // MARK: - Body

    var body: some View {
        // The transcript is a *separate, Equatable* view. Composer keystrokes mutate this view's
        // composer @State and re-run `body`, but `.equatable()` (keyed on session.id) makes
        // SwiftUI skip re-evaluating the transcript on those re-runs — so the message list is NOT
        // rebuilt (no buildChatBlocks, no List re-diff) on each keypress, which is what keeps typing
        // fast in long chats. New messages still update it directly via @Observable (BrokerSession).
        SessionTranscript(broker: broker, session: session)
            .equatable()
            // Tap anywhere on the transcript to dismiss the keyboard ("tap outside"). Applied here,
            // not inside SessionTranscript, so the transcript stays free of composer focus state and
            // the Equatable gate holds. simultaneousGesture fires alongside scrolling + row/link taps
            // without blocking them, and is scoped to the transcript so composer controls are unaffected.
            .simultaneousGesture(TapGesture().onEnded { if composing { composing = false } })
            .safeAreaInset(edge: .bottom, spacing: 0) { chatBottomCluster }
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
            .alert("Microphone access needed", isPresented: $composer.micDenied) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Enable microphone access for supermux in Settings to record voice messages.")
            }
            .onAppear { loadPane() }
            .onDisappear { composer.cancelMic() }
            .onChange(of: session.id) { _, _ in
                composer.reconfigure(
                    context: Self.makeContext(broker: broker, session: session),
                    draft: Self.loadDraft(session: session)
                )
                loadPane()
            }
            .onChange(of: composer.draft) { _, new in UserDefaults.standard.set(new, forKey: draftKey) }
            .onChange(of: composer.refocusToken) { _, _ in composing = true }
            .onChange(of: composer.status) { _, s in
                guard let s else { return }
                showBanner(s)
                composer.status = nil
            }
            // Observe the id (SlashCommand isn't Equatable). The value is cleared right after
            // handling, so the same control command re-applies fine on the next tap.
            .onChange(of: composer.controlCommandToHandle?.id) { _, _ in
                guard let cmd = composer.controlCommandToHandle else { return }
                handleControlCommand(cmd)
                composer.controlCommandToHandle = nil
            }
    }

    // MARK: - Load

    /// (Re)load per-session state owned by this pane. Called on first open and session switch.
    private func loadPane() {
        // Seed the transcript if we don't have it yet — a session resumed from archive arrives
        // via `session_added` (no history), so without this its chat would be empty until the
        // next snapshot/restart. No-op for sessions the snapshot already populated.
        Task { await broker.ensureMessagesLoaded(session.id) }
        Task { reasoning = await broker.reasoning(session.id) }
        Task { await composer.loadGlossary() }
    }

    // MARK: - Control commands + banner

    /// Show a transient status banner via the binding, auto-clearing after 4s.
    private func showBanner(_ text: String) {
        banner = text
        Task { try? await Task.sleep(nanoseconds: 4_000_000_000); banner = nil }
    }

    /// Handle a control slash command (lifted from the old inline `applyCommand` switch).
    private func handleControlCommand(_ cmd: SlashCommand) {
        switch cmd.action?.kind {
        case "model": modelSheet = true
        case "rename": renameText = session.name; showRename = true
        case "mute": broker.toggleMute(session)
        case "stop": broker.interrupt(session.id)
        case "kill": showKillConfirm = true
        default: break   // spawn needs navigation we don't have from chat
        }
    }

    // MARK: - Bottom cluster

    /// Composer + banner, pinned above the system glass tab bar.
    private var chatBottomCluster: some View {
        VStack(spacing: 8) {
            if let banner { bannerView(banner) }
            dock
        }
        .padding(.bottom, 4)
    }

    private func bannerView(_ text: String) -> some View {
        Text(text).font(.caption.weight(.medium)).foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(Theme.teal)
    }

    // MARK: - Composer dock

    private var dock: some View {
        let cmds = broker.commands[session.id] ?? []
        return VStack(spacing: 8) {
            if composerExpanded {
                let matches = composer.slashMatches(in: cmds)
                if !matches.isEmpty {
                    SlashMenu(matches: matches, showsActionGlyph: true) { composer.applyCommand($0) }
                }
            }
            composerField
        }
        .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 2)
        .animation(.smooth(duration: 0.28), value: composerExpanded)
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
    }

    // ONE glass card with an always-present TextField: tapping it focuses natively, so
    // there's no button→field handoff and no focus race (the earlier first-tap bug). The
    // extra controls fade in around the field when it's active (focused / non-empty / recording),
    // and the card morphs (corner radius + padding) between the slim resting pill and the card.
    private var composerField: some View {
        VStack(spacing: 8) {
            if composer.dictation.isListening || composer.recorder.isRecording {
                // Recording takes over the composer. On-device shows the live transcript above
                // the big STOP / small cancel; the audio-fallback path just shows the timer.
                if composer.dictation.isListening && !composer.dictation.transcript.isEmpty {
                    ScrollView {
                        Text(composer.dictation.transcript)
                            .font(.callout).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 120)
                }
                RecordingBar(elapsed: composer.dictation.isListening ? composer.dictation.elapsed : composer.recorder.elapsed,
                             onStop: { Task { await composer.toggleMic() } },
                             onCancel: { composer.cancelMic() })
            } else {
            if composerExpanded {
                if !composer.pending.isEmpty {
                    AttachmentTray(pending: composer.pending, onRemove: { composer.removeAttachment($0) },
                                   onRetry: { _ in Task { await uploadThenSend() } })
                }
                if composer.transcribing {
                    transcribingBar
                }
                if composer.micStarting {
                    preparingBar
                }
            }
            HStack(alignment: .center, spacing: 10) {
                if !composerExpanded {
                    AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                               showVideoCamera: $showVideoCamera,
                               showPaste: pasteboardHasAttachment,
                               onPaste: { Task { await composer.pasteClipboard() } })
                }
                ComposerInput(
                    text: $composer.draft,
                    placeholder: "Message \(session.name)…",
                    maxLines: composerExpanded ? 12 : 1,
                    isFocused: $composing,
                    canSubmit: composer.canSubmit,
                    onSubmit: { sendMessage() },
                    onPasteAttachment: {
                        guard SMPasteboard.hasImages || SMPasteboard.contains(.pdf) else { return false }
                        Task { await composer.pasteClipboard() }
                        return true
                    }
                )
                if !composerExpanded {
                    MicButton(model: composer)
                }
            }
            if composerExpanded {
                HStack(spacing: 12) {
                    AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                               showVideoCamera: $showVideoCamera,
                               showPaste: pasteboardHasAttachment,
                               onPaste: { Task { await composer.pasteClipboard() } })
                    MicButton(model: composer)
                    pill(modelPillLabel, system: "cpu") { modelSheet = true }
                    if reasoning?.visible ?? false {
                        pill(reasoning?.current ?? "reasoning", system: "brain") { reasoningSheet = true }
                    }
                    Spacer()
                    Button { sendMessage() } label: {
                        Image(systemName: "arrow.up")
                            .font(.headline.weight(.bold)).foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(composer.canSubmit ? Theme.teal : Color.gray.opacity(0.4), in: Circle())
                    }
                    .disabled(!composer.canSubmit)
                }
            }
            }
        }
        .padding(.horizontal, composerExpanded ? 12 : 16)
        .padding(.vertical, composerExpanded ? 12 : 10)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: composerExpanded ? 20 : 24, style: .continuous))
    }

    /// Web ModelPill parity: always visible; "Default" when unset, else a short label.
    private var modelPillLabel: String {
        guard let id = session.model, !id.isEmpty else { return "Default" }
        if let slash = id.lastIndex(of: "/") {
            return String(id[id.index(after: slash)...])
        }
        return id
    }

    private var transcribingBar: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text("Transcribing…").font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.smTertiaryFill, in: Capsule())
    }

    /// Shown while the first-run on-device speech model is downloading/preparing — so the
    /// composer never looks frozen and the user doesn't re-tap into a crash.
    private var preparingBar: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text("Preparing speech…").font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.smTertiaryFill, in: Capsule())
    }

    // MARK: - Send

    private func sendMessage() {
        guard composer.canSubmit else { return }
        Task { await uploadThenSend() }
    }

    /// Upload every not-yet-uploaded attachment (chunked+resumable for file-URL videos, single
    /// POST for images/audio), driving each chip's progress. On any failure, mark it failed and
    /// STOP — keep the draft + chips so the user can retry; never send minus an attachment.
    @MainActor
    private func uploadThenSend() async {
        for p in composer.pending where p.uploadedFileId == nil {
            composer.markUploading(p.id)
            // Audio clips → "voice"; images and videos stay nil so the broker infers the kind
            // from the MIME (video/* → "video" server-side). Never mislabel a video as audio.
            let kind = p.mime.hasPrefix("audio") ? "voice" : nil
            let fid: String?
            if let url = p.fileURL {
                fid = await broker.uploadResumable(session.id, source: NSFileHandleChunkSource(path: url.path),
                    filename: p.filename, mime: p.mime, kind: kind) { sent, total in
                        Task { @MainActor in composer.setProgress(p.id, total > 0 ? Double(sent) / Double(total) : 0) }
                    }
            } else if let data = p.data {
                fid = await broker.upload(session.id, data: data, filename: p.filename, mime: p.mime, kind: kind)
            } else {
                fid = nil
            }
            if let fid { composer.markUploaded(p.id, fid) } else { composer.markFailed(p.id); return }
        }
        let ids = composer.pending.compactMap { $0.uploadedFileId }
        let text = composer.draft
        composer.draft = ""
        composer.pending = []
        broker.send(session.id, text, attachments: ids.isEmpty ? nil : ids)
    }

    // MARK: - Shared pill helper

    private func pill(_ text: String, system: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: system).font(.caption2)
                Text(text).font(.caption.weight(.medium)).lineLimit(1)
            }
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(Color.smTertiaryFill, in: Capsule())
            .foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
    }
}

/// The chat transcript (messages + tool-activity), split out of `ChatPane` as its own
/// **Equatable** view. The composer's rapidly-changing state (`draft`, expansion, etc.) lives on
/// `ChatPane`, so every keystroke re-runs `ChatPane.body`. `.equatable()` (keyed on `session.id`)
/// makes SwiftUI skip re-evaluating THIS view on those re-runs, so the transcript is *not* rebuilt
/// — no `buildChatBlocks` (sort + cluster over the whole history) and no List re-diff — on each
/// keypress. That whole-list rebuild per keystroke was the cause of slow typing in long chats.
///
/// Liveness is unaffected: `BrokerSession` is `@Observable`, so new messages/activity invalidate
/// this node directly (it reads `broker.messages[...]` / `broker.activity[...]`), independent of
/// the Equatable gate — the gate only suppresses redundant, composer-driven re-evaluations.
struct SessionTranscript: View, Equatable {
    let broker: BrokerSession
    let session: SessionInfo

    // Only the session identity gates parent-driven re-evaluation: the content is keyed by
    // `session.id` and refreshed via @Observable, so nothing else here needs comparing.
    static func == (lhs: SessionTranscript, rhs: SessionTranscript) -> Bool {
        lhs.session.id == rhs.session.id
    }

    private var log: [LogEntry] { broker.messages[session.id] ?? [] }
    private var phase: String? { broker.agentPhase[session.id] }
    private var working: Bool { broker.agentWorking[session.id] == true }
    private var sending: Bool { broker.pendingSend.contains(session.id) }
    private var activityEvents: [ActivityEvent] { broker.activity[session.id] ?? [] }
    /// Messages + tool-call activity, time-merged into blocks (parity with the web ChatView).
    private var blocks: [ChatBlock] { buildChatBlocks(messages: log, activity: activityEvents) }

    var body: some View {
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
                            case .message(let m): MessageRow(entry: m, broker: broker,
                                                             sessionId: session.id, workdir: session.workdir)
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
                    } else if sending {
                        sendingIndicator
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

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool = true) {
        if animated { withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("__bottom__", anchor: .bottom) } }
        else { proxy.scrollTo("__bottom__", anchor: .bottom) }
    }

    private var workingIndicator: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let since = broker.agentWorkingSince[session.id]
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
        switch broker.agentDetail[session.id] {
        case "running": return "Working…"
        default: return "Thinking…"
        }
    }

    private var sendingIndicator: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text("Sending…")
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

    private var starterPrompts: some View {
        VStack(spacing: 10) {
            Spacer().frame(height: 36)
            Image(systemName: "sparkles").font(.largeTitle).foregroundStyle(Theme.teal)
            Text("Start the conversation").font(.headline)
            ForEach(["What's the current state?", "Run the tests", "Summarize recent changes"], id: \.self) { p in
                Button { broker.send(session.id, p) } label: {
                    Text(p).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .background(Color.smSecondaryBackground,
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain).foregroundStyle(.primary)
            }
        }
        .padding(20)
    }
}
