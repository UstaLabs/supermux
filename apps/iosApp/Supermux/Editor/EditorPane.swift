import SwiftUI
import WebKit
import Shared

/// The native code-editor pane (PWA-parity, Phase 1): a lazy file tree + unlimited
/// tabs over a CodeMirror `WKWebView` surface, with filename search and editor
/// settings. State lives in a per-session `EditorState` cached on the broker, so
/// open tabs / tree expansion survive pane AND session switches.
struct EditorPane: View {
    let broker: BrokerSession
    let session: SessionInfo

    @State private var settings = EditorSettingsStore()
    @State private var showSettings = false
    @State private var webView: WKWebView?
    @State private var keyboardHeight: CGFloat = 0
    @State private var previewMode = false
    @Environment(\.horizontalSizeClass) private var hSize

    /// Cached per-session — same instance every lookup, so returning to a session
    /// restores its open tabs/tree (the dictionary lives on the broker, app-lifetime).
    private var state: EditorState { broker.editorState(for: session.id) }
    /// iPad / wide → tree is a side column; iPhone → full-screen overlay.
    private var isRegular: Bool { hSize == .regular }

    // ── Markdown preview (parity with the PWA's Eye/Pencil toggle) ──────────────
    private func isMarkdown(_ path: String) -> Bool {
        let p = path.lowercased()
        return p.hasSuffix(".md") || p.hasSuffix(".markdown")
    }
    private var activeIsMarkdown: Bool {
        if let p = state.activeTab?.path { return isMarkdown(p) }
        return false
    }
    /// Preview only takes effect for markdown tabs; a non-md file falls back to the editor.
    private var showPreview: Bool { previewMode && activeIsMarkdown && !state.showDiff }
    private var showPreviewToggle: Bool { activeIsMarkdown && !state.showDiff }

    var body: some View {
        VStack(spacing: 0) {
            if state.showDiff {
                diffPane
            } else {
                header
                if !state.tabs.isEmpty {
                    EditorTabsView(state: state,
                                   onSelect: { state.activeTabPath = $0 },
                                   onClose: { state.closeTab($0) })
                    Divider()
                }
                bodyContent
            }
        }
        .background(Color(.systemBackground))
        .overlay(alignment: .bottom) { keyboardDismissOverlay }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            if let f = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect { keyboardHeight = f.height }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardHeight = 0 }
        .sheet(isPresented: $showSettings) { EditorSettingsView(settings: settings) }
        .task(id: session.id) {
            // Headless test hook: open a file by workdir-relative path on launch.
            guard let f = ProcessInfo.processInfo.environment["SM_OPEN_FILE"], !f.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            openFile(f)
            if ProcessInfo.processInfo.environment["SM_PREVIEW"] == "1" {
                try? await Task.sleep(nanoseconds: 900_000_000)
                previewMode = true
            }
        }
        .task(id: session.id) {
            // Headless test hook: open the diff/review view on launch.
            guard ProcessInfo.processInfo.environment["SM_OPEN_DIFF"] == "1" else { return }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            await state.loadDiff()
        }
    }

    // Header: files toggle · filename search · settings gear.
    // (The diff and markdown-preview controls arrive in Phases 2-3.)
    private var header: some View {
        HStack(spacing: 8) {
            Button {
                withAnimation(.snappy(duration: 0.2)) { state.treeVisible.toggle() }
            } label: {
                Image(systemName: "sidebar.left")
                    .font(.body)
                    .foregroundStyle(state.treeVisible ? Theme.teal : .secondary)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(state.treeVisible ? "Hide files" : "Show files")

            EditorSearchField(search: { await broker.fsSearch(session.id, $0) },
                              onOpen: { openFile($0) })

            if showPreviewToggle {
                Button { previewMode.toggle() } label: {
                    Image(systemName: previewMode ? "pencil" : "eye")
                        .font(.body)
                        .foregroundStyle(previewMode ? Theme.teal : .secondary)
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel(previewMode ? "Edit" : "Preview")
            }

            Button {
                Task { await state.loadDiff() }
            } label: {
                Group {
                    if state.diffLoading {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "plus.forwardslash.minus").font(.body).foregroundStyle(.secondary)
                    }
                }
                .frame(width: 36, height: 36)
            }
            .accessibilityLabel("View changes")

            Button { showSettings = true } label: {
                Image(systemName: "textformat.size")
                    .font(.body).foregroundStyle(.secondary)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel("Editor settings")
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
    }

    @ViewBuilder private var bodyContent: some View {
        if isRegular {
            HStack(spacing: 0) {
                if state.treeVisible {
                    fileTree
                        .frame(width: 260)
                        .background(Color(.secondarySystemBackground))
                    Divider()
                }
                editorOrEmpty.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            ZStack {
                editorOrEmpty
                if state.treeVisible {
                    fileTree
                        .background(Color(.systemBackground))
                        .transition(.move(edge: .leading).combined(with: .opacity))
                }
            }
        }
    }

    private var fileTree: some View {
        FileTreeView(state: state,
                     loadDir: { await broker.fsList(session.id, $0) },
                     onOpenFile: { openFile($0) })
    }

    private var diffPane: some View {
        DiffView(
            repos: state.diffRepos,
            comments: state.diffComments,
            onAddComment: { repo, path, anchorLine, anchorContext, hunkHeader, body in
                await broker.reviewAddComment(session.id,
                    AddCommentBody(repo: repo, path: path, side: "RIGHT",
                                   anchorLine: Int32(anchorLine), anchorContext: anchorContext,
                                   body: body, diffHunkHeader: hunkHeader))
            },
            onResolve: { commentId in await broker.reviewResolve(session.id, commentId) },
            onSubmit: { _ = await broker.reviewSubmit(session.id) },
            onReload: { Task { await state.reloadDiff() } },
            onClose: { state.showDiff = false }
        )
    }

    @ViewBuilder private var editorOrEmpty: some View {
        if let tab = state.activeTab {
            if showPreview {
                ScrollView {
                    MarkdownView(text: tab.content)
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                EditorWebView(content: tab.content, path: tab.path,
                              lineWrap: settings.lineWrap, fontSize: settings.fontSize,
                              onChange: { state.updateContent(tab.path, $0) },
                              onSave: { Task { await state.saveActive() } },
                              onMakeView: { webView = $0 })
                    .background(Color(red: 40/255, green: 44/255, blue: 52/255)) // #282c34, matches cm6 one-dark
                    .ignoresSafeArea(.container, edges: .bottom)
            }
        } else {
            ContentUnavailableView {
                Label("No file open", systemImage: "doc.text")
            } description: {
                Text("Open a file from the tree or search.")
            }
        }
    }

    private func openFile(_ path: String) {
        Task {
            await state.openFile(path)
            if !isRegular { withAnimation(.snappy(duration: 0.2)) { state.treeVisible = false } }
        }
    }

    // The WKWebView owns a UIKit keyboard SwiftUI's tap/scroll dismissal can't reach,
    // so we hold the view and `endEditing` it via a floating glass button just above the
    // keyboard (mirrors TerminalPane). SM_KBD=1 fakes a height for headless screenshots.
    private var effectiveKbHeight: CGFloat {
        keyboardHeight > 0 ? keyboardHeight : (ProcessInfo.processInfo.environment["SM_KBD"] == "1" ? 320 : 0)
    }
    @ViewBuilder private var keyboardDismissOverlay: some View {
        if effectiveKbHeight > 0, state.activeTab != nil {
            GeometryReader { geo in
                Button { webView?.endEditing(true) } label: {
                    Image(systemName: "keyboard.chevron.compact.down")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.teal)
                        .frame(width: 44, height: 44)
                        .glassEffect(.regular, in: Circle())
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(.trailing, 16)
                .padding(.bottom, max(0, effectiveKbHeight - geo.safeAreaInsets.bottom) + 8)
            }
            .ignoresSafeArea(.keyboard)
        }
    }
}
