import SwiftUI
import Shared

// MARK: - GitHostingSettingsView

/// Settings screen for GitHub/GitLab connections — parity with the web GitHostingSettingsView.
///
/// Loads via `broker.api.listForges()` to get both the connection list and the CLI
/// presence (gh/glab). Shows a connections list when accounts exist, or an empty-state
/// with CLI-import shortcuts when there are none. A toolbar "+" (or empty-state button)
/// opens `AddForgeSheet`. Swipe / confirmationDialog disconnects an account.
struct GitHostingSettingsView: View {
    let broker: BrokerSession

    @State private var connections: [ForgeConnection] = []
    @State private var cliStatus: ForgeCliStatus? = nil
    @State private var loading = true
    @State private var error: String?
    @State private var addSheetOpen = false
    @State private var addKindPreset: String? = nil        // pre-select kind when opened from empty-state
    @State private var disconnectTarget: ForgeConnection?
    @State private var working = false                     // guards CLI-import / disconnect buttons

    var body: some View {
        Group {
            if loading && connections.isEmpty {
                ProgressView().tint(Theme.teal)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if connections.isEmpty {
                emptyState
            } else {
                connectionList
            }
        }
        .navigationTitle("Git hosting")
        .smInlineNavigationTitle()
        .tint(Theme.teal)
        // iOS-only: in the mac Settings window a NavigationStack toolbar item gets hoisted
        // into the window toolbar and reads as a phantom ninth "tab". The Mac keeps the
        // in-list "Add account" button, which is the native settings idiom anyway.
        #if os(iOS)
        .toolbar {
            ToolbarItem(placement: .smTopTrailing) {
                Button { addKindPreset = nil; addSheetOpen = true }
                    label: { Label("Add account", systemImage: "plus") }
                    .disabled(working)
            }
        }
        #endif
        .sheet(isPresented: $addSheetOpen) {
            AddForgeSheet(broker: broker, presetKind: addKindPreset) { Task { await load() } }
        }
        .confirmationDialog(
            "Disconnect \(disconnectTarget.map { "@\($0.account.login)" } ?? "account")?",
            isPresented: Binding(get: { disconnectTarget != nil }, set: { if !$0 { disconnectTarget = nil } }),
            titleVisibility: .visible
        ) {
            Button("Disconnect", role: .destructive) {
                if let t = disconnectTarget {
                    working = true
                    broker.removeForge(t.id)
                    // removeForge is fire-and-forget; give it a tick before reloading
                    Task { try? await Task.sleep(nanoseconds: 250_000_000); await load(); working = false }
                }
            }
        } message: {
            Text("The account will be removed from this broker. You can reconnect at any time.")
        }
        .task { await load() }
    }

    // MARK: Connection list

    private var connectionList: some View {
        List {
            if let error {
                Section {
                    HStack {
                        Text(error).foregroundStyle(.red).font(.caption)
                        Spacer()
                        Button("Retry") { Task { await load() } }
                    }
                }
            }
            Section {
                ForEach(connections, id: \.id) { c in
                    connectionRow(c)
                        .swipeActions(edge: .trailing) {
                            Button("Disconnect", role: .destructive) { disconnectTarget = c }
                        }
                }
            } header: {
                Text("Accounts")
            }

            // "Add account" dashed button at the bottom of the list
            Section {
                Button {
                    addKindPreset = nil
                    addSheetOpen = true
                } label: {
                    Label("Add account", systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .center)
                        .foregroundStyle(Theme.teal)
                }
                .buttonStyle(.borderless)
            }
        }
    }

    @ViewBuilder
    private func connectionRow(_ c: ForgeConnection) -> some View {
        HStack(spacing: 12) {
            ForgeLogo(kind: c.kind, size: 20)
                .frame(width: 34, height: 34)
                .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 9))

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text("@\(c.account.login)").font(.subheadline.weight(.semibold))
                    statusBadge(c.status)
                }
                Text("\(c.host.isEmpty ? defaultHost(c.kind) : c.host) · \(c.transport.uppercased())\(c.source == "cli" ? " · via CLI" : "")")
                    .font(.caption2).foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            if c.status == "needs_reconnect" {
                Button("Reconnect") {
                    addKindPreset = c.kind
                    addSheetOpen = true
                }
                .font(.caption).foregroundStyle(Theme.teal).buttonStyle(.borderless)
            }

            Button("Disconnect", role: .destructive) { disconnectTarget = c }
                .font(.caption2).foregroundStyle(.secondary).buttonStyle(.borderless)
        }
    }

    @ViewBuilder
    private func statusBadge(_ status: String) -> some View {
        if status == "needs_reconnect" {
            Text("reconnect")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.yellow)
                .padding(.horizontal, 5).padding(.vertical, 2)
                .overlay(
                    Capsule().strokeBorder(Color.yellow.opacity(0.5), lineWidth: 1)
                )
        } else {
            Circle().fill(Color(red: 63/255, green: 185/255, blue: 80/255)).frame(width: 7, height: 7)
        }
    }

    // MARK: Empty state

    private var emptyState: some View {
        ScrollView {
            VStack(spacing: 0) {
                if let error {
                    HStack {
                        Text(error).font(.caption).foregroundStyle(.red)
                        Button("Retry") { Task { await load() } }
                            .buttonStyle(.bordered)
                    }
                    .padding(.horizontal, 16).padding(.top, 16)
                }

                VStack(spacing: 16) {
                    Image(systemName: "point.3.connected.trianglepath.dotted")
                        .font(.system(size: 32))
                        .foregroundStyle(Theme.teal)
                        .frame(width: 56, height: 56)
                        .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 16))

                    VStack(spacing: 6) {
                        Text("Connect a Git host").font(.title3.weight(.semibold))
                        Text("Bring your GitHub & GitLab repos into supermux — clone, create, and launch sessions without leaving the app.")
                            .font(.subheadline).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(.top, 48).padding(.horizontal, 32)

                // CLI import buttons (when gh/glab is available and not yet connected)
                let importable = importableKinds
                if !importable.isEmpty {
                    VStack(spacing: 10) {
                        ForEach(importable, id: \.self) { kind in
                            cliImportButton(kind)
                        }
                    }
                    .padding(.top, 28).padding(.horizontal, 24)
                }

                // Divider before manual connect
                HStack(spacing: 8) {
                    Rectangle().frame(height: 1).foregroundStyle(Color.smSeparator)
                    Text(importable.isEmpty ? "Connect manually" : "or connect manually")
                        .font(.caption).foregroundStyle(.secondary)
                    Rectangle().frame(height: 1).foregroundStyle(Color.smSeparator)
                }
                .padding(.top, 20).padding(.horizontal, 24)

                // Manual connect buttons
                HStack(spacing: 12) {
                    ForEach(["github", "gitlab"], id: \.self) { kind in
                        Button {
                            addKindPreset = kind
                            addSheetOpen = true
                        } label: {
                            HStack(spacing: 8) {
                                ForgeLogo(kind: kind, size: 18)
                                Text(forgeDisplayName(kind)).font(.subheadline.weight(.medium))
                            }
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 14).padding(.horizontal, 24)

                Text("Uses a personal access token or your CLI login.")
                    .font(.caption2).foregroundStyle(.tertiary).multilineTextAlignment(.center)
                    .padding(.top, 10).padding(.horizontal, 32).padding(.bottom, 40)
            }
        }
    }

    @ViewBuilder
    private func cliImportButton(_ kind: String) -> some View {
        let login = cliLogin(kind)
        Button {
            guard !working else { return }
            working = true
            Task {
                _ = await broker.importForge(kind: kind, transport: "https")
                await load()
                working = false
            }
        } label: {
            HStack(spacing: 12) {
                ForgeLogo(kind: kind, size: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Import from \(cliName(kind))").font(.subheadline.weight(.semibold))
                    if let login {
                        Text("@\(login) · already signed in").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text("Import").font(.caption.weight(.semibold))
                    .foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Theme.teal, in: Capsule())
            }
            .padding(14)
            .background(Theme.teal.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.teal.opacity(0.3), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(working)
    }

    // MARK: Helpers

    private var importableKinds: [String] {
        guard let cli = cliStatus else { return [] }
        var result: [String] = []
        for kind in ["github", "gitlab"] {
            let presence = kind == "github" ? cli.github : cli.gitlab
            guard presence.available else { continue }
            if let login = presence.login {
                let alreadyConnected = connections.contains {
                    $0.kind == kind && $0.account.login.lowercased() == login.lowercased()
                }
                if alreadyConnected { continue }
            }
            result.append(kind)
        }
        return result
    }

    private func cliLogin(_ kind: String) -> String? {
        guard let cli = cliStatus else { return nil }
        return kind == "github" ? cli.github.login : cli.gitlab.login
    }

    private func cliName(_ kind: String) -> String { kind == "github" ? "gh" : "glab" }
    private func defaultHost(_ kind: String) -> String { kind == "gitlab" ? "gitlab.com" : "github.com" }

    private func load() async {
        if connections.isEmpty { loading = true }
        defer { loading = false }
        do {
            let r = try await broker.api.listForges()
            guard !Task.isCancelled else { return }
            connections = r.connections
            cliStatus = r.cli
            error = nil
        } catch {
            guard !Task.isCancelled else { return }
            self.error = "Couldn't load connections"
        }
    }
}

// MARK: - AddForgeSheet

private struct AddForgeSheet: View {
    let broker: BrokerSession
    let presetKind: String?
    var onDone: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var kind: String = "github"
    @State private var token = ""
    @State private var hostUrl = ""
    @State private var transport = "https"
    @State private var showAdvanced = false
    @State private var submitting = false
    @State private var error: String?
    @State private var cliStatus: ForgeCliStatus?

    private let kinds = ["github", "gitlab"]

    var body: some View {
        NavigationStack {
            List {
                // Kind picker
                Section {
                    Picker("Provider", selection: $kind) {
                        ForEach(kinds, id: \.self) { k in
                            HStack(spacing: 6) {
                                ForgeLogo(kind: k, size: 16)
                                Text(forgeDisplayName(k))
                            }
                            .tag(k)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                }

                // CLI import (if available for selected kind and not already connected)
                if canImportCli {
                    Section {
                        Button {
                            submitting = true
                            Task {
                                _ = await broker.importForge(kind: kind, transport: transport)
                                dismiss(); onDone()
                            }
                        } label: {
                            HStack(spacing: 10) {
                                ForgeLogo(kind: kind, size: 20)
                                Text("Import token from \(cliName)\(cliLoginLabel)")
                                    .font(.subheadline.weight(.medium))
                                Spacer()
                                if submitting { ProgressView().tint(Theme.teal) }
                            }
                        }
                        .foregroundStyle(.primary)
                        .disabled(submitting)
                    }

                    Section {
                        Text("— or paste a token —")
                            .font(.caption).foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .listRowBackground(Color.clear)
                    }
                }

                // PAT field
                Section {
                    SecureField(kind == "github" ? "github_pat_…" : "glpat-…", text: $token)
                        .autocorrectionDisabled()
                        .smNoAutocapitalization()
                        .font(.system(.subheadline, design: .monospaced))
                } header: {
                    Text("Personal access token")
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(.red)
                    } else {
                        Text("Needs scopes: \(scopesHint)")
                    }
                }

                // Self-hosted & transport disclosure
                Section {
                    DisclosureGroup("Self-hosted & transport", isExpanded: $showAdvanced) {
                        TextField("API base URL — e.g. github.acme.com/api/v3", text: $hostUrl)
                            .autocorrectionDisabled()
                            .smNoAutocapitalization()
                            .font(.system(.subheadline, design: .monospaced))

                        Picker("Transport", selection: $transport) {
                            Text("HTTPS").tag("https")
                            Text("SSH").tag("ssh")
                        }
                        .pickerStyle(.segmented)
                        .padding(.vertical, 4)

                        if transport == "ssh" && kind == "gitlab" {
                            Text("SSH for GitLab is experimental.")
                                .font(.caption2).foregroundStyle(.yellow)
                        }
                    }
                }

                // Connect button
                Section {
                    Button(action: connect) {
                        HStack {
                            Spacer()
                            if submitting {
                                ProgressView().tint(.white)
                            } else {
                                Text("Connect \(forgeDisplayName(kind))").fontWeight(.semibold)
                            }
                            Spacer()
                        }
                        .padding(.vertical, 4)
                        .foregroundStyle(.white)
                    }
                    .listRowBackground(canConnect ? Theme.teal : Color.gray.opacity(0.4))
                    .disabled(!canConnect || submitting)
                }
            }
            .navigationTitle("Add a Git account")
            .smInlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .smTopLeading) {
                    Button("Cancel") { dismiss() }.disabled(submitting)
                }
            }
        }
        .tint(Theme.teal)
        .smPresentationDetents([.medium, .large])
        #if os(macOS)
        .frame(minWidth: 620, minHeight: 540)
        #endif
        .onAppear {
            if let preset = presetKind, kinds.contains(preset) { kind = preset }
            Task { cliStatus = try? await broker.api.listForges().cli }
        }
    }

    // MARK: Computed

    private var canConnect: Bool { !token.trimmingCharacters(in: .whitespaces).isEmpty }

    private var canImportCli: Bool {
        guard let cli = cliStatus else { return false }
        let presence = kind == "github" ? cli.github : cli.gitlab
        return presence.available
    }

    private var cliName: String { kind == "github" ? "gh" : "glab" }

    private var cliLoginLabel: String {
        guard let cli = cliStatus else { return "" }
        let presence = kind == "github" ? cli.github : cli.gitlab
        guard let login = presence.login else { return "" }
        return " (@\(login))"
    }

    private var scopesHint: String {
        if kind == "github" {
            let host = extractHost(hostUrl)
            return (!host.isEmpty && host != "github.com") ? "repo, read:org" : "Contents + Administration (read & write)"
        }
        return "api"
    }

    private func extractHost(_ url: String) -> String {
        url.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "^https?://", with: "", options: .regularExpression)
            .components(separatedBy: "/").first ?? ""
    }

    // MARK: Actions

    private func connect() {
        let t = token.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        submitting = true; error = nil
        let h = hostUrl.trimmingCharacters(in: .whitespaces)
        Task {
            if let _ = await broker.addForge(kind: kind, token: t, host: h.isEmpty ? nil : h, transport: transport) {
                dismiss(); onDone()
            } else {
                error = "Couldn't connect — check your token and try again."
            }
            submitting = false
        }
    }
}

private func forgeDisplayName(_ kind: String) -> String {
    kind == "gitlab" ? "GitLab" : "GitHub"
}
