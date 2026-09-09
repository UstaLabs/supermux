import SwiftUI

/// The composer's recording chrome — the live timer bar and the STT status pill.
///
/// Split out of `Chat/AudioRecorder.swift` by the H6 cutover: the RECORDER (the AVAudioRecorder
/// engine, reached from `SwiftBridge`) is still needed by the iOS app, but these two SwiftUI views
/// are only ever placed by the Mac shell's composer — the Compose shell draws its own. Keeping them
/// beside the engine would have dragged `Theme` (now Mac-only) back into the iOS target for one
/// accent colour.


/// Recording controls that take over the composer while capturing a voice clip.
/// Layout: a small de-emphasized cancel (trash) far left, a blinking dot + timer,
/// and a BIG teal STOP on the right (the primary action, where Send sits) — so
/// stop is the obvious large target and an accidental cancel is hard to hit.
struct RecordingBar: View {
    let elapsed: TimeInterval
    var onStop: () -> Void
    var onCancel: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onCancel) {
                Image(systemName: "trash")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Discard recording")

            Circle().fill(.red).frame(width: 9, height: 9)
                .opacity(Int(elapsed * 2) % 2 == 0 ? 1 : 0.3)   // blink with the timer ticks
            Text(formatRecordTime(elapsed))
                .font(.callout.weight(.medium).monospacedDigit())
                .foregroundStyle(.primary)

            Spacer(minLength: 0)

            Button(action: onStop) {
                Image(systemName: "stop.fill")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(Theme.teal, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Stop and transcribe")
        }
        .padding(.leading, 6).padding(.trailing, 4).padding(.vertical, 2)
    }
}

/// Compact status pill while the composer is busy with STT — broker whisper upload
/// ("Transcribing…") or first-run on-device model prep ("Preparing speech…"). Shared by
/// chat and the new-session launcher so both surfaces show the same progress chrome.
struct ComposerBusyBar: View {
    let label: String

    var body: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text(label).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.smTertiaryFill, in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label)
    }
}

func formatRecordTime(_ t: TimeInterval) -> String {
    let s = Int(t)
    return String(format: "%d:%02d", s / 60, s % 60)
}
