import Foundation
import Shared

/// Finds tappable file-path references in a text run, natively.
///
/// **Why this exists rather than calling the shared `findFilePathRefs`.** Kotlin/Native ships a
/// pure-Kotlin regex implementation; Foundation's is C-backed. Running the *identical* pattern over
/// the *identical* input, producing the identical 64 matches:
///
///     700 chars, 0 matches:    Kotlin/Native  25.9 ms   |  Foundation  0.16 ms
///     4992 chars, 64 matches:  Kotlin/Native 635.9 ms   |  Foundation  0.60 ms
///
/// Linkification runs per markdown block for every agent message, so one ordinary ~5 KB reply cost
/// most of a second on the main thread — the dominant cost of opening a session on macOS/iOS.
/// Android/JVM keeps the shared implementation, where the engine is fast; this only diverges the
/// *engine*, on the platforms that need it.
///
/// Nothing about the *semantics* is re-specified here: the pattern is compiled from the shared
/// `FILE_PATH_BODY` constant and the extension whitelist still comes from the shared
/// `hasKnownExtension`. `FilePathScannerTests` asserts this agrees with the Kotlin implementation
/// match-for-match, so the two can't drift.
enum FilePathScanner {

    /// ASCII spelling of `\w`.
    ///
    /// ICU (Foundation) treats `\w` as **Unicode**-aware, while Kotlin/Native and JavaScript treat it
    /// as ASCII-only. Left as `\w`, this scanner would linkify `ünïcödé/päth/file.ts` in full while
    /// Android and the web app match only `th/file.ts` — a silent per-platform behaviour split.
    /// Foundation's reading is arguably the better one, but that's a product decision, not something
    /// to change as a side effect of a performance fix. So: pin ASCII semantics here, and keep the
    /// question of Unicode paths as a separate, cross-platform change.
    /// (`FilePathScannerTests` pins this — its unicode case is what caught the divergence.)
    private static let asciiWord = "0-9A-Za-z_"

    /// Same shape as the shared `FILE_PATH_MATCH_RE`, built from the same body constant. Every `\w`
    /// in the shared body sits inside a character class, so swapping in the ASCII spelling is a
    /// direct substitution.
    private static let matchRE: NSRegularExpression? = {
        let body = FILE_PATH_BODY.replacingOccurrences(of: #"\w"#, with: asciiWord)
        let pattern = "(?<![\(asciiWord)])(\(body))(?::\\d+(?:-\\d+)?|:[^\\s<>\"'\(asciiWord)]+)?(?![\(asciiWord)])"
        return try? NSRegularExpression(pattern: pattern)
    }()

    /// A match plus its parsed ref. `range` is a UTF-16 `NSRange`, which is what
    /// `NSMutableAttributedString` wants — no offset conversion needed.
    struct Match {
        let range: NSRange
        let ref: FilePathRef
    }

    static func matches(in text: String) -> [Match] {
        guard let re = matchRE, !text.isEmpty else { return [] }
        let ns = text as NSString
        var out: [Match] = []
        re.enumerateMatches(in: text, range: NSRange(location: 0, length: ns.length)) { m, _, _ in
            guard let m, m.numberOfRanges >= 2 else { return }
            let whole = m.range
            let pathRange = m.range(at: 1)
            guard pathRange.location != NSNotFound else { return }
            let path = ns.substring(with: pathRange)

            // Whatever the match consumed after the path — ":12", ":12-20", or trailing punctuation.
            var line: Int?
            var endLine: Int?
            let suffixStart = pathRange.location + pathRange.length
            let suffixLength = (whole.location + whole.length) - suffixStart
            if suffixLength > 0 {
                let suffix = ns.substring(with: NSRange(location: suffixStart, length: suffixLength))
                // The shared `parseFilePathRef` returns nil for a suffix that isn't a line spec,
                // and `findFilePathRefs` then drops the match entirely. Mirror that exactly —
                // including dropping an inverted range like `foo.ts:20-10`.
                guard suffix.hasPrefix(":") else { return }
                let spec = suffix.dropFirst()
                let parts = spec.split(separator: "-", omittingEmptySubsequences: false)
                guard (1...2).contains(parts.count) else { return }
                let numbers = parts.map { Int($0) }
                guard numbers.allSatisfy({ $0 != nil }) else { return }
                line = numbers[0]
                if numbers.count == 2 {
                    endLine = numbers[1]
                    if let l = line, let e = endLine, l > e { return }
                }
            }

            guard hasKnownExtension(path: path) else { return }
            out.append(Match(
                range: whole,
                ref: FilePathRef(path: path,
                                 line: line.map { KotlinInt(int: Int32($0)) },
                                 endLine: endLine.map { KotlinInt(int: Int32($0)) })
            ))
        }
        return out
    }
}
