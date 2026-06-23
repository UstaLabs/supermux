# Native Push — iOS app client + Phase E (2026-06-23)

**Status going in:** server-side core ✅, shared KMP foundation ✅ (decrypt-interop proven on JVM), Android client ✅ (compiles + APK + unit-tested), **iOS Swift decrypt PROVEN** (`apps/iosApp/Supermux/Push/PushCrypto.swift`, verified on the Mac against a real broker-sealed blob). Remaining: the iOS **app wiring**, then the one-time **Phase E** ops that light everything up.

---

## Part 1 — iOS app wiring (build on the remote Mac, verify on the Simulator)

Build/verify per the `mux:ios-simulator-on-remote-mac` recipe (Host `mac`, `source ~/ios-build-env.sh`, tar-over-ssh, ad-hoc sign, detach+poll). Reuses the proven `openSealedPush` in `PushCrypto.swift`.

1. **Keypair** — `apps/iosApp/Supermux/Push/PushKeypair.swift`: generate `P256.KeyAgreement.PrivateKey`; store it in the **Keychain** (`rawRepresentation`, with a **Keychain access group** so the NSE can read it too); expose `publicKeyB64Url` = the key's `x963Representation` (0x04‖X‖Y) → base64url-no-pad. Add an `openSealedPush(_:privateKey:)` overload taking the `P256.KeyAgreement.PrivateKey` directly (the current one takes PKCS#8 b64 — keep it for the test, add the key-based one for the app). Verify with a standalone `swift` run (generate → export pub is 65 bytes / 0x04).

2. **APNs registration** — in `SupermuxApp`/an `AppDelegate`: `UNUserNotificationCenter.requestAuthorization([.alert,.sound,.badge])`, then `registerForRemoteNotifications()`; capture the token in `didRegisterForRemoteNotificationsWithDeviceToken` (hex-encode it).

3. **Orchestration** (on APNs token, when paired): `relayUrl = BrokerApi.pushRelayUrl()` (via the shared `Shared.framework`/SKIE, or a direct HTTP call); `registerPushTokenWithRelay(relayUrl, "ios", apnsHex)`; when the **bootstrap** push arrives (`{kind:"bootstrap",routingToken}` in the payload) → `registerPushDevice("ios", routingToken, publicKeyB64Url)`. (The shared `BrokerApi` calls already exist and bridge to Swift via SKIE.)

4. **Notification Service Extension** (new target) — add to `apps/iosApp/project.yml` (XcodeGen) a `SupermuxPushNSE` target (extension point `com.apple.usernotifications.service`), embedded in the app, with the Push capability + the shared Keychain access group. `NotificationService.swift`: pull the sealed blob from `request.content.userInfo` (the relay sends it under `data.d` for FCM / the `data` field of the `aps` payload for APNs — confirm the APNs shape from `src/relay/apns.ts`, which puts it in `data` alongside `aps`), `openSealedPush(blob, keychainKey)` → set `bestAttemptContent.title = session`, `.body = text` → `contentHandler(bestAttemptContent)`. Generic fallback on decrypt failure. The relay's `apns.ts` already sets `mutable-content: 1` (required to trigger the NSE).

5. **Entitlements** — `aps-environment` (development) on the app + NSE; the shared Keychain access group on both.

6. **Build + verify on the sim** — `xcodegen generate` → `xcodebuild -sdk iphonesimulator -scheme Supermux -destination 'generic/platform=iOS Simulator' ARCHS=arm64 CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build` (ad-hoc — NOT `CODE_SIGNING_ALLOWED=NO`, the App-Group/Keychain-group entitlement would crash on launch otherwise) → `simctl install/launch` → **`xcrun simctl push <udid> dev.supermux.ios payload.apns`** with a crafted payload carrying a REAL sealed blob (generate one with the broker's `sealForDevice` for the sim's keypair) → confirm via `simctl io screenshot` that the NSE decrypted it and the notification shows the real text. **This verifies the receive→decrypt→display path end-to-end on the sim without any real APNs.**
   - The Simulator cannot obtain a *real* APNs device token — that needs a physical device + the paid Apple account (Phase E). `simctl push` proves everything downstream of the token.

7. **watchOS** — notifications auto-mirror from the paired iPhone; no extra work.

**Verifiable on the Mac sim now:** keypair, the NSE decrypt+display (via `simctl push`), the app/NSE compile. **Needs Phase E:** real APNs token acquisition + true remote delivery to a physical phone.

---

## Part 2 — Phase E: one-time ops (Ahmet) to turn it all on

1. **Apple** — create an **APNs Auth Key (.p8)** in your Apple Developer account (note **Key ID** + **Team ID**; bundle `dev.supermux.ios`). Ship the iOS app via **TestFlight/App Store** (the push entitlement needs a provisioned, non-free build). Put `.p8` + Key ID + Team ID into the relay env (`MUX_APNS_KEY_P8`, `MUX_APNS_KEY_ID`, `MUX_APNS_TEAM_ID`, `MUX_APNS_BUNDLE_ID=dev.supermux.ios`; `MUX_APNS_SANDBOX=1` for TestFlight/dev).
2. **Firebase** — create a Firebase project. Download the **service-account JSON** → relay env (`MUX_FCM_SA_JSON` = path, `MUX_FCM_PROJECT_ID`). Download **`google-services.json`** → replace the placeholder `apps/android/google-services.json` (gitignored; a `.example` is committed) and rebuild the Android app.
3. **Deploy the relay** — run the `relay` entrypoint (`bun src/relay/main.ts`) on a host (Coolify), with the APNs + FCM secrets as env. Point DNS at it (e.g. `push.supermux.dev`). Set the broker's `MUX_PUSH_RELAY_URL` to it, and bake the same default into the app builds.
4. **Distribute** — Android via Play/APK (with the real `google-services.json`); iOS via TestFlight/App Store.

Then: open each app while paired → it registers (push token → relay `/register` → bootstrap push delivers the routing token → broker `/push/device`) → an agent finishing/needing input/erroring buzzes your phone, with the content **sealed end-to-end** (the relay + Apple/Google never see it).

---

## What's proven vs pending (honest scorecard)
- **Proven + tested on this host / the Mac:** the relay (APNs ES256/HTTP2 + FCM OAuth2/v1 adapters, register→bootstrap→rate-limit, server), the broker (migration, seal, relay client, `/push/device`, fan-out into all notify paths), the shared decrypt (JVM interop test), the Android client (compile + APK + 16 unit tests), the iOS Swift decrypt (CryptoKit, verified on the Mac).
- **Code-complete, builds, but needs Phase E for live delivery:** Android FCM token + push (real `google-services.json` + Play-services device), iOS APNs token + push (real `.p8` + physical device + TestFlight).
- **Remaining code:** the iOS app wiring above (keypair, APNs reg, orchestration, NSE target) — all verifiable on the Mac sim via `simctl push` except real-token acquisition.
