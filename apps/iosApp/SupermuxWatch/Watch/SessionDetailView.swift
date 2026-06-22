import SwiftUI
import Shared

/// A session's conversation: agent replies (Markdown) + your messages + inline
/// photos, Crown-scrollable, with a pinned mic button to talk to the agent.
struct SessionDetailView: View {
    let broker: WatchBrokerSession
    let session: SessionInfo
    @State private var showVoice = false

    private var entries: [LogEntry] { broker.messages[session.id] ?? [] }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(entries, id: \.id) { entry in
                        MessageBubble(entry: entry, broker: broker).id(entry.id)
                    }
                }
                .padding(.horizontal, 2)
                .padding(.vertical, 4)
            }
            .onChange(of: entries.count) {
                if let last = entries.last?.id { withAnimation { proxy.scrollTo(last, anchor: .bottom) } }
            }
            .onAppear {
                if let last = entries.last?.id { proxy.scrollTo(last, anchor: .bottom) }
            }
        }
        .navigationTitle(session.name)
        .safeAreaInset(edge: .bottom) {
            Button { showVoice = true } label: {
                Label("Talk", systemImage: "mic.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 6)
        }
        .sheet(isPresented: $showVoice) {
            VoiceInputView(broker: broker, sessionId: session.id)
        }
    }
}

private struct MessageBubble: View {
    let entry: LogEntry
    let broker: WatchBrokerSession

    private var isAgent: Bool { entry.direction.hasPrefix("out") }

    var body: some View {
        VStack(alignment: isAgent ? .leading : .trailing, spacing: 4) {
            if let text = entry.text, !text.isEmpty {
                Text(markdown(text))
                    .font(.body)
                    .multilineTextAlignment(.leading)
                    .padding(8)
                    .background(
                        isAgent ? Color.gray.opacity(0.25) : Color.accentColor.opacity(0.35),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
            }
            if let atts = entry.attachments {
                ForEach(atts, id: \.file_id) { att in
                    AttachmentThumb(att: att, broker: broker)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: isAgent ? .leading : .trailing)
    }

    private func markdown(_ s: String) -> AttributedString {
        (try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(s)
    }
}

private struct AttachmentThumb: View {
    let att: Attachment
    let broker: WatchBrokerSession
    @State private var image: UIImage?
    @State private var full = false

    private var isImage: Bool { (att.mime ?? "").hasPrefix("image") || att.kind == "photo" }

    var body: some View {
        Group {
            if isImage {
                if let image {
                    Image(uiImage: image)
                        .resizable().scaledToFit()
                        .frame(maxWidth: .infinity)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .onTapGesture { full = true }
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 44)
                }
            } else {
                Label(att.name ?? att.kind ?? "Attachment", systemImage: "paperclip")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .task(id: att.file_id) {
            if isImage, image == nil, let data = await broker.loadFile(att.file_id) {
                image = UIImage(data: data)
            }
        }
        .sheet(isPresented: $full) {
            if let image {
                ScrollView([.horizontal, .vertical]) {
                    Image(uiImage: image).resizable().scaledToFit()
                }
            }
        }
    }
}
