// apps/iosApp/Supermux/Chat/Composer/ComposerInput.swift
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import UniformTypeIdentifiers

#if canImport(UIKit)
/// A multiline composer text field backed by UIKit so it can do what SwiftUI's `TextField` can't on
/// iOS: intercept a PASTE of an image/PDF (long-press "Paste" or ⌘V) and stage it as an attachment,
/// exactly how WhatsApp/Messages accept a pasted photo. Plain-text paste falls through to normal
/// behavior. Mirrors the previous `TextField`: bound draft, placeholder, auto-grow up to `maxLines`,
/// focus mirrored via a plain `Bool` binding, hardware Return = send / Shift+Return = newline.
struct ComposerInput: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String
    var maxLines: Int
    @Binding var isFocused: Bool
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

        // Focus bridge — deferred so we never mutate the focus binding *during* a SwiftUI update
        // (which becomeFirstResponder → textViewDidBeginEditing would do synchronously).
        let want = isFocused
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
            if !parent.isFocused { parent.isFocused = true }
        }
        func textViewDidEndEditing(_ textView: UITextView) {
            if parent.isFocused { parent.isFocused = false }
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
#else
/// Mac twin of the composer input, mirroring the iOS wrapper's interface 1:1: an `NSTextView`
/// in a borderless scroll view with a bound draft, placeholder, auto-grow up to `maxLines`
/// (then it scrolls), focus mirrored via the plain `Bool` binding, Return = send /
/// Shift+Return = newline, and paste interception so an image/PDF clipboard routes to
/// `onPasteAttachment` instead of inserting into the text.
struct ComposerInput: NSViewRepresentable {
    @Binding var text: String
    var placeholder: String
    var maxLines: Int
    @Binding var isFocused: Bool
    var canSubmit: Bool
    var onSubmit: () -> Void
    /// Invoked on a non-text paste; returns true if it staged something (so the view skips the
    /// default paste and inserts nothing into the text).
    var onPasteAttachment: () -> Bool

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let tv = PasteTextView()
        tv.delegate = context.coordinator
        tv.onPasteAttachment = { context.coordinator.parent.onPasteAttachment() }
        tv.onFocusChange = { context.coordinator.focusChanged($0) }
        tv.font = .preferredFont(forTextStyle: .body, options: [:])
        tv.textColor = .smLabel
        tv.drawsBackground = false
        tv.isRichText = false
        tv.allowsUndo = true
        tv.textContainerInset = .zero
        tv.textContainer?.lineFragmentPadding = 0
        tv.textContainer?.widthTracksTextView = true
        tv.isVerticallyResizable = true
        tv.isHorizontallyResizable = false
        tv.autoresizingMask = [.width]
        tv.minSize = NSSize(width: 0, height: 0)
        tv.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)

        let scroll = NSScrollView()
        scroll.documentView = tv
        scroll.borderType = .noBorder
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = false
        scroll.hasHorizontalScroller = false
        scroll.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.parent = self
        guard let tv = scroll.documentView as? PasteTextView else { return }
        if tv.string != text {
            tv.string = text
            tv.invalidateIntrinsicContentSize()
        }
        tv.placeholderLabel.stringValue = placeholder
        tv.placeholderLabel.isHidden = !text.isEmpty
        tv.needsLayout = true

        // Focus bridge — deferred so we never mutate the focus binding *during* a SwiftUI update
        // (parity with the iOS becomeFirstResponder deferral).
        let want = isFocused
        let isFirst = tv.window?.firstResponder === tv
        if want != isFirst {
            DispatchQueue.main.async {
                guard let window = tv.window else { return }
                let isFirstNow = window.firstResponder === tv
                if want, !isFirstNow { window.makeFirstResponder(tv) }
                else if !want, isFirstNow { window.makeFirstResponder(nil) }
            }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView scroll: NSScrollView, context: Context) -> CGSize? {
        guard let tv = scroll.documentView as? PasteTextView else { return nil }
        let width = proposal.width ?? scroll.bounds.width
        guard width > 0 else { return nil }
        guard let container = tv.textContainer, let lm = tv.layoutManager else { return nil }
        container.size = NSSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        lm.ensureLayout(for: container)
        let fitH = ceil(lm.usedRect(for: container).height)
        let lineH = ceil(lm.defaultLineHeight(for: tv.font ?? .preferredFont(forTextStyle: .body, options: [:])))
        let maxH = ceil(lineH * CGFloat(maxLines))
        let minH = lineH
        scroll.hasVerticalScroller = fitH > maxH
        return CGSize(width: width, height: min(max(fitH, minH), maxH))
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: ComposerInput
        init(_ parent: ComposerInput) { self.parent = parent }

        func textDidChange(_ notification: Notification) {
            guard let tv = notification.object as? PasteTextView else { return }
            parent.text = tv.string
            tv.placeholderLabel.isHidden = !tv.string.isEmpty
            tv.invalidateIntrinsicContentSize()
        }
        /// Return = send / Shift+Return = newline — parity with the iOS hardware key command:
        /// a plain Return is consumed either way (send when sendable, swallowed otherwise).
        func textView(_ textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
            guard commandSelector == #selector(NSResponder.insertNewline(_:)) else { return false }
            if NSApp.currentEvent?.modifierFlags.contains(.shift) == true { return false }
            handleHardwareReturn()
            return true
        }
        func focusChanged(_ focused: Bool) {
            if parent.isFocused != focused { parent.isFocused = focused }
        }
        func handleHardwareReturn() {
            // Parity with ComposerEnterAction: Enter sends when there's something to send,
            // otherwise it's swallowed (the command already consumed the event → no newline).
            if parent.canSubmit { parent.onSubmit() }
        }
    }
}

/// `NSTextView` that intercepts image/PDF paste (parity with the iOS PasteTextView), reports
/// first-responder changes for the focus binding, and manages a placeholder label manually
/// (NSTextView has no native placeholder either).
final class PasteTextView: NSTextView {
    var onPasteAttachment: (() -> Bool)?
    var onFocusChange: ((Bool) -> Void)?
    /// Injectable so the paste logic is unit-testable; production reads the system clipboard.
    var pasteboard: NSPasteboard = .general

    let placeholderLabel: NSTextField = {
        let l = PassthroughLabel(labelWithString: "")
        l.textColor = .placeholderTextColor
        l.font = .preferredFont(forTextStyle: .body, options: [:])
        l.lineBreakMode = .byTruncatingTail
        l.maximumNumberOfLines = 1
        return l
    }()

    override init(frame frameRect: NSRect, textContainer container: NSTextContainer?) {
        super.init(frame: frameRect, textContainer: container)
        addSubview(placeholderLabel)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layout() {
        super.layout()
        let inset = textContainerInset
        let pad = textContainer?.lineFragmentPadding ?? 0
        let maxW = max(0, bounds.width - 2 * (inset.width + pad))
        let fit = placeholderLabel.sizeThatFits(NSSize(width: maxW, height: CGFloat.greatestFiniteMagnitude))
        placeholderLabel.frame = NSRect(x: inset.width + pad, y: inset.height,
                                        width: maxW, height: fit.height)
    }

    /// True when the clipboard holds something we stage as an attachment rather than inserting.
    private var clipboardHasAttachment: Bool {
        pasteboard.canReadObject(forClasses: [NSImage.self], options: nil)
            || pasteboard.availableType(from: [NSPasteboard.PasteboardType(UTType.pdf.identifier)]) != nil
    }

    /// Keep "Paste" (⌘V / Edit menu) enabled for an image/PDF clipboard — a plain text view
    /// would otherwise disable it (mac analog of the iOS `canPerformAction` override).
    override func validateUserInterfaceItem(_ item: NSValidatedUserInterfaceItem) -> Bool {
        if item.action == #selector(NSText.paste(_:)), clipboardHasAttachment {
            return true
        }
        return super.validateUserInterfaceItem(item)
    }

    override func paste(_ sender: Any?) {
        if clipboardHasAttachment, onPasteAttachment?() == true {
            return   // staged as an attachment; do NOT insert anything into the text
        }
        super.paste(sender)   // plain text → normal paste
    }

    // Focus mirroring (the mac analog of textViewDidBegin/EndEditing, which on AppKit only
    // fire around actual edits, not focus).
    override func becomeFirstResponder() -> Bool {
        let ok = super.becomeFirstResponder()
        if ok { onFocusChange?(true) }
        return ok
    }
    override func resignFirstResponder() -> Bool {
        let ok = super.resignFirstResponder()
        if ok { onFocusChange?(false) }
        return ok
    }
}

/// Label that never intercepts clicks, so clicking the placeholder area focuses the text view
/// underneath (parity with UILabel's default `isUserInteractionEnabled = false`).
private final class PassthroughLabel: NSTextField {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}
#endif
