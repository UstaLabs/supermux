import SwiftUI
import Shared

/// System settings — mirrors `SystemSettingsView.vue` on the web PWA.
///
/// Shows broker update status and, for `binary` installs with an update
/// available, an **Update broker** button that triggers the broker's
/// self-updater (`POST /api/update/run`) and polls until it settles. Source /
/// docker installs can't self-update, so the broker's instruction is shown
/// instead. Restart broker is kept. (The *app* itself still updates via
/// TestFlight; this updates the *broker* it talks to.)
///
/// Broker calls used:
///   `broker.updateStatus()` — async → `UpdateStatus?`
///   `broker.runUpdate()` — async → `RunUpdateResult?`
///   `broker.restartBroker()` — fire-and-forget
struct SystemSettingsView: View {
    let broker: BrokerSession

    @State private var status: UpdateStatus?
    @State private var loading = true
    @State private var loadError: String?

    @State private var showRestartConfirm = false
    @State private var restarting = false

    @State private var runError: String?
    @State private var pollTask: Task<Void, Never>?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                form
            }
        }
        .navigationTitle("System")
        .smInlineNavigationTitle()
        .tint(Theme.teal)
        .task { await load() }
        .onDisappear { pollTask?.cancel() }
    }

    // MARK: - Form

    private var form: some View {
        Form {
            updatesSection
            maintenanceSection
        }
    }

    // MARK: - Updates section

    private var updatesSection: some View {
        Section("Updates") {
            if let loadError, status == nil {
                Text(loadError)
                    .foregroundStyle(.red)
                    .font(.subheadline)
            } else if let s = status {
                // Version row
                VStack(alignment: .leading, spacing: 3) {
                    Text("supermux \(s.current)")
                        .font(.subheadline)
                    Text(shortCommit(s.commit))
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)

                // Update availability
                updateAvailabilityRow(s)

                // State if not idle
                if s.state != "idle" {
                    statusRow(s.state)
                }

                // Last checked
                if let checkedLine = lastCheckedText(s.lastChecked) {
                    Text(checkedLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                // Last error
                if let err = s.lastError {
                    Text(err)
                        .font(.caption)
                        .foregroundStyle(.red)
                }

                // Release notes link
                if let notesStr = s.notesUrl, let notesUrl = URL(string: notesStr) {
                    Link(destination: notesUrl) {
                        HStack(spacing: 4) {
                            Text("Release notes")
                            Image(systemName: "arrow.up.right")
                                .font(.caption2)
                        }
                        .font(.subheadline)
                        .foregroundStyle(Theme.teal)
                    }
                }

                // Update broker — binary self-updater only. Source/docker can't
                // self-update (the broker says so); hide while an update is in
                // flight (the state row above shows progress) and once staged
                // (restart-required needs a restart, not a re-run).
                if s.mode == "binary",
                   (s.updateAvailable || s.state == "failed"),
                   !isRunning(s.state),
                   s.state != "restart-required" {
                    Button {
                        Task { await startUpdate() }
                    } label: {
                        HStack {
                            Text(s.state == "failed" ? "Retry update" : "Update broker")
                            Spacer()
                        }
                    }
                    .tint(Theme.teal)
                }
                if let runError {
                    Text(runError)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            } else {
                // Should only hit this if load() returns status=nil without error.
                Text("Status unavailable")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func updateAvailabilityRow(_ s: UpdateStatus) -> some View {
        if s.disabled {
            Text("Update checks disabled.")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else if s.updateAvailable {
            HStack(spacing: 4) {
                Image(systemName: "arrow.down.circle")
                    .foregroundStyle(Theme.teal)
                    .font(.caption)
                Text("Update available\(s.latest.map { ": \($0)" } ?? "")")
                    .font(.subheadline)
            }
        } else {
            HStack(spacing: 4) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Theme.teal)
                    .font(.caption)
                Text("Up to date")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func statusRow(_ state: String) -> some View {
        HStack(spacing: 6) {
            if state == "failed" {
                Image(systemName: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.red)
            } else {
                ProgressView()
                    .scaleEffect(0.7)
                    .tint(Theme.teal)
            }
            Text(stateLabel(state))
                .font(.caption)
                .foregroundStyle(state == "failed" ? .red : .secondary)
        }
    }

    // MARK: - Maintenance section

    private var maintenanceSection: some View {
        Section("Maintenance") {
            Button(role: .destructive) {
                showRestartConfirm = true
            } label: {
                HStack {
                    Text(restarting ? "Restarting…" : "Restart broker")
                    Spacer()
                    if restarting {
                        ProgressView()
                            .scaleEffect(0.8)
                            .tint(.red)
                    }
                }
            }
            .disabled(restarting)
            .confirmationDialog(
                "Restart the broker?",
                isPresented: $showRestartConfirm,
                titleVisibility: .visible
            ) {
                Button("Restart", role: .destructive) {
                    restarting = true
                    broker.restartBroker()
                    // The broker goes down → WS will show reconnecting state.
                    // We leave `restarting` true briefly so the row reads "Restarting…".
                    Task {
                        try? await Task.sleep(nanoseconds: 4_000_000_000)
                        restarting = false
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Sessions will reconnect automatically.")
            }

            if restarting {
                Text("Restarting…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Helpers

    private func load() async {
        loading = true
        defer { loading = false }
        if let s = await broker.updateStatus() {
            status = s
            loadError = nil
        } else {
            loadError = "Couldn't load update status."
        }
    }

    /// Silent status refresh used while polling an in-flight update (keeps the
    /// form on screen instead of flashing the full-screen loading state).
    private func refresh() async {
        if let s = await broker.updateStatus() {
            status = s
            loadError = nil
        }
    }

    private func startUpdate() async {
        runError = nil
        guard let result = await broker.runUpdate() else {
            runError = "Couldn't reach the broker."
            return
        }
        if result.started {
            pollUntilSettled()
        } else if let err = result.error {
            runError = result.instruction ?? err
        }
    }

    /// Poll the update status every 1.5s until the broker leaves the
    /// downloading/swapping states (idle / restart-required / failed).
    private func pollUntilSettled() {
        pollTask?.cancel()
        pollTask = Task {
            for _ in 0..<120 {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if Task.isCancelled { return }
                await refresh()
                if let s = status, !isRunning(s.state) { return }
            }
        }
    }

    private func isRunning(_ state: String) -> Bool {
        state == "checking" || state == "downloading" || state == "swapping"
    }

    /// Formats epoch-ms Double? (from SKIE-bridged Kotlin `Double?` → `KotlinDouble?`)
    /// into a relative "checked Xm ago" string.
    private func lastCheckedText(_ raw: KotlinDouble?) -> String? {
        guard let ms = raw?.doubleValue, ms > 0 else { return nil }
        let date = Date(timeIntervalSince1970: ms / 1000)
        let diff = max(0, -date.timeIntervalSinceNow)
        let relStr: String
        if diff < 60 {
            relStr = "<1m ago"
        } else if diff < 3600 {
            relStr = "\(Int(diff / 60))m ago"
        } else if diff < 86400 {
            relStr = "\(Int(diff / 3600))h ago"
        } else {
            relStr = "\(Int(diff / 86400))d ago"
        }
        return "Checked \(relStr)"
    }

    private func shortCommit(_ commit: String) -> String {
        commit.isEmpty ? "" : String(commit.prefix(8))
    }

    private func stateLabel(_ state: String) -> String {
        switch state {
        case "checking":         return "Checking…"
        case "downloading":      return "Downloading…"
        case "swapping":         return "Swapping…"
        case "restart-required": return "Restart required"
        case "failed":           return "Failed"
        default:                 return state
        }
    }
}
