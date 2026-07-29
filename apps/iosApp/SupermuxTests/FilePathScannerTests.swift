import XCTest
import Shared
@testable import Supermux

/// `FilePathScanner` replaces the shared `findFilePathRefs` on Apple platforms for speed
/// (Kotlin/Native's regex engine measured ~1000x slower than Foundation's on the same pattern).
/// Speed is worthless if it changes behaviour, so this pins the two implementations together:
/// every case below must produce identical matches, offsets and parsed refs.
final class FilePathScannerTests: XCTestCase {

    /// Compare native vs shared on one input, field by field.
    private func assertParity(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        let native = FilePathScanner.matches(in: text)
        let shared = findFilePathRefs(text: text)

        XCTAssertEqual(native.count, shared.count,
                       "match count differs for \(text.debugDescription)", file: file, line: line)
        guard native.count == shared.count else { return }

        for (n, k) in zip(native, shared) {
            XCTAssertEqual(n.range.location, Int(k.start),
                           "start differs in \(text.debugDescription)", file: file, line: line)
            XCTAssertEqual(n.range.location + n.range.length, Int(k.end),
                           "end differs in \(text.debugDescription)", file: file, line: line)
            XCTAssertEqual(n.ref.path, k.ref.path,
                           "path differs in \(text.debugDescription)", file: file, line: line)
            XCTAssertEqual(n.ref.line?.intValue, k.ref.line?.intValue,
                           "line differs in \(text.debugDescription)", file: file, line: line)
            XCTAssertEqual(n.ref.endLine?.intValue, k.ref.endLine?.intValue,
                           "endLine differs in \(text.debugDescription)", file: file, line: line)
        }
    }

    func testParityOnRepresentativeInputs() {
        [
            "",
            "no paths here at all",
            "see apps/iosApp/Supermux/Chat/ChatPane.swift",
            "see apps/iosApp/Supermux/Chat/ChatPane.swift:425",
            "range apps/iosApp/Supermux/Chat/ChatPane.swift:425-430",
            "inverted range src/main.ts:99-10 should be dropped",
            "non-numeric suffix src/main.ts:abc",
            "trailing punctuation src/main.ts, and more",
            "in parens (src/core/session-manager/messages.ts:87)",
            "relative ./src/main.ts and ../lib/util.js",
            "absolute /Users/ahmet/projects/supermux/package.json",
            "home ~/projects/supermux/bun.lock",
            "unknown extension src/thing.zzz should not match",
            "no extension src/Makefile",
            "two on one line: src/a.ts:1 and src/b.ts:2",
            "code-ish let x = a/b/c_d/e_f/g_h + foo_bar_baz(qux)",
            "deep a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p.ts:3",
            "unicode ünïcödé/päth/file.ts:12 after emoji 🎉 src/x.ts:4",
            "adjacent-word xsrc/main.tsx",
            "url-ish https://example.com/path/to/thing.js",
        ].forEach { assertParity($0) }
    }

    /// A realistic agent reply — the shape that actually hit this path.
    func testParityOnRealisticMessage() {
        assertParity("""
        Traced it. The merge lives in `apps/iosApp/Supermux/Chat/ChatActivity.swift:39` and the call
        site is `apps/iosApp/Supermux/Chat/ChatPane.swift:425`.

        - `src/core/session-manager/messages.ts:87` caps history at 200
        - see also ./scripts/build.sh and ~/.mux/state/db.sqlite3

        ```swift
        let blocks = buildChatBlocks(messages: log, activity: activityEvents)
        ```
        """)
    }

    /// The whole point: the native scan must be dramatically faster than the shared one.
    func testNativeScanIsFasterThanShared() {
        let text = String(repeating:
            "Traced the path in apps/iosApp/Supermux/Chat/ChatPane.swift:425 and it works.\n", count: 64)

        let t0 = CFAbsoluteTimeGetCurrent()
        let native = FilePathScanner.matches(in: text)
        let nativeMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000

        let t1 = CFAbsoluteTimeGetCurrent()
        let shared = findFilePathRefs(text: text)
        let sharedMs = (CFAbsoluteTimeGetCurrent() - t1) * 1000

        print(String(format: "PATHSCAN native=%.3f ms shared=%.3f ms (%.0fx) matches=%d/%d",
                     nativeMs, sharedMs, sharedMs / max(nativeMs, 0.0001), native.count, shared.count))
        XCTAssertEqual(native.count, shared.count)
        XCTAssertLessThan(nativeMs, sharedMs, "native scan should beat the Kotlin/Native engine")
    }
}
