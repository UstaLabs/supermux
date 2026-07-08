//
//  ExpandedNotificationView.swift
//  SupermuxNotifContent
//
//  The custom view shown when a collapsed chat notification is long-pressed / pulled down.
//  Draws the chat's recent messages as a mini-transcript threaded on the app's session-log
//  spine (a hairline + per-message nodes), newest tipped in teal, with the session title and
//  an unread count. Colours are semantic (`.primary`/`.secondary`/`.tertiary`) so the card
//  reads correctly on the system notification material in both light and dark; the one
//  accent (teal) adapts per appearance to stay legible on either ground.
//

import SwiftUI
import UIKit

/// One row of the expanded transcript.
struct NotifMessage: Identifiable {
    let id = UUID()
    let text: String
    let time: String
    let isLatest: Bool
}

/// Everything the expanded card renders — parsed from the notification's userInfo.
struct ExpandedNotifModel {
    let title: String
    let count: Int
    let messages: [NotifMessage]
}

struct ExpandedNotificationView: View {
    let model: ExpandedNotifModel

    /// Brand teal, tuned per appearance (deeper on light, brighter on dark) so it holds
    /// contrast on the notification material either way.
    private var teal: Color {
        Color(UIColor(dynamicProvider: { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.176, green: 0.831, blue: 0.749, alpha: 1)   // #2dd4bf
                : UIColor(red: 0.051, green: 0.580, blue: 0.533, alpha: 1)   // #0d9488
        }))
    }
    private var tint: Color { teal.opacity(0.15) }
    private var spine: Color { Color.primary.opacity(0.12) }

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(spacing: 8) {
                Text(model.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Spacer(minLength: 6)
                if model.count > 1 {
                    Text("\(model.count) new")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(teal)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(tint))
                }
            }
            transcript
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 15)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var transcript: some View {
        ZStack(alignment: .topLeading) {
            // The session-log spine: a hairline behind the message nodes.
            Rectangle()
                .fill(spine)
                .frame(width: 1.5)
                .padding(.leading, 6.25)
                .padding(.vertical, 7)

            VStack(alignment: .leading, spacing: 12) {
                ForEach(model.messages) { message in
                    HStack(alignment: .top, spacing: 9) {
                        node(isLatest: message.isLatest)
                        Text(message.text)
                            .font(.system(size: 14))
                            .foregroundStyle(message.isLatest ? Color.primary : Color.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if !message.time.isEmpty {
                            Text(message.time)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .padding(.top, 3)
                        }
                    }
                }
            }
        }
    }

    /// A message node on the spine: a small dot, teal + haloed for the newest message.
    private func node(isLatest: Bool) -> some View {
        ZStack {
            if isLatest {
                Circle().fill(tint).frame(width: 14, height: 14)
            }
            Circle()
                .fill(isLatest ? teal : Color.secondary.opacity(0.45))
                .frame(width: 7, height: 7)
        }
        .frame(width: 14, alignment: .center)
        .padding(.top, 4)
    }
}
