import SwiftUI
import Observation

/// Which work panes are open for a single session. Mirrors the per-session pane flags in
/// the PWA's `ChatPanelState` (`src/web-app/src/stores/layout.ts`): chat defaults on, the
/// work panes default off. Persisted per session (see `WorkspaceLayoutModel.panes`).
struct PaneVisibility: Codable, Equatable {
    var chatOpen = true
    var editorOpen = false
    var terminalOpen = false
    var displayOpen = false
}

/// Layout state for the regular-width iPad workspace. Split ratios (percent of the available
/// axis) and sidebar width/collapse are GLOBAL — matching the web PWA, which keeps
/// `chatSplitPct/editorTermSplitPct/workDisplaySplitPct/sidebarWidth/sidebarCollapsed` on its
/// single layout store. Which panes are open is PER SESSION (PWA `panels[sessionId]`), so
/// switching sessions no longer carries one session's open/closed layout to another. All of it
/// is persisted to UserDefaults and clamped to the PWA's bounds (`src/web-app/src/stores/layout.ts`).
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

    /// UserDefaults key for the per-session pane-visibility map (JSON `[sessionId: PaneVisibility]`).
    @ObservationIgnored private static let panesKey = "ipad.panes.v1"

    /// Per-session open/closed pane state (PWA `panels[sessionId]`). Assigned as a whole new
    /// dict on mutation so `@Observable` tracks reads of `panes(for:)` in views; persisted to
    /// UserDefaults on every write. Defaults to empty — an unseen session reads a fresh default.
    private var panes: [String: PaneVisibility]

    /// The open-pane state for `sessionId`, or a fresh default (chat on, work panes off) if the
    /// session has never had its panes touched. Read-only — does NOT insert; use `setPanes` to store.
    func panes(for sessionId: String) -> PaneVisibility { panes[sessionId] ?? PaneVisibility() }

    /// Stores `sessionId`'s pane state and persists the whole map. Assigning a brand-new dict
    /// (rather than mutating in place) is what lets `@Observable` see the change and re-render.
    func setPanes(_ v: PaneVisibility, for sessionId: String) {
        var next = panes
        next[sessionId] = v
        panes = next
        persistPanes()
    }

    private func persistPanes() {
        if let data = try? JSONEncoder().encode(panes) { store.set(data, forKey: Self.panesKey) }
    }

    init(store: UserDefaults = .standard) {
        self.store = store
        chatPct = Self.load(store, "ipad.split.chatPct", B.chat.def, B.chat.min, B.chat.max)
        editorTermPct = Self.load(store, "ipad.split.editorTermPct", B.editorTerm.def, B.editorTerm.min, B.editorTerm.max)
        workDisplayPct = Self.load(store, "ipad.split.workDisplayPct", B.workDisplay.def, B.workDisplay.min, B.workDisplay.max)
        sidebarWidth = Self.load(store, "ipad.sidebar.width", B.sidebar.def, B.sidebar.min, B.sidebar.max)
        // object(forKey:) as? Bool distinguishes "key absent → default false" from a stored false (which bool(forKey:) couldn't)
        sidebarCollapsed = store.object(forKey: "ipad.sidebar.collapsed") as? Bool ?? false
        // Decode the per-session pane map; absent/corrupt → empty (every session reads its default).
        if let data = store.data(forKey: Self.panesKey),
           let decoded = try? JSONDecoder().decode([String: PaneVisibility].self, from: data) {
            panes = decoded
        } else {
            panes = [:]
        }
    }

    static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { Swift.min(hi, Swift.max(lo, v)) }
    private static func load(_ s: UserDefaults, _ k: String, _ d: Double, _ lo: Double, _ hi: Double) -> Double {
        s.object(forKey: k) == nil ? d : clamp(s.double(forKey: k), lo, hi)
    }
}
