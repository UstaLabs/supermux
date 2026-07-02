import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

// MARK: - Platform typealiases
// One vocabulary for both UIKit and AppKit. Files that today `import UIKit`
// switch to these names and stop importing UIKit directly.

#if canImport(UIKit)
typealias PlatformView = UIView
typealias PlatformColor = UIColor
typealias PlatformFont = UIFont
typealias PlatformImage = UIImage
#else
typealias PlatformView = NSView
typealias PlatformColor = NSColor
typealias PlatformFont = NSFont
typealias PlatformImage = NSImage
#endif

// MARK: - PlatformViewRepresentable
// Write `makePlatformView`/`updatePlatformView` ONCE; the conditional extension
// maps it onto UIViewRepresentable or NSViewRepresentable.

#if canImport(UIKit)
protocol PlatformViewRepresentable: UIViewRepresentable {
    associatedtype PlatformViewType: UIView
    func makePlatformView(context: Context) -> PlatformViewType
    func updatePlatformView(_ view: PlatformViewType, context: Context)
}
extension PlatformViewRepresentable where UIViewType == PlatformViewType {
    func makeUIView(context: Context) -> PlatformViewType { makePlatformView(context: context) }
    func updateUIView(_ view: PlatformViewType, context: Context) { updatePlatformView(view, context: context) }
}
#else
protocol PlatformViewRepresentable: NSViewRepresentable {
    associatedtype PlatformViewType: NSView
    func makePlatformView(context: Context) -> PlatformViewType
    func updatePlatformView(_ view: PlatformViewType, context: Context)
}
extension PlatformViewRepresentable where NSViewType == PlatformViewType {
    func makeNSView(context: Context) -> PlatformViewType { makePlatformView(context: context) }
    func updateNSView(_ view: PlatformViewType, context: Context) { updatePlatformView(view, context: context) }
}
#endif

// MARK: - Semantic colors (UIColor names ↔ NSColor names)

extension PlatformColor {
    static var smLabel: PlatformColor {
        #if canImport(UIKit)
        .label
        #else
        .labelColor
        #endif
    }
    static var smSecondaryLabel: PlatformColor {
        #if canImport(UIKit)
        .secondaryLabel
        #else
        .secondaryLabelColor
        #endif
    }
    /// iOS `.tertiarySystemBackground` — closest AppKit analog for a raised card fill.
    static var smTertiaryBackground: PlatformColor {
        #if canImport(UIKit)
        .tertiarySystemBackground
        #else
        .underPageBackgroundColor
        #endif
    }
    /// iOS `.tertiarySystemFill` — subtle fill for pills/chips.
    static var smTertiaryFill: PlatformColor {
        #if canImport(UIKit)
        .tertiarySystemFill
        #else
        .quaternaryLabelColor.withAlphaComponent(0.18)
        #endif
    }
}

// MARK: - Images

extension PlatformImage {
    static func sm(cgImage: CGImage) -> PlatformImage {
        #if canImport(UIKit)
        UIImage(cgImage: cgImage)
        #else
        NSImage(cgImage: cgImage, size: .zero)
        #endif
    }
}

extension Image {
    init(platform image: PlatformImage) {
        #if canImport(UIKit)
        self.init(uiImage: image)
        #else
        self.init(nsImage: image)
        #endif
    }
}

// MARK: - Screen metrics

enum SMScreen {
    /// Main-screen width in points (used only for layout caps in markdown tables).
    static var mainWidth: CGFloat {
        #if canImport(UIKit)
        UIScreen.main.bounds.width
        #else
        NSScreen.main?.frame.width ?? 1280
        #endif
    }
}

// MARK: - Pasteboard

enum SMPasteboard {
    static var string: String? {
        #if canImport(UIKit)
        UIPasteboard.general.string
        #else
        NSPasteboard.general.string(forType: .string)
        #endif
    }
    static func set(_ s: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = s
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
        #endif
    }
    static var image: PlatformImage? {
        #if canImport(UIKit)
        UIPasteboard.general.image
        #else
        NSImage(pasteboard: NSPasteboard.general)
        #endif
    }
}

// MARK: - Haptics (no-op on the Mac)

enum Haptics {
    static func selection() {
        #if canImport(UIKit)
        UISelectionFeedbackGenerator().selectionChanged()
        #endif
    }
}

// MARK: - Keyboard kinds (UIKeyboardType is UIKit-only)

enum SMKeyboardKind {
    case asciiCapable, url, emailAddress, numberPad, plain
    #if canImport(UIKit)
    var uiKind: UIKeyboardType {
        switch self {
        case .asciiCapable: return .asciiCapable
        case .url: return .URL
        case .emailAddress: return .emailAddress
        case .numberPad: return .numberPad
        case .plain: return .default
        }
    }
    #endif
}

// MARK: - Presentation detents (iOS-only concept)

enum SMDetent {
    case medium, large
    case fraction(CGFloat)
    case height(CGFloat)
    #if os(iOS)
    var native: PresentationDetent {
        switch self {
        case .medium: return .medium
        case .large: return .large
        case .fraction(let f): return .fraction(f)
        case .height(let h): return .height(h)
        }
    }
    #endif
}

// MARK: - Toolbar placement

extension ToolbarItemPlacement {
    static var smTopTrailing: ToolbarItemPlacement {
        #if os(iOS)
        .topBarTrailing
        #else
        .automatic
        #endif
    }
    static var smTopLeading: ToolbarItemPlacement {
        #if os(iOS)
        .topBarLeading
        #else
        .navigation
        #endif
    }
}

// MARK: - View modifier shims (iOS-only modifiers become no-ops or mac analogs)

extension View {
    @ViewBuilder func smInlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
    @ViewBuilder func smLargeNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.large)
        #else
        self
        #endif
    }
    @ViewBuilder func smHideNavigationBar() -> some View {
        #if os(iOS)
        toolbar(.hidden, for: .navigationBar)
        #else
        self
        #endif
    }
    @ViewBuilder func smNoAutocapitalization() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never)
        #else
        self
        #endif
    }
    @ViewBuilder func smKeyboard(_ kind: SMKeyboardKind) -> some View {
        #if os(iOS)
        keyboardType(kind.uiKind)
        #else
        self
        #endif
    }
    @ViewBuilder func smPresentationDetents(_ detents: [SMDetent]) -> some View {
        #if os(iOS)
        presentationDetents(Set(detents.map(\.native)))
        #else
        self
        #endif
    }
    @ViewBuilder func smHoverHighlight() -> some View {
        #if os(iOS)
        hoverEffect(.highlight)
        #else
        self
        #endif
    }
    /// iOS full-screen cover; a regular sheet on the Mac (macOS has no full-screen cover).
    @ViewBuilder func smFullScreenCover<C: View>(
        isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> C
    ) -> some View {
        #if os(iOS)
        fullScreenCover(isPresented: isPresented, onDismiss: onDismiss, content: content)
        #else
        sheet(isPresented: isPresented, onDismiss: onDismiss, content: content)
        #endif
    }
    @ViewBuilder func smFullScreenCover<I: Identifiable, C: View>(
        item: Binding<I?>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (I) -> C
    ) -> some View {
        #if os(iOS)
        fullScreenCover(item: item, onDismiss: onDismiss, content: content)
        #else
        sheet(item: item, onDismiss: onDismiss, content: content)
        #endif
    }
}
