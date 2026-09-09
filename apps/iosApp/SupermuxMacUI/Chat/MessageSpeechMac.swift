import AVFoundation
import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Read-aloud UI — the Mac shell's half of `Chat/MessageSpeech.swift`.
///
/// Split out by the H6 cutover: `MessageMetaRow` is a SwiftUI row placed only by the Mac
/// transcript. The Compose shell draws its own copy/read-aloud affordances and reaches the
/// synthesizer through `SwiftBridge` instead.

/// Copy + Read aloud under an agent reply (web/Android parity).
struct MessageMetaRow: View {
    let text: String
    var broker: BrokerSession?
    @ObservedObject private var speech = MessageSpeech.shared
    @State private var copied = false

    private var speechKey: String { MessageSpeech.plainTextForSpeech(text) }
    private var speaking: Bool { speech.isSpeaking(speechKey) }

    var body: some View {
        HStack(spacing: 2) {
            metaButton(
                systemName: copied ? "checkmark" : "doc.on.doc",
                label: copied ? "Copied" : "Copy response",
                tinted: copied
            ) {
                #if canImport(UIKit)
                UIPasteboard.general.string = text
                #else
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(text, forType: .string)
                #endif
                copied = true
                Task {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    copied = false
                }
            }
            metaButton(
                systemName: speaking ? "stop.fill" : "speaker.wave.2",
                label: speaking ? "Stop reading" : "Read aloud",
                tinted: speaking
            ) {
                speech.toggle(rawText: text, broker: broker)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 2)
    }

    @ViewBuilder
    private func metaButton(systemName: String, label: String, tinted: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(tinted ? Color.accentColor : Color.secondary.opacity(0.75))
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
