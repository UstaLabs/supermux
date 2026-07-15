import SwiftUI
import Shared

/// Agents settings — parity with the web `AgentLoginPanel.vue` + `OpenCodeProviderAuth.vue`.
///
/// One row per detected agent (claude / codex / cursor / opencode). Link authorization
/// is the primary action for the CLI-login agents (claude/codex/cursor): it calls
/// `startAgentLogin`, then a polling Task calls
///     `agentLoginState` every ~1.5s until the phase is terminal. While active it shows
///     the auth URL (Open + Copy), the device `code` if present, a paste-`code` field
///     (`sendAgentLoginCode`) when `needsCode`, and Cancel (`cancelAgentLogin`).
/// API-key / OAuth-token entry lives under "Other ways to authorize". OpenCode's
/// primary action first explains its key handoff; additional providers live under
/// "Other providers" and support verified API-key saves or browser authorization that
/// preserves the provider's instructions and callback style.
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
                Text("Authorize via link, or use a key when needed.")
            }

            if let error {
                Section {
                    HStack {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                        Spacer()
                        Button("Retry") { Task { await load() } }
                            .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    private func load() async {
        if statuses.isEmpty { loading = true }
        defer { loading = false }
        let response = await broker.loadAgentStatuses()
        guard !Task.isCancelled else { return }
        guard let result = response else {
            error = "Couldn't reach the host."
            return
        }
        statuses = result
        error = nil
        onReadinessChanged?(Self.canProceed(with: result))
        if result.isEmpty { error = "The host returned no coding agents." }
    }

    static func canProceed(with statuses: [AgentInstallStatus]) -> Bool {
        statuses.contains { $0.authed || ($0.kind == "opencode" && $0.installed) }
    }
}

// MARK: - Per-agent row

/// Self-contained agent row: primary authorization, optional fallback disclosures,
/// and the active login state + polling Task.
private struct AgentRow: View {
    let broker: BrokerSession
    let status: AgentInstallStatus
    /// Called after a successful auth so the parent can refresh statuses.
    let onAuthChanged: () -> Void

    @Environment(\.openURL) private var openURL

    /// Agents whose auth uses the device-code / browser link flow.
    private static let loginKinds: Set<String> = ["claude", "codex", "cursor"]

    @State private var otherWaysExpanded = false
    @State private var otherProvidersExpanded = false
    @State private var openCodeAuthActive = false
    @State private var openCodeSaved = false
    @State private var openCodeError: String?
    /// True from "Start authorization" until the flow ends (terminal phase / cancel /
    /// row teardown). Drives the login-vs-key UI without us constructing a shared type.
    @State private var loginActive = false
    /// Latest polled state. Nil while `loginActive` but before the first poll returns —
    /// that window renders the "generating link" spinner.
    @State private var login: AgentLoginState?
    @State private var pollTask: Task<Void, Never>?
    @State private var keyValue = ""
    @State private var saving = false
    @State private var credentialSaved = false
    @State private var credentialError: String?
    @State private var codeValue = ""
    @State private var install: AgentInstallJob?
    @State private var installTask: Task<Void, Never>?
    @State private var installRequestFailed = false

    private var isLoginKind: Bool { Self.loginKinds.contains(status.kind) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if !status.installed {
                installSection
            } else if status.kind == "opencode" {
                if openCodeAuthActive {
                    openCodePrimaryAuth
                }
                expansionButton(
                    title: "Other providers",
                    isExpanded: otherProvidersExpanded,
                    accessibilityID: "agent_other_providers"
                ) {
                    otherProvidersExpanded.toggle()
                }
                if otherProvidersExpanded {
                    OpenCodeProvidersSection(broker: broker)
                        .padding(.top, 8)
                }
            } else {
                if loginActive {
                    loginFlow()
                }
                if status.kind == "codex" && !loginActive {
                    Text("Device-code login must be enabled in ChatGPT → Settings → Security.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                expansionButton(
                    title: "Other ways to authorize",
                    isExpanded: otherWaysExpanded,
                    accessibilityID: "agent_other_ways_\(status.kind)"
                ) {
                    otherWaysExpanded.toggle()
                }
                if otherWaysExpanded {
                    apiKeyField
                        .padding(.top, 8)
                }
            }
        }
        .padding(.vertical, 6)
        // Tear down any live poll when the row leaves the hierarchy.
        .onDisappear {
            stopPoll()
            stopInstallPoll()
        }
    }

    private func expansionButton(
        title: String,
        isExpanded: Bool,
        accessibilityID: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .rotationEffect(.degrees(isExpanded ? 90 : 0))
                Text(title)
                    .font(.caption.weight(.medium))
                Spacer()
            }
            .foregroundStyle(.secondary)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityID)
        .accessibilityValue(isExpanded ? "Expanded" : "Collapsed")
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            AgentLogo(agent: status.kind, size: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(displayName).font(.body.weight(.medium))
                Text(statusLabel).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if status.authed {
                Label("Ready", systemImage: "checkmark.circle.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Theme.teal)
            }
            if status.installed && (isLoginKind || status.kind == "opencode") {
                Button(primaryActionTitle) {
                    if status.kind == "opencode" {
                        startOpenCodeAuth()
                    } else {
                        startLogin()
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .controlSize(.small)
                .disabled(loginActive)
            }
        }
    }

    private var displayName: String {
        status.kind == "opencode" ? "OpenCode" : status.kind.capitalized
    }

    private var primaryActionTitle: String {
        if loginActive { return "Authorizing…" }
        return status.authed ? "Reauthorize" : "Authorize"
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
            if credentialSaved {
                Label("Credential saved and verified.", systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(Theme.teal)
            }
            if let credentialError {
                Label(credentialError, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.red)
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
        credentialSaved = false
        credentialError = nil
        Task {
            defer { saving = false }
            if await broker.saveAgentCredential(kind: status.kind, value: value) {
                keyValue = ""
                credentialSaved = true
                onAuthChanged()
            } else {
                credentialError = "Couldn't verify this credential. Check it and try again."
            }
        }
    }

    // MARK: Link / device-code login

    private var openCodePrimaryAuth: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Connect OpenCode Zen and Go", systemImage: "key.horizontal")
                .font(.callout.weight(.semibold))
            Text("1. Open OpenCode and create or copy an API key.\n2. Return to Supermux, paste the key below, and save it.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                if let url = URL(string: "https://opencode.ai/auth") { openURL(url) }
            } label: {
                Label("Open OpenCode", systemImage: "arrow.up.right.square")
            }
            .buttonStyle(.bordered)
            .tint(Theme.teal)

            HStack(spacing: 8) {
                SecureField("OpenCode key (Zen + Go)", text: $keyValue)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .smNoAutocapitalization()
                    .font(.callout.monospaced())
                Button(saving ? "Saving…" : "Save") { saveOpenCodeKey() }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.teal)
                    .disabled(trimmedKey.isEmpty || saving)
            }
            if openCodeSaved {
                Label("OpenCode key saved.", systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(Theme.teal)
            }
            if let openCodeError {
                Label(openCodeError, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .padding(12)
        .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 10))
    }

    private func startOpenCodeAuth() {
        // Keep the user in Supermux long enough to read the handoff. The inline panel owns
        // an explicit "Open OpenCode" button instead of immediately stealing app focus.
        openCodeAuthActive = true
        openCodeSaved = false
        openCodeError = nil
    }

    private func saveOpenCodeKey() {
        let value = trimmedKey
        guard !value.isEmpty else { return }
        saving = true
        openCodeSaved = false
        openCodeError = nil
        Task {
            defer { saving = false }
            if await broker.saveOpenCodeKey(providerId: "opencode", key: value) {
                keyValue = ""
                openCodeSaved = true
                onAuthChanged()
            } else {
                openCodeError = "Couldn't save the OpenCode key. Check the host connection and retry."
            }
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

/// Provider sub-list for the opencode agent. Each provider supports verified API-key saves
/// and/or an OAuth handoff that presents its instructions before opening the browser.
private struct OpenCodeProvidersSection: View {
    let broker: BrokerSession

    @State private var providers: [OpenCodeProvider] = []
    @State private var loading = true
    @State private var error: String?

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
                ForEach(providers, id: \.id) { provider in
                    OpenCodeProviderRow(broker: broker, provider: provider,
                                        onChanged: { Task { await load() } })
                }
                if providers.isEmpty {
                    Text("No additional providers available.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
            if let error {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .task { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        // Anthropic/OpenAI are covered by their agent rows; OpenCode Zen + Go is the
        // primary authorization action above this disclosure.
        let hidden: Set<String> = ["anthropic", "openai", "opencode", "opencode-go"]
        let response = await broker.loadOpenCodeProviders()
        guard !Task.isCancelled else { return }
        guard let loaded = response else {
            error = "Couldn't load providers from the host."
            return
        }
        providers = loaded.filter { !hidden.contains($0.id) }
        error = nil
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
    @State private var oauthCallbackMethod = "code"
    @State private var oauthInstructions: String?
    @State private var oauthOpened = false
    @State private var oauthError: String?
    @State private var startingOAuth = false
    @State private var oauthCode = ""
    @State private var finishing = false
    @State private var saveError: String?

    private var oauthMethod: OpenCodeAuthMethod? { provider.methods.first { $0.type == "oauth" } }
    private var apiMethod: OpenCodeAuthMethod? { provider.methods.first { $0.type == "api" } }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                ProviderLogo(provider: provider.id, size: 24)
                Text(prettyName).font(.callout.weight(.medium))
                if provider.configured {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.caption).foregroundStyle(Theme.teal)
                }
            }

            if let oauthURL {
                // Show the provider's device code / handoff guidance before leaving Supermux.
                Label("Before you open the browser", systemImage: "info.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.teal)
                Text(oauthInstructions?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                     ?? "Authorize Supermux on the provider's sign-in page, then return here.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    openURL(oauthURL)
                    oauthOpened = true
                } label: {
                    Label(oauthOpened ? "Reopen sign-in page" : "Open sign-in page",
                          systemImage: "arrow.up.right.square")
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)

                if oauthCallbackMethod == "auto" {
                    Text("After approving in the browser, return here to complete the connection.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Button(finishing ? "Completing…" : "Complete authorization") {
                        finishOAuth(code: "authorized")
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.teal)
                    .disabled(!oauthOpened || finishing)
                } else {
                    Text("Paste the authorization code returned by the provider:")
                        .font(.caption2).foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        TextField("paste code", text: $oauthCode)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                            .smNoAutocapitalization()
                            .font(.callout.monospaced())
                        Button(finishing ? "…" : "Finish") { finishOAuth(code: oauthCode) }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.teal)
                            .disabled(oauthCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || finishing)
                    }
                }
                if let oauthError {
                    Label(oauthError, systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            } else {
                if oauthMethod != nil {
                    Button { startOAuth() } label: {
                        Label(startingOAuth ? "Preparing sign-in…" : "Login via browser", systemImage: "link")
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.teal)
                    .disabled(startingOAuth)
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
                if let saveError {
                    Label(saveError, systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        }
        .padding(10)
        .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 10))
    }

    private var prettyName: String {
        switch provider.id.lowercased() {
        case "github-copilot": return "GitHub Copilot"
        case "gitlab": return "GitLab"
        case "xai": return "xAI"
        case "digitalocean": return "DigitalOcean"
        case "cloudflare-workers-ai": return "Cloudflare Workers AI"
        case "cloudflare-ai-gateway": return "Cloudflare AI Gateway"
        case "snowflake-cortex": return "Snowflake Cortex"
        case "opencode-go": return "OpenCode Go"
        default: break
        }
        return provider.id
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
        saveError = nil
        Task {
            defer { saving = false }
            if await broker.saveOpenCodeKey(providerId: provider.id, key: key) {
                keyValue = ""
                onChanged()
            } else {
                saveError = "Couldn't save this provider key."
            }
        }
    }

    private func startOAuth() {
        guard let method = oauthMethod else { return }
        oauthCode = ""
        oauthError = nil
        oauthOpened = false
        startingOAuth = true
        Task { [broker] in
            defer { startingOAuth = false }
            guard let start = await broker.startOpenCodeOAuth(providerId: provider.id, method: Int(method.index)),
                  let url = URL(string: start.url) else {
                oauthError = "Couldn't prepare provider authorization."
                return
            }
            await MainActor.run {
                self.oauthMethodIndex = Int(method.index)
                self.oauthURL = url
                self.oauthCallbackMethod = start.method
                self.oauthInstructions = start.instructions
            }
        }
    }

    private func finishOAuth(code rawCode: String) {
        let code = rawCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty, let methodIndex = oauthMethodIndex else { return }
        finishing = true
        oauthError = nil
        Task {
            defer { finishing = false }
            if await broker.finishOpenCodeAuthorization(providerId: provider.id, method: methodIndex, code: code) {
                oauthURL = nil
                oauthMethodIndex = nil
                oauthInstructions = nil
                oauthCode = ""
                oauthOpened = false
                onChanged()
            } else {
                oauthError = "Authorization didn't complete. Confirm it in the browser and try again."
            }
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

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
