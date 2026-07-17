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

    func testModifiedReturnInsertsNewlineWithoutSubmitting() {
        let view = PasteTextView(frame: .zero)
        view.string = "first"
        view.setSelectedRange(NSRange(location: view.string.utf16.count, length: 0))
        var submitCount = 0
        view.onHardwareReturn = { submitCount += 1 }

        let event = NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: [.control, .shift],
            timestamp: 0, windowNumber: 0, context: nil, characters: "\r",
            charactersIgnoringModifiers: "\r", isARepeat: false, keyCode: 36
        )
        XCTAssertNotNil(event)
        view.keyDown(with: event!)

        XCTAssertEqual(view.string, "first\n")
        XCTAssertEqual(submitCount, 0)
    }
}
#endif
