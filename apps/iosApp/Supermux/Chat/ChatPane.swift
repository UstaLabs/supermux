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
        .smContentWidthCap()
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
                HStack(spacing: 10) {
                    AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                               showVideoCamera: $showVideoCamera,
                               showPaste: pasteboardHasAttachment,
                               onPaste: { Task { await composer.pasteClipboard() } })
                    MicButton(model: composer)
                    SoftFilterPill(text: modelPillLabel, systemImage: "cpu", active: session.model != nil) {
                        modelSheet = true
                    }
                    .smOptionPicker(isPresented: $modelSheet) {
                        OptionSwitchSheet(title: "Model", broker: broker, session: session, kind: .model)
                    }
                    if reasoning?.visible ?? false {
                        // Live session state first (kept fresh by session_state frames);
                        // the fetched payload supplies the resolved default when unset.
                        SoftFilterPill(
                            text: session.reasoningLevel ?? reasoning?.current ?? "reasoning",
                            systemImage: "brain",
                            active: session.reasoningLevel != nil
                        ) { reasoningSheet = true }
                            .smOptionPicker(isPresented: $reasoningSheet) {
                                OptionSwitchSheet(title: "Reasoning", broker: broker, session: session, kind: .reasoning)
                            }
                    }
                    Spacer(minLength: 0)
                    SendCircleButton(enabled: composer.canSubmit, size: 34) { sendMessage() }
                }
            }
            }
        }
        .padding(.horizontal, composerExpanded ? 12 : 16)
        .padding(.vertical, composerExpanded ? 12 : 10)
        .composerSurface(cornerRadius: composerExpanded ? 20 : 24)
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

/// Per-row chrome for the transcript. On iOS these are `List` row modifiers; on macOS the rows
/// live in a `LazyVStack`, where the same spacing is plain padding and there are no separators or
/// row backgrounds to suppress.
private extension View {
    @ViewBuilder func transcriptRow() -> some View {
        #if os(macOS)
        self.smContentWidthCap()
            .padding(.horizontal, 16)
            .padding(.vertical, 5)
        #else
        self.smContentWidthCap()
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
            .listRowBackground(Color.clear)
        #endif
    }
}

#if os(macOS)
/// Owns the semantic scroll position for one macOS transcript.
///
/// A bottom `defaultScrollAnchor` is only an initial layout hint. With variable-height rows,
/// `LazyVStack` can revise its estimates after that hint has been applied and leave the viewport in
/// an unrealized gap until a wheel event forces another layout. The caller keeps a small eager tail
/// at the bottom, while `ScrollPosition` keeps that real bottom edge stable as estimates settle.
struct MacTranscriptScrollView<Content: View>: View {
    let messageCount: Int
    let content: Content
    @State private var position = ScrollPosition(idType: String.self, edge: .bottom)

    init(messageCount: Int, @ViewBuilder content: () -> Content) {
        self.messageCount = messageCount
        self.content = content()
    }

    var body: some View {
        ScrollView {
            content
        }
        .scrollPosition($position, anchor: .bottom)
        // Only use the default anchor to align transcripts shorter than the viewport. Initial
        // positioning is owned by `position`, so the two mechanisms never race.
        .defaultScrollAnchor(.bottom, for: .alignment)
        .onAppear {
            position.scrollTo(edge: .bottom)
        }
        // Un-animated on purpose. `withAnimation` here drove the bottom-pin through a 0.2s
        // animated transaction, which re-lays out the transcript every frame for the duration —
        // and `messageCount` also changes on a session switch, so opening a chat paid for an
        // animation nobody asked for. Jumping straight to the bottom is what the user wants and
        // costs one layout pass.
        .onChange(of: messageCount) { _, _ in
            position.scrollTo(edge: .bottom)
        }
        .softScrollEdges()
    }
}
#endif

struct SessionTranscript: View, Equatable {
    let broker: BrokerSession
    let session: SessionInfo
    /// Global chat density (UserDefaults; web `cmux:chat-detail` parity). Observed so Low↔Medium flips re-render.
    @AppStorage("chatDetailLevel") private var chatDetailRaw: String = "medium"

    // Only the session identity gates parent-driven re-evaluation: the content is keyed by
    // `session.id` and refreshed via @Observable, so nothing else here needs comparing.
    // chatDetailRaw is self-invalidating via @AppStorage (not compared here).
    static func == (lhs: SessionTranscript, rhs: SessionTranscript) -> Bool {
        lhs.session.id == rhs.session.id
    }

    /// Per-session buffer (not the flat `messages`/`activity` maps) so other sessions' traffic
    /// does not re-render this transcript. See `BrokerSession.chatBuffer(for:)`.
    private var chat: SessionChatBuffer { broker.chatBuffer(for: session.id) }
    private var log: [LogEntry] { chat.messages }
    private var phase: String? { broker.agentPhase[session.id] }
    private var working: Bool { broker.agentWorking[session.id] == true }
    private var sending: Bool { broker.pendingSend.contains(session.id) }
    private var waiting: Bool { broker.agentWaiting[session.id] == true }
    /// Only RUNNING tasks get a chip — a chip clears the moment its task finishes, so
    /// chips never accumulate. The outcome (done/failed) lives in the chat stream.
    private var visibleBgTasks: [ServerFrameBgTask] {
        (broker.bgTasks[session.id] ?? []).filter { $0.status == "running" }
    }
    private var activityEvents: [ActivityEvent] { chat.activity }
    private var chatDetail: ChatDetailLevel { ChatDetailLevel.parse(chatDetailRaw) }
    /// Messages + tool-call activity, time-merged into blocks (parity with the web ChatView).
    ///
    /// Read from the per-session buffer, which derives it when the transcript actually changes —
    /// building it HERE re-ran the whole-history merge on every observation tick (agent phase,
    /// working flag, bg tasks) that this body also reads. See `SessionChatBuffer.blocks`.
    private var blocks: [ChatBlock] {
        guard chatDetail.effective == .low else { return chat.blocks }
        return chat.blocks.filter { if case .message = $0 { return true } else { return false } }
    }

    /// Roughly one viewport. Unlike the reverted 24-row experiment, this does not render more
    /// message rows than the fast pure-LazyVStack path normally realizes (~7), but it gives the
    /// bottom edge a real height so macOS cannot initially park in an unrealized estimate gap.
    private static let macEagerTailCount = 8

    var body: some View {
        // Bound once: `blocks` is read by both the empty-check and the `ForEach`, and in low chat
        // detail it filters the buffer's timeline. (The whole-history merge itself now happens in
        // `SessionChatBuffer`, not here.)
        let blocks = self.blocks
        Group {
            #if os(macOS)
            scroller(blocks: blocks)
            #else
            ScrollViewReader { proxy in
                scroller(blocks: blocks)
                    .onChange(of: log.count) { _, _ in scrollToBottom(proxy) }
                    .task(id: session.id) {
                        // Assert the bottom on open. `onChange(log.count)` doesn't fire here (the count
                        // hasn't changed), and neither container positions itself at the bottom purely
                        // from `defaultScrollAnchor` on first layout. Immediate pass, then one delayed
                        // pass as a safety net for a container that hasn't finished laying out yet; both
                        // are un-animated and idempotent, so running twice is invisible.
                        scrollToBottom(proxy, animated: false)
                        try? await Task.sleep(nanoseconds: 100_000_000)
                        scrollToBottom(proxy, animated: false)
                    }
            }
            #endif
        }
        // Identity per session. Removing this was measured (2026-07-29) on the theory that the
        // teardown+rebuild was the fixed per-switch cost: the result was MIXED (4 of 6 sessions
        // faster, the two content-heaviest notably slower, with NSTextView.make jumping 6→17 per
        // click as ForEach churned rows), so it is not a win. It is also load-bearing:
        // `MacTranscriptScrollView` resets its `@State` scroll position from `onAppear`, which
        // only fires because this `.id()` recreates the view on a switch.
        .id(session.id)
    }

    /// The scrolling container.
    ///
    /// **macOS uses `ScrollView` + lazy history + an 8-block eager tail; iOS keeps `List`.**
    /// This split is deliberate and load-bearing in both directions:
    ///
    /// - `List` realizes a fixed batch of rows far beyond the viewport. Measured on a 1440x900
    ///   window with a fresh 200-message session: **23 rows / 11,684 pt realized for a 900 pt
    ///   viewport** — 13x more than is visible — costing ~257 ms of blocked main thread per session
    ///   switch. Lazy history realizes strictly by viewport (7 rows / 1,554 pt); the 8-block
    ///   eager tail prevents bottom-anchor estimate gaps while keeping work in that same range.
    /// - iOS must keep `List`: this code moved FROM `ScrollView`+`LazyVStack` TO `List` precisely
    ///   because LazyVStack blanked on the **keyboard-avoidance relayout** (blank-on-keyboard,
    ///   blank-on-open). macOS has no keyboard avoidance, so it doesn't inherit that bug — but iOS
    ///   still does. Do not "unify" these without re-testing the iOS keyboard case.
    @ViewBuilder private func scroller(blocks: [ChatBlock]) -> some View {
        #if os(macOS)
        MacTranscriptScrollView(messageCount: log.count) {
            if blocks.isEmpty {
                starterPrompts
                    .frame(maxWidth: .infinity)
                    .smContentWidthCap()
            } else {
                let eagerStart = max(0, blocks.count - Self.macEagerTailCount)
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(blocks.prefix(eagerStart)) { block in
                        blockRow(block)
                            .smContentWidthCap()
                            .padding(.horizontal, 16)
                            .padding(.vertical, 5)
                    }
                }
                .frame(maxWidth: .infinity)
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(blocks.suffix(from: eagerStart)) { block in
                        blockRow(block)
                            .smContentWidthCap()
                            .padding(.horizontal, 16)
                            .padding(.vertical, 5)
                    }
                    trailers
                    Color.clear.frame(height: 1).id("__bottom__")
                }
                .frame(maxWidth: .infinity)
            }
        }
        #else
        List {
            if blocks.isEmpty {
                starterPrompts
                    .frame(maxWidth: .infinity)
                    .smContentWidthCap()
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } else {
                ForEach(blocks) { block in
                    blockRow(block).transcriptRow()
                }
                trailers
                Color.clear.frame(height: 1).id("__bottom__")
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .defaultScrollAnchor(.bottom)
        .scrollDismissesKeyboard(.interactively)
        .softScrollEdges()
        #endif
    }

    /// One timeline block — a message, or a cluster of tool rows.
    @ViewBuilder private func blockRow(_ block: ChatBlock) -> some View {
        switch block {
        case .message(let m):
            // `.equatable()` keeps MessageRow (and its MarkdownView) from re-running body when
            // SessionTranscript rebuilds for an unrelated broker observation (other sessions
            // appending messages into the shared `messages` dict, phase ticks, etc.).
            MessageRow(entry: m, broker: broker, sessionId: session.id, workdir: session.workdir)
                .equatable()
        case .tools(let rows):
            VStack(alignment: .leading, spacing: 2) {
                ForEach(rows) { ToolRowView(row: $0, highDetail: chatDetail.effective == .high) }
            }
        }
    }

    /// Background-task chips + the single working/sending/waiting indicator, in that order.
    @ViewBuilder private var trailers: some View {
        if !visibleBgTasks.isEmpty {
            BgTaskChipsView(tasks: visibleBgTasks).transcriptRow()
        }
        if working {
            workingIndicator.transcriptRow()
        } else if sending {
            sendingIndicator.transcriptRow()
        } else if waiting {
            waitingIndicator.transcriptRow()
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool = true) {
        if animated { withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("__bottom__", anchor: .bottom) } }
        else { proxy.scrollTo("__bottom__", anchor: .bottom) }
    }

    private var workingIndicator: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let since = broker.agentWorkingSince[session.id]
            let elapsed = since.map { max(0, Int64(Date().timeIntervalSince1970 - Double($0) / 1000.0)) }
            let duration = elapsed.map { formatDuration(totalSeconds: $0) } ?? ""
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(workingStatusText(durationLabel: duration))
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
    private func workingStatusText(durationLabel: String) -> String {
        let detail = broker.agentDetail[session.id]
        let tool = broker.agentTool[session.id]
        if chatDetail.effective == .low {
            let base: String
            switch detail {
            case "running": base = "Working…"
            default: base = "Thinking…"
            }
            let count = countToolsThisTurn(
                messages: log,
                activity: activityEvents,
                workingSince: broker.agentWorkingSince[session.id].map { Double($0) }
            )
            return formatLowWorkingStatus(
                baseLabel: base,
                detail: detail,
                tool: tool,
                toolCount: count,
                durationLabel: durationLabel
            )
        }
        // Medium: existing platform copy + duration suffix
        let base: String
        switch detail {
        case "running":
            if let tool, !tool.isEmpty { base = "Working… · \(tool)" }
            else { base = "Working…" }
        default: base = "Thinking…"
        }
        return durationLabel.isEmpty ? base : "\(base) · \(durationLabel)"
    }

    /// Turn over, background tasks still open: the harness will wake the agent when
    /// they finish. Amber pulse = attention-not-error; no Stop (nothing to interrupt).
    private var waitingIndicator: some View {
        HStack(spacing: 8) {
            Text("⧗")
                .font(.caption)
                .foregroundStyle(Color.orange)
                .modifier(WaitingPulse())
            Text("Waiting on background tasks")
                .font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
            Image(systemName: "bubble.left.and.bubble.right").font(.largeTitle).foregroundStyle(Theme.teal)
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
