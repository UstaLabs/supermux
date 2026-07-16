import SwiftUI
import Shared
#if os(iOS)
import UIKit
#endif

/// Add-host flow (spec §3.4 / §5): three ways to pair another broker into the merged fleet.
///  - **Scan** a current claim QR or a legacy `/pair?t=…` QR (iOS camera).
///  - **Paste** either pairing format.
///  - **URL** — a plain typed host URL for Tailscale/VPN/reverse-proxy users: GET /host to confirm
///    it's a supermux broker, then a "mint a claim on the host" hint if it's already set up.
///
/// Current claims retain the hostId mismatch guard; legacy device-token URLs are validated against
/// the old broker before storage. The SwiftUI mirror of Android's `AddHostScreen`.
struct AddHostView: View {
    let fleet: Fleet
    var onAdded: () -> Void
    @Environment(\.dismiss) private var dismiss

    enum Mode: Hashable { case scan, paste, url }

    @State private var mode: Mode = .paste
    @State private var pasteInput = ""
    @State private var urlInput = ""
    @State private var deviceName = AddHostView.defaultDeviceName
    @State private var busy = false
    @State private var error: String?
    @State private var info: String?
    #if os(iOS)
    @State private var showScanner = false
    #endif

    private var trimmedName: String {
        let t = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? AddHostView.defaultDeviceName : t
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("Pair another broker to see all its sessions in one merged list.")
                        .font(.subheadline).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 4)

                    Picker("Mode", selection: $mode) {
                        #if os(iOS)
                        Text("Scan").tag(Mode.scan)
                        #endif
                        Text("Paste link").tag(Mode.paste)
                        Text("URL").tag(Mode.url)
                    }
                    .pickerStyle(.segmented)
                    .disabled(busy)
                    .onChange(of: mode) { _, _ in error = nil; info = nil }

                    TextField("This device's name", text: $deviceName)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .smNoAutocapitalization()
                        .disabled(busy)
                        .accessibilityIdentifier("add_host_device_name")

                    modeContent

                    if busy { ProgressView().padding(.top, 4) }
                    if let error {
                        Text(error).font(.subheadline).foregroundStyle(.red)
                            .multilineTextAlignment(.center)
                    }
                    if let info {
                        Text(info).font(.subheadline).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    Spacer(minLength: 0)
                }
                .padding(24)
            }
            .navigationTitle("Add host")
            .smInlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .smTopTrailing) { Button("Cancel") { dismiss() } }
            }
            #if os(iOS)
            .smFullScreenCover(isPresented: $showScanner) { scannerSheet }
            #endif
        }
        .tint(Theme.teal)
    }

    @ViewBuilder private var modeContent: some View {
        switch mode {
        case .scan:
            // The `.scan` segment only exists on iOS (camera); keep the case handled on macOS so
            // the switch stays exhaustive there.
            #if os(iOS)
            Button {
                error = nil; info = nil; showScanner = true
            } label: {
                Label("Scan pairing QR", systemImage: "qrcode.viewfinder")
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent).tint(Theme.teal)
            .disabled(busy)
            .accessibilityIdentifier("add_host_scan")
            #else
            EmptyView()
            #endif
        case .paste:
            VStack(spacing: 12) {
                TextField("https://host/pair?t=… or {\"v\":1,…}", text: $pasteInput, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .smNoAutocapitalization()
                    .disabled(busy)
                    .onChange(of: pasteInput) { _, _ in error = nil }
                    .accessibilityIdentifier("add_host_paste_field")
                Button("Add host") { submitPaste() }
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 12)
                    .foregroundStyle(.white)
                    .background(canPaste ? Theme.teal : Color.gray.opacity(0.4),
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .disabled(busy || !canPaste)
                    .accessibilityIdentifier("add_host_paste_submit")
            }
        case .url:
            VStack(spacing: 12) {
                TextField("https://my-mac.tailnet.ts.net", text: $urlInput)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .smNoAutocapitalization()
                    .smKeyboard(.url)
                    .disabled(busy)
                    .onChange(of: urlInput) { _, _ in error = nil; info = nil }
                    .accessibilityIdentifier("add_host_url_field")
                Button("Connect") { submitUrl() }
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 12)
                    .foregroundStyle(.white)
                    .background(canUrl ? Theme.teal : Color.gray.opacity(0.4),
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .disabled(busy || !canUrl)
                    .accessibilityIdentifier("add_host_url_submit")
            }
        }
    }

    #if os(iOS)
    private var scannerSheet: some View {
        ZStack(alignment: .topTrailing) {
            QRScannerView { decoded in
                showScanner = false
                submitClaim(raw: decoded)
            }
            .ignoresSafeArea()
            Button { showScanner = false } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title).foregroundStyle(.white)
                    .padding()
            }
        }
    }
    #endif

    private var canPaste: Bool { !pasteInput.trimmingCharacters(in: .whitespaces).isEmpty }
    private var canUrl: Bool { !urlInput.trimmingCharacters(in: .whitespaces).isEmpty }

    private func submitPaste() { submitClaim(raw: pasteInput) }

    private func submitClaim(raw: String) {
        error = nil; info = nil; busy = true
        let name = trimmedName
        Task { handle(await fleet.claim(raw: raw, deviceName: name)) }
    }

    private func submitUrl() {
        error = nil; info = nil; busy = true
        let url = urlInput
        let name = trimmedName
        Task { handle(await fleet.claimByUrl(url: url, deviceName: name)) }
    }

    private func handle(_ result: AddHostResult) {
        busy = false
        switch result {
        case .added:
            onAdded()
            dismiss()
        case .needsClaim(let identity):
            info = "Found \(identity.name.isEmpty ? "the host" : identity.name). It's already set up — "
                + "run `mux pair` on it (or use its Add-device screen) to mint a pairing link, then paste it above."
        case .error(let message):
            error = message
        }
    }

    private static var defaultDeviceName: String {
        #if os(iOS)
        return UIDevice.current.name
        #else
        return Host.current().localizedName ?? "Mac"
        #endif
    }
}
