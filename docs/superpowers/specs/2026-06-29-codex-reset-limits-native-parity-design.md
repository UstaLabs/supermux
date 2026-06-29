# Codex Rate-Limit Resets — Native Parity (iOS + Android)

**Status:** proposed
**Author:** supermux-6 (with Ahmet)
**Date:** 2026-06-29
**Related:** [web design](./2026-06-29-codex-reset-limits-usage-panel-design.md)

## Summary

Mirror the web usage-panel feature (show banked Codex resets + confirm-gated
redeem) in the native apps: shared KMP model/API + the Codex usage card in iOS
(SwiftUI) and Android (Compose). The broker change already shipped, so all three
clients hit the same `GET /usage` (now carrying `codex.resetCredits`) and
`POST /usage/codex/reset`.

## Goals

1. iOS & Android Codex cards show "🎟️ Resets banked: N".
2. When N ≥ 1, a confirm-gated "Use a reset" action redeems one and refreshes.
3. Each platform uses its native confirm idiom (iOS `confirmationDialog`,
   Android Material `AlertDialog`).
4. Shared KMP gains the field + redeem call; verified by `:shared:test`.

## Non-goals

- Any backend change (done) or web change (done).
- Telegram redeem action (still display-only there).

## Shared KMP — `apps/shared/.../net/BrokerApi.kt` (DONE in this branch)

- `CodexUsage` gains `val resetCredits: Int = 0`.
- New `@Serializable data class CodexResetResult(code: String, windowsReset: Int, codex: CodexUsage?)`.
- New `suspend fun redeemCodexReset(): CodexResetResult = postReturningJson("$httpBase/usage/codex/reset", EmptyBody())`.
- Tests in `BrokerApiTest.kt`: `resetCredits` parses (and defaults 0); `CodexResetResult` round-trips.

SKIE bridges the Kotlin `suspend fun` to a Swift `async` automatically, so iOS
calls it like the existing `usage()`.

## iOS — `apps/iosApp/.../Sessions/InfoPages.swift` + `Broker/BrokerSession.swift`

- `BrokerSession`: add `func redeemCodexReset() async -> CodexResetResult? { try? await api.redeemCodexReset() }` (next to `usage()`).
- `UsageView`: add `@State private var redeeming = false`, `@State private var showResetConfirm = false`, `@State private var resetNote: String? = nil`.
- `codexCard`: after the credits row, add:
  - `Divider()` + `rowLine("🎟️ Resets banked", "\(u.resetCredits)")`.
  - When `u.resetCredits > 0`: a "Use a reset" `Button` (disabled while `redeeming`)
    attached to a `.confirmationDialog` — confirm button titled
    "Use a reset (spends 1 of \(u.resetCredits))" runs `Task { await useReset() }`,
    plus a `.cancel` button.
  - If `resetNote != nil`: a caption line showing it.
- `useReset()` async: set `redeeming=true`; `let res = await broker.redeemCodexReset()`;
  if `res != nil` set `resetNote = codexResetNote(res.code, res.windowsReset)` and
  `await load()` (refresh, which repopulates `data` incl. the new `resetCredits`);
  else `resetNote = "Reset failed"`; finally `redeeming=false`.
- `codexResetNote(_ code: String, _ windows: Int) -> String`: mirrors the web helper
  (reset → "✓ Reset — cleared N window(s)", nothing_to_reset, no_credit,
  already_redeemed, default).

Build note: the iOS Swift app cannot be compiled from this Linux host (needs
macOS + Xcode + the SKIE-generated Shared.framework). Code is written to match the
existing `UsageView` patterns exactly; it needs a Mac/device build to confirm.

## Android — `apps/android/.../settings/MoreScreens.kt`, `AppViewModel.kt`, `MainActivity.kt`

- Local `CodexUsageData` (private parse model): add `val resetCredits: Int`.
- `parseUsage` codex block: `resetCredits = o.optInt("resetCredits", 0)`.
- `AppViewModel`: add `suspend fun redeemCodexReset(): CodexResetResult? = runCatching { api.redeemCodexReset() }.getOrNull()`.
- `MainActivity` (line ~374): pass `onRedeem = { vm.redeemCodexReset() }` into `UsageScreen`.
- `UsageScreen`: add param `onRedeem: suspend () -> CodexResetResult?`; expose a way
  to refresh after redeem (reuse `reloadKey++`). Pass redeem + refresh into `CodexUsageCard`.
- `CodexUsageCard`: after the credits row, add a "🎟️ Resets banked: N" row; when
  N > 0 a `Button("Use a reset")` (disabled while redeeming) that opens an
  `AlertDialog` (“Use a banked reset? Spends 1 of N.” — Confirm / Cancel); on confirm,
  `scope.launch { val r = onRedeem(); note = codexResetNote(r); onRefresh() }`.
- `codexResetNote(r: CodexResetResult?): String`: mirrors the web/iOS helper.

Build: verify with `./gradlew :android:assembleDebug` (and `:shared:test`,
`:shared:compileDebugKotlinAndroid`) from `apps/`.

## Confirm-UX rationale

Native dialogs (not the web's inline two-step) because that's the platform idiom
for a consequential, resource-spending confirm. Same safety as web: the button only
shows when `resetCredits > 0`, and the backend returns `nothing_to_reset` (no credit
spent) if windows aren't capped.

## Implementation order

1. Shared KMP (model + result + method + tests) — DONE.
2. Verify `:shared:compileDebugKotlinAndroid` + `:shared:test`.
3. iOS card + broker wrapper (subagent, follows mux:ios-development; no Linux build).
4. Android card + VM + wiring (subagent, follows mux:android-development; `:android:assembleDebug`).
5. Land all together with the web commits.
