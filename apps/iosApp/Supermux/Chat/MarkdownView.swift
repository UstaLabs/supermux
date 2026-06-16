import SwiftUI
import UIKit

/// Lightweight Markdown block renderer. `AttributedString(markdown:)` in inline-only
/// mode collapses code fences, headings, lists and quotes into run-together text;
/// this splits the source into blocks and renders each properly — fenced code as a
/// monospaced, horizontally-scrollable card with a copy button, plus headings,
/// bullet/numbered lists, and blockquotes. Inline spans (bold/italic/code/links)
/// still go through AttributedString.
struct MarkdownView: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(parseMarkdown(text).enumerated()), id: \.offset) { _, block in
                row(block)
            }
        }
    }

    @ViewBuilder private func row(_ b: MDBlock) -> some View {
        switch b {
        case .code(let code, _):
            CodeBlock(code: code)
        case .heading(let level, let s):
            inlineText(s).font(headingFont(level)).fontWeight(.bold)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .quote(let s):
            HStack(alignment: .top, spacing: 8) {
                RoundedRectangle(cornerRadius: 1).fill(Theme.teal.opacity(0.5)).frame(width: 3)
                inlineText(s).foregroundStyle(.secondary)
            }
        case .bullet(let s):
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text("•").foregroundStyle(.secondary)
                inlineText(s).frame(maxWidth: .infinity, alignment: .leading)
            }
        case .numbered(let n, let s):
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text("\(n).").foregroundStyle(.secondary).monospacedDigit()
                inlineText(s).frame(maxWidth: .infinity, alignment: .leading)
            }
        case .paragraph(let s):
            inlineText(s).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func inlineText(_ s: String) -> Text {
        if let a = try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace,
                           failurePolicy: .returnPartiallyParsedIfPossible)
        ) { return Text(a) }
        return Text(s)
    }
    private func headingFont(_ level: Int) -> Font {
        switch level { case 1: return .title3; case 2: return .headline; default: return .subheadline }
    }
}

private enum MDBlock {
    case paragraph(String)
    case code(String, lang: String?)
    case heading(Int, String)
    case quote(String)
    case bullet(String)
    case numbered(Int, String)
}

private func parseMarkdown(_ text: String) -> [MDBlock] {
    var blocks: [MDBlock] = []
    let lines = text.components(separatedBy: "\n")
    var i = 0
    var para: [String] = []
    func flushPara() {
        let joined = para.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        if !joined.isEmpty { blocks.append(.paragraph(joined)) }
        para = []
    }
    while i < lines.count {
        let line = lines[i]
        let t = line.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("```") {
            flushPara()
            let lang = String(t.dropFirst(3)).trimmingCharacters(in: .whitespaces)
            var code: [String] = []
            i += 1
            while i < lines.count, !lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                code.append(lines[i]); i += 1
            }
            blocks.append(.code(code.joined(separator: "\n"), lang: lang.isEmpty ? nil : lang))
            i += 1
            continue
        }
        if let h = headingLevel(t) {
            flushPara()
            blocks.append(.heading(h, String(t.drop(while: { $0 == "#" || $0 == " " }))))
        } else if t.hasPrefix("> ") || t == ">" {
            flushPara()
            blocks.append(.quote(String(t.dropFirst()).trimmingCharacters(in: .whitespaces)))
        } else if t.hasPrefix("- ") || t.hasPrefix("* ") {
            flushPara()
            blocks.append(.bullet(String(t.dropFirst(2))))
        } else if let (n, rest) = numberedItem(t) {
            flushPara()
            blocks.append(.numbered(n, rest))
        } else if t.isEmpty {
            flushPara()
        } else {
            para.append(line)
        }
        i += 1
    }
    flushPara()
    return blocks
}

private func headingLevel(_ s: String) -> Int? {
    guard s.hasPrefix("#") else { return nil }
    let hashes = s.prefix(while: { $0 == "#" }).count
    if hashes >= 1, hashes <= 6, s.dropFirst(hashes).hasPrefix(" ") { return hashes }
    return nil
}
private func numberedItem(_ s: String) -> (Int, String)? {
    guard let dot = s.firstIndex(of: "."), let n = Int(s[s.startIndex..<dot]),
          s.index(after: dot) < s.endIndex, s[s.index(after: dot)] == " " else { return nil }
    return (n, String(s[s.index(dot, offsetBy: 2)...]))
}

/// Monospaced, horizontally-scrollable code block with a copy button.
private struct CodeBlock: View {
    let code: String
    @State private var copied = false
    var body: some View {
        ZStack(alignment: .topTrailing) {
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code).font(.system(.caption, design: .monospaced))
                    .padding(12).textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            Button {
                UIPasteboard.general.string = code
                copied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
            } label: {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(.caption2).foregroundStyle(copied ? Theme.teal : .secondary)
                    .padding(6).background(.regularMaterial, in: Circle())
            }
            .padding(6)
        }
    }
}
