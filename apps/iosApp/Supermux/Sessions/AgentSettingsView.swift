import SwiftUI
import Shared

/// Agents settings — parity with the web `AgentLoginPanel.vue` + `OpenCodeProviderAuth.vue`.
///
/// One expandable row per detected agent (claude / codex / cursor / opencode). For the
/// CLI-login agents (claude/codex/cursor) a row offers:
///   • "Authorize via link" → `startAgentLogin`, then a polling Task that calls
///     `agentLoginState` every ~1.5s until the phase is terminal. While active it shows
///     the auth URL (Open + Copy), the device `code` if present, a paste-`code` field
///     (`sendAgentLoginCode`) when `needsCode`, and Cancel (`cancelAgentLogin`).
///   • an API-key / OAuth-token SecureField that saves via `saveConfig(...)`.
/// The opencode row expands to a per-provider sub-list (`openCodeProviders`) with an
/// API-key field (`setOpenCodeKey`) and, for an oauth method, a browser login
/// (`startOpenCodeOAuth` → open URL) + paste-code finish (`finishOpenCodeOAuth`).
///
/// Pushed inside the app's NavigationStack, so this view owns no NavigationStack — it
/// just sets `.navigationTitle("Agents")`.
struct AgentSettingsView: View {
    let broker: BrokerSession
    var showsNavigationTitle = true
    var onReadinessChanged: ((Bool) -> Void)?

    @State private var statuses: [AgentInstallStatus] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        Group {
            if loading && statuses.isEmpty {
                ProgressView("Loading…")
                    .tint(Theme.teal)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                list
            }
        }
        .navigationTitle(showsNavigationTitle ? "Agents" : "")
        .tint(Theme.teal)
        .task { await load() }
    }

    private var list: some View {
        List {
            Section {
                ForEach(statuses, id: \.kind) { status in
                    AgentRow(broker: broker, status: status, onAuthChanged: { Task { await load() } })
                }
            } footer: {
                Text("Manage CLI authorization and API-key fallback for each agent.")
            }

            if let error {
                Section {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        let result = await broker.agentStatuses()
        statuses = result
        onReadinessChanged?(Self.canProceed(with: result))
        // `agentStatuses()` swallows errors (returns []). Only surface "couldn't load"
        // when we have nothing to show, so a transient empty refresh after a successful
        // login doesn't flash an error over real rows.
        error = result.isEmpty ? "Couldn't load agent statuses." : nil
    }

    static func canProceed(with statuses: [AgentInstallStatus]) -> Bool {
        statuses.contains { $0.authed || ($0.kind == "opencode" && $0.installed) }
    }
}

// MARK: - Per-agent row

/// Expandable row for a single agent. Self-contained: owns its disclosure state, the
/// API-key field, and (for CLI-login agents) the active login state + polling Task.
private struct AgentRow: View {
    let broker: BrokerSession
    let status: AgentInstallStatus
    /// Called after a successful auth so the parent can refresh statuses.
    let onAuthChanged: () -> Void

    /// Agents whose auth uses the device-code / browser link flow.
    private static let loginKinds: Set<String> = ["claude", "codex", "cursor"]

    @State private var expanded: Bool
    /// True from "Start authorization" until the flow ends (terminal phase / cancel /
    /// row teardown). Drives the login-vs-key UI without us constructing a shared type.
    @State private var loginActive = false
    /// Latest polled state. Nil while `loginActive` but before the first poll returns —
    /// that window renders the "generating link" spinner.
    @State private var login: AgentLoginState?
    @State private var pollTask: Task<Void, Never>?
    @State private var keyValue = ""
    @State private var saving = false
    @State private var codeValue = ""
    @State private var install: AgentInstallJob?
    @State private var installTask: Task<Void, Never>?
    @State private var installRequestFailed = false

    init(broker: BrokerSession, status: AgentInstallStatus, onAuthChanged: @escaping () -> Void) {
        self.broker = broker
        self.status = status
        self.onAuthChanged = onAuthChanged
        // Un-authed agents start expanded so setup is obvious; authed ones collapse.
        _expanded = State(initialValue: !status.authed)
    }

    private var isLoginKind: Bool { Self.loginKinds.contains(status.kind) }

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: 14) {
                if !status.installed {
                    installSection
                } else if status.kind == "opencode" {
                    OpenCodeProvidersSection(broker: broker)
                } else if loginActive {
                    loginFlow()
                } else {
                    apiKeyField
                    if status.installed && isLoginKind { linkLoginButton }
                }
            }
            .padding(.vertical, 6)
        } label: {
            header
        }
        // Tear down any live poll when the row leaves the hierarchy.
        .onDisappear {
            stopPoll()
            stopInstallPoll()
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: status.authed ? "checkmark.seal.fill" : iconName)
                .font(.title3)
                .foregroundStyle(status.authed ? Theme.teal : .secondary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(status.kind.capitalized).font(.body)
                Text(statusLabel).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if status.authed {
                Text("Ready").font(.caption.weight(.medium)).foregroundStyle(Theme.teal)
            }
        }
    }

    private var iconName: String {
        status.installed ? "terminal" : "exclamationmark.triangle"
    }

    private var statusLabel: String {
        if status.authed { return "Authenticated" }
        if !status.installed { return "Not installed" }
        // opencode's free `opencode/*` tier works with no credentials.
        if status.kind == "opencode" { return "Ready · free tier" }
        return "Installed, not authenticated"
    }

    // MARK: Agent installation

    private var installSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Install the \(status.kind.capitalized) CLI on this Mac to use it with Supermux.")
                .font(.caption)
                .foregroundStyle(.secondary)

            if install?.state == "running" {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small).tint(Theme.teal)
                    Text("Installing \(status.kind)…").font(.callout)
                }
            } else {
                Button(install?.state == "failed" ? "Retry installation" : "Install") {
                    startInstall()
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
            }

            if install?.state == "failed" || installRequestFailed {
                Label(installRequestFailed ? "Couldn't start installation." : "Installation failed.",
                      systemImage: "xmark.octagon")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            if let log = install?.log, !log.isEmpty {
                ScrollView {
                    Text(String(log.suffix(2_000)))
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 110)
                .padding(8)
                .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    private func startInstall() {
        stopInstallPoll()
        installRequestFailed = false
        installTask = Task { [broker] in
            guard let initial = await broker.startAgentInstall(kind: status.kind) else {
                installRequestFailed = true
                return
            }
            install = initial
            while !Task.isCancelled, install?.state == "running" {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                guard let next = await broker.agentInstallState(kind: status.kind) else { continue }
                install = next
                if next.state == "done" {
                    onAuthChanged()
                    return
                }
                if next.state == "failed" { return }
            }
        }
    }

    private func stopInstallPoll() {
        installTask?.cancel()
        installTask = nil
    }

    // MARK: API key / OAuth token

    private var apiKeyField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(status.kind == "claude" ? "Paste OAuth token" : "Paste API key",
                  systemImage: "key")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)

            Text(helpText).font(.caption2).foregroundStyle(.secondary)

            if status.kind == "claude" {
                CopyableCommand(command: "claude setup-token")
            }

            HStack(spacing: 8) {
                SecureField(status.kind == "claude" ? "oauth_token_…" : "sk-…", text: $keyValue)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .smNoAutocapitalization()
                    .font(.callout.monospaced())
                Button {
                    saveKey()
                } label: {
                    Text(saving ? "Saving…" : "Save")
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .disabled(trimmedKey.isEmpty || saving)
            }
        }
    }

    private var helpText: String {
        switch status.kind {
        case "claude": return "On a machine with a browser, run this and paste the token it prints:"
        case "codex": return "Paste your OpenAI API key."
        case "cursor": return "Paste your Cursor API key."
        default: return "Paste an API key."
        }
    }

    private var trimmedKey: String { keyValue.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func saveKey() {
        let value = trimmedKey
        guard !value.isEmpty else { return }
        saving = true
        Task {
            defer { saving = false }
            // Pass only the single field matching this agent (mirrors fieldByKind).
            switch status.kind {
            case "claude": await broker.saveConfig(claudeOauthToken: value)
            case "codex": await broker.saveConfig(codexApiKey: value)
            case "cursor": await broker.saveConfig(cursorApiKey: value)
            default: break
            }
            keyValue = ""
            onAuthChanged()
        }
    }

    // MARK: Link / device-code login

    private var linkLoginButton: some View {
        VStack(alignment: .leading, spacing: 8) {
            Divider()
            Label("Authorize via link", systemImage: "link")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            if status.kind == "codex" {
                Text("Requires \"Allow device code login\" enabled in ChatGPT → Settings → Security.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Button("Start authorization") { startLogin() }
                .buttonStyle(.bordered)
                .tint(Theme.teal)
        }
    }

    @ViewBuilder
    private func loginFlow() -> some View {
        if let state = login {
            switch state.phase {
            case "success":
                Label("Authorized successfully.", systemImage: "checkmark.circle.fill")
                    .font(.callout).foregroundStyle(Theme.teal)
            case "failed":
                VStack(alignment: .leading, spacing: 10) {
                    Label("Login failed: \(state.error ?? "unknown error")", systemImage: "xmark.octagon")
                        .font(.callout).foregroundStyle(.red)
                    Button("Retry authorization") { startLogin() }
                        .buttonStyle(.bordered)
                        .tint(Theme.teal)
                }
            case "cancelled":
                VStack(alignment: .leading, spacing: 10) {
                    Text("Login cancelled.").font(.callout).foregroundStyle(.secondary)
                    Button("Start authorization again") { startLogin() }
                        .buttonStyle(.bordered)
                        .tint(Theme.teal)
                }
            default:
                // "starting" / "awaiting_user" — progress, then the link/code once present.
                awaitingUser(state)
            }
        } else {
            // Login kicked off; first poll hasn't returned yet.
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    ProgressView().tint(Theme.teal)
                    Text("Generating sign-in link — this can take a few seconds…")
                        .font(.caption).foregroundStyle(.secondary)
                }
                cancelButton
            }
        }
    }

    @ViewBuilder
    private func awaitingUser(_ state: AgentLoginState) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if let urlString = state.url, let url = URL(string: urlString) {
                Text("Open this link to authorize.").font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    Link(destination: url) {
                        Label("Open sign-in page", systemImage: "arrow.up.right.square")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.teal)
                    Button {
                        SMPasteboard.set(urlString)
                    } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.teal)
                }
                if let code = state.code {
                    HStack(spacing: 6) {
                        Text("Enter code:").font(.caption).foregroundStyle(.secondary)
                        Text(code).font(.title3.monospaced().weight(.bold))
                            .textSelection(.enabled)
                    }
                }
                Label("Waiting for authorization…", systemImage: "clock")
                    .font(.caption2).foregroundStyle(.secondary)
                if state.needsCode {
                    Text("After authorizing, paste the code from the browser here:")
                        .font(.caption2).foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        TextField("paste code", text: $codeValue)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                            .smNoAutocapitalization()
                            .font(.callout.monospaced())
                        Button("Submit") { submitCode() }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.teal)
                            .disabled(codeValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            } else {
                HStack(spacing: 8) {
                    ProgressView().tint(Theme.teal)
                    Text("Generating sign-in link — this can take a few seconds…")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            cancelButton
        }
    }

    private var cancelButton: some View {
        Button(role: .cancel) { cancelLogin() } label: {
            Label("Cancel", systemImage: "xmark")
        }
        .buttonStyle(.bordered)
        .tint(.secondary)
    }

    // MARK: Poll lifecycle

    private func startLogin() {
        // Flip to the login UI immediately; `login` stays nil until the first poll lands,
        // which renders the "generating link" spinner (we never construct the shared type).
        loginActive = true
        login = nil
        codeValue = ""
        stopPoll()
        let kind = status.kind
        pollTask = Task { [broker] in
            _ = await broker.startAgentLogin(kind: kind)
            // Poll until a terminal phase or the Task is cancelled (row gone / user cancel).
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if Task.isCancelled { return }
                guard let state = await broker.agentLoginState(kind: kind) else { continue }
                if Task.isCancelled { return }
                await MainActor.run { self.login = state }
                switch state.phase {
                case "success":
                    await MainActor.run { self.onAuthChanged() }
                    return
                case "failed", "cancelled":
                    return
                default:
                    continue
                }
            }
        }
    }

    private func submitCode() {
        let code = codeValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }
        broker.sendAgentLoginCode(kind: status.kind, code: code)
        codeValue = ""
    }

    private func cancelLogin() {
        stopPoll()
        broker.cancelAgentLogin(kind: status.kind)
        loginActive = false
        login = nil
        codeValue = ""
    }

    private func stopPoll() {
        pollTask?.cancel()
        pollTask = nil
    }
}

// MARK: - opencode providers

/// Provider sub-list for the opencode agent. Mirrors `OpenCodeProviderAuth.vue`:
/// per provider an API-key field (`setOpenCodeKey`) and, for an oauth method, a browser
/// login (`startOpenCodeOAuth`) followed by a paste-code finish (`finishOpenCodeOAuth`).
private struct OpenCodeProvidersSection: View {
    let broker: BrokerSession

    @State private var providers: [OpenCodeProvider] = []
    @State private var loading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Connect a provider")
                    .font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Spacer()
                Button { Task { await load() } } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .tint(Theme.teal)
                .disabled(loading)
            }
            Text("Free models work out of the box — connect a subscription for more.")
                .font(.caption2).foregroundStyle(.secondary)

            if loading && providers.isEmpty {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity)
            } else {
                // The OpenCode key (Zen + Go) isn't in /provider/auth — it's the built-in
                // free tier + a key from opencode.ai/auth. Surface one explicit row (the
                // backend pairs the key onto both opencode + opencode-go) when the
                // providers list doesn't already include it. Mirrors web `visible`.
                if !providers.contains(where: { $0.id == "opencode" }) {
                    OpenCodeZenKeyRow(broker: broker, onChanged: { Task { await load() } })
                }
                ForEach(providers, id: \.id) { provider in
                    OpenCodeProviderRow(broker: broker, provider: provider,
                                        onChanged: { Task { await load() } })
                }
                if providers.isEmpty {
                    Text("No additional providers available.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        // Anthropic + OpenAI are covered by the claude/codex agents above — hide them
        // here to avoid duplicate auth surfaces (parity with web HIDDEN set).
        let hidden: Set<String> = ["anthropic", "openai"]
        providers = await broker.openCodeProviders().filter { !hidden.contains($0.id) }
    }
}

/// The explicit "OpenCode key (Zen + Go)" row. Not backed by an `OpenCodeProvider`
/// (Zen isn't in /provider/auth); saving posts the key under providerId "opencode",
/// which the backend also mirrors onto "opencode-go".
private struct OpenCodeZenKeyRow: View {
    let broker: BrokerSession
    let onChanged: () -> Void

    @Environment(\.openURL) private var openURL
    @State private var keyValue = ""
    @State private var saving = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("OpenCode").font(.callout.weight(.medium))
            Button {
                if let url = URL(string: "https://opencode.ai/auth") { openURL(url) }
            } label: {
                Label("Get a key at opencode.ai/auth", systemImage: "arrow.up.right")
            }
            .font(.caption2).buttonStyle(.borderless).tint(Theme.teal)
            HStack(spacing: 8) {
                SecureField("OpenCode key (Zen + Go)", text: $keyValue)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .smNoAutocapitalization()
                    .font(.callout.monospaced())
                Button(saving ? "…" : "Save") { saveKey() }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.teal)
                    .disabled(keyValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || saving)
            }
        }
        .padding(10)
        .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 10))
    }

    private func saveKey() {
        let key = keyValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return }
        saving = true
        broker.setOpenCodeKey(providerId: "opencode", key: key)
        keyValue = ""
        Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            saving = false
            onChanged()
        }
    }
}

private struct OpenCodeProviderRow: View {
    let broker: BrokerSession
    let provider: OpenCodeProvider
    let onChanged: () -> Void

    @Environment(\.openURL) private var openURL
    @State private var keyValue = ""
    @State private var saving = false
    @State private var oauthURL: URL?
    @State private var oauthMethodIndex: Int?
    @State private var oauthCode = ""
    @State private var finishing = false

    private var oauthMethod: OpenCodeAuthMethod? { provider.methods.first { $0.type == "oauth" } }
    private var apiMethod: OpenCodeAuthMethod? { provider.methods.first { $0.type == "api" } }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(prettyName).font(.callout.weight(.medium))
                if provider.configured {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.caption).foregroundStyle(Theme.teal)
                }
            }

            if let oauthURL {
                // Browser login in progress — reopen + paste-code finish.
                Text("A browser tab opened — authorize, then paste the code:")
                    .font(.caption2).foregroundStyle(.secondary)
                Link(destination: oauthURL) {
                    Label("Reopen sign-in", systemImage: "arrow.up.right")
                }
                .font(.caption).tint(Theme.teal)
                HStack(spacing: 8) {
                    TextField("paste code", text: $oauthCode)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .smNoAutocapitalization()
                        .font(.callout.monospaced())
                    Button(finishing ? "…" : "Finish") { finishOAuth() }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.teal)
                        .disabled(oauthCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || finishing)
                }
            } else {
                if oauthMethod != nil {
                    Button { startOAuth() } label: {
                        Label("Login via browser", systemImage: "link")
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.teal)
                }
                if let apiMethod {
                    HStack(spacing: 8) {
                        SecureField(apiMethod.label.isEmpty ? "API key" : apiMethod.label, text: $keyValue)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                            .smNoAutocapitalization()
                            .font(.callout.monospaced())
                        Button(saving ? "…" : "Save") { saveKey() }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.teal)
                            .disabled(keyValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || saving)
                    }
                }
            }
        }
        .padding(10)
        .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 10))
    }

    private var prettyName: String {
        provider.id
            .split(whereSeparator: { $0 == "-" || $0 == "_" })
            .map { word -> String in
                String(word.prefix(1)).uppercased() + String(word.dropFirst())
            }
            .joined(separator: " ")
    }

    private func saveKey() {
        let key = keyValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return }
        saving = true
        broker.setOpenCodeKey(providerId: provider.id, key: key)
        keyValue = ""
        // `setOpenCodeKey` is fire-and-forget; give the broker a moment, then refresh
        // so the "configured" check reflects the new key.
        Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            saving = false
            onChanged()
        }
    }

    private func startOAuth() {
        guard let method = oauthMethod else { return }
        oauthCode = ""
        Task { [broker] in
            guard let start = await broker.startOpenCodeOAuth(providerId: provider.id, method: Int(method.index)),
                  let url = URL(string: start.url) else { return }
            await MainActor.run {
                self.oauthMethodIndex = Int(method.index)
                self.oauthURL = url
                openURL(url)
            }
        }
    }

    private func finishOAuth() {
        let code = oauthCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty, let methodIndex = oauthMethodIndex else { return }
        finishing = true
        broker.finishOpenCodeOAuth(providerId: provider.id, method: methodIndex, code: code)
        Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            finishing = false
            oauthURL = nil
            oauthMethodIndex = nil
            oauthCode = ""
            onChanged()
        }
    }
}

// MARK: - Helpers

/// A monospaced command line with a copy button (used for `claude setup-token`).
private struct CopyableCommand: View {
    let command: String
    @State private var copied = false

    var body: some View {
        HStack(spacing: 8) {
            Text(command)
                .font(.caption.monospaced())
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 8))
            Button {
                SMPasteboard.set(command)
                copied = true
            } label: {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
            }
            .buttonStyle(.bordered)
            .tint(Theme.teal)
        }
    }
}
