// #167: the share sheet (iOS) — the look approved in the prototype: what's shared, To (Agents | Sessions, a host
// picker with 2+ hosts), a message, Send in the navigation bar.
import SwiftUI

private extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { t in
            let v = t.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255, blue: CGFloat(v & 0xff) / 255, alpha: 1)
        })
    }
    // supermux's own palette (apps/shared Theme.kt), light / dark.
    static let smPrimary = Color(light: 0x007368, dark: 0x4BBAA7)
    static let smBackground = Color(light: 0xF0F1EC, dark: 0x080A07)
    static let smCard = Color(light: 0xFEFEFB, dark: 0x10120F)
    static let smMuted = Color(light: 0xE1E2DC, dark: 0x1E201C)
}

struct ShareSheetView: View {
    @ObservedObject var model: ShareModel

    var body: some View {
        NavigationView {
            ZStack(alignment: .top) {
                Color.smBackground.ignoresSafeArea()
                switch model.phase {
                case .done(let text), .queued(let text):
                    result(text, ok: { if case .done = model.phase { return true } else { return false } }())
                case .failed(let text):
                    VStack(spacing: 14) {
                        Image(systemName: "exclamationmark.triangle").font(.system(size: 34)).foregroundColor(.orange)
                        Text(text).multilineTextAlignment(.center)
                        Button(String(localized: "OK")) { model.cancel() }.buttonStyle(.borderedProminent).tint(.smPrimary)
                    }.padding(30).frame(maxHeight: .infinity)
                default:
                    form
                }
            }
            .navigationTitle(String(localized: "Send to agent"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel")) { model.cancel() }.tint(.smPrimary)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.phase == .sending || (model.loading && model.picked != nil) {
                        ProgressView()
                    } else {
                        Button(String(localized: "Send")) { model.send() }
                            .font(.body.weight(.semibold)).tint(.smPrimary)
                            .disabled(model.picked == nil || model.phase != .editing)
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
        .accentColor(.smPrimary)
    }

    private func result(_ text: String, ok: Bool) -> some View {
        VStack(spacing: 12) {
            Image(systemName: ok ? "checkmark.circle.fill" : "clock.fill").font(.system(size: 44)).foregroundColor(ok ? .smPrimary : .orange)
            Text(text).font(.headline).multilineTextAlignment(.center)
        }.padding(30).frame(maxHeight: .infinity)
    }

    private var form: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    preview
                    HStack {
                        Text(String(localized: "To").uppercased()).font(.caption).foregroundColor(.secondary)
                        Spacer()
                        if model.hosts.count > 1 {
                            Menu {
                                Button(String(localized: "All hosts")) { model.hostFilter = nil }
                                ForEach(model.hosts, id: \.id) { h in Button(h.name) { model.hostFilter = h.id } }
                            } label: {
                                Text(String(format: String(localized: "Host: %@"), model.hosts.first { $0.id == model.hostFilter }?.name ?? String(localized: "All hosts")))
                                    .font(.caption).padding(.horizontal, 10).padding(.vertical, 4)
                                    .background(Capsule().fill(Color.smMuted)).foregroundColor(.primary)
                            }
                        }
                    }.padding(.top, 4)
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                        TextField(String(localized: "Search agents"), text: $model.query).disableAutocorrection(true)
                    }
                    .padding(9).background(RoundedRectangle(cornerRadius: 12).fill(Color.smMuted))
                    Picker("", selection: $model.agentsTab) {
                        Text(String(localized: "Agents")).tag(true)
                        Text(String(localized: "Sessions")).tag(false)
                    }.pickerStyle(.segmented)
                    list
                }
                .padding(14)
            }
            footer
        }
    }

    private var preview: some View {
        let files = model.things.compactMap { $0.file }
        let first = model.things.first
        let title: String
        let sub: String
        if model.things.count > 1 {
            title = String(format: String(localized: "%d items"), model.things.count)
            sub = files.map { $0.name }.joined(separator: ", ")
        } else if let f = first?.file {
            title = f.name
            sub = ByteCountFormatter.string(fromByteCount: f.size, countStyle: .file)
        } else {
            title = first?.text ?? ""
            sub = first?.kind == .link ? String(localized: "Link") : String(localized: "Text")
        }
        return HStack(spacing: 10) {
            if model.loading {
                ProgressView().frame(width: 56, height: 56)
            } else {
                HStack(spacing: 6) {
                    ForEach(model.things.prefix(3)) { thumb($0, size: model.things.count > 1 ? 46 : 56) }
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(2)
                if !sub.isEmpty { Text(sub).font(.caption).foregroundColor(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.smCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.smMuted))
    }

    private func thumb(_ t: SharedThing, size: CGFloat) -> some View {
        Group {
            if let img = t.thumb {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                ZStack {
                    Color.smMuted
                    Image(systemName: t.kind == .link ? "link" : t.kind == .text ? "text.alignleft" : "doc").foregroundColor(.secondary)
                }
            }
        }
        .frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var list: some View {
        VStack(spacing: 0) {
            if model.shown.isEmpty {
                Text(model.targets.isEmpty ? String(localized: "Open supermux once to see your agents here.") : String(localized: "No match"))
                    .foregroundColor(.secondary).padding(16)
            }
            ForEach(model.shown) { t in
                let selected = model.picked?.id == t.id
                Button { model.picked = t } label: {
                    HStack(spacing: 10) {
                        avatar(t)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(t.name).font(.subheadline.weight(.semibold)).foregroundColor(.primary).lineLimit(1)
                            let sub = [t.preview, model.hosts.count > 1 ? t.hostName : nil].compactMap { $0 }.joined(separator: " · ")
                            if !sub.isEmpty { Text(sub).font(.caption).foregroundColor(.secondary).lineLimit(1) }
                        }
                        Spacer()
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle").font(.title3).foregroundColor(selected ? .smPrimary : .secondary.opacity(0.5))
                    }
                    .padding(.horizontal, 12).padding(.vertical, 9)
                    .background(selected ? Color.smPrimary.opacity(0.10) : Color.clear)
                }
                .buttonStyle(.plain)
                if t.id != model.shown.last?.id { Divider().padding(.leading, 60) }
            }
        }
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.smCard))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func avatar(_ t: ShareTargetRow) -> some View {
        Image(uiImage: UIImage(data: ShareDonation.avatarPNG(t.name)) ?? UIImage())
            .resizable().frame(width: 38, height: 38).clipShape(Circle())
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.picked.map { "\(String(localized: "To")) \($0.name)" } ?? String(localized: "Pick an agent"))
                .font(.caption).foregroundColor(.secondary).padding(.leading, 6)
            TextField(String(localized: "Add a message…"), text: $model.message)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 20).fill(Color.smCard))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.smMuted))
        }
        .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 12)
        .background(Color.smBackground)
    }
}
