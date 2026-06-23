import XCTest
@testable import Supermux

/// Unit tests for `composerEnterAction` — hardware-keyboard Return policy in the chat
/// composer (Enter sends, Shift+Enter newline; soft keyboard unchanged).
final class ComposerKeyboardTests: XCTestCase {

    func testSoftKeyboardEnterInsertsNewline() {
        XCTAssertEqual(composerEnterAction(hardwareKeyboard: false, shift: false, canSubmit: true), .insertNewline)
        XCTAssertEqual(composerEnterAction(hardwareKeyboard: false, shift: true, canSubmit: true), .insertNewline)
    }

    func testHardwareEnterSendsWhenAllowed() {
        XCTAssertEqual(composerEnterAction(hardwareKeyboard: true, shift: false, canSubmit: true), .send)
    }

    func testHardwareShiftEnterInsertsNewline() {
        XCTAssertEqual(composerEnterAction(hardwareKeyboard: true, shift: true, canSubmit: true), .insertNewline)
        XCTAssertEqual(composerEnterAction(hardwareKeyboard: true, shift: true, canSubmit: false), .insertNewline)
    }

    func testHardwareEnterNoopWhenEmpty() {
        XCTAssertEqual(composerEnterAction(hardwareKeyboard: true, shift: false, canSubmit: false), .noop)
    }
}
