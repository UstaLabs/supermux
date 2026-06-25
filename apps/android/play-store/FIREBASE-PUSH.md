# Android push (Firebase / FCM) — status & remaining step

## ✅ Done (app side — fully wired and verified)
- Created Firebase project **`supermux-app`** (project number `1005235485338`).
- Registered the Android app **`dev.supermux.android`** (App ID
  `1:1005235485338:android:0e7882d02d1bbdadb7fd23`).
- Downloaded the real **`google-services.json`** → `apps/android/google-services.json`
  (replaces the old placeholder; gitignored). Confirmed it's **bundled in the release AAB**.
- **FCM v1 API is enabled** on the project.
- Minted a **service-account key** and **validated it against FCM** (a `validate_only`
  send authenticated successfully — only a deliberately-fake device token was rejected).
  Key saved on the broker box at **`~/.mux/state/supermux-fcm-sa.json`** (chmod 600).

## ⚠️ Remaining step (relay side — your infrastructure)
The broker doesn't send push itself — it forwards to the **push relay** at
**`https://push.supermux.dev`** (the default `MUX_PUSH_RELAY_URL`, also what serves iOS
APNs). That relay — `src/relay/main.ts`, run separately from this box — is what actually
calls FCM, so the service-account key must live **there**:

```
MUX_FCM_PROJECT_ID=supermux-app
MUX_FCM_SA_JSON=/path/on/the/relay/host/supermux-fcm-sa.json
```
…then restart the relay. The key file to copy over is `~/.mux/state/supermux-fcm-sa.json`.

> ⚠️ During setup, **`push.supermux.dev` did not resolve from this box** (DNS timeout).
> So before chasing Android push, confirm the relay is actually deployed and reachable —
> if iOS push is working, it's reachable from wherever your broker runs; if not, the relay
> itself may still need standing up. Either way, Android push needs the SA key + the two env
> vars above on that relay host, then a relay restart.

## Note
- This box runs the **broker** (`mux.service` → `src/main.ts`), **not** the relay — so adding
  `MUX_FCM_*` to `~/.mux/state/.env` here would have no effect (I intentionally did **not**).
- Push is **not required** for the Play closed test; the app works fully without it. Wire the
  relay before production so public users get notifications.
- Want me to finish the relay side? Tell me where `push.supermux.dev` runs (or give me access)
  and I'll deploy the key, set the env, and verify a real end-to-end push to a device.
