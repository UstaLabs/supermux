import SwiftUI
import Observation

/// Layout state for the regular-width iPad workspace: split ratios (percent of the
/// available axis), which work panes are open, and sidebar width/collapse. Persisted
/// globally (matching the web PWA's single layout store) and clamped to the PWA's
/// bounds. Bounds mirror `src/web-app/src/stores/layout.ts`.
@Observable final class WorkspaceLayoutModel {
    enum B {
        static let chat = (def: 25.0, min: 20.0, max: 80.0)
        static let editorTerm = (def: 75.0, min: 20.0, max: 80.0)
        static let workDisplay = (def: 55.0, min: 25.0, max: 75.0)
        static let sidebar = (def: 320.0, min: 220.0, max: 560.0)
        static let rail = 56.0
    }

    @ObservationIgnored private let store: UserDefaults

    var chatPct: Double {
        didSet {
            let c = Self.clamp(chatPct, B.chat.min, B.chat.max)
            if chatPct != c { chatPct = c }                 // self-assign in own didSet does NOT recurse past the guard
            store.set(chatPct, forKey: "ipad.split.chatPct")
        }
    }
    var editorTermPct: Double {
        didSet {
            let c = Self.clamp(editorTermPct, B.editorTerm.min, B.editorTerm.max)
            if editorTermPct != c { editorTermPct = c }
            store.set(editorTermPct, forKey: "ipad.split.editorTermPct")
        }
    }
    var workDisplayPct: Double {
        didSet {
            let c = Self.clamp(workDisplayPct, B.workDisplay.min, B.workDisplay.max)
            if workDisplayPct != c { workDisplayPct = c }
            store.set(workDisplayPct, forKey: "ipad.split.workDisplayPct")
        }
    }
    var sidebarWidth: Double {
        didSet {
            let c = Self.clamp(sidebarWidth, B.sidebar.min, B.sidebar.max)
            if sidebarWidth != c { sidebarWidth = c }
            store.set(sidebarWidth, forKey: "ipad.sidebar.width")
        }
    }
    var sidebarCollapsed: Bool {
        didSet { store.set(sidebarCollapsed, forKey: "ipad.sidebar.collapsed") }
    }

    /// Which panes are open (chat is always on). UI state — not persisted here.
    var chatOpen = true
    var editorOpen = false
    var terminalOpen = false
    var displayOpen = false

    init(store: UserDefaults = .standard) {
        self.store = store
        chatPct = Self.load(store, "ipad.split.chatPct", B.chat.def, B.chat.min, B.chat.max)
        editorTermPct = Self.load(store, "ipad.split.editorTermPct", B.editorTerm.def, B.editorTerm.min, B.editorTerm.max)
        workDisplayPct = Self.load(store, "ipad.split.workDisplayPct", B.workDisplay.def, B.workDisplay.min, B.workDisplay.max)
        sidebarWidth = Self.load(store, "ipad.sidebar.width", B.sidebar.def, B.sidebar.min, B.sidebar.max)
        // object(forKey:) as? Bool distinguishes "key absent → default false" from a stored false (which bool(forKey:) couldn't)
        sidebarCollapsed = store.object(forKey: "ipad.sidebar.collapsed") as? Bool ?? false
    }

    static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { Swift.min(hi, Swift.max(lo, v)) }
    private static func load(_ s: UserDefaults, _ k: String, _ d: Double, _ lo: Double, _ hi: Double) -> Double {
        s.object(forKey: k) == nil ? d : clamp(s.double(forKey: k), lo, hi)
    }
}
