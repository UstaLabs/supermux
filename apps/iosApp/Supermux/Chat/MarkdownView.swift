import SwiftUI
import UIKit

/// Agent replies render here. We parse the markdown into blocks, build ONE
/// `NSAttributedString` for the whole message, and show it in a non-editable,
/// non-scrolling, *selectable* `UITextView` (`SelectableText`).
///
/// Why not SwiftUI `Text` + `.textSelection(.enabled)`:
///   • SwiftUI selection is per-`Text`, so you can't drag-select a phrase that
///     spans paragraphs / list items — you only get whole blocks at a time.
///   • `Text` *truncates* an overlong unbreakable token (e.g.
///     `full_duration_song_clip.song_clip_id`) with a "…" instead of wrapping it.
/// A UITextView fixes both: native arbitrary selection (any word/phrase, across
/// blocks) and real wrapping (long tokens break across lines; code char-wraps).
struct MarkdownView: View {
    let text: String

    var body: some View {
        SelectableText(attributed: MarkdownAttributed.build(text))
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Attributed-string builder

private enum MarkdownAttributed {
    static func build(_ text: String) -> NSAttributedString {
        let out = NSMutableAttributedString()
        let blocks = parseMarkdown(text)
        for (i, b) in blocks.enumerated() {
            if i > 0 { out.append(NSAttributedString(string: "\n")) }
            out.append(attributed(for: b))
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
        let opts = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible)
        // SwiftUI's AttributedString(markdown:) (String) is well-supported; bridge it
        // to NSAttributedString. Markdown parsing only sets *intent* attributes
        // (no fonts/colors), so we layer our own base font/color on top.
        let parsed = (try? AttributedString(markdown: s, options: opts)) ?? AttributedString(s)
        let m = NSMutableAttributedString(attributedString: NSAttributedString(parsed))
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

private extension UIFont {
    func withTraits(_ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        let merged = fontDescriptor.symbolicTraits.union(traits)
        guard let d = fontDescriptor.withSymbolicTraits(merged) else { return self }
        return UIFont(descriptor: d, size: 0)
    }
}

// MARK: - Selectable, self-sizing text view

/// A non-editable UITextView that is fully selectable (any range, across the
/// whole message) and sizes itself to its wrapped content for the width SwiftUI
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

// MARK: - Block parser

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
