import SwiftUI

/// Editor preferences — line wrap + code font size — persisted in `UserDefaults`.
///
/// Fixed-interface store (mirrors the Android `cmux-editor-settings` prefs). An
/// `@Observable` wrapper over `UserDefaults` so a SwiftUI view can bind to it
/// (`@Bindable`) AND an owner (`EditorPane` / `EditorWebView`) can read the plain
/// `lineWrap: Bool` / `fontSize: Int` values directly. Writes persist immediately;
/// `fontSize` is clamped to `10…24`. Keys match the agreed `cmux:editor:*` namespace.
@MainActor
@Observable
final class EditorSettingsStore {
    static let lineWrapKey = "cmux:editor:lineWrap"
    static let fontSizeKey = "cmux:editor:fontSize"
    static let fontRange = 10...24

    private let defaults: UserDefaults

    var lineWrap: Bool {
        didSet {
            guard lineWrap != oldValue else { return }
            defaults.set(lineWrap, forKey: Self.lineWrapKey)
        }
    }

    /// Always within `fontRange`: the setter clamps before storing, so reads are safe.
    var fontSize: Int {
        didSet {
            let clamped = fontSize.clamped(to: Self.fontRange)
            if clamped != fontSize {
                fontSize = clamped   // re-enters didSet once, then the guard below stops it
                return
            }
            guard fontSize != oldValue else { return }
            defaults.set(fontSize, forKey: Self.fontSizeKey)
        }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // Defaults: lineWrap = true, fontSize = 13 (registered so an unset key reads
        // the intended default rather than `false` / `0`).
        defaults.register(defaults: [
            Self.lineWrapKey: true,
            Self.fontSizeKey: 13,
        ])
        self.lineWrap = defaults.bool(forKey: Self.lineWrapKey)
        self.fontSize = defaults.integer(forKey: Self.fontSizeKey).clamped(to: Self.fontRange)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

/// A settings surface for the editor — presented as a `.sheet` from the header gear.
/// Matches `SettingsView`'s style: a `Form` with sections, a switch in a list row
/// (applies immediately), and a `Stepper` showing the current font size.
struct EditorSettingsView: View {
    @Bindable var settings: EditorSettingsStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Appearance") {
                    Toggle("Wrap long lines", isOn: $settings.lineWrap)

                    Stepper(value: $settings.fontSize,
                            in: EditorSettingsStore.fontRange,
                            step: 1) {
                        LabeledContent("Font size", value: "\(settings.fontSize) pt")
                    }
                }
            }
            .navigationTitle("Editor")
            .smInlineNavigationTitle()
            .tint(Theme.teal)
            .toolbar {
                ToolbarItem(placement: .smTopTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .smPresentationDetents([.medium])
    }
}
