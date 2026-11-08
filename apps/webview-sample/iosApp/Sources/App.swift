import SwiftUI
import WebViewSample

/// The Compose sample (WebViewSample.framework) as the whole screen. Compose pads itself with the safe-area and
/// keyboard insets, so the SwiftUI side ignores them all.
struct SampleView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}

@main struct WebViewSampleApp: App {
    var body: some Scene { WindowGroup { SampleView().ignoresSafeArea() } }
}
