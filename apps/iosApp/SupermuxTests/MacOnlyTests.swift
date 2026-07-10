// apps/iosApp/SupermuxTests/MacOnlyTests.swift
import XCTest
@testable import Supermux

// Gated so the iOS lane (`SupermuxTests`) compiles this file to an empty no-op — its content
// only makes sense against the AppKit-backed `SupermuxMac` module, and isn't listed in the
// SupermuxMacTests excludes because it's the one file we WANT running only there.
#if os(macOS)
import AppKit

final class MacOnlyTests: XCTestCase {

    /// `PasteTextView` (ComposerInput.swift's macOS `NSTextView` subclass) declares no custom
    /// initializer at all — it relies entirely on `NSTextView`'s inherited `init(frame:)` to
    /// wire up the full TextKit stack (text container + layout manager + text storage). Pin
    /// that invariant: a future edit that adds a custom initializer without preserving this
    /// wiring would silently ship a composer with a dead text system, otherwise caught only by
    /// hand (this is the exact seam the Task 12 review flagged after the green-build fix).
    func testPasteTextViewBuildsTextKitStack() {
        XCTAssertNotNil(PasteTextView(frame: .zero).textContainer)
    }
}
#endif
