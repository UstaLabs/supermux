# Android push (Firebase / FCM) — status

## ✅ Done

### App side
- Firebase project **`supermux-app`**, package `dev.supermux.android`, real `google-services.json`
  (gitignored; bundled in release AAB).
- FCM client stack: `SupermuxMessagingService` + decrypt + notification channel +
  `POST_NOTIFICATIONS`.
- **Registration fixed (2026-07-27):**
  1. Credentials come from `SecureTokenStore` / multi-host `PairedHostStore` — **not**
     `DevConfig.brokerUrl()` (was `ws://CHANGE_ME:9898` on physical devices).
  2. `registerIfPaired()` runs on cold start when paired, right after pairing, and after
     adding a host — parity with iOS `PushManager.registerIfPaired`. `onNewToken` alone
     was insufficient when the FCM token arrived before pairing.
  3. **HTTP routingToken (same day, 2nd pass):** relay `POST /register` returns
     `routingToken` in the 202 body; the app POSTs `/push/device` immediately.
     Waiting only for bootstrap FCM left 0 Android rows — data-only bootstrap is flaky.

### Relay side (this box)
- User unit `supermux-relay.service` with `MUX_FCM_PROJECT_ID` + `MUX_FCM_SA_JSON`.
- Logs `relay_ready {"fcm":true,...}` on :8788; public URL `https://push.supermux.dev`.

## Device verify checklist
1. Install a build that includes this registration fix (Play/internal or sideload).
2. Grant notification permission when prompted.
3. Pair (or reopen the app if already paired) — logcat `SupermuxFCM` should show
   `registerIfPaired` → `registered FCM token with relay` → `device registered with broker`.
4. Broker DB: `SELECT platform,COUNT(*) FROM device_push_tokens GROUP BY platform;` must show
   an `android` row.
5. Trigger an agent reply while the app is backgrounded → notification banner.

## Note
- Push is not required for Play closed testing; wire + verify before production marketing
  that promises notifications.
