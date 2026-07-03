import Observation
import Foundation

/// Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
/// Mirrors the web launcher's `cmux:launcher-prefs` localStorage shape (SessionLauncherView.vue).
struct LauncherPrefs: Codable, Equatable {
    var agent: String = "claude"
    var models: [String: String] = [:]
}

/// In-progress New Session launcher draft — cleared once a session is actually created.
/// `workdir` is nil when nothing was explicitly restored (so NewSessionView's own
/// `projects.first` fallback still applies). Mirrors the web launcher's `cmux:launcher-draft`.
struct LauncherDraft: Codable, Equatable {
    var workdir: String?
    var useWorktree: Bool = true
    var baseBranch: String = ""
    var text: String = ""
}

/// Persists New Session launcher state to UserDefaults so it survives navigating away and a full
/// app relaunch. Two lifecycles: `prefs` persists forever (pre-fills every future launch);
/// `draft` persists only until a session is created, then `clearDraft()`. Injectable `store` for
/// test isolation — same shape as `WorkspaceLayoutModel` (Shell/WorkspaceLayoutModel.swift).
@Observable final class LauncherStateStore {
    @ObservationIgnored private let store: UserDefaults
    private static let prefsKey = "cmux:launcher-prefs"
    private static let draftKey = "cmux:launcher-draft"

    var prefs: LauncherPrefs {
        didSet { if let data = try? JSONEncoder().encode(prefs) { store.set(data, forKey: Self.prefsKey) } }
    }
    var draft: LauncherDraft {
        didSet { if let data = try? JSONEncoder().encode(draft) { store.set(data, forKey: Self.draftKey) } }
    }

    init(store: UserDefaults = .standard) {
        self.store = store
        if let data = store.data(forKey: Self.prefsKey),
           let decoded = try? JSONDecoder().decode(LauncherPrefs.self, from: data) {
            prefs = decoded
        } else {
            prefs = LauncherPrefs()
        }
        if let data = store.data(forKey: Self.draftKey),
           let decoded = try? JSONDecoder().decode(LauncherDraft.self, from: data) {
            draft = decoded
        } else {
            draft = LauncherDraft()
        }
    }

    /// Clears the in-progress draft after a session is created. Leaves `prefs` untouched — a
    /// separate, forever-sticky lifecycle.
    func clearDraft() { draft = LauncherDraft() }
}
