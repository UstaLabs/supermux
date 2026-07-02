import SwiftUI
import Shared

/// The chat **Finish** bottom sheet — native parity with the PWA `FinishSheet.vue`.
///
/// A three-state machine driven by `chrome.currentJob` (kept fresh by the WS `finish_job`
/// frame): `menu` (readiness preflight → Merge / Open PR / Keep / Discard) → `running`
/// (live stage) → `outcome` (per-status recovery: view PR, merge-anyway, commit & merge,
/// generate verify, let-the-agent-fix, retry, …). All finish state + actions live on
/// `SessionChrome`; this view only renders them and owns the local input drafts.
struct FinishSheet: View {
    let chrome: SessionChrome
    @Environment(\.dismiss) private var dismiss

    private enum View_ { case menu, running, outcome }

    // Local input state (web's component-local refs).
    @State private var confirmingDiscard = false
    @State private var pendingVerify: String?    // "merge" | "pr" | nil
    @State private var commitMessage = "Session changes"
    @State private var verifyDraft: VerifyDraft?
    @State private var verifySaving = false

    struct VerifyDraft { var content: String; let source: String }

    private var job: FinishJobDto? { chrome.broker.finishJobs[chrome.session.id] }
    private var readiness: FinishReadiness? { chrome.readiness }
    private var outcome: FinishResult? { job?.outcome }
    private var oStatus: String { outcome?.status ?? "" }

    private var view: View_ {
        guard let j = job else { return .menu }
        return j.status == "running" ? .running : .outcome
    }

    var body: some View {
        NavigationStack {
            Group {
                switch view {
                case .menu: menu
                case .running: running
                case .outcome: outcomeView
                }
            }
            .navigationTitle(navTitle)
            .smInlineNavigationTitle()
            .toolbar {
                // No Done while running — mirror web hiding the close button mid-job.
                if view != .running {
                    ToolbarItem(placement: .smTopTrailing) { Button("Done") { dismiss() } }
                }
            }
            .task {
                // On open: clear any stale kickoff error, ack the badge, and (re)load readiness
                // when there's no in-flight job (no job at all, or one that already finished).
                chrome.runError = nil
                chrome.ack()
                if job == nil || job?.status == "done" { await chrome.loadReadiness() }
            }
        }
        .tint(Theme.teal)
        .smPresentationDetents([.medium, .large])
    }

    private var navTitle: String {
        switch view {
        case .menu: return "Finish · \(readiness?.branch ?? chrome.session.session_branch ?? "")"
        case .running: return "Finishing"
        case .outcome: return "Finish"
        }
    }

    // MARK: - Menu

    @ViewBuilder private var menu: some View {
        List {
            if let err = chrome.runError {
                Section {
                    Label(err, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote).foregroundStyle(.red)
                }
            }
            if let r = readiness {
                Section { readinessCard(r) } header: { Text("\(r.branch) → \(r.base)") }
            } else {
                Section {
                    HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Checking branch…").foregroundStyle(.secondary) }
                        .font(.footnote)
                }
            }

            Section {
                if readiness?.nothingToLand == true {
                    Text("No new commits to land").font(.footnote).foregroundStyle(.secondary)
                    actionRow("Keep", systemImage: "archivebox") { pendingVerify = nil; chrome.run(action: "keep"); dismiss() }
                    discardRow
                } else {
                    actionRow("Merge locally", systemImage: "arrow.triangle.merge",
                              highlighted: readiness?.recommended == "merge") {
                        confirmingDiscard = false
                        pendingVerify = pendingVerify == "merge" ? nil : "merge"
                    }
                    if pendingVerify == "merge" { verifyChoiceRows(action: "merge", prompt: "Run tests before merging?") }
                    prRow
                    if pendingVerify == "pr" { verifyChoiceRows(action: "pr", prompt: "Run tests before opening the PR?") }
                    actionRow("Keep", systemImage: "archivebox") { pendingVerify = nil; chrome.run(action: "keep"); dismiss() }
                    discardRow
                }
            }
        }
    }

    private func readinessCard(_ r: FinishReadiness) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text(r.behind > 0 ? "↑\(r.ahead) · ↓\(r.behind)" : "↑\(r.ahead)")
                    .font(.footnote.weight(.semibold))
                Text("\(r.filesChanged) files · +\(r.insertions)/−\(r.deletions)")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                if r.conflictPreflight == "will_conflict" {
                    Label("may conflict", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption).foregroundStyle(.orange)
                } else if r.conflictPreflight == "clean" {
                    Label("no conflict", systemImage: "checkmark").font(.caption).foregroundStyle(.green)
                }
                if !r.dirtyFiles.isEmpty {
                    Label("\(r.dirtyFiles.count) uncommitted", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption).foregroundStyle(.orange)
                }
            }
        }
        .padding(.vertical, 2)
    }

    /// Open PR / "Push & open PR" — disabled without a remote (matches web).
    @ViewBuilder private var prRow: some View {
        let r = readiness
        let label = (r?.hasRemote == true && r?.ghAvailable == false) ? "Push & open PR" : "Open PR"
        Button { confirmingDiscard = false; pendingVerify = pendingVerify == "pr" ? nil : "pr" } label: {
            HStack {
                Label(label, systemImage: "arrow.triangle.pull")
                Spacer()
                if r != nil && r?.hasRemote == false {
                    Text("no remote").font(.caption).foregroundStyle(.secondary)
                }
            }
            .fontWeight(.medium)
            .foregroundStyle(r?.recommended == "pr" ? Theme.teal : .primary)
        }
        .disabled(r != nil && r?.hasRemote == false)
    }

    @ViewBuilder private var discardRow: some View {
        Button(role: .destructive) { pendingVerify = nil; confirmingDiscard = true } label: {
            Label("Discard", systemImage: "trash")
        }
        if confirmingDiscard {
            VStack(alignment: .leading, spacing: 8) {
                Text("Discard all work on this branch?").font(.footnote)
                HStack(spacing: 10) {
                    Button("Discard", role: .destructive) { confirmingDiscard = false; chrome.run(action: "discard") }
                        .buttonStyle(.borderedProminent).controlSize(.small)
                    Button("Cancel") { confirmingDiscard = false }
                        .buttonStyle(.bordered).controlSize(.small)
                }
            }
            .padding(.vertical, 2)
        }
    }

    /// A primary menu action; `highlighted` tints it teal (web's emerald `recommended`).
    private func actionRow(_ title: String, systemImage: String, highlighted: Bool = false,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .fontWeight(.medium)
                .foregroundStyle(highlighted ? Theme.teal : .primary)
        }
    }

    /// Inline Run/Skip choice — shown under Merge/Open PR (mirrors discardRow confirm).
    @ViewBuilder private func verifyChoiceRows(action: String, prompt: String) -> some View {
        Text(prompt).font(.footnote).foregroundStyle(.secondary)
        Button { pendingVerify = nil; chrome.run(action: action, skipVerify: false) } label: {
            Label("Run tests", systemImage: "checkmark.circle")
        }
        if canSkipTests(action: action, prRequiresGreen: readiness?.prRequiresGreen ?? false) {
            Button { pendingVerify = nil; chrome.run(action: action, skipVerify: true) } label: {
                Label("Skip tests", systemImage: "forward")
            }.foregroundStyle(.orange)
        }
    }

    // MARK: - Running

    private var running: some View {
        VStack(spacing: 14) {
            ProgressView().controlSize(.large)
            Text(job?.stage ?? "Finishing…").font(.subheadline.weight(.medium))
            Text("You can close this — I'll notify you when it's done.")
                .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }

    // MARK: - Outcome

    @ViewBuilder private var outcomeView: some View {
        let o = outcome
        List {
            switch oStatus {
            case "integrated":
                Section {
                    Label("Merged into \(o?.base ?? "base")", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green).font(.subheadline.weight(.medium))
                    doneRow
                }

            case "pr_opened":
                Section {
                    Label("Pull request opened", systemImage: "arrow.triangle.pull")
                        .font(.subheadline.weight(.medium))
                    if let s = o?.prUrl, let url = URL(string: s) {
                        Link(destination: url) { Label("View PR", systemImage: "arrow.up.right.square") }
                    }
                    dismissRow; doneRow
                }

            case "branch_published":
                Section {
                    Label("Branch pushed", systemImage: "arrow.triangle.pull")
                        .font(.subheadline.weight(.medium))
                    if let e = o?.prError { Text(e).font(.footnote).foregroundStyle(.secondary) }
                    if let s = o?.compareUrl, let url = URL(string: s) {
                        Link(destination: url) { Label("Open a PR", systemImage: "arrow.up.right.square") }
                    }
                    dismissRow; doneRow
                }

            case "tests_failed":
                Section {
                    Label("Tests failed", systemImage: "xmark.circle.fill")
                        .foregroundStyle(.red).font(.subheadline.weight(.medium))
                    outputBlock(o?.output)
                    dismissRow
                    Button { chrome.run(action: "merge", skipVerify: true) } label: {
                        Label("Merge anyway", systemImage: "exclamationmark.triangle")
                    }.foregroundStyle(.orange)
                    letAgentFixRow
                }

            case "sync_conflict", "dirty_overlap":
                Section {
                    Label(oStatus == "sync_conflict" ? "Merge conflicts" : "Base has unsaved changes",
                          systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange).font(.subheadline.weight(.medium))
                    fileList(o?.files ?? [])
                    dismissRow; letAgentFixRow
                }

            case "uncommitted":
                Section {
                    Text("These changes aren't committed yet").font(.subheadline)
                    fileList(o?.files ?? [])
                    TextField("Commit message", text: $commitMessage)
                    dismissRow
                    Button {
                        chrome.run(action: job?.action == "pr" ? "pr" : "merge",
                                   commitFirst: true, commitMessage: commitMessage)
                    } label: { Text("Commit & \(job?.action == "pr" ? "open PR" : "merge")") }
                }

            case "no_verify":
                Section {
                    if verifyDraft == nil {
                        Text("No .mux/verify.sh configured").font(.subheadline)
                        dismissRow
                        Button { chrome.run(action: "merge", skipVerify: true) } label: {
                            Label("Merge without verifying", systemImage: "exclamationmark.triangle")
                        }.foregroundStyle(.orange)
                        Button { Task { await loadVerifyDraft() } } label: {
                            Label("Generate verify", systemImage: "sparkles")
                        }
                    } else {
                        Text("Draft · \(verifyDraft?.source ?? "")")
                            .font(.caption).textCase(.uppercase).foregroundStyle(.secondary)
                        TextEditor(text: Binding(
                            get: { verifyDraft?.content ?? "" },
                            set: { verifyDraft?.content = $0 }))
                            .font(.system(.footnote, design: .monospaced))
                            .frame(minHeight: 140)
                        dismissRow
                        Button { Task { await saveVerify() } } label: { Text("Save") }
                            .disabled(verifySaving)
                    }
                }

            case "push_auth_failed", "push_rejected":
                Section {
                    Label("Push failed", systemImage: "xmark.circle.fill")
                        .foregroundStyle(.red).font(.subheadline.weight(.medium))
                    if let m = o?.message { Text(m).font(.footnote) }
                    dismissRow
                    Button { chrome.run(action: "pr") } label: { Text("Retry") }
                }

            case "nothing_to_do":
                Section {
                    Text("Nothing to land — no new commits.").font(.subheadline)
                    dismissRow
                }

            case "kept", "discarded":
                Section {
                    Label(oStatus == "kept" ? "Branch kept" : "Work discarded", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green).font(.subheadline.weight(.medium))
                    doneRow
                }

            case "non_ff":
                Section {
                    Label("Base branch moved", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange).font(.subheadline.weight(.medium))
                    Text("The base branch moved while finishing. Re-sync and merge again.")
                        .font(.footnote).foregroundStyle(.secondary)
                    dismissRow
                    Button { chrome.run(action: "merge") } label: { Text("Merge again") }
                }

            default:
                Section {
                    Label("Finish failed", systemImage: "xmark.circle.fill")
                        .foregroundStyle(.red).font(.subheadline.weight(.medium))
                    Text(o?.message ?? oStatus).font(.footnote)
                    dismissRow
                }
            }
        }
    }

    // MARK: - Outcome building blocks

    // Done/Dismiss both clear the (terminal) job so reopening returns to the readiness menu
    // instead of replaying the old outcome — and so a `failed` finish can be dismissed at all.
    private var doneRow: some View { Button("Done") { chrome.clearJob(); dismiss() } }
    private var dismissRow: some View { Button("Dismiss") { chrome.clearJob(); dismiss() }.foregroundStyle(.secondary) }

    private var letAgentFixRow: some View {
        Button {
            if let o = outcome { chrome.letAgentFix(outcome: o); chrome.clearJob(); dismiss() }
        } label: { Label("Let the agent fix it", systemImage: "paperplane") }
    }

    @ViewBuilder private func fileList(_ files: [String]) -> some View {
        ForEach(files, id: \.self) { f in
            Text(f).font(.system(.footnote, design: .monospaced))
                .lineLimit(1).truncationMode(.middle).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private func outputBlock(_ text: String?) -> some View {
        if let text, !text.isEmpty {
            ScrollView {
                Text(text).font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
            }
            .frame(maxHeight: 220)
        }
    }

    // MARK: - Verify recovery

    private func loadVerifyDraft() async {
        if let r = await chrome.generateVerify() { verifyDraft = VerifyDraft(content: r.content, source: r.source) }
    }
    private func saveVerify() async {
        guard let draft = verifyDraft, !verifySaving else { return }
        verifySaving = true
        defer { verifySaving = false }
        if let r = await chrome.saveVerify(content: draft.content), r.ok {
            verifyDraft = nil
            chrome.run(action: "merge")   // auto-run merge after save (web parity)
        }
    }
}
