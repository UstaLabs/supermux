import SwiftUI
import Shared
import UIKit
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

    // MARK: - Composer state
    @State private var draft = ""
    @State private var pending: [PendingAttachment] = []
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showCamera = false
    @State private var recorder = AudioRecorder()
    @State private var dictation = SpeechDictation()
    @State private var transcribing = false
    @State private var micDenied = false
    // True while dictation.start() is in flight (notably the first-run on-device model
    // download). Drives the "Preparing speech…" state AND blocks a second tap from starting a
    // concurrent mic session — two installTap on one audio bus is a hard crash (the first-voice
    // crash users hit on a fresh install before the speech model is ready).
    @State private var micStarting = false
    // Voice glossary (project/technical terms), cached from the broker on appear and fed to
    // on-device dictation as contextual hints so it spells them right at the source.
    @State private var glossary: [String] = []
    @FocusState private var composing: Bool

    // MARK: - Model / reasoning sheet state
    @State private var modelSheet = false
    @State private var reasoningSheet = false
    @State private var reasoning: ReasoningResponse?

    // MARK: - Derived computeds
    private var draftKey: String { "cmux:draft:\(session.id)" }

    /// Composer is expanded (full controls) when focused, when there's a draft or a
    /// staged attachment, or while recording; otherwise it rests as a slim glass pill.
    private var composerExpanded: Bool { composing || !draft.isEmpty || !pending.isEmpty || dictation.isListening || recorder.isRecording || transcribing || micStarting }

    // MARK: - Body

    var body: some View {
        // The transcript is a *separate, Equatable* view. Composer keystrokes mutate this view's
        // `draft`/composer @State and re-run `body`, but `.equatable()` (keyed on session.id) makes
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
            .alert("Microphone access needed", isPresented: $micDenied) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Enable microphone access for supermux in Settings to record voice messages.")
            }
            .onAppear { loadPane() }
            .onDisappear { dictation.cancel(); recorder.cancel() }
            .onChange(of: session.id) { _, _ in dictation.cancel(); recorder.cancel(); loadPane() }
            .onChange(of: draft) { _, new in UserDefaults.standard.set(new, forKey: draftKey) }
    }

    // MARK: - Load

    /// (Re)load per-session state owned by this pane. Called on first open and session switch.
    private func loadPane() {
        draft = UserDefaults.standard.string(forKey: draftKey)
            ?? ProcessInfo.processInfo.environment["SM_DRAFT"] ?? ""
        Task { reasoning = await broker.reasoning(session.id) }
        Task { await loadGlossary() }
    }

    /// Cache the voice glossary from the broker (shared across sessions). Best-effort —
    /// dictation still works without it; the terms are just contextual bias.
    private func loadGlossary() async {
        if let terms = try? await broker.fetchGlossary() { glossary = terms }
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
            if dictation.isListening || recorder.isRecording {
                // Recording takes over the composer. On-device shows the live transcript above
                // the big STOP / small cancel; the audio-fallback path just shows the timer.
                if dictation.isListening && !dictation.transcript.isEmpty {
                    ScrollView {
                        Text(dictation.transcript)
                            .font(.callout).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 120)
                }
                RecordingBar(elapsed: dictation.isListening ? dictation.elapsed : recorder.elapsed,
                             onStop: { Task { await toggleMic() } },
                             onCancel: { dictation.cancel(); recorder.cancel() })
            } else {
            if composerExpanded {
                if !pending.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { ForEach(pending) { attachmentChip($0) } }
                    }
                }
                if transcribing {
                    transcribingBar
                }
                if micStarting {
                    preparingBar
                }
            }
            HStack(alignment: .center, spacing: 10) {
                if !composerExpanded {
                    attachMenu
                }
                TextField("Message \(session.name)…", text: $draft, axis: .vertical)
                    .lineLimit(composerExpanded ? (1...12) : (1...1))
                    .focused($composing)
                    .composerHardwareKeyboardSubmit(canSubmit: canSend) { sendMessage() }
                if !composerExpanded {
                    micButton
                }
            }
            if composerExpanded {
                HStack(spacing: 12) {
                    attachMenu
                    micButton
                    pill(modelPillLabel, system: "cpu") { modelSheet = true }
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
        }
        .padding(.horizontal, composerExpanded ? 12 : 16)
        .padding(.vertical, composerExpanded ? 12 : 10)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: composerExpanded ? 20 : 24, style: .continuous))
    }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespaces).isEmpty || !pending.isEmpty }

    /// Web ModelPill parity: always visible; "Default" when unset, else a short label.
    private var modelPillLabel: String {
        guard let id = session.model, !id.isEmpty else { return "Default" }
        if let slash = id.lastIndex(of: "/") {
            return String(id[id.index(after: slash)...])
        }
        return id
    }

    /// Attachment (+) menu — shared by the collapsed and expanded composer states. The 44×44
    /// content shape gives it a real tap target (HIG minimum); previously the collapsed "+" was a
    /// static, non-tappable Image and the expanded one had only the tiny glyph as its hit area.
    private var attachMenu: some View {
        Menu {
            Button { showPhotos = true } label: { Label("Photos", systemImage: "photo") }
            Button { showFiles = true } label: { Label("Files", systemImage: "folder") }
            Button { showCamera = true } label: { Label("Camera", systemImage: "camera") }
        } label: {
            Image(systemName: "plus")
                .font(.body.weight(.medium))
                .foregroundStyle(.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
    }

    // MARK: - Mic / dictation

    // Mic → on-device STT first (real-time → /transcribe draft → cleanup), falling back to
    // audio recording → host whisper when on-device is unavailable. Cleaned text is appended
    // to the composer (never sent). Stop/cancel live in the RecordingBar while recording.
    private var micButton: some View {
        Button {
            Task { await toggleMic() }
        } label: {
            Group {
                if micStarting {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "mic").font(.body.weight(.medium)).foregroundStyle(.secondary)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .disabled(transcribing || micStarting)
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

    /// Shown while the first-run on-device speech model is downloading/preparing — so the
    /// composer never looks frozen and the user doesn't re-tap into a crash.
    private var preparingBar: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text("Preparing speech…").font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color(.tertiarySystemFill), in: Capsule())
    }

    /// Drive the mic: on-device STT first (real-time → /transcribe draft → cleanup), falling
    /// back to audio recording → host whisper when on-device is unavailable. Always sets the
    /// composer text; never sends.
    private func toggleMic() async {
        if recorder.isRecording {
            // Fallback audio path was active — finish + transcribe via multipart (whisper).
            guard let (data, name) = recorder.stop() else { showBanner("Didn't catch that"); return }
            await runTranscription { try await broker.transcribeAudio(sessionId: session.id, data: data, filename: name) }
            return
        }
        if dictation.isListening {
            // On-device path — stop, then clean the on-device draft. Raw draft is the fallback
            // so the dictation still lands even if the cleanup call fails.
            let (text, _) = await dictation.stop()
            guard !text.isEmpty else { showBanner("Didn't catch that"); return }
            await runTranscription(rawFallback: text) {
                try await broker.transcribeDraft(sessionId: session.id, draft: text)
            }
            return
        }
        // Start on-device; fall back to audio recording if it's unavailable / model downloading.
        // The glossary biases the recognizer toward our project/technical terms.
        // Guard re-entry: on a fresh install start() can take seconds (model download), and a
        // second tap during that wait would spin up a concurrent mic session on the same audio
        // bus → crash. micStarting also surfaces the "Preparing speech…" state.
        guard !micStarting else { return }
        micStarting = true
        defer { micStarting = false }
        switch await dictation.start(contextualStrings: glossary) {
        case .started: break
        case .denied: micDenied = true
        case .unavailable, .downloading, .failed:
            if case .denied = await recorder.start() { micDenied = true }
        }
    }

    /// Run a transcribe call with the "Transcribing…" state, then append the cleaned text
    /// to the composer (a space joins it to any existing draft). On ANY failure we keep the
    /// raw on-device draft (when we have one) so the dictation isn't lost; the agent cleanup
    /// is just an enhancement. We only surface an error when there's no text to keep at all.
    private func runTranscription(rawFallback: String? = nil,
                                  _ call: @escaping () async throws -> String) async {
        transcribing = true
        defer { transcribing = false }
        let result: String
        do {
            result = try await call()
        } catch {
            if let raw = rawFallback?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
                appendToDraft(raw)
            } else {
                showBanner("Transcription failed")
            }
            return
        }
        let cleaned = result.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty {
            if let raw = rawFallback?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
                appendToDraft(raw)
            }
            return
        }
        appendToDraft(cleaned)
    }

    /// Append text to the composer draft, space-joining onto any existing draft, and focus.
    private func appendToDraft(_ text: String) {
        let existing = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        draft = existing.isEmpty ? text : existing + " " + text
        composing = true
    }

    /// Show a transient status banner via the binding, auto-clearing after 4s.
    private func showBanner(_ text: String) {
        banner = text
        Task { try? await Task.sleep(nanoseconds: 4_000_000_000); banner = nil }
    }

    // MARK: - Attachment chips

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

    // MARK: - File / photo loading

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

    // MARK: - Send

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

    // MARK: - Slash commands

    /// Active `/command` token at the end of the draft (cursor assumed at the end),
    /// starting at the beginning or after whitespace — mirrors web activeSlashToken.
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

    // MARK: - Shared pill helper

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
    private var working: Bool {
        ["working", "thinking", "running", "tool", "busy", "sending"].contains(phase ?? "")
    }
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
}
