import SwiftUI
import GameController

/// What a hardware-keyboard Return should do in the chat composer (web parity:
/// Enter sends, Shift+Enter inserts a newline). The soft keyboard keeps the default
/// multiline Return behavior.
enum ComposerEnterAction: Equatable {
    case insertNewline
    case send
    /// Hardware Return when there's nothing to send — swallow the key.
    case noop
}

func composerEnterAction(hardwareKeyboard: Bool, shift: Bool, canSubmit: Bool) -> ComposerEnterAction {
    guard hardwareKeyboard else { return .insertNewline }
    if shift { return .insertNewline }
    return canSubmit ? .send : .noop
}

/// Intercepts hardware-keyboard Return in a multiline composer. Only active while
/// `GCKeyboard.coalesced` is non-nil; soft-keyboard Return is unchanged.
struct ComposerHardwareKeyboardSubmit: ViewModifier {
    let canSubmit: Bool
    let onSubmit: () -> Void

    func body(content: Content) -> some View {
        content.onKeyPress(phases: .down) { press in
            guard press.key == .return else { return .ignored }
            switch composerEnterAction(
                hardwareKeyboard: GCKeyboard.coalesced != nil,
                shift: press.modifiers.contains(.shift),
                canSubmit: canSubmit
            ) {
            case .insertNewline:
                return .ignored
            case .send:
                onSubmit()
                return .handled
            case .noop:
                return .handled
            }
        }
    }
}

extension View {
    func composerHardwareKeyboardSubmit(canSubmit: Bool, onSubmit: @escaping () -> Void) -> some View {
        modifier(ComposerHardwareKeyboardSubmit(canSubmit: canSubmit, onSubmit: onSubmit))
    }
}
