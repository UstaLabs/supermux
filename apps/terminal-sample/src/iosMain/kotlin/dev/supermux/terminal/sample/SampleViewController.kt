package dev.supermux.terminal.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The iOS host of the sample: one `UIViewController` an Xcode app (or a Swift Playground) presents.
 *
 * There is no Xcode project in this repository for it on purpose — the iOS app of this monorepo is
 * `apps/iosApp`, and a second one would be a second thing to keep signed. What the Mac builds is
 * the FRAMEWORK: `:terminal-sample:linkDebugFrameworkIosSimulatorArm64` produces
 * `TerminalSample.framework`, and an app that imports it calls this function from
 * `UIHostingController`-free Swift:
 *
 * ```swift
 * import TerminalSample
 * let controller = SampleViewControllerKt.sampleViewController()
 * window.rootViewController = controller
 * ```
 *
 * Building that framework is the iOS check this task runs; see the module README.
 */
fun sampleViewController(): UIViewController = ComposeUIViewController {
    SampleRoot()
}
