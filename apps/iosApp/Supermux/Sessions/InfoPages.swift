import SwiftUI
import Shared
import CoreImage

// Relative "last seen" + ISO parsing shared by the device/usage pages.
func parseISODate(_ s: String) -> Date? {
    let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f.date(from: s) ?? ISO8601DateFormatter().date(from: s)
}
func relTime(_ ts: String?) -> String {
    guard let ts, let d = parseISODate(ts) else { return "never" }
    let diff = -d.timeIntervalSinceNow
    if diff < 60 { return "just now" }
    if diff < 3600 { return "\(Int(diff / 60))m ago" }
    if diff < 86400 { return "\(Int(diff / 3600))h ago" }
    return "\(Int(diff / 86400))d ago"
}
func archivedDate(_ ts: String) -> String {
    guard let d = parseISODate(ts) else { return String(ts.prefix(10)) }
    let f = DateFormatter(); f.dateFormat = "MMM d, yyyy"
    return f.string(from: d)
}

/// The quick read-only pages reachable from the list header ⋮ menu.
enum InfoSheet: String, Identifiable, CaseIterable {
    case archived, usage, proxies, displays, devices, settings
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
    var systemImage: String {
        switch self {
        case .archived: return "archivebox"
        case .usage: return "chart.bar"
        case .proxies: return "network"
        case .displays: return "display"
        case .devices: return "ipad.and.iphone"
        case .settings: return "gearshape"
        }
    }
    @ViewBuilder func view(broker: BrokerSession) -> some View {
        switch self {
        case .archived: ArchivedView(broker: broker)
        case .usage: UsageView(broker: broker)
        case .proxies: ProxiesView(broker: broker)
        case .displays: DisplaysView(broker: broker)
        case .devices: DevicesView(broker: broker)
        case .settings: SettingsView(broker: broker)
        }
    }
}

private struct Loadable<Content: View>: View {
    let title: String
    let loading: Bool
    let isEmpty: Bool
    @ViewBuilder let content: () -> Content
    var body: some View {
        Group {
            if loading {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if isEmpty {
                ContentUnavailableView("Nothing here", systemImage: "tray")
            } else {
                content()
            }
        }
        .navigationTitle(title)
    }
}

/// Paired devices — mint a one-time pairing link (＋) or revoke (swipe). Parity
/// with the web DevicesView.
struct DevicesView: View {
    let broker: BrokerSession
    @State private var items: [DeviceDto] = []
    @State private var loading = true
    @State private var adding = false
    @State private var revokeTarget: DeviceDto?

    var body: some View {
        Group {
            if loading && items.isEmpty {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if items.isEmpty {
                ContentUnavailableView("No devices paired", systemImage: "iphone",
                    description: Text("Tap ＋ to mint a one-time pairing link."))
            } else {
                List {
                    ForEach(items, id: \.name) { d in
                        HStack(spacing: 12) {
                            Image(systemName: "iphone").foregroundStyle(.secondary)
                                .frame(width: 34, height: 34)
                                .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 9))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(d.name).font(.subheadline.weight(.medium))
                                Text(subtitle(d)).font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 8)
                            Button("Revoke", role: .destructive) { revokeTarget = d }
                                .font(.subheadline).foregroundStyle(.red).buttonStyle(.borderless)
                        }
                        .swipeActions {
                            Button("Revoke", role: .destructive) { revokeTarget = d }
                        }
                    }
                }
            }
        }
        .navigationTitle("Devices").smInlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .smTopTrailing) {
                Button { adding = true } label: { Label("Add device", systemImage: "plus") }
            }
        }
        .sheet(isPresented: $adding) { AddDeviceSheet(broker: broker) { Task { await load() } } }
        .confirmationDialog("Revoke \(revokeTarget?.name ?? "")?",
            isPresented: Binding(get: { revokeTarget != nil }, set: { if !$0 { revokeTarget = nil } }),
            titleVisibility: .visible) {
            Button("Revoke", role: .destructive) {
                if let t = revokeTarget { Task { await broker.revokeDevice(t.name); await load() } }
            }
        } message: { Text("That device will disconnect immediately.") }
        .task { await load() }
    }
    private func load() async { loading = true; items = (try? await broker.api.devices()) ?? []; loading = false }
    private func subtitle(_ d: DeviceDto) -> String {
        var parts: [String] = []
        if let c = d.created_at { parts.append("paired \(String(c.prefix(10)))") }
        parts.append("last seen \(relTime(d.last_seen_at))")
        return parts.joined(separator: " · ")
    }
}

/// Mint-a-pairing-link dialog: name → one-time URL with QR + copy.
private struct AddDeviceSheet: View {
    let broker: BrokerSession
    var onDone: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url: String?
    @State private var minting = false
    @State private var copied = false

    var body: some View {
        NavigationStack {
            Group {
                if let url { minted(url) } else { entry }
            }
            .padding(20)
            .navigationTitle("Add device").smInlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .smTopTrailing) { Button("Done") { dismiss() } } }
        }
        .tint(Theme.teal)
        .smPresentationDetents([.medium, .large])
    }

    private var entry: some View {
        VStack(spacing: 14) {
            Text("Give the new device a name. You'll get a one-time link to open on it.")
                .font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            TextField("e.g. laptop, ipad, kitchen", text: $name)
                .textFieldStyle(.roundedBorder).autocorrectionDisabled().smNoAutocapitalization()
            Button {
                minting = true
                Task {
                    let r = await broker.addDevice(name.trimmingCharacters(in: .whitespaces))
                    url = r?.url; minting = false; onDone()
                }
            } label: {
                HStack { Spacer()
                    if minting { ProgressView().tint(.white) } else { Text("Mint token").fontWeight(.semibold) }
                    Spacer() }.padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent).tint(Theme.teal)
            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || minting)
            Spacer()
        }
    }

    private func minted(_ url: String) -> some View {
        VStack(spacing: 14) {
            Text("Open this link on the new device, or scan it:")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            if let img = qrImage(url) {
                Image(platform: img).interpolation(.none).resizable().scaledToFit()
                    .frame(width: 190, height: 190).padding(8)
                    .background(.white, in: RoundedRectangle(cornerRadius: 12))
            }
            Text(url).font(.caption.monospaced()).foregroundStyle(.secondary)
                .lineLimit(3).truncationMode(.middle).padding(10).frame(maxWidth: .infinity)
                .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 10))
            Button {
                SMPasteboard.set(url); copied = true
            } label: { Label(copied ? "Copied" : "Copy link", systemImage: copied ? "checkmark" : "doc.on.doc") }
                .buttonStyle(.bordered).tint(Theme.teal)
            Text("Treat this link like a password — anyone who opens it gets access until you revoke the device.")
                .font(.caption2).foregroundStyle(.tertiary).multilineTextAlignment(.center)
            Spacer()
        }
    }

    private func qrImage(_ s: String) -> PlatformImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(s.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return PlatformImage.sm(cgImage: cg)
    }
}

/// Proxies — each row toggles public/private (public asks for confirmation
/// first). Parity with the web ProxiesView + PublicProxyConfirmDialog.
struct ProxiesView: View {
    let broker: BrokerSession
    @State private var items: [ProxyDto] = []
    @State private var loading = true
    @State private var confirmPublic: ProxyDto?

    var body: some View {
        Group {
            if loading && items.isEmpty {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if items.isEmpty {
                ContentUnavailableView("No active proxies", systemImage: "network",
                    description: Text("Expose a local port from any session to the web."))
            } else {
                List {
                    ForEach(items, id: \.domain) { p in
                        HStack(spacing: 12) {
                            Image(systemName: "network").foregroundStyle(.secondary)
                                .frame(width: 34, height: 34)
                                .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 9))
                            VStack(alignment: .leading, spacing: 2) {
                                if let u = URL(string: proxyUrl(proxy: p)) {
                                    Link(destination: u) {
                                        HStack(spacing: 4) {
                                            Text(proxyDisplayUrl(proxy: p)).lineLimit(1)
                                            Image(systemName: "arrow.up.right").font(.caption2)
                                        }
                                    }.font(.subheadline.weight(.medium)).tint(Theme.teal)
                                } else {
                                    Text(proxyDisplayUrl(proxy: p)).font(.subheadline.weight(.medium)).lineLimit(1)
                                }
                                Text("\(p.sessionName) · :\(p.port)").font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 8)
                            VStack(alignment: .trailing, spacing: 2) {
                                Toggle("", isOn: Binding(get: { p.isPublic }, set: { onToggle(p, $0) }))
                                    .labelsHidden().tint(Theme.teal)
                                Text(p.isPublic ? "public" : "private").font(.caption2)
                                    .foregroundStyle(p.isPublic ? Theme.teal : .secondary)
                            }
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) {
                                Task { await broker.removeProxy(p.domain); await load() }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Proxies").smInlineNavigationTitle()
        .alert("Make proxy public?",
            isPresented: Binding(get: { confirmPublic != nil }, set: { if !$0 { confirmPublic = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Make public") {
                if let p = confirmPublic { Task { await apply(p.domain, true) } }
            }
        } message: {
            Text("Anyone with the link to \(confirmPublic?.domain ?? "") can reach the app on that port without pairing. Only enable if you intend to share it.")
        }
        .task { await load() }
    }
    private func load() async { loading = true; items = (try? await broker.api.proxies()) ?? []; loading = false }
    private func onToggle(_ p: ProxyDto, _ next: Bool) {
        if next { confirmPublic = p } else { Task { await apply(p.domain, false) } }
    }
    private func apply(_ domain: String, _ isPublic: Bool) async {
        await broker.setProxyPublic(domain, isPublic)
        await load()
    }
}

/// Management for active display streams (parity with the web SessionDisplayPanel list):
/// rows live off `broker.displays`, tap opens a full-screen live viewer, swipe stops a
/// stream, and ＋ starts a host-default (VNC) display. Live via display_added/removed.
struct DisplaysView: View {
    let broker: BrokerSession
    @State private var loading = true
    @State private var viewing: DisplayStreamItem?
    @State private var banner: String?

    /// Identifiable wrapper so `.smFullScreenCover(item:)` can present a `DisplayStream`
    /// (the SKIE-bridged Kotlin type isn't `Identifiable` on the Swift side).
    private struct DisplayStreamItem: Identifiable { let stream: DisplayStream; var id: String { stream.id } }

    private var items: [DisplayStream] { broker.displays }

    var body: some View {
        Loadable(title: "Displays", loading: loading, isEmpty: items.isEmpty) {
            List {
                ForEach(items, id: \.id) { d in
                    Button { viewing = DisplayStreamItem(stream: d) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(rowTitle(d)).font(.subheadline.weight(.medium))
                            Text("\(d.sessionName.isEmpty ? "—" : d.sessionName) · \(d.status)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button("Stop", role: .destructive) { stop(d) }
                    }
                }
            }
        }
        .smInlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .smTopTrailing) {
                Button { start() } label: { Label("Start display", systemImage: "plus") }
            }
        }
        .overlay(alignment: .bottom) {
            if let banner {
                Text(banner).font(.caption.weight(.medium)).foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Theme.teal, in: Capsule())
                    .padding(.bottom, 16)
            }
        }
        .smFullScreenCover(item: $viewing) { item in
            DisplayViewerSheet(broker: broker, stream: item.stream)
            #if os(macOS)
            // `smFullScreenCover` degrades to a `.sheet` on macOS. A default sheet is far too
            // small for a live display surface, so give it a generous, resizable size. ESC is
            // NOT wired to dismiss here — the KeyCaptureView swallows keyCode 53 and routes it
            // to the remote; dismissal is the explicit close (xmark) button in DisplayViewerSheet.
                .frame(minWidth: 1000, minHeight: 700)
                .presentationSizing(.page)
            #endif
        }
        .task { await broker.refreshDisplays(); loading = false }
    }

    private func rowTitle(_ d: DisplayStream) -> String {
        let display = d.display.isEmpty ? d.id : d.display
        return "\(display) · \(d.provider)"
    }

    private func start() {
        Task {
            // Web "+" sends no args → host-default (VNC) for the foremost session context.
            _ = try? await broker.api.startDisplay(
                sessionName: "", provider: nil, device: nil, width: nil, height: nil)
            await broker.refreshDisplays()
        }
    }

    private func stop(_ d: DisplayStream) {
        Task {
            try? await broker.api.stopDisplay(id: d.id)
            await broker.refreshDisplays()
            showBanner("Display stopped")
        }
    }

    private func showBanner(_ text: String) {
        banner = text
        Task { try? await Task.sleep(nanoseconds: 2_500_000_000); banner = nil }
    }
}

/// Full-screen live viewer for a single display stream, reusing `DisplayStreamView`
/// (the same surface + input the chat Display tab uses) with a close affordance.
private struct DisplayViewerSheet: View {
    let broker: BrokerSession
    let stream: DisplayStream
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            DisplayStreamView(broker: broker, stream: stream)
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
                    .glassEffect(.regular, in: Circle())
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            .padding(.leading, 12)
        }
    }
}

/// Per-provider usage cards (Claude / Codex / Cursor / opencode) with progress
/// bars — parity with the web UsageView (was raw JSON before).
struct UsageView: View {
    let broker: BrokerSession
    @State private var data: UsageResponse?
    @State private var loading = true
    @State private var redeeming = false
    @State private var showResetConfirm = false
    @State private var resetNote: String? = nil

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if loading && data == nil {
                    ProgressView().tint(Theme.teal).frame(maxWidth: .infinity).padding(.top, 60)
                } else if let d = data {
                    claudeCard(d.claude, err: d.errors["claude"])
                    codexCard(d.codex, err: d.errors["codex"])
                    cursorCard(d.cursor, err: d.errors["cursor"])
                    opencodeCard(d.opencode, err: d.errors["opencode"])
                } else {
                    ContentUnavailableView("Usage unavailable", systemImage: "chart.bar")
                        .padding(.top, 40)
                }
            }
            .padding(16)
        }
        .navigationTitle("Usage").smInlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .smTopTrailing) {
                Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(loading)
            }
        }
        .task { await load() }
    }

    private func load() async {
        loading = true
        data = await broker.usage()
        loading = false
    }

    /// Redeem one banked Codex rate-limit reset, then refresh the card. Gated behind the
    /// confirmationDialog above; mirrors the web UsageView handler.
    private func useReset() async {
        redeeming = true
        defer { redeeming = false; showResetConfirm = false }
        let res = await broker.redeemCodexReset()
        if let res {
            resetNote = codexResetNote(res.code, Int(res.windowsReset))
            await load()
        } else {
            resetNote = "Reset failed"
        }
    }

    /// Map a redeem result `code` to a short user-facing note (web/Android parity).
    private func codexResetNote(_ code: String, _ windows: Int) -> String {
        switch code {
        case "reset": return "✓ Reset — cleared \(windows) window\(windows == 1 ? "" : "s")"
        case "nothing_to_reset": return "Nothing to reset right now"
        case "no_credit": return "No banked resets left"
        case "already_redeemed": return "That reset was already redeemed"
        default: return "Reset request completed"
        }
    }

    @ViewBuilder private func claudeCard(_ u: ClaudeUsage?, err: String?) -> some View {
        UsageCard(title: "Claude", subtitle: "Pro plan", dimmed: u == nil) {
            if let u {
                usageBar("5-hour window", u.fiveHour.used, reset: resetClaude(u.fiveHour.resetsAt))
                usageBar("7-day window", u.sevenDay.used, reset: resetClaude(u.sevenDay.resetsAt))
                // Per-model weekly caps — shown only when Anthropic returns them.
                if let sonnet = u.sevenDaySonnet {
                    usageBar("7-day Sonnet", sonnet.used, reset: resetClaude(sonnet.resetsAt))
                }
                if let fable = u.sevenDayFable {
                    usageBar("7-day Fable", fable.used, reset: resetClaude(fable.resetsAt))
                }
                if let extra = u.extraUsage, extra.enabled {
                    Divider()
                    rowLine("Extra usage", String(format: "$%.2f / $%.2f", extra.usedCredits, extra.monthlyLimit))
                }
            } else { unavailable(err) }
        }
    }

    @ViewBuilder private func codexCard(_ u: CodexUsage?, err: String?) -> some View {
        UsageCard(title: "Codex", subtitle: u?.plan ?? "unknown", dimmed: u == nil,
                  badge: (u?.limitReached == true) ? "limit reached" : nil) {
            if let u {
                usageBar("5-hour window", u.primaryWindow.used, reset: resetCodex(u.primaryWindow.resetsAt))
                usageBar("7-day window", u.secondaryWindow.used, reset: resetCodex(u.secondaryWindow.resetsAt))
                if let c = u.credits, c.hasCredits {
                    Divider(); rowLine("Credits balance", "$\(c.balance)")
                }
                Divider()
                rowLine("🎟️ Resets banked", "\(u.resetCredits)")
                if u.resetCredits > 0 {
                    Button("Use a reset") { showResetConfirm = true }
                        .buttonStyle(.bordered).controlSize(.small).tint(Theme.teal)
                        .disabled(redeeming)
                        .confirmationDialog("Use a banked reset?", isPresented: $showResetConfirm,
                                            titleVisibility: .visible) {
                            Button("Use a reset (spends 1 of \(u.resetCredits))") {
                                Task { await useReset() }
                            }
                            Button("Cancel", role: .cancel) {}
                        } message: {
                            Text("Spends one of your banked Codex resets to clear your rate-limit windows now.")
                        }
                }
                if let note = resetNote {
                    Text(note).font(.caption2).foregroundStyle(.secondary)
                }
            } else { unavailable(err) }
        }
    }

    @ViewBuilder private func cursorCard(_ u: CursorUsage?, err: String?) -> some View {
        UsageCard(title: "Cursor", subtitle: "Billing cycle", dimmed: u == nil) {
            if let u {
                usageBar("Usage", u.totalPercentUsed, reset: "")
                Divider()
                rowLine("Spend", String(format: "$%.2f / $%.2f included", u.totalSpendCents / 100, u.includedCents / 100))
            } else { unavailable(err) }
        }
    }

    @ViewBuilder private func opencodeCard(_ u: OpenCodeUsage?, err: String?) -> some View {
        UsageCard(title: "opencode", subtitle: "Local usage · all time", dimmed: u == nil,
                  trailing: u.map { String(format: "$%.2f", $0.totalCostUsd) }) {
            if let u {
                HStack(spacing: 16) { tokenStat("Input", u.inputTokens); tokenStat("Output", u.outputTokens) }
                HStack(spacing: 16) { tokenStat("Cache read", u.cacheReadTokens); tokenStat("Cache write", u.cacheWriteTokens) }
                Divider()
                Text("\(u.sessions) sessions · \(u.messages) messages").font(.caption2).foregroundStyle(.secondary)
            } else { unavailable(err) }
        }
    }

    private func usageBar(_ label: String, _ used: Double, reset: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text("\(Int(used.rounded()))% used").font(.caption)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.smTertiaryFill)
                    Capsule().fill(barColor(used)).frame(width: geo.size.width * min(max(used, 0), 100) / 100)
                }
            }.frame(height: 7)
            if !reset.isEmpty { Text(reset).font(.caption2).foregroundStyle(.tertiary) }
        }
    }
    private func rowLine(_ l: String, _ r: String) -> some View {
        HStack { Text(l).font(.caption).foregroundStyle(.secondary); Spacer(); Text(r).font(.caption) }
    }
    private func tokenStat(_ l: String, _ n: Int64) -> some View {
        HStack { Text(l).font(.caption).foregroundStyle(.secondary); Spacer(); Text(formatTokens(n)).font(.caption) }
            .frame(maxWidth: .infinity)
    }
    private func unavailable(_ err: String?) -> some View {
        Text(err ?? "Not available").font(.caption).foregroundStyle(.secondary)
    }
    private func barColor(_ pct: Double) -> Color { pct >= 85 ? .red : pct >= 60 ? .yellow : Theme.teal }
    private func formatTokens(_ n: Int64) -> String {
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.1fK", Double(n) / 1_000) }
        return "\(n)"
    }
    private func resetClaude(_ iso: String?) -> String {
        guard let iso, let d = parseISO(iso) else { return "" }
        return resetText(d)
    }
    private func resetCodex(_ epochSec: KotlinDouble?) -> String {
        guard let s = epochSec?.doubleValue else { return "" }
        return resetText(Date(timeIntervalSince1970: s))
    }
    private func parseISO(_ s: String) -> Date? {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: s) ?? ISO8601DateFormatter().date(from: s)
    }
    private func resetText(_ date: Date) -> String {
        let diff = date.timeIntervalSinceNow
        if diff <= 0 { return "resets soon" }
        if diff < 86400 {
            let h = Int(diff) / 3600, m = (Int(diff) % 3600) / 60
            return h > 0 ? "resets in \(h)h \(m)m" : "resets in \(m)m"
        }
        let fmt = DateFormatter(); fmt.dateFormat = "MMM d"
        return "resets \(fmt.string(from: date))"
    }
}

private struct UsageCard<Content: View>: View {
    let title: String
    let subtitle: String
    var dimmed: Bool = false
    var badge: String? = nil
    var trailing: String? = nil
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold))
                    Text(subtitle).font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                if let badge {
                    Text(badge).font(.caption2.weight(.semibold)).foregroundStyle(.red)
                        .padding(.horizontal, 7).padding(.vertical, 3)
                        .background(Color.red.opacity(0.12), in: Capsule())
                }
                if let trailing { Text(trailing).font(.subheadline.weight(.semibold)) }
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .opacity(dimmed ? 0.55 : 1)
    }
}

/// Archived sessions — tapping opens the (read-only) transcript; swipe to Resume.
struct ArchivedView: View {
    let broker: BrokerSession
    @State private var items: [ArchivedDto] = []
    @State private var loading = true
    @State private var projectFilter: String? = nil

    private var projects: [ArchivedProject] { archivedProjects(sessions: items, home: nil) }
    private var visible: [ArchivedDto] { filterArchivedByProject(sessions: items, key: projectFilter) }

    var body: some View {
        Group {
            if loading && items.isEmpty {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if items.isEmpty {
                ContentUnavailableView("No archived sessions", systemImage: "archivebox")
            } else {
                List {
                    ForEach(visible, id: \.id) { a in
                        NavigationLink { ArchivedChatView(broker: broker, archived: a) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(alignment: .firstTextBaseline) {
                                    Text(a.name).font(.subheadline.weight(.medium)).lineLimit(1)
                                    Spacer(minLength: 6)
                                    Text(a.agent).font(.caption2).foregroundStyle(Theme.teal.opacity(0.8))
                                }
                                let projectPath = a.repo_root ?? a.workdir
                                Text(formatWorkdir(workdir: projectPath, home: inferHomeDir(workdir: projectPath)))
                                    .font(.caption2.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                                if let k = a.killed_at {
                                    Text("Archived \(archivedDate(k))").font(.caption2).foregroundStyle(.tertiary)
                                }
                            }
                        }
                        .swipeActions {
                            Button("Resume") {
                                broker.resume(a.id); items.removeAll { $0.id == a.id }
                                if let f = projectFilter, !items.contains(where: { ($0.repo_root ?? $0.workdir) == f }) {
                                    projectFilter = nil
                                }
                            }.tint(Theme.teal)
                        }
                    }
                }
            }
        }
        .navigationTitle("Archived").smInlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .smTopTrailing) {
                if !items.isEmpty {
                    Menu {
                        Picker("Filter by project", selection: $projectFilter) {
                            Text("All projects").tag(String?.none)
                            ForEach(projects, id: \.key) { p in
                                Text("\(p.label) (\(p.count))").tag(String?.some(p.key))
                            }
                        }
                    } label: {
                        Image(systemName: projectFilter == nil
                            ? "line.3.horizontal.decrease.circle"
                            : "line.3.horizontal.decrease.circle.fill")
                            .accessibilityLabel(projectFilter == nil ? "Filter by project" : "Filter active")
                    }
                }
            }
        }
        .task { items = await broker.archived(); loading = false }
    }
}

/// Read-only transcript of an archived (killed) session, with a Resume action.
struct ArchivedChatView: View {
    let broker: BrokerSession
    let archived: ArchivedDto
    @State private var logs: [LogEntry] = []
    @State private var loading = true
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if loading {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if logs.isEmpty {
                ContentUnavailableView("No transcript", systemImage: "doc.text")
            } else {
                // List (collection-view-backed) materializes rows reliably even on a
                // fresh, large transcript — unlike ScrollView+LazyVStack, which could
                // render blank until a manual scroll (intermittent on slower devices).
                // Read-only, so no scroll-to-bottom needed.
                List {
                    ForEach(logs, id: \.id) { entry in
                        ArchivedMessageRow(entry: entry)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                            .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle(archived.name).smInlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .smTopTrailing) {
                Button("Resume") { broker.resume(archived.id); dismiss() }.tint(Theme.teal)
            }
        }
        .task { logs = await broker.archivedLogs(archived.id); loading = false }
    }
}

private struct ArchivedMessageRow: View {
    let entry: LogEntry
    private var isAgent: Bool { entry.direction.hasPrefix("out") }
    var body: some View {
        let text = entry.text ?? ""
        if !text.isEmpty {
            if isAgent {
                MarkdownView(text: text).font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading).transcriptCard()
            } else {
                Text(text).font(.subheadline.weight(.medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

/// Personal Assistants — list (online dot, default star) + create (＋) + kill (swipe).
/// Tapping a PA opens its chat. Parity with the web PersonalAssistantsView.
struct PersonalAssistantsView: View {
    let broker: BrokerSession
    var onOpen: (String) -> Void
    @State private var pas: [PADto] = []
    @State private var loading = true
    @State private var creating = false
    @State private var killTarget: PADto?

    var body: some View {
        Group {
            if loading && pas.isEmpty {
                ProgressView().tint(Theme.teal).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if pas.isEmpty {
                ContentUnavailableView("No personal assistants", systemImage: "person.crop.circle",
                    description: Text("Tap ＋ to create one."))
            } else {
                List {
                    ForEach(pas, id: \.id) { pa in
                        Button { onOpen(pa.id) } label: { paRow(pa) }
                            .buttonStyle(.plain)
                            .swipeActions { Button("Kill", role: .destructive) { killTarget = pa } }
                    }
                }
            }
        }
        .navigationTitle("Assistants").smInlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .smTopTrailing) {
                Button { creating = true } label: { Label("New", systemImage: "plus") }
            }
        }
        .sheet(isPresented: $creating) { CreatePASheet(broker: broker) { Task { await load() } } }
        .confirmationDialog("Kill \(killTarget?.name ?? "")?",
            isPresented: Binding(get: { killTarget != nil }, set: { if !$0 { killTarget = nil } }),
            titleVisibility: .visible) {
            Button("Kill", role: .destructive) {
                if let t = killTarget { broker.kill(t.id); Task { await load() } }
            }
        }
        .task { await load() }
    }
    private func load() async { loading = true; pas = await broker.pas(); loading = false }
    private func paRow(_ pa: PADto) -> some View {
        HStack(spacing: 11) {
            AgentLogo(agent: pa.agent ?? "claude", size: 30)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(pa.name).font(.subheadline.weight(.medium)).lineLimit(1)
                    if pa.isDefault { Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow) }
                }
                Text([pa.agent, pa.model].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Circle().fill(pa.connected ? Theme.teal : Color.gray.opacity(0.4)).frame(width: 8, height: 8)
        }
    }
}

private struct CreatePASheet: View {
    let broker: BrokerSession
    var onDone: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var agent = "claude"
    @State private var focus = ""
    @State private var creating = false
    private let agents = ["claude", "codex", "cursor", "opencode"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. coder, researcher", text: $name)
                        .autocorrectionDisabled().smNoAutocapitalization()
                }
                Section("Agent") {
                    Picker("Agent", selection: $agent) {
                        ForEach(agents, id: \.self) { a in
                            HStack { AgentLogo(agent: a, size: 20); Text(a.capitalized) }.tag(a)
                        }
                    }.pickerStyle(.inline)
                }
                Section("Focus") {
                    TextField("What should this PA focus on?", text: $focus, axis: .vertical).lineLimit(3...8)
                }
                Section {
                    Button(action: create) {
                        HStack { Spacer()
                            if creating { ProgressView().tint(.white) } else { Text("Create").fontWeight(.semibold) }
                            Spacer() }.foregroundStyle(.white).padding(.vertical, 4)
                    }
                    .listRowBackground(canCreate ? Theme.teal : Color.gray.opacity(0.4))
                    .disabled(!canCreate || creating)
                }
            }
            .navigationTitle("New assistant").smInlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .smTopLeading) { Button("Cancel") { dismiss() } } }
        }
        .tint(Theme.teal)
    }
    private var canCreate: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }
    private func create() {
        creating = true
        Task {
            await broker.createPA(name: name.trimmingCharacters(in: .whitespaces), agent: agent,
                                  model: nil, focusText: focus.isEmpty ? nil : focus)
            creating = false; onDone(); dismiss()
        }
    }
}
