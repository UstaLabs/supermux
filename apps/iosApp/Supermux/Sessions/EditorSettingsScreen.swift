import SwiftUI
import Shared

/// Full-page settings screen for the editor — mirrors `EditorSettingsView.vue` + `editorSettings.ts`.
///
/// **Appearance** (device-local, backed by `EditorSettingsStore`):
///   - Wrap long lines toggle
///   - Font size stepper (10–24 pt)
///
/// **Language servers** (broker-remote, via `BrokerSession`):
///   - Per-server enable toggle (→ `setLspEnabled`)
///   - State badge: Ready / Needs <requires> / Not installed
///   - Install button when `installable && enabled && state != ready` (→ `installEditorLsp`)
///   - Delete button for custom servers (→ `removeCustomEditorLsp`)
///   - "Add language server" form for custom LSP registration (→ `addCustomEditorLsp`)
///
/// Pushed onto a `NavigationStack` via `NavigationLink` in `SettingsView`.
struct EditorSettingsScreen: View {
    let broker: BrokerSession

    // MARK: - Appearance (device-local)
    @State private var editorSettings = EditorSettingsStore()

    // MARK: - LSP list
    @State private var servers: [LspServer] = []
    @State private var loading = true
    @State private var lspError: String?

    // MARK: - Per-row async state
    /// Id of the server currently being toggled (one at a time).
    @State private var toggling: String?
    /// Id of the server currently installing.
    @State private var installing: String?
    /// Install result (label, ok, last log line) shown inline until dismissed.
    @State private var installResult: InstallResult?
    /// Id of the server currently being removed.
    @State private var removing: String?

    // MARK: - Add-server form
    @State private var showAddForm = false
    @State private var addSaving = false
    @State private var addError: String?

    @State private var newLabel = ""
    @State private var newId = ""
    @State private var newCommand = ""
    @State private var newArgs = ""
    @State private var newExtensions = ""
    @State private var newLanguageId = ""
    @State private var newInstallCmd = ""

    // MARK: - Body

    var body: some View {
        Form {
            appearanceSection
            lspSection
        }
        .navigationTitle("Editor")
        .smInlineNavigationTitle()
        .tint(Theme.teal)
        .task { await loadLsp() }
    }

    // MARK: - Sections

    private var appearanceSection: some View {
        Section {
            Toggle("Wrap long lines", isOn: Binding(
                get: { editorSettings.lineWrap },
                set: { editorSettings.lineWrap = $0 }
            ))

            Stepper(
                value: Binding(
                    get: { editorSettings.fontSize },
                    set: { editorSettings.fontSize = $0 }
                ),
                in: EditorSettingsStore.fontRange,
                step: 1
            ) {
                LabeledContent("Font size", value: "\(editorSettings.fontSize) pt")
            }
        } header: {
            Text("Appearance")
        } footer: {
            Text("Font size and line wrapping apply to this device only.")
        }
    }

    @ViewBuilder
    private var lspSection: some View {
        Section {
            if loading {
                HStack {
                    ProgressView()
                    Text("Loading…")
                        .foregroundStyle(.secondary)
                }
            } else if let lspError {
                Text(lspError)
                    .foregroundStyle(.red)
                Button("Retry") { Task { await loadLsp() } }
            } else {
                ForEach(servers, id: \.id) { server in
                    serverRow(server)
                }
                addServerFooter
            }
        } header: {
            Text("Language servers")
        } footer: {
            if !loading && lspError == nil {
                Text("Language servers run on the broker host.")
            }
        }
    }

    // MARK: - Server row

    @ViewBuilder
    private func serverRow(_ server: LspServer) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 0) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(server.label)
                            .font(.body)
                        if server.custom {
                            Text("Custom")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .textCase(.uppercase)
                        }
                    }
                    if !server.extensions.isEmpty {
                        Text(extSummary(server.extensions))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(stateLabel(server))
                        .font(.caption)
                        .foregroundStyle(server.state == "ready" ? Color.green : Color.orange)
                }

                Spacer()

                HStack(spacing: 8) {
                    // Delete button for custom servers
                    if server.custom {
                        Button {
                            Task { await removeServer(server) }
                        } label: {
                            if removing == server.id {
                                ProgressView()
                                    .frame(width: 18, height: 18)
                            } else {
                                Image(systemName: "trash")
                                    .foregroundStyle(.red)
                            }
                        }
                        .buttonStyle(.borderless)
                        .disabled(removing == server.id)
                    }

                    // Enable toggle
                    Toggle("", isOn: Binding(
                        get: { server.enabled },
                        set: { newValue in
                            Task { await toggleServer(server, enabled: newValue) }
                        }
                    ))
                    .labelsHidden()
                    .disabled(toggling == server.id)
                }
            }

            // Install button (shown when enabled, installable, not yet ready)
            if server.enabled && server.installable && server.state != "ready" {
                Button {
                    Task { await installServer(server) }
                } label: {
                    HStack(spacing: 4) {
                        if installing == server.id {
                            ProgressView()
                                .frame(width: 12, height: 12)
                        } else {
                            Image(systemName: "arrow.down.circle")
                                .font(.caption)
                        }
                        Text(server.installLabel ?? "Install")
                            .font(.caption)
                    }
                    .foregroundStyle(Theme.teal)
                }
                .buttonStyle(.borderless)
                .disabled(installing != nil)
                .padding(.leading, 0)
            }

            // Inline install result feedback
            if let result = installResult, result.serverId == server.id {
                HStack(spacing: 4) {
                    Image(systemName: result.ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .foregroundStyle(result.ok ? Color.green : Color.red)
                        .font(.caption)
                    Text(result.message)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        if installResult?.serverId == server.id { installResult = nil }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.borderless)
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - Add-server footer

    @ViewBuilder
    private var addServerFooter: some View {
        if !showAddForm {
            Button {
                showAddForm = true
            } label: {
                Label("Add language server", systemImage: "plus.circle")
            }
        } else {
            addServerForm
        }
    }

    private var addServerForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Add language server")
                .font(.headline)
                .padding(.top, 4)

            if let addError {
                Text(addError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Group {
                LabeledFormField(label: "Display name", placeholder: "Zig", text: $newLabel)
                    .onChange(of: newLabel) { _, v in
                        if newId.isEmpty && !v.trimmingCharacters(in: .whitespaces).isEmpty {
                            newId = slugId(v)
                        }
                    }
                LabeledFormField(label: "Server id", placeholder: "zig", text: $newId, mono: true)
                LabeledFormField(label: "Command on broker", placeholder: "zls", text: $newCommand, mono: true)
                LabeledFormField(label: "Args (optional)", placeholder: "--stdio", text: $newArgs, mono: true)
                LabeledFormField(label: "Extensions", placeholder: ".zig, .zon", text: $newExtensions, mono: true)
                LabeledFormField(label: "Language id (optional)", placeholder: "zig", text: $newLanguageId, mono: true)
                LabeledFormField(label: "Install command (optional)", placeholder: "apt install -y zls", text: $newInstallCmd, mono: true)
            }

            Text("Install command runs as the broker user — do not use sudo.")
                .font(.caption2)
                .foregroundStyle(.secondary)

            HStack(spacing: 10) {
                Button {
                    Task { await submitAdd() }
                } label: {
                    HStack {
                        if addSaving { ProgressView().frame(width: 14, height: 14) }
                        Text(addSaving ? "Saving…" : "Save")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .disabled(addSaving)

                Button("Cancel") {
                    showAddForm = false
                    resetAddForm()
                    addError = nil
                }
                .buttonStyle(.bordered)
                .disabled(addSaving)
            }
        }
        .padding(.vertical, 4)
    }

    // MARK: - Async actions

    private func loadLsp() async {
        loading = true
        lspError = nil
        defer { loading = false }
        if let response = await broker.getEditorSettings() {
            servers = response.lsp.servers
        } else {
            lspError = "Couldn't load language server settings"
        }
    }

    private func toggleServer(_ server: LspServer, enabled: Bool) async {
        guard toggling == nil else { return }
        toggling = server.id
        defer { toggling = nil }
        if let response = await broker.setLspEnabled(server.id, enabled: enabled) {
            servers = response.lsp.servers
        }
        // On failure: leave the list as-is; the toggle snaps back because `servers`
        // is unchanged and `toggling` is cleared, re-enabling the control.
    }

    private func installServer(_ server: LspServer) async {
        guard installing == nil else { return }
        installing = server.id
        installResult = nil
        defer { installing = nil }
        if let result = await broker.installEditorLsp(server.id) {
            let lastLine = result.lines.last ?? (result.ok ? "Installed" : "Install failed")
            installResult = InstallResult(serverId: server.id, ok: result.ok, message: lastLine)
            // Reload to pick up the new state
            await loadLsp()
        } else {
            installResult = InstallResult(serverId: server.id, ok: false, message: "Install failed — check broker logs")
        }
    }

    private func removeServer(_ server: LspServer) async {
        guard removing == nil else { return }
        removing = server.id
        defer { removing = nil }
        if let result = await broker.removeCustomEditorLsp(server.id) {
            if result.ok {
                servers = result.lsp?.servers ?? servers.filter { $0.id != server.id }
            }
            // On failure, leave the list unchanged (no crash, no silent data loss)
        }
    }

    private func submitAdd() async {
        let idStr = newId.trimmingCharacters(in: .whitespaces).isEmpty
            ? slugId(newLabel) : newId.trimmingCharacters(in: .whitespaces)
        let labelStr = newLabel.trimmingCharacters(in: .whitespaces)
        let commandStr = newCommand.trimmingCharacters(in: .whitespaces)
        let extStr = newExtensions.trimmingCharacters(in: .whitespaces)

        guard !idStr.isEmpty, !labelStr.isEmpty, !commandStr.isEmpty, !extStr.isEmpty else {
            addError = "Fill in display name, command, and extensions"
            return
        }

        let argsArr = newArgs.trimmingCharacters(in: .whitespaces)
            .split(separator: " ").map(String.init).filter { !$0.isEmpty }
        let extsArr = extStr.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let langId = newLanguageId.trimmingCharacters(in: .whitespaces)
        let installCmd = newInstallCmd.trimmingCharacters(in: .whitespaces)

        addSaving = true
        addError = nil
        defer { addSaving = false }

        let result = await broker.addCustomEditorLsp(
            id: idStr,
            label: labelStr,
            command: commandStr,
            extensions: extsArr,
            args: argsArr,
            languageId: langId.isEmpty ? nil : langId,
            installCmd: installCmd.isEmpty ? nil : installCmd
        )

        if let result {
            if result.ok {
                servers = result.lsp?.servers ?? servers
                showAddForm = false
                resetAddForm()
            } else {
                addError = result.error ?? "Could not add server"
            }
        } else {
            addError = "Could not add server — check broker connection"
        }
    }

    // MARK: - Helpers

    // No optimistic-update helper needed: toggleServer reloads the full list from the
    // broker response, which is authoritative. The Toggle's disabled state (toggling == id)
    // prevents double-tap during the in-flight call.

    private func stateLabel(_ server: LspServer) -> String {
        switch server.state {
        case "ready":
            if server.custom, let cmd = server.command {
                return "Ready (\(cmd))"
            }
            return "Ready"
        case "prereq-missing":
            return "Needs \(server.requires ?? "toolchain")"
        default:
            return server.custom ? "Binary not found on broker" : "Not installed"
        }
    }

    private func extSummary(_ exts: [String]) -> String {
        var seen = Set<String>()
        let unique = exts.filter { seen.insert($0).inserted }.prefix(6)
        let tail = exts.count > unique.count ? "…" : ""
        return unique.joined(separator: ", ") + tail
    }

    private func slugId(_ label: String) -> String {
        let slug = label
            .lowercased()
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .joined(separator: "-")
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return String(slug.prefix(48)).isEmpty ? "server" : String(slug.prefix(48))
    }

    private func resetAddForm() {
        newLabel = ""; newId = ""; newCommand = ""
        newArgs = ""; newExtensions = ""; newLanguageId = ""; newInstallCmd = ""
    }

    // MARK: - Local types

    private struct InstallResult {
        let serverId: String
        let ok: Bool
        let message: String
    }
}

// MARK: - Labelled form field helper

private struct LabeledFormField: View {
    let label: String
    let placeholder: String
    @Binding var text: String
    var mono: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(placeholder, text: $text)
                .font(mono ? .system(.body, design: .monospaced) : .body)
                .autocorrectionDisabled()
                .smNoAutocapitalization()
        }
    }
}
