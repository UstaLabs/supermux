import SwiftUI
import UniformTypeIdentifiers
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
protocol PlatformViewRepresentable: UIViewRepresentable where UIViewType == PlatformViewType {
    associatedtype PlatformViewType: UIView
    func makePlatformView(context: Context) -> PlatformViewType
    func updatePlatformView(_ view: PlatformViewType, context: Context)
}
extension PlatformViewRepresentable {
    func makeUIView(context: Context) -> PlatformViewType { makePlatformView(context: context) }
    func updateUIView(_ view: PlatformViewType, context: Context) { updatePlatformView(view, context: context) }
}
#else
protocol PlatformViewRepresentable: NSViewRepresentable where NSViewType == PlatformViewType {
    associatedtype PlatformViewType: NSView
    func makePlatformView(context: Context) -> PlatformViewType
    func updatePlatformView(_ view: PlatformViewType, context: Context)
}
extension PlatformViewRepresentable {
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
        .textBackgroundColor
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

// MARK: - Semantic SwiftUI colors

extension Color {
    /// iOS grouped-list canvas. AppKit's `.underPageBackgroundColor` is much too dark for a
    /// sidebar, so use an explicit adaptive neutral that matches `systemGroupedBackground`.
    static var smGroupedBackground: Color {
        #if canImport(UIKit)
        Color(.systemGroupedBackground)
        #else
        Color(nsColor: NSColor(name: nil) { appearance in
            let dark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            return NSColor(calibratedWhite: dark ? 0.115 : 0.955, alpha: 1)
        })
        #endif
    }
    /// iOS `.systemBackground`
    static var smBackground: Color {
        #if canImport(UIKit)
        Color(.systemBackground)
        #else
        Color(nsColor: .windowBackgroundColor)
        #endif
    }
    /// iOS `.secondarySystemBackground`
    static var smSecondaryBackground: Color {
        #if canImport(UIKit)
        Color(.secondarySystemBackground)
        #else
        Color(nsColor: .controlBackgroundColor)
        #endif
    }
    /// iOS `.separator`
    static var smSeparator: Color {
        #if canImport(UIKit)
        Color(.separator)
        #else
        Color(nsColor: .separatorColor)
        #endif
    }
    /// iOS `.tertiarySystemBackground`
    static var smTertiaryBackground: Color {
        #if canImport(UIKit)
        Color(.tertiarySystemBackground)
        #else
        Color(nsColor: .textBackgroundColor)
        #endif
    }
    /// iOS `.tertiarySystemFill`
    static var smTertiaryFill: Color {
        #if canImport(UIKit)
        Color(.tertiarySystemFill)
        #else
        Color(nsColor: .quaternaryLabelColor.withAlphaComponent(0.18))
        #endif
    }
}

// MARK: - Images

extension PlatformImage {
    static func sm(cgImage: CGImage) -> PlatformImage {
        #if canImport(UIKit)
        UIImage(cgImage: cgImage)
        #else
        NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
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
    /// True when the clipboard holds at least one image (type-presence check only).
    static var hasImages: Bool {
        #if canImport(UIKit)
        UIPasteboard.general.hasImages
        #else
        NSPasteboard.general.canReadObject(forClasses: [NSImage.self], options: nil)
        #endif
    }
    /// True when the clipboard advertises the given content type (e.g. `.pdf`).
    static func contains(_ type: UTType) -> Bool {
        #if canImport(UIKit)
        UIPasteboard.general.contains(pasteboardTypes: [type.identifier])
        #else
        NSPasteboard.general.availableType(from: [NSPasteboard.PasteboardType(type.identifier)]) != nil
        #endif
    }
}

// MARK: - Haptics (no-op on the Mac)

enum SMHaptics {
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
        .primaryAction
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

// MARK: - Search field placement

extension SearchFieldPlacement {
    /// iOS `.navigationBarDrawer(displayMode: .always)`; `.automatic` on macOS.
    static var smNavDrawerAlways: SearchFieldPlacement {
        #if os(iOS)
        .navigationBarDrawer(displayMode: .always)
        #else
        .automatic
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
    /// iOS `.insetGrouped` list style → `.inset` on macOS (the closest inset analog;
    /// `.insetGrouped` is UIKit-backed and unavailable on macOS).
    @ViewBuilder func smInsetGroupedListStyle() -> some View {
        #if os(iOS)
        listStyle(.insetGrouped)
        #else
        listStyle(.inset)
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
    /// The composer's option lists (model / reasoning) anchor to their pill as a popover on
    /// macOS — the native idiom for a small anchored chooser — and stay a detented sheet on iOS.
    @ViewBuilder func smOptionPicker<C: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> C) -> some View {
        #if os(macOS)
        popover(isPresented: isPresented) { content() }
        #else
        sheet(isPresented: isPresented) { content() }
        #endif
    }
    @ViewBuilder func smHoverHighlight() -> some View {
        #if os(iOS)
        hoverEffect(.highlight)
        #else
        modifier(MacHoverHighlight())
        #endif
    }
    /// macOS renders a default `Button` as a bordered bezel (iOS: borderless label) — the
    /// bezel is right for dialogs but wrong for the icon/pill controls these shims serve.
    /// No-op on iOS so call sites keep their exact current rendering.
    @ViewBuilder func smMacPlainButton() -> some View {
        #if os(macOS)
        buttonStyle(.plain)
        #else
        self
        #endif
    }
    /// macOS renders a default `Menu` as a bordered pull-down with an indicator chevron;
    /// our icon-labelled menus (link/⋯/+) want the iOS look: just the label. No-op on iOS.
    /// (`.button` + `.plain` is the non-deprecated borderless combo — `.borderlessButton`
    /// is soft-deprecated on macOS 14+.)
    @ViewBuilder func smMacBorderlessMenu() -> some View {
        #if os(macOS)
        menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden)
        #else
        self
        #endif
    }
    /// Cap reading-content width on the Mac's wide panes (a chat-only layout is ~1000 pt —
    /// ~180 chars/line; cap to a readable measure and center). No-op on iOS/iPad, whose
    /// pane widths are already bounded.
    @ViewBuilder func smContentWidthCap(_ max: CGFloat = 860) -> some View {
        #if os(macOS)
        frame(maxWidth: max).frame(maxWidth: .infinity, alignment: .center)
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

// MARK: - JPEG encoding (UIImage.jpegData has no NSImage counterpart)

extension PlatformImage {
    func smJpegData(quality: CGFloat) -> Data? {
        #if canImport(UIKit)
        jpegData(compressionQuality: quality)
        #else
        guard let tiff = tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .jpeg, properties: [.compressionFactor: quality])
        #endif
    }
}

// MARK: - Mac hover affordance

#if os(macOS)
/// The Mac analog of iOS `.hoverEffect(.highlight)`: a subtle background wash while the
/// pointer is over the control. Used by `.smHoverHighlight()` at the `.plain`-style sites
/// (pane toggles, dividers, header menus) that otherwise have no pointer feedback on macOS.
private struct MacHoverHighlight: ViewModifier {
    @State private var hovering = false
    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color.primary.opacity(hovering ? 0.08 : 0))
            )
            .animation(.easeOut(duration: 0.12), value: hovering)
            .onHover { hovering = $0 }
    }
}
#endif

// MARK: - Notifications

extension Notification.Name {
    /// Posted by the macOS menu bar's File ▸ New Session (⌘N); observed by `RootView` to
    /// route to the new-session launcher. Menu commands live in the `App` scene, not the
    /// view tree, so they reach `RootView` via NotificationCenter rather than a binding.
    static let smNewSession = Notification.Name("sm.newSession")

    /// Posted by macOS File ▸ Pair New Device…. Unlike the session-header overflow,
    /// the File menu remains available when the host has no sessions yet.
    static let smPairNewDevice = Notification.Name("sm.pairNewDevice")
}
