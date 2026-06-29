import SwiftUI
import UIKit

/// Agent replies render here. We parse the markdown into blocks and render them
/// as a vertical stack: runs of "flow" blocks (paragraphs, headings, lists,
/// quotes, code) collapse into ONE selectable `UITextView` (`SelectableText`) so
/// you can drag-select a phrase across them, while each GFM **table** renders as
/// a native, horizontally-scrollable grid (`MarkdownTableView`).
///
/// Why a UITextView for the flow blocks (not SwiftUI `Text`):
///   • SwiftUI selection is per-`Text`, so you can't drag-select a phrase that
///     spans paragraphs / list items — you only get whole blocks at a time.
///   • `Text` *truncates* an overlong unbreakable token (e.g.
///     `full_duration_song_clip.song_clip_id`) with a "…" instead of wrapping it.
/// A UITextView fixes both: native arbitrary selection (any word/phrase, across
/// blocks) and real wrapping (long tokens break across lines; code char-wraps).
///
/// Tables can't live inside that single attributed string (TextKit table layout
/// is unreliable and can't scroll), so a message is split into segments at table
/// boundaries: text-runs stay one selectable block; tables become grid views.
struct MarkdownView: View {
    let text: String

    var body: some View {
        let segments = groupSegments(parseMarkdown(text))
        VStack(alignment: .leading, spacing: 8) {
            ForEach(segments.indices, id: \.self) { i in
                switch segments[i] {
                case .text(let blocks):
                    SelectableText(attributed: MarkdownAttributed.build(blocks: blocks))
                        .frame(maxWidth: .infinity, alignment: .leading)
                case .table(let table):
                    MarkdownTableView(table: table)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Segments (text-runs vs tables)

/// A message is a sequence of segments: consecutive non-table blocks coalesce
/// into one selectable text run; each table is its own segment.
enum MDSegment {
    case text([MDBlock])
    case table(MDTable)
}

func groupSegments(_ blocks: [MDBlock]) -> [MDSegment] {
    var segments: [MDSegment] = []
    var run: [MDBlock] = []
    func flushRun() {
        if !run.isEmpty { segments.append(.text(run)); run = [] }
    }
    for b in blocks {
        if case .table(let t) = b {
            flushRun()
            segments.append(.table(t))
        } else {
            run.append(b)
        }
    }
    flushRun()
    return segments
}

// MARK: - Attributed-string builder (flow blocks)

private enum MarkdownAttributed {
    /// Build the attributed string for a run of NON-table blocks.
    static func build(blocks: [MDBlock]) -> NSAttributedString {
        let out = NSMutableAttributedString()
        var first = true
        for b in blocks {
            if case .table = b { continue }   // tables render via MarkdownTableView
            if !first { out.append(NSAttributedString(string: "\n")) }
            out.append(attributed(for: b))
            first = false
        }
        return out
    }

    private static var bodyFont: UIFont { .preferredFont(forTextStyle: .subheadline) }

    private static func headingFont(_ level: Int) -> UIFont {
        let style: UIFont.TextStyle = level == 1 ? .title3 : (level == 2 ? .headline : .subheadline)
        return UIFont.preferredFont(forTextStyle: style).withTraits(.traitBold)
    }

    /// Base paragraph style: word-wrap, a little spacing between blocks.
    private static func paragraph() -> NSMutableParagraphStyle {
        let p = NSMutableParagraphStyle()
        p.lineBreakMode = .byWordWrapping
        p.paragraphSpacing = 7
        p.lineSpacing = 1.5
        return p
    }

    private static func attributed(for b: MDBlock) -> NSAttributedString {
        switch b {
        case .paragraph(let s):
            return styledInline(s, font: bodyFont, color: .label, paragraph: paragraph())
        case .heading(let level, let s):
            return styledInline(s, font: headingFont(level), color: .label, paragraph: paragraph())
        case .quote(let s):
            let p = paragraph(); p.firstLineHeadIndent = 12; p.headIndent = 12
            return styledInline(s, font: bodyFont, color: .secondaryLabel, paragraph: p)
        case .bullet(let s):
            return listItem(marker: "•", body: s)
        case .numbered(let n, let s):
            return listItem(marker: "\(n).", body: s)
        case .code(let code, _):
            return codeBlock(code)
        case .table:
            return NSAttributedString()   // handled by MarkdownTableView, never reached here
        }
    }

    /// A list row with a hanging indent so wrapped lines align under the text,
    /// not under the marker. Marker and body are separated by a tab to the stop.
    private static func listItem(marker: String, body: String) -> NSAttributedString {
        let indent: CGFloat = 22
        let line = NSMutableAttributedString(
            string: "\(marker)\t",
            attributes: [.font: bodyFont, .foregroundColor: UIColor.secondaryLabel])
        line.append(styledInline(body, font: bodyFont, color: .label, paragraph: nil))
        let p = paragraph()
        p.headIndent = indent
        p.firstLineHeadIndent = 0
        p.tabStops = [NSTextTab(textAlignment: .left, location: indent)]
        p.defaultTabInterval = indent
        line.addAttribute(.paragraphStyle, value: p, range: NSRange(location: 0, length: line.length))
        return line
    }

    /// Monospaced code: char-wrap so long lines wrap instead of clipping, with a
    /// subtle per-line background and a small inset.
    private static func codeBlock(_ code: String) -> NSAttributedString {
        let p = NSMutableParagraphStyle()
        p.lineBreakMode = .byCharWrapping
        p.paragraphSpacing = 7
        p.firstLineHeadIndent = 8
        p.headIndent = 8
        let f = UIFont.monospacedSystemFont(ofSize: max(11, bodyFont.pointSize - 1), weight: .regular)
        return NSAttributedString(string: code, attributes: [
            .font: f,
            .foregroundColor: UIColor.label,
            .backgroundColor: UIColor.tertiarySystemBackground,
            .paragraphStyle: p,
        ])
    }

    /// Parse inline markdown (bold/italic/code/strikethrough/links) and normalize
    /// every run onto our base font/color so the UITextView renders consistently.
    private static func styledInline(_ s: String, font: UIFont, color: UIColor,
                                     paragraph: NSParagraphStyle?) -> NSAttributedString {
        let m = NSMutableAttributedString(attributedString: MarkdownInline.nsAttributed(s))
        let whole = NSRange(location: 0, length: m.length)
        m.addAttributes([.font: font, .foregroundColor: color], range: whole)

        // Collect inline-intent spans first; applying attributes *during*
        // enumerateAttribute mutates the receiver mid-walk (undefined behavior).
        var spans: [(NSRange, InlinePresentationIntent)] = []
        m.enumerateAttribute(.inlinePresentationIntent, in: whole) { val, range, _ in
            let intent: InlinePresentationIntent?
            if let i = val as? InlinePresentationIntent { intent = i }
            else if let n = val as? NSNumber { intent = InlinePresentationIntent(rawValue: n.uintValue) }
            else { intent = nil }
            if let intent { spans.append((range, intent)) }
        }
        for (range, intent) in spans {
            var f = font
            if intent.contains(.stronglyEmphasized) { f = f.withTraits(.traitBold) }
            if intent.contains(.emphasized) { f = f.withTraits(.traitItalic) }
            if intent.contains(.code) {
                f = .monospacedSystemFont(ofSize: max(11, font.pointSize - 0.5), weight: .regular)
                m.addAttribute(.backgroundColor, value: UIColor.tertiarySystemFill, range: range)
            }
            if intent.contains(.strikethrough) {
                m.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: range)
            }
            m.addAttribute(.font, value: f, range: range)
        }
        if let paragraph { m.addAttribute(.paragraphStyle, value: paragraph, range: whole) }
        return m
    }
}

// MARK: - Inline markdown parsing (shared by flow blocks and table cells)

/// Parses inline-only markdown (bold/italic/code/strikethrough/links). Used both
/// for the attributed-string flow blocks and for SwiftUI `Text` table cells.
enum MarkdownInline {
    private static var options: AttributedString.MarkdownParsingOptions {
        .init(interpretedSyntax: .inlineOnlyPreservingWhitespace,
              failurePolicy: .returnPartiallyParsedIfPossible)
    }

    /// SwiftUI-friendly `AttributedString` (Text renders bold/italic/code/links).
    static func attributed(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: options)) ?? AttributedString(s)
    }

    /// Bridge to `NSAttributedString` (intents only; callers layer font/color).
    static func nsAttributed(_ s: String) -> NSAttributedString {
        NSAttributedString(attributed(s))
    }
}

private extension UIFont {
    func withTraits(_ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        let merged = fontDescriptor.symbolicTraits.union(traits)
        guard let d = fontDescriptor.withSymbolicTraits(merged) else { return self }
        return UIFont(descriptor: d, size: 0)
    }
}

// MARK: - Table view (native, horizontally scrollable grid)

/// Renders a GFM table as a bordered grid. Cells keep inline formatting and
/// per-column alignment; the grid scrolls **horizontally** so wide tables stay
/// readable on a phone instead of squishing or overflowing the message.
struct MarkdownTableView: View {
    let table: MDTable

    var body: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(0..<table.columnCount, id: \.self) { c in
                        cell(table.headers[c], column: c, header: true)
                    }
                }
                ForEach(0..<table.rows.count, id: \.self) { r in
                    GridRow {
                        ForEach(0..<table.columnCount, id: \.self) { c in
                            cell(table.rows[r][c], column: c, header: false)
                        }
                    }
                }
            }
            .padding(1)   // keep the cells' outer hairline off the scroll edge
        }
    }

    /// One cell. `fixedSize` gives the text a definite single-line ideal width so
    /// `Grid` can size the column to its widest cell; `frame(maxWidth:.infinity)`
    /// then fills that column width so the hairline borders connect into a grid.
    /// Single-line (no wrap) means wide tables scroll instead of squishing.
    @ViewBuilder
    private func cell(_ raw: String, column: Int, header: Bool) -> some View {
        Text(MarkdownInline.attributed(raw))
            .font(.subheadline)
            .fontWeight(header ? .semibold : .regular)
            .fixedSize(horizontal: true, vertical: true)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: table.aligns[column].frameAlignment)
            .background(header ? Theme.teal.opacity(0.10) : Color.clear)
            .overlay(Rectangle().strokeBorder(Theme.hairline, lineWidth: 0.5))
    }
}

// MARK: - Block model

enum MDBlock {
    case paragraph(String)
    case code(String, lang: String?)
    case heading(Int, String)
    case quote(String)
    case bullet(String)
    case numbered(Int, String)
    case table(MDTable)
}

/// A parsed GFM table. `headers.count` is the canonical column count; `aligns`
/// and every row are normalized to that width by the parser.
struct MDTable {
    let headers: [String]
    let aligns: [MDColumnAlign]
    let rows: [[String]]
    var columnCount: Int { headers.count }
}

enum MDColumnAlign: Equatable {
    case leading, center, trailing

    var textAlignment: TextAlignment {
        switch self {
        case .leading: return .leading
        case .center: return .center
        case .trailing: return .trailing
        }
    }
    var frameAlignment: Alignment {
        switch self {
        case .leading: return .leading
        case .center: return .center
        case .trailing: return .trailing
        }
    }
}

// MARK: - Block parser

func parseMarkdown(_ text: String) -> [MDBlock] {
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
        if let (table, consumed) = parseTable(lines, from: i) {
            flushPara()
            blocks.append(.table(table))
            i += consumed
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

// MARK: - GFM table parsing

/// Detect a GFM table starting at `lines[start]`: a header row followed by a
/// delimiter row (`|---|:--:|--:|`). Returns the parsed table and how many lines
/// it consumed, or nil (so the caller falls back to normal block parsing — no
/// regression for non-table content that merely contains a `|`).
func parseTable(_ lines: [String], from start: Int) -> (MDTable, Int)? {
    guard start + 1 < lines.count else { return nil }
    guard looksLikeTableRow(lines[start]),
          let aligns = parseDelimiterRow(lines[start + 1]) else { return nil }

    let headers = splitTableRow(lines[start])
    guard !headers.isEmpty else { return nil }
    let cols = headers.count

    var rows: [[String]] = []
    var j = start + 2
    while j < lines.count, looksLikeTableRow(lines[j]) {
        rows.append(padRow(splitTableRow(lines[j]), to: cols))
        j += 1
    }

    let table = MDTable(headers: headers,
                        aligns: normalize(aligns, to: cols),
                        rows: rows)
    return (table, j - start)
}

/// Cheap pre-check: a candidate table line contains a pipe (the strict gate is
/// the delimiter row).
private func looksLikeTableRow(_ line: String) -> Bool {
    line.contains("|")
}

/// A delimiter row is cells of `:?-+:?` separated by pipes. Returns the per-column
/// alignment, or nil if any cell isn't a valid delimiter (so it's not a table).
private func parseDelimiterRow(_ line: String) -> [MDColumnAlign]? {
    let cells = splitTableRow(line)
    guard !cells.isEmpty else { return nil }
    var aligns: [MDColumnAlign] = []
    for c in cells {
        guard c.range(of: "^:?-+:?$", options: .regularExpression) != nil else { return nil }
        let left = c.hasPrefix(":"), right = c.hasSuffix(":")
        aligns.append(left && right ? .center : (right ? .trailing : .leading))
    }
    return aligns
}

/// Split a `| a | b |` row into trimmed cells, honoring escaped `\|` and optional
/// outer pipes.
private func splitTableRow(_ line: String) -> [String] {
    var s = line.trimmingCharacters(in: .whitespaces)
    if s.hasPrefix("|") { s.removeFirst() }
    if s.hasSuffix("|") { s.removeLast() }
    var cells: [String] = []
    var current = ""
    var escaped = false
    for ch in s {
        if escaped {
            current.append(ch); escaped = false
        } else if ch == "\\" {
            escaped = true
        } else if ch == "|" {
            cells.append(current); current = ""
        } else {
            current.append(ch)
        }
    }
    cells.append(current)
    return cells.map { $0.trimmingCharacters(in: .whitespaces) }
}

private func padRow(_ row: [String], to n: Int) -> [String] {
    if row.count == n { return row }
    if row.count > n { return Array(row.prefix(n)) }
    return row + Array(repeating: "", count: n - row.count)
}

private func normalize(_ aligns: [MDColumnAlign], to n: Int) -> [MDColumnAlign] {
    if aligns.count == n { return aligns }
    if aligns.count > n { return Array(aligns.prefix(n)) }
    return aligns + Array(repeating: .leading, count: n - aligns.count)
}

// MARK: - Selectable, self-sizing text view

/// A non-editable UITextView that is fully selectable (any range, across the
/// whole run) and sizes itself to its wrapped content for the width SwiftUI
/// proposes. `isScrollEnabled = false` makes it lay out like a label.
struct SelectableText: UIViewRepresentable {
    let attributed: NSAttributedString

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.isEditable = false
        tv.isSelectable = true
        tv.isScrollEnabled = false
        tv.backgroundColor = .clear
        tv.textContainerInset = .zero
        tv.textContainer.lineFragmentPadding = 0
        tv.textContainer.lineBreakMode = .byWordWrapping
        tv.adjustsFontForContentSizeCategory = true
        tv.dataDetectorTypes = []                 // markdown links already carry .link
        tv.tintColor = UIColor(Theme.teal)         // selection handles + link color
        tv.setContentHuggingPriority(.required, for: .vertical)
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        if !tv.attributedText.isEqual(attributed) { tv.attributedText = attributed }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView tv: UITextView, context: Context) -> CGSize? {
        let proposed = proposal.width ?? UIScreen.main.bounds.width
        let width = (proposed.isFinite && proposed > 0) ? proposed : UIScreen.main.bounds.width
        let fit = tv.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fit.height))
    }
}
