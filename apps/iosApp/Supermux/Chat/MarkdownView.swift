import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import Shared

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
struct MarkdownView: View, Equatable {
    let text: String
    /// When set (agent messages), tapped file-path links call back here; nil leaves the
    /// links inert (taps are still intercepted, never opened by the system).
    var onOpenFile: ((FilePathRef) -> Void)? = nil

    /// Text identity is enough: logged message text is immutable, and linkify follows
    /// `onOpenFile != nil`. Parent re-evaluations (broker observation noise) skip re-parse.
    static func == (lhs: MarkdownView, rhs: MarkdownView) -> Bool {
        lhs.text == rhs.text && (lhs.onOpenFile != nil) == (rhs.onOpenFile != nil)
    }

    var body: some View {
        let segments = groupSegments(parseMarkdown(text))
        let linkify = onOpenFile != nil
        VStack(alignment: .leading, spacing: 8) {
            ForEach(segments.indices, id: \.self) { i in
                switch segments[i] {
                case .text(let blocks):
                    SelectableText(attributed: MarkdownAttributed.build(blocks: blocks, linkify: linkify), onOpenFile: onOpenFile)
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
    static func build(blocks: [MDBlock], linkify: Bool = false) -> NSAttributedString {
        let out = NSMutableAttributedString()
        var first = true
        for b in blocks {
            if case .table = b { continue }   // tables render via MarkdownTableView
            if !first { out.append(NSAttributedString(string: "\n")) }
            let piece = NSMutableAttributedString(attributedString: attributed(for: b))
            if linkify { FilePathLinks.decorate(piece) }  // only agent messages get tappable file links
            out.append(piece)
            first = false
        }
        return out
    }

    private static var bodyFont: PlatformFont {
        #if os(macOS)
        .preferredFont(forTextStyle: .body)
        #else
        .preferredFont(forTextStyle: .subheadline)
        #endif
    }

    private static func headingFont(_ level: Int) -> PlatformFont {
        let style: PlatformFont.TextStyle = level == 1 ? .title3 : (level == 2 ? .headline : .subheadline)
        return PlatformFont.preferredFont(forTextStyle: style).withTraits(.traitBold)
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
            return styledInline(s, font: bodyFont, color: .smLabel, paragraph: paragraph())
        case .heading(let level, let s):
            return styledInline(s, font: headingFont(level), color: .smLabel, paragraph: paragraph())
        case .quote(let s):
            let p = paragraph(); p.firstLineHeadIndent = 12; p.headIndent = 12
            return styledInline(s, font: bodyFont, color: .smSecondaryLabel, paragraph: p)
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
            attributes: [.font: bodyFont, .foregroundColor: PlatformColor.smSecondaryLabel])
        line.append(styledInline(body, font: bodyFont, color: .smLabel, paragraph: nil))
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
        let f = PlatformFont.monospacedSystemFont(ofSize: max(11, bodyFont.pointSize - 1), weight: .regular)
        return NSAttributedString(string: code, attributes: [
            .font: f,
            .foregroundColor: PlatformColor.smLabel,
            .backgroundColor: PlatformColor.smTertiaryBackground,
            .paragraphStyle: p,
        ])
    }

    /// Parse inline markdown and apply fonts/colors in one pass.
    ///
    /// **Does not use `AttributedString(markdown:)`.** That Foundation API was the dominant
    /// per-paragraph cost on first layout (prior bench: most of a multi-ms "cache miss" row),
    /// because every paragraph/list/heading called it and then walked `.inlinePresentationIntent`.
    /// The hand parser matches the GFM subset we render (bold/italic/code/strike/links).
    private static func styledInline(_ s: String, font: PlatformFont, color: PlatformColor,
                                     paragraph: NSParagraphStyle?) -> NSAttributedString {
        MarkdownInline.nsAttributed(s, baseFont: font, color: color, paragraph: paragraph)
    }
}

// MARK: - File-path links (tap a path → open it in the editor)

/// Detects file paths in an agent message's rendered text (via the shared KMP
/// `findFilePathRefs`) and tags them with a custom `supermux-file://` `.link`, so
/// `SelectableText`'s delegate can intercept the tap and open the editor — instead of
/// letting the system try to open the URL. The whole `FilePathRef` round-trips through
/// the URL's query so the delegate can reconstruct it.
enum FilePathLinks {
    /// Custom scheme: the UITextView delegate intercepts this (vs. opening a real URL).
    static let scheme = "supermux-file"

    /// Tag every detected file-path range in `s` with a `supermux-file://` link + teal underline.
    static func decorate(_ s: NSMutableAttributedString) {
        let length = (s.string as NSString).length
        // `FilePathScanner`, not the shared `findFilePathRefs`: same pattern and same semantics, but
        // Foundation's regex engine instead of Kotlin/Native's — see FilePathScanner for the
        // measurements (this call was ~636 ms for one 5 KB agent message before).
        for m in FilePathScanner.matches(in: s.string) {
            let range = m.range
            guard range.length > 0, range.location + range.length <= length else { continue }
            guard let url = url(for: m.ref) else { continue }
            s.addAttributes([
                .link: url,
                .foregroundColor: PlatformColor(Theme.teal),
                .underlineStyle: NSUnderlineStyle.single.rawValue,
            ], range: range)
        }
    }

    /// Encode a `FilePathRef` as `supermux-file://open?path=…&line=…&end=…`. The path
    /// lives in the query (not the URL path) so absolute/`~` paths can't be misread as an
    /// authority, and URLComponents handles all the percent-encoding.
    static func url(for ref: FilePathRef) -> URL? {
        var c = URLComponents()
        c.scheme = scheme
        c.host = "open"
        var q: [URLQueryItem] = [URLQueryItem(name: "path", value: ref.path)]
        if let line = ref.line { q.append(URLQueryItem(name: "line", value: "\(line.intValue)")) }
        if let end = ref.endLine { q.append(URLQueryItem(name: "end", value: "\(end.intValue)")) }
        c.queryItems = q
        return c.url
    }

    /// Parse a tapped `supermux-file://` URL back into a `FilePathRef` (nil if not ours).
    static func ref(from url: URL) -> FilePathRef? {
        guard url.scheme == scheme else { return nil }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        guard let path = items?.first(where: { $0.name == "path" })?.value, !path.isEmpty else { return nil }
        let line = items?.first { $0.name == "line" }?.value.flatMap { Int($0) }
        let end = items?.first { $0.name == "end" }?.value.flatMap { Int($0) }
        return FilePathRef(path: path,
                           line: line.map { KotlinInt(int: Int32($0)) },
                           endLine: end.map { KotlinInt(int: Int32($0)) })
    }
}

// MARK: - Inline markdown parsing (shared by flow blocks and table cells)

/// Fast pure-Swift inline markdown (bold / italic / `code` / ~~strike~~ / [label](url)).
///
/// Replaces Foundation's `AttributedString(markdown:)`, which is correct but far too heavy to
/// run once per paragraph for every visible agent message on first layout. Semantics match the
/// GFM subset the transcript actually uses; unmatched markers stay literal (same as before).
enum MarkdownInline {
    private struct Flags: OptionSet {
        let rawValue: Int
        static let bold = Flags(rawValue: 1 << 0)
        static let italic = Flags(rawValue: 1 << 1)
        static let code = Flags(rawValue: 1 << 2)
        static let strike = Flags(rawValue: 1 << 3)
    }

    private struct Run {
        var text: String
        var flags: Flags
        var link: String?
    }

    /// SwiftUI-friendly `AttributedString` for table cells (no NS font plumbing needed).
    static func attributed(_ s: String) -> AttributedString {
        var out = AttributedString()
        for run in parse(s) {
            var piece = AttributedString(run.text)
            if run.flags.contains(.bold) { piece.inlinePresentationIntent = .stronglyEmphasized }
            if run.flags.contains(.italic) {
                piece.inlinePresentationIntent =
                    (piece.inlinePresentationIntent ?? []).union(.emphasized)
            }
            if run.flags.contains(.code) {
                piece.inlinePresentationIntent =
                    (piece.inlinePresentationIntent ?? []).union(.code)
            }
            if run.flags.contains(.strike) {
                piece.inlinePresentationIntent =
                    (piece.inlinePresentationIntent ?? []).union(.strikethrough)
            }
            if let link = run.link, let url = URL(string: link) { piece.link = url }
            out.append(piece)
        }
        return out
    }

    /// Build a fully-styled `NSAttributedString` (fonts/colors applied — no second intent pass).
    static func nsAttributed(_ s: String, baseFont: PlatformFont, color: PlatformColor,
                             paragraph: NSParagraphStyle?) -> NSAttributedString {
        let out = NSMutableAttributedString()
        for run in parse(s) {
            var font = baseFont
            if run.flags.contains(.bold) { font = font.withTraits(.traitBold) }
            if run.flags.contains(.italic) { font = font.withTraits(.traitItalic) }
            if run.flags.contains(.code) {
                font = .monospacedSystemFont(ofSize: max(11, baseFont.pointSize - 0.5), weight: .regular)
            }
            var attrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: color,
            ]
            if run.flags.contains(.code) {
                attrs[.backgroundColor] = PlatformColor.smTertiaryFill
            }
            if run.flags.contains(.strike) {
                attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
            }
            if let link = run.link, let url = URL(string: link) {
                attrs[.link] = url
                attrs[.foregroundColor] = PlatformColor(Theme.teal)
                attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue
            }
            out.append(NSAttributedString(string: run.text, attributes: attrs))
        }
        if let paragraph, out.length > 0 {
            out.addAttribute(.paragraphStyle, value: paragraph,
                             range: NSRange(location: 0, length: out.length))
        }
        return out
    }

    /// Scan `s` left-to-right. Delimiters that don't form a closed pair are emitted literally.
    private static func parse(_ s: String) -> [Run] {
        if s.isEmpty { return [] }
        var runs: [Run] = []
        var buf = ""
        var i = s.startIndex

        func flush() {
            guard !buf.isEmpty else { return }
            runs.append(Run(text: buf, flags: [], link: nil))
            buf = ""
        }
        func peek(_ n: Int) -> String {
            guard let end = s.index(i, offsetBy: n, limitedBy: s.endIndex) else { return "" }
            return String(s[i..<end])
        }
        func advance(_ n: Int) {
            i = s.index(i, offsetBy: n, limitedBy: s.endIndex) ?? s.endIndex
        }
        /// Find closing delimiter `mark` after `i`, not counting escapes. Returns the index of the
        /// closer's first character, or nil.
        func findClose(_ mark: String) -> String.Index? {
            var j = i
            let m0 = mark[mark.startIndex]
            while j < s.endIndex {
                if s[j] == "\\" {
                    j = s.index(after: j)
                    if j < s.endIndex { j = s.index(after: j) }
                    continue
                }
                if s[j] == m0 {
                    if mark.count == 1 { return j }
                    let next = s.index(after: j)
                    if next < s.endIndex, String(s[j...next]) == mark { return j }
                }
                j = s.index(after: j)
            }
            return nil
        }

        while i < s.endIndex {
            // Escape: emit the next character literally.
            if s[i] == "\\", s.index(after: i) < s.endIndex {
                flush()
                let next = s.index(after: i)
                buf.append(s[next])
                flush()
                i = s.index(after: next)
                continue
            }
            // Inline code `...` (no nesting; first closer wins).
            if s[i] == "`", let close = findClose("`"), close > i {
                flush()
                let innerStart = s.index(after: i)
                let inner = String(s[innerStart..<close])
                runs.append(Run(text: inner, flags: .code, link: nil))
                i = s.index(after: close)
                continue
            }
            // Link [label](url)
            if s[i] == "[",
               let labelEnd = findClose("]"),
               s.index(after: labelEnd) < s.endIndex,
               s[s.index(after: labelEnd)] == "(" {
                let urlStart = s.index(labelEnd, offsetBy: 2)
                if let urlEnd = s[urlStart...].firstIndex(of: ")") {
                    flush()
                    let label = String(s[s.index(after: i)..<labelEnd])
                    let url = String(s[urlStart..<urlEnd])
                    runs.append(Run(text: label.isEmpty ? url : label, flags: [], link: url))
                    i = s.index(after: urlEnd)
                    continue
                }
            }
            // ~~strike~~
            if peek(2) == "~~" {
                var j = s.index(i, offsetBy: 2)
                var closeAt: String.Index?
                while j < s.endIndex {
                    if s[j] == "~", s.index(after: j) < s.endIndex, s[s.index(after: j)] == "~" {
                        closeAt = j; break
                    }
                    j = s.index(after: j)
                }
                if let closeAt, closeAt > s.index(i, offsetBy: 1) {
                    flush()
                    let innerStart = s.index(i, offsetBy: 2)
                    for r in parse(String(s[innerStart..<closeAt])) {
                        runs.append(Run(text: r.text, flags: r.flags.union(.strike), link: r.link))
                    }
                    i = s.index(closeAt, offsetBy: 2)
                    continue
                }
            }
            // **bold** or __bold__
            let two = peek(2)
            if two == "**" || two == "__" {
                let mark = two
                var j = s.index(i, offsetBy: 2)
                var closeAt: String.Index?
                while j < s.endIndex {
                    if String(s[j...]).hasPrefix(mark) { closeAt = j; break }
                    j = s.index(after: j)
                }
                if let closeAt, closeAt >= s.index(i, offsetBy: 2) {
                    flush()
                    let innerStart = s.index(i, offsetBy: 2)
                    for r in parse(String(s[innerStart..<closeAt])) {
                        runs.append(Run(text: r.text, flags: r.flags.union(.bold), link: r.link))
                    }
                    i = s.index(closeAt, offsetBy: 2)
                    continue
                }
            }
            // *italic* or _italic_ (single; not ** / __)
            if (s[i] == "*" || s[i] == "_"), peek(2) != "**", peek(2) != "__" {
                let mark = String(s[i])
                if let close = findClose(mark), close > i {
                    let innerStart = s.index(after: i)
                    if close > innerStart {
                        flush()
                        for r in parse(String(s[innerStart..<close])) {
                            runs.append(Run(text: r.text, flags: r.flags.union(.italic), link: r.link))
                        }
                        i = s.index(after: close)
                        continue
                    }
                }
            }
            buf.append(s[i])
            i = s.index(after: i)
        }
        flush()
        return runs
    }
}

#if canImport(UIKit)
private extension UIFont {
    func withTraits(_ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        let merged = fontDescriptor.symbolicTraits.union(traits)
        guard let d = fontDescriptor.withSymbolicTraits(merged) else { return self }
        return UIFont(descriptor: d, size: 0)
    }
}
#else
// AppKit spells the traits `.bold`/`.italic` (no `trait` prefix); alias them so the
// shared call sites above (`withTraits(.traitBold)`) read identically on both platforms.
private extension NSFontDescriptor.SymbolicTraits {
    static var traitBold: NSFontDescriptor.SymbolicTraits { .bold }
    static var traitItalic: NSFontDescriptor.SymbolicTraits { .italic }
}
private extension NSFont {
    func withTraits(_ traits: NSFontDescriptor.SymbolicTraits) -> NSFont {
        // Unlike UIKit, `withSymbolicTraits` is non-optional on AppKit; the font init is
        // the failable step here.
        let d = fontDescriptor.withSymbolicTraits(fontDescriptor.symbolicTraits.union(traits))
        return NSFont(descriptor: d, size: 0) ?? self
    }
}
#endif

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
            .font(tableFont)
            .fontWeight(header ? .semibold : .regular)
            .fixedSize(horizontal: true, vertical: true)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: table.aligns[column].frameAlignment)
            .background(header ? Theme.teal.opacity(0.10) : Color.clear)
            .overlay(Rectangle().strokeBorder(Theme.hairline, lineWidth: 0.5))
    }

    private var tableFont: Font {
        #if os(macOS)
        .body
        #else
        .subheadline
        #endif
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

#if canImport(UIKit)
/// A non-editable UITextView that is fully selectable (any range, across the
/// whole run) and sizes itself to its wrapped content for the width SwiftUI
/// proposes. `isScrollEnabled = false` makes it lay out like a label.
struct SelectableText: UIViewRepresentable {
    let attributed: NSAttributedString
    /// Tapped file-path link handler (nil → file links are inert but still intercepted).
    var onOpenFile: ((FilePathRef) -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator(onOpenFile: onOpenFile) }

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
        tv.dataDetectorTypes = []                 // markdown + file-path links already carry .link
        tv.tintColor = UIColor(Theme.teal)         // selection handles + link color
        tv.delegate = context.coordinator          // intercept supermux-file:// link taps
        tv.setContentHuggingPriority(.required, for: .vertical)
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        context.coordinator.onOpenFile = onOpenFile
        if !tv.attributedText.isEqual(attributed) {
            tv.attributedText = attributed
            context.coordinator.invalidateLayout()
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView tv: UITextView, context: Context) -> CGSize? {
        let proposed = proposal.width ?? SMScreen.mainWidth
        let width = (proposed.isFinite && proposed > 0) ? proposed : SMScreen.mainWidth
        if let cached = context.coordinator.cachedSize(forWidth: width) { return cached }
        let fit = tv.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        let size = CGSize(width: width, height: ceil(fit.height))
        context.coordinator.remember(size: size, width: width)
        return size
    }

    /// Intercepts taps on our `supermux-file://` links (→ open in the editor) while letting
    /// every other link (http(s), etc.) fall through to the system's default handling.
    final class Coordinator: NSObject, UITextViewDelegate {
        var onOpenFile: ((FilePathRef) -> Void)?
        private var laidOutWidth: CGFloat = -1
        private var laidOutSize: CGSize = .zero
        init(onOpenFile: ((FilePathRef) -> Void)?) { self.onOpenFile = onOpenFile }

        func invalidateLayout() { laidOutWidth = -1 }
        func cachedSize(forWidth width: CGFloat) -> CGSize? {
            abs(width - laidOutWidth) < 0.5 ? laidOutSize : nil
        }
        func remember(size: CGSize, width: CGFloat) {
            laidOutWidth = width
            laidOutSize = size
        }

        // NOTE: `shouldInteractWith` is deprecated on iOS 17+ but still functional, and is the
        // simplest way to intercept a custom scheme; the modern UITextItem API needs more wiring.
        func textView(_ textView: UITextView, shouldInteractWith URL: URL,
                      in characterRange: NSRange, interaction: UITextItemInteraction) -> Bool {
            if let ref = FilePathLinks.ref(from: URL) {
                onOpenFile?(ref)
                return false   // handled — don't let the system try to open the custom-scheme URL
            }
            return true        // non-file links keep their default behavior
        }
    }
}
#else
/// Mac twin: a non-editable, selectable NSTextView that hugs its wrapped content height for
/// the width SwiftUI proposes (mirrors the iOS UITextView configuration — non-scrolling,
/// zero insets, width-tracking container) and routes `supermux-file://` link clicks to the
/// same `onOpenFile` closure the iOS coordinator calls.
struct SelectableText: NSViewRepresentable {
    let attributed: NSAttributedString
    /// Tapped file-path link handler (nil → file links are inert but still intercepted).
    var onOpenFile: ((FilePathRef) -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator(onOpenFile: onOpenFile) }

    func makeNSView(context: Context) -> NSTextView {
        let tv = NSTextView(frame: .zero)
        tv.isEditable = false
        tv.isSelectable = true
        tv.drawsBackground = false
        tv.textContainerInset = .zero
        tv.textContainer?.lineFragmentPadding = 0
        tv.textContainer?.widthTracksTextView = true
        tv.isVerticallyResizable = false
        tv.isHorizontallyResizable = false
        // Display-only: turn off the full editor surface. `usesFontPanel` alone has been measured
        // to drag NSTextView layout through Touch-Bar/font-panel machinery on large strings;
        // the rest of these are free no-ops for a non-editable view and keep first-layout lean.
        tv.usesFontPanel = false
        tv.usesRuler = false
        tv.importsGraphics = false
        tv.allowsUndo = false
        tv.isAutomaticQuoteSubstitutionEnabled = false
        tv.isAutomaticDashSubstitutionEnabled = false
        tv.isAutomaticTextReplacementEnabled = false
        tv.isAutomaticSpellingCorrectionEnabled = false
        tv.isContinuousSpellCheckingEnabled = false
        tv.isGrammarCheckingEnabled = false
        tv.isRichText = true
        // The attributed string already carries explicit teal + underline on file links;
        // pin the default link styling to the same look so plain markdown links match the
        // iOS tint (NSTextView would otherwise paint every .link range system-blue).
        tv.linkTextAttributes = [
            .foregroundColor: PlatformColor(Theme.teal),
            .underlineStyle: NSUnderlineStyle.single.rawValue,
            .cursor: NSCursor.pointingHand,
        ]
        tv.delegate = context.coordinator          // intercept supermux-file:// link clicks
        tv.setContentHuggingPriority(.required, for: .vertical)
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return tv
    }

    func updateNSView(_ tv: NSTextView, context: Context) {
        context.coordinator.onOpenFile = onOpenFile
        if tv.textStorage?.isEqual(attributed) != true {
            tv.textStorage?.setAttributedString(attributed)
            context.coordinator.invalidateLayout()
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView tv: NSTextView, context: Context) -> CGSize? {
        let proposed = proposal.width ?? SMScreen.mainWidth
        let width = (proposed.isFinite && proposed > 0) ? proposed : SMScreen.mainWidth
        // SwiftUI asks sizeThatFits many times per frame with the same width. Re-running
        // ensureLayout for identical inputs is pure main-thread waste on long agent replies.
        if let cached = context.coordinator.cachedSize(forWidth: width) { return cached }
        guard let container = tv.textContainer, let lm = tv.layoutManager else { return nil }
        container.size = NSSize(width: width, height: .greatestFiniteMagnitude)
        lm.ensureLayout(for: container)
        let fit = lm.usedRect(for: container)
        let size = CGSize(width: width, height: ceil(fit.height))
        context.coordinator.remember(size: size, width: width)
        return size
    }

    /// Intercepts clicks on our `supermux-file://` links (→ open in the editor) while letting
    /// every other link (http(s), etc.) fall through to the system's default handling.
    /// Note the inverted contract vs UIKit: returning `true` here means "handled".
    final class Coordinator: NSObject, NSTextViewDelegate {
        var onOpenFile: ((FilePathRef) -> Void)?
        private var laidOutWidth: CGFloat = -1
        private var laidOutSize: CGSize = .zero
        init(onOpenFile: ((FilePathRef) -> Void)?) { self.onOpenFile = onOpenFile }

        func invalidateLayout() { laidOutWidth = -1 }
        func cachedSize(forWidth width: CGFloat) -> CGSize? {
            abs(width - laidOutWidth) < 0.5 ? laidOutSize : nil
        }
        func remember(size: CGSize, width: CGFloat) {
            laidOutWidth = width
            laidOutSize = size
        }

        func textView(_ textView: NSTextView, clickedOnLink link: Any, at charIndex: Int) -> Bool {
            let url = (link as? URL) ?? (link as? String).flatMap { URL(string: $0) }
            if let url, let ref = FilePathLinks.ref(from: url) {
                onOpenFile?(ref)
                return true    // handled — don't let the system try to open the custom-scheme URL
            }
            return false       // non-file links keep their default behavior (NSWorkspace open)
        }
    }
}
#endif
