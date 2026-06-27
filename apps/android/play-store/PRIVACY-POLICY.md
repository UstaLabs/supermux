# supermux — Privacy Policy

_Last updated: 24 June 2026_

supermux ("the app") is the mobile client for **supermux**, a self-hosted, open-source
tool. The app is published by UstaLabs ("we", "us"). This policy explains what the app
does with data. The short version: **we run no servers and collect nothing about you.**

## We operate no backend
supermux has no vendor cloud and no developer account system. The app connects **only to
the supermux broker that you install and run on hardware you control** (a VPS, mini PC, or
your own computer). Your data flows between your phone and your own server. It never
reaches us, and we have no ability to see it.

## What the app stores on your device
- **Pairing credentials** for your broker, kept in the Android encrypted keystore
  (`EncryptedSharedPreferences`). Uninstalling the app, or unpairing, removes them.
- **Preferences** (e.g. UI settings) stored locally.

## What the app sends, and only to your own broker
When you use a feature, the app transmits the relevant data **directly to your broker** over
an encrypted connection — never to us or to any third party for our purposes:
- **Microphone audio** — only when you use voice input, to transcribe your message.
- **Photos / camera** — only files you explicitly attach, or the camera frame while you scan
  a pairing QR code.
- **Messages and commands** you type into a session, and the terminal/editor you open.

These are sent to the server you own, the same way a self-hosted file-sync client talks to
your own NAS.

## Push notifications (Firebase Cloud Messaging)
To deliver notifications when an agent finishes or asks a question, the app uses **Google
Firebase Cloud Messaging (FCM)**. This means:
- A **device push token** is issued by Google and shared with your broker so it can address
  notifications to your device. Google processes this token to route the message.
- **Notification contents are end-to-end encrypted** by your broker; the payload Google
  relays is ciphertext.
- Google's handling of FCM data is governed by the
  [Google Privacy Policy](https://policies.google.com/privacy).

## What we do NOT do
- No analytics, no advertising, no tracking SDKs.
- No selling or sharing of personal data with third parties.
- No data collection by the developer of any kind.

## Permissions and why
| Permission | Why |
|---|---|
| Internet | Connect to your broker. |
| Microphone | Voice input (only while you record). |
| Camera | Scan the pairing QR code; capture attachments you choose to send. |
| Notifications | Show push alerts from your sessions. |

All are optional to the extent the OS allows; denying one only disables that feature.

## Children
The app is a developer tool and is not directed at children under 13.

## Changes
We may update this policy; the "last updated" date will change. Material changes will be
noted in the app's release notes.

## Contact
Questions: **support@supermux.dev** · Source code: https://github.com/UstaLabs/supermux
