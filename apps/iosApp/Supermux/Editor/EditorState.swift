import Foundation
import Shared

/// Source of truth for the editor pane — open tabs, their content/dirty state, and
/// the file-tree UI state. Mirrors the Android `EditorState`, but with UNLIMITED tabs
/// (no cap, no eviction — overrides the PWA's MAX_TABS=10). The WKWebView is a pushed
/// renderer; this type owns the data. `fsRead`/`fsWrite` are injected so it stays
/// testable and decoupled from `BrokerSession`.
@MainActor
@Observable
final class EditorState {
    struct Tab: Identifiable {
        let path: String
        var content: String
        var savedContent: String
        var scrollTop: Int
        var id: String { path }
        var isDirty: Bool { content != savedContent }
    }

    private(set) var tabs: [Tab] = []
    var activeTabPath: String?
    private(set) var loadingPath: String?
    private(set) var loadError: String?
    private(set) var saving = false

    // Tree UI state — persists across pane / session switches.
    var treeRoot: [TreeNode] = []
    var treeRootLoaded = false
    var expandedPaths: Set<String> = []
    var treeLoadingPaths: Set<String> = []
    var treeVisible = true
    var searchQuery = ""

    // Diff / code-review state — per-session, survives switches like the tree.
    var showDiff = false
    private(set) var diffRepos: [RepoDiff] = []
    private(set) var diffComments: [ReviewComment] = []
    private(set) var diffLoading = false

    let sessionId: String
    private let fsRead: (String) async throws -> String
    private let fsWrite: (String, String) async -> Bool
    private let fsDiff: (() async -> FsDiffResult?)?

    var activeTab: Tab? { tabs.first { $0.path == activeTabPath } }

    init(sessionId: String,
         fsRead: @escaping (String) async throws -> String,
         fsWrite: @escaping (String, String) async -> Bool,
         fsDiff: (() async -> FsDiffResult?)? = nil) {
        self.sessionId = sessionId
        self.fsRead = fsRead
        self.fsWrite = fsWrite
        self.fsDiff = fsDiff
    }

    func loadDiff() async {
        guard let fsDiff else { showDiff = true; return }
        diffLoading = true
        if let res = await fsDiff() {
            diffRepos = res.repos
            diffComments = res.comments
        }
        diffLoading = false
        showDiff = true
    }

    func reloadDiff() async {
        guard let fsDiff, let res = await fsDiff() else { return }
        diffRepos = res.repos
        diffComments = res.comments
    }

    func openFile(_ path: String) async {
        if tabs.contains(where: { $0.path == path }) {
            activeTabPath = path
            loadError = nil
            return
        }
        loadingPath = path
        loadError = nil
        do {
            let content = try await fsRead(path)
            tabs.append(Tab(path: path, content: content, savedContent: content, scrollTop: 0))
            activeTabPath = path
        } catch {
            loadError = error.localizedDescription
        }
        loadingPath = nil
    }

    func closeTab(_ path: String) {
        guard let idx = tabs.firstIndex(where: { $0.path == path }) else { return }
        tabs.remove(at: idx)
        if activeTabPath == path {
            activeTabPath = tabs.isEmpty ? nil : tabs[min(idx, tabs.count - 1)].path
        }
        if activeTabPath == nil { loadError = nil }
    }

    func updateContent(_ path: String, _ s: String) {
        guard let idx = tabs.firstIndex(where: { $0.path == path }) else { return }
        tabs[idx].content = s
    }

    func saveActive() async {
        guard !saving, let path = activeTabPath,
              let idx = tabs.firstIndex(where: { $0.path == path }) else { return }
        saving = true
        let content = tabs[idx].content
        if await fsWrite(path, content),
           let i = tabs.firstIndex(where: { $0.path == path }) {
            tabs[i].savedContent = content
        }
        saving = false
    }

    func reload(_ path: String) async {
        guard tabs.contains(where: { $0.path == path }) else { return }
        loadingPath = path
        do {
            let content = try await fsRead(path)
            if let idx = tabs.firstIndex(where: { $0.path == path }) {
                tabs[idx].content = content
                tabs[idx].savedContent = content
            }
        } catch {
            loadError = error.localizedDescription
        }
        loadingPath = nil
    }

    func setScroll(_ path: String, _ top: Int) {
        guard let idx = tabs.firstIndex(where: { $0.path == path }) else { return }
        tabs[idx].scrollTop = top
    }
}

struct TreeNode: Identifiable {
    let entry: FsEntry
    let path: String
    var children: [TreeNode]?
    var loaded: Bool
    var id: String { path }
    var isDir: Bool { children != nil }
}
