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
    let description: String?
    let input: String?
    let output: String?
    let status: ToolStatus
    let truncated: Bool
    let body: ActivityToolBody?
    let resultBody: ActivityToolBody?
}

enum ToolStatus { case running, done, error }

/// `tsMs` is hot: `buildChatBlocks` calls it once per message AND once per activity event over the
/// whole history on every transcript rebuild. `ISO8601DateFormatter` is expensive to construct
/// (it wraps a CFDateFormatter), so building one per entry was pure waste — the format never
/// changes. Broker timestamps are `new Date().toISOString()` (fractional-seconds form), so
/// `isoFractionalParser` is the hit path; `isoPlainParser` is the fallback.
private let isoFractionalParser: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()
private let isoPlainParser = ISO8601DateFormatter()

func tsMs(_ s: String) -> Double {
    if let d = Double(s) { return d > 1_000_000_000_000 ? d : d * 1000 }
    if let date = isoFractionalParser.date(from: s) ?? isoPlainParser.date(from: s) {
        return date.timeIntervalSince1970 * 1000
    }
    return 0
}

/// Merge messages + "tool" activity into time-ordered blocks; consecutive tool
/// rows cluster together. tool_result events are folded into their tool row.
/// - Parameter hideTools: when true (chat detail = low), omit tool cards; activity is still stored.
func buildChatBlocks(messages: [LogEntry], activity: [ActivityEvent], hideTools: Bool = false) -> [ChatBlock] {
    var resultByCall: [String: ActivityEvent] = [:]
    for e in activity where e.kind == "tool_result" {
        if let c = e.callId { resultByCall[c] = e }
    }
    enum Payload { case message(LogEntry); case tool(ToolRow) }
    var rows: [(ts: Double, rank: Int, payload: Payload)] = []
    for m in messages { rows.append((tsMs(m.ts), 1, .message(m))) }
    if !hideTools {
        for e in activity where e.kind == "tool" {
            let res = e.callId.flatMap { resultByCall[$0] }
            let status: ToolStatus = res == nil ? .running : (res?.title == "error" ? .error : .done)
            let title = e.title ?? ""
            let prefix = "\(e.tool ?? ""): "
            let summary = (e.tool != nil && title.hasPrefix(prefix)) ? String(title.dropFirst(prefix.count)) : title
            let id = e.seq.map { "a:\($0.intValue)" } ?? "a:\(e.ts):\(e.tool ?? "")"
            let row = ToolRow(
                id: id,
                toolName: e.tool ?? "tool",
                summary: summary.isEmpty ? nil : summary,
                description: e.description,
                input: e.detail,
                output: res?.detail,
                status: status,
                truncated: (e.truncated?.boolValue == true) || (res?.truncated?.boolValue == true),
                body: e.body,
                resultBody: res?.body
            )
            rows.append((tsMs(e.ts), 0, .tool(row)))
        }
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

// MARK: - Chat detail level (web/Android/Desktop parity)

enum ChatDetailLevel: String, CaseIterable {
    case low, medium, high
    var label: String {
        switch self {
        case .low: return "Low"
        case .medium: return "Medium"
        case .high: return "High"
        }
    }
    var isImplemented: Bool { true }
    var effective: ChatDetailLevel { self }
    static func parse(_ raw: String?) -> ChatDetailLevel {
        switch raw {
        case "low": return .low
        case "medium": return .medium
        case "high": return .high
        default: return .medium
        }
    }
}

/// Tools since last user message (`direction` starts with "in" = user on native).
func countToolsThisTurn(messages: [LogEntry], activity: [ActivityEvent], workingSince: Double?) -> Int {
    var sinceMs: Double = 0
    for m in messages.reversed() {
        if m.direction.hasPrefix("in") {
            sinceMs = tsMs(m.ts)
            break
        }
    }
    if sinceMs == 0, let w = workingSince { sinceMs = w }
    return activity.filter { $0.kind == "tool" && tsMs($0.ts) >= sinceMs }.count
}

func formatLowWorkingStatus(
    baseLabel: String,
    detail: String?,
    tool: String?,
    toolCount: Int,
    durationLabel: String
) -> String {
    var parts = [baseLabel]
    if detail == "running", let tool, !tool.isEmpty { parts.append(tool) }
    if toolCount > 0 {
        parts.append(toolCount == 1 ? "1 tool" : "\(toolCount) tools")
    }
    if !durationLabel.isEmpty { parts.append(durationLabel) }
    return parts.joined(separator: " · ")
}

// MARK: - Tool views

/// Dispatches medium quiet rows vs High terminal/diff panes.
struct ToolRowView: View {
    let row: ToolRow
    var highDetail: Bool = false

    var body: some View {
        if highDetail, let bash = resolveBash(row) {
            ToolTerminalView(
                command: bash.command,
                output: bash.output,
                exitCode: bash.exitCode,
                description: row.description,
                status: row.status,
                truncated: row.truncated
            )
        } else if highDetail, let edit = resolveEdit(row) {
            ToolDiffView(
                path: edit.path,
                mode: edit.mode,
                diff: edit.diff,
                content: edit.content,
                description: row.description,
                status: row.status,
                truncated: row.truncated
            )
        } else {
            ToolQuietRow(row: row)
        }
    }

    private struct BashBits { let command: String?; let output: String?; let exitCode: Int32? }
    private struct EditBits { let path: String; let mode: String?; let diff: String?; let content: String? }

    private func resolveBash(_ row: ToolRow) -> BashBits? {
        let start = row.body?.kind == "bash" ? row.body : nil
        let end = row.resultBody?.kind == "bash" ? row.resultBody : nil
        let isBash = row.toolName == "Bash" || start != nil || end != nil
        guard isBash else { return nil }
        let command = start?.command ?? (row.toolName == "Bash" ? row.input : nil)
        let output = end?.output ?? start?.output ?? row.output
        let exit: Int32? = {
            if let v = end?.exitCode { return v.int32Value }
            if let v = start?.exitCode { return v.int32Value }
            return nil
        }()
        if (command ?? "").isEmpty && (output ?? "").isEmpty && row.toolName != "Bash" { return nil }
        return BashBits(command: command, output: output, exitCode: exit)
    }

    private func resolveEdit(_ row: ToolRow) -> EditBits? {
        if let b = row.body, b.kind == "edit" {
            return EditBits(path: b.path ?? "file", mode: b.mode, diff: b.diff, content: nil)
        }
        if let b = row.body, b.kind == "write" {
            let content = b.content
            let diff = content?.split(separator: "\n", omittingEmptySubsequences: false).map { "+\($0)" }.joined(separator: "\n")
            return EditBits(path: b.path ?? "file", mode: "add", diff: diff, content: content)
        }
        if row.toolName == "Edit" || row.toolName == "Write" {
            let path = (row.input?.split(separator: "\n").first.map(String.init) ?? "file")
            let looksDiff = (row.input ?? "").contains("\n+") || (row.input ?? "").contains("\n-") || (row.input ?? "").contains("@@")
            return EditBits(
                path: path,
                mode: row.toolName == "Write" ? "add" : "update",
                diff: looksDiff ? row.input : nil,
                content: (!looksDiff && row.toolName == "Write") ? row.input : nil
            )
        }
        return nil
    }
}

/// Quiet single-line tool activity (medium / high fallback).
struct ToolQuietRow: View {
    let row: ToolRow
    @State private var open = false
    private var hasContent: Bool { !(row.input ?? "").isEmpty || !(row.output ?? "").isEmpty }
    private var primary: String? { row.description ?? row.summary }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Button { if hasContent { open.toggle() } } label: {
                HStack(spacing: 6) {
                    if row.status == .running {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: icon)
                            .font(.caption2)
                            .foregroundStyle(row.status == .error ? Color.red.opacity(0.7) : .secondary.opacity(0.7))
                    }
                    Text(label)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                    if let p = primary, !p.isEmpty {
                        Text(p)
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 4)
                    if row.status == .error {
                        Text("failed").font(.caption2).foregroundStyle(.red.opacity(0.8))
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!hasContent)

            if open, hasContent {
                VStack(alignment: .leading, spacing: 4) {
                    if let i = row.input, !i.isEmpty {
                        Text(i)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary.opacity(0.85))
                            .textSelection(.enabled)
                            .frame(maxHeight: 160, alignment: .topLeading)
                    }
                    if let o = row.output, !o.isEmpty {
                        Text(o + (row.truncated ? " …" : ""))
                            .font(.caption2.monospaced())
                            .foregroundStyle(row.status == .error ? .red.opacity(0.85) : .secondary.opacity(0.8))
                            .textSelection(.enabled)
                            .frame(maxHeight: 160, alignment: .topLeading)
                    }
                }
                .padding(.leading, 18)
                .overlay(alignment: .leading) {
                    Rectangle().fill(Color.secondary.opacity(0.25)).frame(width: 1)
                }
            }
        }
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
}

struct ToolTerminalView: View {
    let command: String?
    let output: String?
    let exitCode: Int32?
    let description: String?
    let status: ToolStatus
    let truncated: Bool

    private var statusLabel: String {
        if status == .running { return "running" }
        if status == .error {
            if let exitCode { return "exit \(exitCode)" }
            return "error"
        }
        if let exitCode { return "exit \(exitCode)" }
        return "done"
    }
    private var statusColor: Color {
        switch status {
        case .running: return .orange
        case .error: return .red
        case .done: return .green.opacity(0.85)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                HStack(spacing: 3) {
                    Circle().fill(Color(red: 1, green: 0.37, blue: 0.34)).frame(width: 7, height: 7)
                    Circle().fill(Color(red: 1, green: 0.74, blue: 0.18)).frame(width: 7, height: 7)
                    Circle().fill(Color(red: 0.16, green: 0.78, blue: 0.25)).frame(width: 7, height: 7)
                }
                Image(systemName: "terminal").font(.caption2).foregroundStyle(.white.opacity(0.45))
                Text("terminal").font(.caption2).foregroundStyle(.white.opacity(0.45))
                if let description, !description.isEmpty {
                    Text(description).font(.caption2).foregroundStyle(.white.opacity(0.75))
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                if status == .running { ProgressView().controlSize(.mini).tint(statusColor) }
                Text(statusLabel).font(.caption2.monospaced()).foregroundStyle(statusColor)
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(Color(red: 0.09, green: 0.09, blue: 0.10))

            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    if let command, !command.isEmpty {
                        HStack(alignment: .top, spacing: 4) {
                            Text("$").font(.caption.monospaced()).foregroundStyle(Color.green.opacity(0.9))
                            Text(command).font(.caption.monospaced()).foregroundStyle(.white.opacity(0.95))
                                .textSelection(.enabled)
                        }
                    }
                    if let output, !output.isEmpty {
                        Text(output + (truncated ? " …" : ""))
                            .font(.caption.monospaced())
                            .foregroundStyle(status == .error ? Color.red.opacity(0.85) : .white.opacity(0.8))
                            .textSelection(.enabled)
                    } else if status == .running && (command ?? "").isEmpty {
                        Text("Running…").font(.caption2).foregroundStyle(.white.opacity(0.4))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
            }
            .frame(maxHeight: 220)
            .background(Color(red: 0.05, green: 0.05, blue: 0.055))
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5))
    }
}

struct ToolDiffView: View {
    let path: String
    let mode: String?
    let diff: String?
    let content: String?
    let description: String?
    let status: ToolStatus
    let truncated: Bool

    private var rendered: String {
        if let diff, !diff.isEmpty { return diff }
        if let content {
            return content.split(separator: "\n", omittingEmptySubsequences: false).map { "+\($0)" }.joined(separator: "\n")
        }
        return ""
    }
    /// Pre-split so the type checker does not expand a huge nested ForEach expression.
    private var lines: [String] {
        rendered.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
    }
    private var modeLabel: String {
        switch (mode ?? "").lowercased() {
        case "add", "added": return "added"
        case "delete", "deleted": return "deleted"
        case "move", "renamed": return "moved"
        default: return "edited"
        }
    }
    private var statusLabel: String {
        switch status {
        case .running: return "applying"
        case .error: return "error"
        case .done: return "done"
        }
    }
    private var statusColor: Color {
        switch status {
        case .running: return .orange
        case .error: return .red
        case .done: return .green.opacity(0.85)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if rendered.isEmpty {
                Text(status == .running ? "Preparing edit…" : "No diff content")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.4))
                    .padding(10)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                            DiffLineRow(text: line)
                        }
                        if truncated {
                            Text("… truncated").font(.caption2).foregroundStyle(.white.opacity(0.35))
                                .padding(.horizontal, 10).padding(.vertical, 4)
                        }
                    }
                }
                .frame(maxHeight: 220)
            }
        }
        .background(Color(red: 0.05, green: 0.05, blue: 0.055))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5))
    }

    private var header: some View {
        HStack(spacing: 6) {
            Image(systemName: "square.and.pencil").font(.caption2).foregroundStyle(.white.opacity(0.45))
            Text(path).font(.caption2.weight(.medium)).foregroundStyle(.white.opacity(0.92))
                .lineLimit(1)
            if let description, !description.isEmpty {
                Text(description).font(.caption2).foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Text(modeLabel).font(.caption2).foregroundStyle(.white.opacity(0.4))
            Text(statusLabel).font(.caption2).foregroundStyle(statusColor)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(Color(red: 0.09, green: 0.09, blue: 0.10))
    }
}

/// One diff line — extracted so SwiftUI type-checking stays bounded (CI xcodebuild).
private struct DiffLineRow: View {
    let text: String

    private var kind: Kind {
        if text.hasPrefix("+") && !text.hasPrefix("+++") { return .add }
        if text.hasPrefix("-") && !text.hasPrefix("---") { return .del }
        if text.hasPrefix("@@") { return .hunk }
        return .ctx
    }

    private enum Kind { case add, del, hunk, ctx }

    private var fg: Color {
        switch kind {
        case .add: return Color.green.opacity(0.9)
        case .del: return Color.red.opacity(0.85)
        case .hunk: return Color.cyan.opacity(0.8)
        case .ctx: return Color.white.opacity(0.55)
        }
    }

    private var bg: Color {
        switch kind {
        case .add: return Color.green.opacity(0.10)
        case .del: return Color.red.opacity(0.10)
        case .hunk, .ctx: return Color.clear
        }
    }

    var body: some View {
        Text(text.isEmpty ? " " : text)
            .font(.caption2.monospaced())
            .foregroundStyle(fg)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10)
            .background(bg)
    }
}
