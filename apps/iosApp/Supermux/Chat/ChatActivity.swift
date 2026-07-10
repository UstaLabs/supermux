import SwiftUI
import Shared

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

func tsMs(_ s: String) -> Double {
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
    private var summaryLong: Bool { (row.summary ?? "").count > 80 }
    private var canExpand: Bool { hasContent || summaryLong }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { if canExpand { open.toggle() } } label: {
                HStack(spacing: 8) {
                    Image(systemName: icon).font(.caption).foregroundStyle(.secondary).frame(width: 16)
                    Text(label).font(.caption.weight(.semibold)).foregroundStyle(.primary)
                    if let s = row.summary {
                        Text(s).font(.caption2.monospaced()).foregroundStyle(.secondary)
                            .lineLimit(open ? nil : 2).truncationMode(.middle)
                    }
                    Spacer(minLength: 4)
                    Circle().fill(statusColor).frame(width: 6, height: 6)
                    if canExpand {
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
        .background(Color.smSecondaryBackground.opacity(0.4),
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
            .background(Color.smTertiaryFill, in: RoundedRectangle(cornerRadius: 6))
        }
    }
}
