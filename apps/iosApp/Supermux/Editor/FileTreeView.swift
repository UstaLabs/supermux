import SwiftUI
import Shared
import UIKit

/// Lazy file tree for the editor pane. Mirrors the Android `FileTree` / PWA `FileTree.vue`:
/// the root loads from `loadDir(".")`, directories expand on tap (loaded once, tracked in
/// `state.treeLoadingPaths`), gitignored entries dim to 50%, rows indent by depth. All tree
/// state lives in `EditorState` so it survives pane / session switches (no local `@State`).
struct FileTreeView: View {
    let state: EditorState
    /// Injected directory loader — `EditorPane` passes `broker.fsList(session.id, ·)`.
    let loadDir: (String) async -> [FsEntry]
    let onOpenFile: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(visibleRows, id: \.node.path) { row in
                    TreeRow(node: row.node, depth: row.depth,
                            expanded: state.expandedPaths.contains(row.node.path),
                            loading: state.treeLoadingPaths.contains(row.node.path),
                            onTap: { onTap(row.node) })
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task {
            // Load the root once. Re-opening the pane keeps the already-loaded tree.
            guard !state.treeRootLoaded else { return }
            state.treeRoot = await buildNodes(parent: ".")
            state.treeRootLoaded = true
        }
    }

    /// The currently-visible rows, flattened depth-first (only expanded dirs descend).
    /// A flat list avoids a self-referential recursive `some View`, and lets the
    /// LazyVStack render rows lazily for big trees.
    private var visibleRows: [(node: TreeNode, depth: Int)] {
        var out: [(node: TreeNode, depth: Int)] = []
        func walk(_ nodes: [TreeNode], _ depth: Int) {
            for n in nodes {
                out.append((node: n, depth: depth))
                if n.isDir, state.expandedPaths.contains(n.path), let kids = n.children {
                    walk(kids, depth + 1)
                }
            }
        }
        walk(state.treeRoot, 0)
        return out
    }

    // MARK: tree building / lazy expansion

    /// Sort dirs-first, then case-insensitive by name (parity with Android `sortedForTree`).
    private func buildNodes(parent: String) async -> [TreeNode] {
        let entries = await loadDir(parent)
        let sorted = entries.sorted { a, b in
            let ad = a.type == "dir", bd = b.type == "dir"
            if ad != bd { return ad }
            return a.name.lowercased() < b.name.lowercased()
        }
        return sorted.map { entry in
            TreeNode(entry: entry,
                     path: childPath(parent, entry.name),
                     children: entry.type == "dir" ? [] : nil,
                     loaded: false)
        }
    }

    private func childPath(_ parent: String, _ name: String) -> String {
        parent == "." ? name : "\(parent)/\(name)"
    }

    private func toggleDir(_ node: TreeNode) {
        if state.expandedPaths.contains(node.path) {
            state.expandedPaths.remove(node.path)
            return
        }
        if !node.loaded {
            state.treeLoadingPaths.insert(node.path)
            Task {
                let children = await buildNodes(parent: node.path)
                setChildren(children, loadedFor: node.path, in: &state.treeRoot)
                state.treeLoadingPaths.remove(node.path)
            }
        }
        state.expandedPaths.insert(node.path)
    }

    /// Walk the tree by path and graft freshly-loaded children onto the matching node.
    /// (TreeNode is a value type, so we mutate the stored array in place.)
    private func setChildren(_ children: [TreeNode], loadedFor path: String, in nodes: inout [TreeNode]) {
        for i in nodes.indices {
            if nodes[i].path == path {
                nodes[i].children = children
                nodes[i].loaded = true
                return
            }
            if nodes[i].children != nil {
                setChildren(children, loadedFor: path, in: &nodes[i].children!)
            }
        }
    }

    private func onTap(_ node: TreeNode) {
        UISelectionFeedbackGenerator().selectionChanged()
        if node.isDir { toggleDir(node) } else { onOpenFile(node.path) }
    }

    // MARK: row

    private struct TreeRow: View {
        let node: TreeNode
        let depth: Int
        let expanded: Bool
        let loading: Bool
        let onTap: () -> Void

        private var isDir: Bool { node.isDir }
        private var alpha: Double { node.entry.ignored ? 0.5 : 1.0 }

        var body: some View {
            Button(action: onTap) {
                HStack(spacing: 6) {
                    // chevron (dir) / spinner (loading) / blank (file) — fixed slot keeps names aligned
                    ZStack {
                        if loading {
                            ProgressView().controlSize(.mini)
                        } else if isDir {
                            Image(systemName: expanded ? "chevron.down" : "chevron.right")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(width: 14, height: 16)

                    Image(systemName: isDir ? "folder" : "doc")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .frame(width: 18)

                    Text(node.entry.name)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .truncationMode(.middle)

                    Spacer(minLength: 0)
                }
                .opacity(alpha)
                .padding(.leading, CGFloat(depth) * 14 + 10)
                .padding(.trailing, 10)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(node.entry.name)
            .accessibilityValue(isDir ? (expanded ? "Expanded folder" : "Collapsed folder") : "File")
            .accessibilityHint(isDir ? "Double tap to \(expanded ? "collapse" : "expand")" : "Double tap to open")
        }
    }
}
