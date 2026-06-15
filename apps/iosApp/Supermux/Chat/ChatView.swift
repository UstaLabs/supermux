import SwiftUI
import Shared

/// Document-style transcript (user = plain text, agent = outlined Markdown card),
/// a Chat∣Native pill, and a glass composer — mirroring the PWA.
struct ChatView: View {
    let broker: BrokerSession
    let session: SessionInfo
    @State private var draft = ""

    private var log: [LogEntry] { broker.messages[session.id] ?? [] }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(log, id: \.id) { entry in
                            MessageRow(entry: entry).id(entry.id)
                        }
                    }
                    .padding(16)
                }
                .onChange(of: log.count) { _, _ in
                    if let last = log.last {
                        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }
            dock
        }
        .navigationTitle(session.name)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var dock: some View {
        VStack(spacing: 8) {
            // Chat ∣ Native pill (Native disabled — Phase 2)
            HStack(spacing: 3) {
                pill("Chat", systemImage: "bubble.left", on: true)
                pill("Native", systemImage: "terminal", on: false).opacity(0.5)
            }
            .padding(3)
            .background(.quaternary, in: Capsule())

            HStack(spacing: 10) {
                TextField("Message \(session.name)…", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .glassSurface(cornerRadius: 20)
                Button {
                    broker.send(session.id, draft); draft = ""
                } label: {
                    Image(systemName: "arrow.up").font(.headline.weight(.bold)).foregroundStyle(.white)
                        .frame(width: 36, height: 36).background(Theme.teal, in: Circle())
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 6)
        .background(.bar)
    }

    private func pill(_ title: String, systemImage: String, on: Bool) -> some View {
        Label(title, systemImage: systemImage)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 14).padding(.vertical, 6)
            .foregroundStyle(on ? .white : .secondary)
            .background(on ? Theme.teal : .clear, in: Capsule())
    }
}

private struct MessageRow: View {
    let entry: LogEntry
    /// Agent replies arrive outbound (broker→user) and render as cards; the user's
    /// own messages (inbound) render as plain text.
    private var isAgent: Bool { (entry.direction ?? "").hasPrefix("out") }

    var body: some View {
        let text = entry.text ?? ""
        Group {
            if isAgent {
                markdown(text).font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transcriptCard()
            } else {
                Text(text).font(.subheadline.weight(.medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func markdown(_ s: String) -> Text {
        if let a = try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        ) { return Text(a) }
        return Text(s)
    }
}
