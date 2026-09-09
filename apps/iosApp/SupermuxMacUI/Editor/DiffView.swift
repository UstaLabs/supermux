import SwiftUI
import Shared

/// Native git-diff viewer + lightweight code-review tool — 1:1 parity with the PWA
/// `DiffView.vue`. Renders unified diffs as monospaced rows (add/del/ctx/hunk), groups
/// files per-repo (only when >1 repo), and lets the reviewer leave inline comments,
/// resolve them, and submit the review back to the agent.
///
/// Pure SwiftUI + `@State` (no Combine / ObservableObject). All data-mutating work is
/// delegated to the injected async closures; the parent owns the source of truth and
/// re-supplies `repos` / `comments` after `onReload()`.
struct DiffView: View {
    let repos: [RepoDiff]
    let comments: [ReviewComment]
    /// repo, path, anchorLine (new-side), anchorContext (line text), hunkHeader (@@ line), body.
    let onAddComment: (_ repo: String, _ path: String, _ anchorLine: Int, _ anchorContext: String, _ hunkHeader: String, _ body: String) async -> Void
    let onResolve: (_ commentId: String) async -> Void
    let onSubmit: () async -> Void
    let onReload: () -> Void
    let onClose: () -> Void
    // Adjustable diff base (global, primary-repo refs — web parity): the current spec string
    // (session-start | head | commit:<sha> | branch:<name>), the refs feeding the commit/branch
    // submenus, and the setter. Target is always the working tree.
    let base: String
    let refs: [RepoRefs]
    let onSetBase: (String) -> Void

    // Repos expand by default; files collapse by default. Keys are stable strings so the
    // sets survive re-renders (matches the Vue Set<string> approach).
    @State private var expandedFiles: Set<String> = []
    @State private var expandedRepos: Set<String> = []
    @State private var didSeedRepos = false

    /// `repo||path||newLine` of the line whose composer is open (nil = none open).
    @State private var composerFor: String?
    @State private var draft = ""
    @State private var submitting = false
    @State private var wrap = true

    // ── Diff colours — match the web Tailwind palette (emerald/red/blue/amber) but
    // applied as opacity tints so they read in both light and dark. State is never
    // conveyed by colour alone: each row keeps its +/-/@@ sigil and status text.
    private static let emerald = Color(red: 0.20, green: 0.78, blue: 0.55)
    private static let red = Color(red: 0.90, green: 0.30, blue: 0.30)
    private static let blue = Color(red: 0.36, green: 0.56, blue: 0.94)
    private static let amber = Color(red: 0.98, green: 0.75, blue: 0.14)

    private var totalFiles: Int { repos.reduce(0) { $0 + $1.files.count } }
    private var multiRepo: Bool { repos.count > 1 }
    private var openCount: Int { comments.filter { $0.status == "open" }.count }
    private var hasComments: Bool { !comments.isEmpty || openCount > 0 }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            content
            if hasComments {
                Divider()
                submitBar
            }
        }
        .background(Color.smBackground)
        // Seed expansion once, and re-seed when the repo set itself changes (parity with
        // the Vue `watch(immediate:true)` that expands every repo by default).
        .onAppear { seedRepos() }
        .onChange(of: repos.map(\.repo)) { _, _ in didSeedRepos = false; seedRepos() }
    }

    private func seedRepos() {
        guard !didSeedRepos else { return }
        expandedRepos = Set(repos.map(\.repo))
        didSeedRepos = true
    }

    // MARK: - Header (floating glass chrome: file count + wrap toggle + close)

    private var header: some View {
        HStack(spacing: 8) {
            Text("\(totalFiles) changed file\(totalFiles == 1 ? "" : "s")")
                .font(.subheadline.weight(.medium))
            Spacer(minLength: 8)
            baseSelector
            Button {
                SMHaptics.selection()
                wrap.toggle()
            } label: {
                Text("Wrap")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(wrap ? Theme.teal : .secondary)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Line wrap")
            .accessibilityValue(wrap ? "On" : "Off")
            .accessibilityAddTraits(wrap ? [.isButton, .isSelected] : .isButton)

            Button {
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close diff")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 2)
        .background(.regularMaterial)
    }

    // MARK: - Diff base selector (global; primary-repo refs — parity with the web DiffView)

    /// Native `Menu` showing `Base: <label>`, with direct base buttons plus nested submenus
    /// for a previous commit / another branch (populated from the first repo's refs).
    private var baseSelector: some View {
        Menu {
            baseButton("Session start", "session-start")
            baseButton("Uncommitted (HEAD)", "head")
            Menu("Previous commit") {
                if primaryRefs?.commits.isEmpty ?? true {
                    Button("None") {}.disabled(true)
                } else {
                    ForEach(primaryRefs?.commits ?? [], id: \.sha) { c in
                        baseButton("\(shortSha(c.sha)) \(c.subject)", "commit:\(c.sha)")
                    }
                }
            }
            Menu("Another branch") {
                if primaryRefs?.branches.isEmpty ?? true {
                    Button("None") {}.disabled(true)
                } else {
                    ForEach(primaryRefs?.branches ?? [], id: \.self) { b in
                        baseButton(b, "branch:\(b)")
                    }
                }
            }
        } label: {
            HStack(spacing: 3) {
                Text("Base: \(baseLabel)")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2.weight(.semibold))
            }
            .foregroundStyle(.secondary)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Diff base")
        .accessibilityValue(baseLabel)
    }

    /// One base option — a checkmark marks the active base (native selected-menu-item look).
    private func baseButton(_ label: String, _ spec: String) -> some View {
        Button {
            SMHaptics.selection()
            onSetBase(spec)
        } label: {
            if base == spec { Label(label, systemImage: "checkmark") }
            else { Text(label) }
        }
    }

    /// Submenus use the first repo's refs (global selector, primary-repo refs — matches web).
    private var primaryRefs: RepoRefs? { refs.first }

    private func shortSha(_ sha: String) -> String { String(sha.prefix(7)) }

    /// Human label for the current base spec (Session start / Uncommitted / short sha / branch).
    private var baseLabel: String {
        if base == "session-start" { return "Session start" }
        if base == "head" { return "Uncommitted" }
        if base.hasPrefix("commit:") { return shortSha(String(base.dropFirst("commit:".count))) }
        if base.hasPrefix("branch:") { return String(base.dropFirst("branch:".count)) }
        return base
    }

    // MARK: - Body

    @ViewBuilder private var content: some View {
        if totalFiles == 0 {
            ContentUnavailableView {
                Label("No changes", systemImage: "checkmark.circle")
            } description: {
                Text("No changes found")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(repos, id: \.repo) { repo in
                        if multiRepo {
                            repoHeader(repo)
                            Divider()
                        }
                        if !multiRepo || expandedRepos.contains(repo.repo) {
                            ForEach(repo.files, id: \.path) { file in
                                fileSection(repo: repo.repo, file: file)
                                Divider()
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: - Repo group header (only when >1 repo)

    private func repoHeader(_ repo: RepoDiff) -> some View {
        let expanded = expandedRepos.contains(repo.repo)
        let label = repo.repo.isEmpty ? "workdir" : repo.repo
        return Button {
            SMHaptics.selection()
            toggle(&expandedRepos, repo.repo)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: expanded ? "chevron.down" : "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 16)
                Text(label)
                    .font(.footnote.weight(.medium))
                    .fontDesign(.monospaced)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 8)
                Text("\(repo.files.count) file\(repo.files.count == 1 ? "" : "s")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(Color.smSecondaryBackground)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label), \(repo.files.count) files")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(expanded ? "Double tap to collapse" : "Double tap to expand")
    }

    // MARK: - File section (header row + expanded diff)

    @ViewBuilder private func fileSection(repo: String, file: DiffFile) -> some View {
        let key = fileKey(repo, file.path)
        let expanded = expandedFiles.contains(key)
        let stats = diffStats(file.diff)
        VStack(alignment: .leading, spacing: 0) {
            fileHeader(repo: repo, file: file, key: key, expanded: expanded, stats: stats)
            if expanded {
                Divider()
                diffBody(repo: repo, file: file)
            }
        }
        .padding(.leading, multiRepo ? 12 : 0)
    }

    private func fileHeader(repo: String, file: DiffFile, key: String, expanded: Bool,
                            stats: (added: Int, deleted: Int)) -> some View {
        Button {
            SMHaptics.selection()
            toggle(&expandedFiles, key)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: expanded ? "chevron.down" : "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 16)
                Text(file.path)
                    .font(.subheadline)
                    .fontDesign(.monospaced)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 8)
                if file.binary {
                    tag("Binary")
                } else if file.modeChange {
                    tag("Mode")
                }
                Text(statusLabel(file.status))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(statusColor(file.status))
                if !file.binary {
                    if stats.added > 0 {
                        Text("+\(stats.added)").font(.caption.weight(.medium)).foregroundStyle(Self.emerald)
                    }
                    if stats.deleted > 0 {
                        Text("-\(stats.deleted)").font(.caption.weight(.medium)).foregroundStyle(Self.red)
                    }
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(fileA11yLabel(file: file, stats: stats))
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(expanded ? "Double tap to collapse" : "Double tap to expand")
    }

    private func tag(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(.secondary)
    }

    private func fileA11yLabel(file: DiffFile, stats: (added: Int, deleted: Int)) -> String {
        var parts = [file.path, statusLabel(file.status)]
        if file.binary { parts.append("binary") }
        else if file.modeChange { parts.append("mode change") }
        if !file.binary {
            if stats.added > 0 { parts.append("\(stats.added) added") }
            if stats.deleted > 0 { parts.append("\(stats.deleted) deleted") }
        }
        return parts.joined(separator: ", ")
    }

    // MARK: - Diff body (monospaced rows + inline comments)

    @ViewBuilder private func diffBody(repo: String, file: DiffFile) -> some View {
        let lines = parseDiffLines(file.diff)
        if file.binary {
            placeholder("Binary file — no text diff")
        } else if file.modeChange && lines.isEmpty {
            placeholder("File mode changed")
        } else {
            let rows = rowsView(repo: repo, path: file.path, lines: lines)
            if wrap {
                rows.frame(maxWidth: .infinity, alignment: .leading)
            } else {
                // No wrap → the diff scrolls horizontally; comment rows live in the same
                // scroll so they stay column-aligned with their line.
                ScrollView(.horizontal, showsIndicators: true) {
                    rows
                }
            }
        }
    }

    private func rowsView(repo: String, path: String, lines: [DiffLine]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(lines.enumerated()), id: \.offset) { idx, line in
                diffRow(repo: repo, path: path, line: line)
                if line.type == .add || line.type == .ctx, let newLine = line.newLine {
                    let key = composerKey(repo, path, newLine)
                    if composerFor == key {
                        composer(repo: repo, path: path, line: line,
                                 hunkHeader: hunkHeader(in: lines, before: idx))
                    }
                    ForEach(commentsFor(repo: repo, path: path, newLine: newLine), id: \.id) { c in
                        commentThreadRow(c)
                    }
                }
            }
        }
        .background(Color.smSecondaryBackground.opacity(0.4))
    }

    /// One diff line: a gutter sigil (− / @@ / a tappable ⊕ on add+ctx) + the line text.
    private func diffRow(repo: String, path: String, line: DiffLine) -> some View {
        HStack(alignment: .top, spacing: 0) {
            gutter(repo: repo, path: path, line: line)
            Text(line.content.isEmpty ? " " : line.content)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(textColor(line.type))
                .textSelection(.enabled)
                .fixedSize(horizontal: !wrap, vertical: true)
                .frame(maxWidth: wrap ? .infinity : nil, alignment: .leading)
                .padding(.trailing, 8)
                .padding(.vertical, 1)
        }
        .frame(maxWidth: wrap ? .infinity : nil, alignment: .leading)
        .background(rowBackground(line.type))
    }

    @ViewBuilder private func gutter(repo: String, path: String, line: DiffLine) -> some View {
        switch line.type {
        case .del:
            gutterText("-", Self.red)
        case .hunk:
            gutterText("@@", Self.blue)
        case .add, .ctx:
            if let newLine = line.newLine {
                let key = composerKey(repo, path, newLine)
                let open = composerFor == key
                Button {
                    SMHaptics.selection()
                    toggleComposer(key)
                } label: {
                    Image(systemName: open ? "plus.circle.fill" : "plus.circle")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.teal)
                        .frame(width: 28, height: 28)        // padded by the row to a ≥44pt total target
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(open ? "Close comment composer on line \(newLine)" : "Add comment on line \(newLine)")
            } else {
                gutterText(line.type == .add ? "+" : "", Self.emerald)
            }
        }
    }

    private func gutterText(_ s: String, _ color: Color) -> some View {
        Text(s)
            .font(.system(.caption2, design: .monospaced))
            .foregroundStyle(color)
            .frame(width: 28)
            .padding(.vertical, 1)
    }

    private func placeholder(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .italic()
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color.smSecondaryBackground.opacity(0.4))
    }

    // MARK: - Inline comment composer

    private func composer(repo: String, path: String, line: DiffLine, hunkHeader: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("Leave a comment…", text: $draft, axis: .vertical)
                .font(.subheadline)
                .lineLimit(2...6)
                .textFieldStyle(.roundedBorder)
                .frame(minWidth: 220)
            HStack(spacing: 10) {
                Spacer()
                Button("Cancel") { cancelComposer() }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                Button {
                    Task { await addComment(repo: repo, path: path, line: line, hunkHeader: hunkHeader) }
                } label: {
                    Text("Add")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .tint(Theme.teal)
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || submitting)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.smTertiaryBackground)
    }

    // MARK: - Existing comment thread row

    private func commentThreadRow(_ c: ReviewComment) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: "text.bubble")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(c.author.isEmpty ? "You" : c.author)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                commentStatusBadge(c)
                Spacer(minLength: 8)
                if c.status == "open" {
                    Button {
                        Task { await resolve(c) }
                    } label: {
                        Text("Resolve")
                            .font(.caption.weight(.medium))
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.teal)
                    .accessibilityLabel("Resolve comment")
                }
            }
            Text(c.body)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.smTertiaryBackground)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func commentStatusBadge(_ c: ReviewComment) -> some View {
        if c.outdated {
            badge("outdated", Self.amber)
        } else if c.status == "submitted" {
            badge("submitted", Self.blue)
        } else if c.status == "resolved" {
            badge("resolved", Self.emerald)
        } else {
            badge("open", .secondary)
        }
    }

    private func badge(_ text: String, _ color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.18), in: Capsule())
    }

    // MARK: - Sticky submit bar

    private var submitBar: some View {
        HStack(spacing: 8) {
            Text("\(openCount) open comment\(openCount == 1 ? "" : "s")")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Button {
                Task {
                    submitting = true
                    await onSubmit()
                    submitting = false
                    onReload()
                }
            } label: {
                Text("Submit review")
                    .font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.regular)
            .tint(Theme.teal)
            .disabled(openCount == 0 || submitting)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.regularMaterial)
    }

    // MARK: - Actions

    /// Toggle the composer for a line — tapping the same ⊕ again closes it (parity with
    /// the Vue `openComposer`, which flips `composerFor` to null on a repeat tap).
    private func toggleComposer(_ key: String) {
        if composerFor == key { composerFor = nil; draft = "" }
        else { composerFor = key; draft = "" }
    }

    private func cancelComposer() {
        composerFor = nil
        draft = ""
    }

    private func addComment(repo: String, path: String, line: DiffLine, hunkHeader: String) async {
        let body = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, let newLine = line.newLine else { return }
        submitting = true
        await onAddComment(repo, path, newLine, line.content, hunkHeader, body)
        draft = ""
        composerFor = nil
        submitting = false
        onReload()
    }

    private func resolve(_ c: ReviewComment) async {
        await onResolve(c.id)
        onReload()
    }

    // MARK: - Comment filtering

    /// Existing top-level comments anchored at this new-side line. Mirrors the Vue filter:
    /// `repo == && path == && (currentLine ?? anchorLine) == newLine`.
    ///
    /// Bridging: `currentLine` is a nullable Kotlin `Int?`, so it arrives as `KotlinInt?`
    /// and we read the boxed value via `.intValue` (which is an `Int32`); `anchorLine` is a
    /// non-null Kotlin `Int` that bridges straight to `Int32`. Both are normalised to Swift
    /// `Int` so the `??` operands and the `== newLine` comparison are unambiguous.
    private func commentsFor(repo: String, path: String, newLine: Int) -> [ReviewComment] {
        comments.filter { c in
            guard c.repo == repo, c.path == path else { return false }
            let effective = c.currentLine.map { Int($0.intValue) } ?? Int(c.anchorLine)
            return effective == newLine
        }
    }

    // MARK: - Keys + expansion helpers

    private func fileKey(_ repo: String, _ path: String) -> String { "\(repo) \(path)" }
    private func composerKey(_ repo: String, _ path: String, _ newLine: Int) -> String {
        "\(repo)||\(path)||\(newLine)"
    }
    private func toggle(_ set: inout Set<String>, _ key: String) {
        if set.contains(key) { set.remove(key) } else { set.insert(key) }
    }

    /// The nearest preceding `@@` header content for the line at `index` (its hunk header).
    private func hunkHeader(in lines: [DiffLine], before index: Int) -> String {
        for i in stride(from: index, through: 0, by: -1) where lines[i].type == .hunk {
            return lines[i].content
        }
        return ""
    }
}

// MARK: - Diff parsing (ported 1:1 from parseDiffLines / diffStats / status* in DiffView.vue)

extension DiffView {
    enum DiffLineType { case add, del, ctx, hunk }

    struct DiffLine {
        let type: DiffLineType
        let content: String
        let newLine: Int?
    }

    /// Parse a unified diff into typed rows. Only counts new-side line numbers (`newLine`),
    /// assigned to `add` and `ctx` rows — exactly like the web `parseDiffLines`.
    func parseDiffLines(_ diff: String) -> [DiffLine] {
        var out: [DiffLine] = []
        var inHunk = false
        var newLn = 0
        for line in diff.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            if line.hasPrefix("@@") {
                inHunk = true
                newLn = newSideStart(line) ?? 0
                out.append(DiffLine(type: .hunk, content: line, newLine: nil))
                continue
            }
            if !inHunk { continue }
            if line.hasPrefix("+") {
                out.append(DiffLine(type: .add, content: String(line.dropFirst()), newLine: newLn))
                newLn += 1
            } else if line.hasPrefix("-") {
                out.append(DiffLine(type: .del, content: String(line.dropFirst()), newLine: nil))
            } else if line.hasPrefix(" ") {
                out.append(DiffLine(type: .ctx, content: String(line.dropFirst()), newLine: newLn))
                newLn += 1
            }
        }
        return out
    }

    /// Extract the new-side start line from a hunk header — the first `+<digits>` group.
    /// Mirrors the JS regex `/\+(\d+)/`: scan for a `+` that is immediately followed by a
    /// digit (a `+` not followed by a digit is skipped), then read the run of digits.
    private func newSideStart(_ hunk: String) -> Int? {
        let chars = Array(hunk)
        var i = 0
        while i < chars.count {
            if chars[i] == "+", i + 1 < chars.count, chars[i + 1].isNumber {
                var digits = ""
                var j = i + 1
                while j < chars.count, chars[j].isNumber { digits.append(chars[j]); j += 1 }
                return Int(digits)
            }
            i += 1
        }
        return nil
    }

    /// +/- counts, ignoring the `+++`/`---` file headers (parity with web `diffStats`).
    func diffStats(_ diff: String) -> (added: Int, deleted: Int) {
        var added = 0, deleted = 0
        for line in diff.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            if line.hasPrefix("+") && !line.hasPrefix("+++") { added += 1 }
            else if line.hasPrefix("-") && !line.hasPrefix("---") { deleted += 1 }
        }
        return (added, deleted)
    }

    func statusColor(_ status: String) -> Color {
        switch status {
        case "added": return Self.emerald
        case "deleted": return Self.red
        case "renamed": return Self.blue
        default: return Self.amber
        }
    }

    func statusLabel(_ status: String) -> String {
        switch status {
        case "added": return "Added"
        case "deleted": return "Deleted"
        case "renamed": return "Renamed"
        default: return "Modified"
        }
    }

    // Row tints — opacity over the diff surface so they read in light + dark.
    func rowBackground(_ type: DiffLineType) -> Color {
        switch type {
        case .add: return Self.emerald.opacity(0.12)
        case .del: return Self.red.opacity(0.12)
        case .hunk: return Self.blue.opacity(0.08)
        case .ctx: return .clear
        }
    }

    func textColor(_ type: DiffLineType) -> Color {
        switch type {
        case .add: return Self.emerald
        case .del: return Self.red
        case .hunk: return Self.blue
        case .ctx: return .secondary
        }
    }
}
