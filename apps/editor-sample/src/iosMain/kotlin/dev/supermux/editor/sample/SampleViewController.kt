package dev.supermux.editor.sample

import androidx.compose.ui.window.ComposeUIViewController
import dev.supermux.editor.syntax.NativeBackend
import platform.UIKit.UIViewController

/**
 * The iOS host of the sample, presented by `iosApp` (apps/editor-sample/iosApp/project.yml):
 * `SampleViewControllerKt.sampleViewController()`. The app draws its own background and pads
 * itself with the safe-drawing insets (a Compose screen without a background is black on iOS:
 * the M0 lesson).
 */
fun sampleViewController(): UIViewController = ComposeUIViewController { SampleApp(loadBackend = { NativeBackend() }) }
