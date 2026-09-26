import SwiftUI
import EditorSample

/// The Compose sample (EditorSample.framework) as the whole screen. Compose pads itself with the
/// safe-area and keyboard insets, so the SwiftUI side ignores them all.
struct SampleView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { SampleViewControllerKt.sampleViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}

@main struct EditorSampleApp: App {
    var body: some Scene { WindowGroup { SampleView().ignoresSafeArea() } }
}
