// apps/iosApp/Supermux/Chat/Composer/ComposerInput.swift
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A multiline composer text field backed by UIKit so it can do what SwiftUI's `TextField` can't on
/// iOS: intercept a PASTE of an image/PDF (long-press "Paste" or ⌘V) and stage it as an attachment,
/// exactly how WhatsApp/Messages accept a pasted photo. Plain-text paste falls through to normal
/// behavior. Mirrors the previous `TextField`: bound draft, placeholder, auto-grow up to `maxLines`,
/// focus bridged to `@FocusState`, hardware Return = send / Shift+Return = newline.
struct ComposerInput: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String
    var maxLines: Int
    var isFocused: FocusState<Bool>.Binding
    var canSubmit: Bool
    var onSubmit: () -> Void
    /// Invoked on a non-text paste; returns true if it staged something (so the view skips the
    /// default paste and inserts nothing into the text).
    var onPasteAttachment: () -> Bool

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> PasteTextView {
        let tv = PasteTextView()
        tv.delegate = context.coordinator
        tv.onPasteAttachment = { context.coordinator.parent.onPasteAttachment() }
        tv.onHardwareReturn = { context.coordinator.handleHardwareReturn() }
        tv.font = .preferredFont(forTextStyle: .body)
        tv.textColor = .label
        tv.backgroundColor = .clear
        tv.isScrollEnabled = false
        tv.textContainerInset = .zero
        tv.textContainer.lineFragmentPadding = 0
        tv.adjustsFontForContentSizeCategory = true
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return tv
    }

    func updateUIView(_ tv: PasteTextView, context: Context) {
        context.coordinator.parent = self
        if tv.text != text {
            tv.text = text
            tv.invalidateIntrinsicContentSize()
        }
        tv.placeholderLabel.text = placeholder
        tv.placeholderLabel.isHidden = !text.isEmpty
        tv.maxHeight = ceil((tv.font?.lineHeight ?? 20) * CGFloat(maxLines))

        // Focus bridge — deferred so we never mutate @FocusState *during* a SwiftUI update
        // (which becomeFirstResponder → textViewDidBeginEditing would do synchronously).
        let want = isFocused.wrappedValue
        if want != tv.isFirstResponder {
            DispatchQueue.main.async {
                if want, !tv.isFirstResponder { tv.becomeFirstResponder() }
                else if !want, tv.isFirstResponder { tv.resignFirstResponder() }
            }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: PasteTextView, context: Context) -> CGSize? {
        let width = proposal.width ?? uiView.bounds.width
        guard width > 0 else { return nil }
        let fit = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        let lineH = uiView.font?.lineHeight ?? 20
        let maxH = ceil(lineH * CGFloat(maxLines))
        let minH = ceil(lineH)
        uiView.isScrollEnabled = fit.height > maxH
        return CGSize(width: width, height: min(max(fit.height, minH), maxH))
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ComposerInput
        init(_ parent: ComposerInput) { self.parent = parent }

        func textViewDidChange(_ textView: UITextView) {
            parent.text = textView.text
            if let tv = textView as? PasteTextView {
                tv.placeholderLabel.isHidden = !textView.text.isEmpty
                tv.invalidateIntrinsicContentSize()
            }
        }
        func textViewDidBeginEditing(_ textView: UITextView) {
            if !parent.isFocused.wrappedValue { parent.isFocused.wrappedValue = true }
        }
        func textViewDidEndEditing(_ textView: UITextView) {
            if parent.isFocused.wrappedValue { parent.isFocused.wrappedValue = false }
        }
        func handleHardwareReturn() {
            // Parity with ComposerEnterAction: Enter sends when there's something to send,
            // otherwise it's swallowed (the key command already consumed the event → no newline).
            if parent.canSubmit { parent.onSubmit() }
        }
    }
}

/// `UITextView` that intercepts image/PDF paste (the WhatsApp-style win) and hardware Return,
/// with a manually-managed placeholder label (UITextView has no native placeholder).
final class PasteTextView: UITextView {
    var onPasteAttachment: (() -> Bool)?
    var onHardwareReturn: (() -> Void)?
    var maxHeight: CGFloat = .greatestFiniteMagnitude
    /// Injectable so the paste logic is unit-testable; production reads the system clipboard.
    var pasteboard: UIPasteboard = .general

    let placeholderLabel: UILabel = {
        let l = UILabel()
        l.textColor = .placeholderText
        l.font = .preferredFont(forTextStyle: .body)
        l.adjustsFontForContentSizeCategory = true
        l.numberOfLines = 1
        return l
    }()

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        addSubview(placeholderLabel)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let maxW = bounds.width - textContainerInset.left - textContainerInset.right
        let fit = placeholderLabel.sizeThatFits(CGSize(width: maxW, height: .greatestFiniteMagnitude))
        placeholderLabel.frame = CGRect(x: textContainerInset.left, y: textContainerInset.top,
                                        width: maxW, height: fit.height)
    }

    /// True when the clipboard holds something we stage as an attachment rather than inserting.
    private var clipboardHasAttachment: Bool {
        pasteboard.hasImages || pasteboard.contains(pasteboardTypes: [UTType.pdf.identifier])
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        // Enable "Paste" in the edit menu for an image/PDF clipboard (a plain text view would
        // otherwise disable it, so no callout would appear).
        if action == #selector(UIResponder.paste(_:)), clipboardHasAttachment {
            return true
        }
        return super.canPerformAction(action, withSender: sender)
    }

    override func paste(_ sender: Any?) {
        if clipboardHasAttachment, onPasteAttachment?() == true {
            return   // staged as an attachment; do NOT insert anything into the text
        }
        super.paste(sender)   // plain text → normal paste
    }

    override var keyCommands: [UIKeyCommand]? {
        // Hardware Return = send; Shift+Return has no command so it falls through to a newline.
        // Key commands fire only for a physical keyboard, so the soft keyboard keeps its default
        // multiline Return.
        let ret = UIKeyCommand(input: "\r", modifierFlags: [], action: #selector(hardwareReturn))
        ret.wantsPriorityOverSystemBehavior = true
        return [ret]
    }
    @objc private func hardwareReturn() { onHardwareReturn?() }
}
