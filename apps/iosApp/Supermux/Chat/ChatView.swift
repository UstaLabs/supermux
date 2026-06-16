import SwiftUI
import Shared
import UIKit

/// Document-style transcript + info bar (agent · workdir · model/reasoning pills)
/// + live "Working…" indicator + glass composer with the Chat∣Native pill.
struct ChatView: View {
    let broker: BrokerSession
    let session: SessionInfo
    @State private var draft = ""
    @State private var modelSheet = false
    @State private var reasoningSheet = false

    private var log: [LogEntry] { broker.messages[session.id] ?? [] }
    private var phase: String? { broker.agentPhase[session.id] }
    private var working: Bool {
        ["working", "thinking", "running", "tool", "busy", "sending"].contains(phase ?? "")
    }

    var body: some View {
        VStack(spacing: 0) {
            infoBar
            Divider().opacity(0.4)
            transcript
            dock
            paneBar
        }
        .navigationTitle(session.name)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $modelSheet) {
            OptionSwitchSheet(title: "Model", broker: broker, session: session, kind: .model)
        }
        .sheet(isPresented: $reasoningSheet) {
            OptionSwitchSheet(title: "Reasoning", broker: broker, session: session, kind: .reasoning)
        }
    }

    private var infoBar: some View {
        HStack(spacing: 8) {
            AgentLogo(agent: session.agent, size: 24)
            Text(formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir)))
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Spacer(minLength: 6)
            if let m = session.model, !m.isEmpty { pill(m, system: "cpu") { modelSheet = true } }
            pill("reasoning", system: "brain") { reasoningSheet = true }
        }
        .padding(.horizontal, 14).padding(.vertical, 7)
        .background(.bar)
    }

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
                if log.isEmpty {
                    starterPrompts
                } else {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(log, id: \.id) { MessageRow(entry: $0, broker: broker).id($0.id) }
                        if working { workingIndicator.id("__working__") }
                    }
                    .padding(16)
                }
            }
            .onChange(of: log.count) { _, _ in
                if let last = log.last {
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
    }

    private var workingIndicator: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let since = broker.agentSince[session.id]
            let elapsed = since.map { max(0, Int(Date().timeIntervalSince1970 - Double($0) / 1000.0)) }
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(workingLabel + (elapsed.map { " · \($0)s" } ?? ""))
                    .font(.caption).foregroundStyle(.secondary)
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
            HStack(spacing: 3) {
                pillSeg("Chat", system: "bubble.left", on: true)
                pillSeg("Native", system: "terminal", on: false).opacity(0.5)
            }
            .padding(3).background(.quaternary, in: Capsule())

            HStack(spacing: 10) {
                TextField("Message \(session.name)…", text: $draft, axis: .vertical)
                    .lineLimit(1...5).padding(.horizontal, 14).padding(.vertical, 9)
                    .glassSurface(cornerRadius: 20)
                Button { broker.send(session.id, draft); draft = "" } label: {
                    Image(systemName: "arrow.up").font(.headline.weight(.bold)).foregroundStyle(.white)
                        .frame(width: 36, height: 36).background(Theme.teal, in: Circle())
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 6).background(.bar)
    }
    private func pillSeg(_ t: String, system: String, on: Bool) -> some View {
        Label(t, systemImage: system).font(.caption.weight(.semibold))
            .padding(.horizontal, 14).padding(.vertical, 6)
            .foregroundStyle(on ? .white : .secondary)
            .background(on ? Theme.teal : .clear, in: Capsule())
    }

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
}

private struct MessageRow: View {
    let entry: LogEntry
    let broker: BrokerSession
    private var isAgent: Bool { (entry.direction ?? "").hasPrefix("out") }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            let text = entry.text ?? ""
            if !text.isEmpty {
                if isAgent {
                    markdown(text).font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading).transcriptCard()
                } else {
                    Text(text).font(.subheadline.weight(.medium))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if let atts = entry.attachments, !atts.isEmpty {
                ForEach(atts, id: \.file_id) { AttachmentView(att: $0, broker: broker) }
            }
        }
    }
    private func markdown(_ s: String) -> Text {
        if let a = try? AttributedString(
            markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        ) { return Text(a) }
        return Text(s)
    }
}

private struct AttachmentView: View {
    let att: Attachment
    let broker: BrokerSession
    @State private var image: UIImage?
    private var isImage: Bool {
        (att.mime ?? "").hasPrefix("image") || (att.kind ?? "") == "photo"
    }
    var body: some View {
        Group {
            if isImage {
                if let image {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: 240, alignment: .leading)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                } else {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Color(.secondarySystemBackground)).frame(height: 140)
                        .overlay(ProgressView())
                }
            } else {
                Label(att.name ?? "file", systemImage: "doc")
                    .font(.caption).padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Color(.secondarySystemBackground), in: Capsule())
            }
        }
        .task {
            if isImage, image == nil, let data = await broker.loadFile(att.file_id) {
                image = UIImage(data: data)
            }
        }
    }
}

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
