import SwiftUI
import EditorSpike

struct Probe: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { ImeProbeViewControllerKt.imeProbeViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}

@main struct EditorImeProbeApp: App {
    var body: some Scene { WindowGroup { Probe().ignoresSafeArea(.keyboard) } }
}
