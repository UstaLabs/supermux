# Codex Rate-Limit Resets — Usage Panel

**Status:** proposed
**Author:** supermux-6 (with Ahmet)
**Date:** 2026-06-29
**Related:** `src/core/usage/index.ts`, `src/web-app/src/views/UsageView.vue`

## Summary

Surface Codex's **rate-limit reset banking** in the usage panel. OpenAI (2026-06-11)
lets Codex users *bank* rate-limit resets (1 free at launch, more via referrals,
each good for 30 days) and *spend* one to instantly clear their usage windows
instead of waiting them out.

Two parts:

1. **Show** how many banked resets the user has — already returned by the *same*
   `wham/usage` endpoint the panel calls today (`rate_limit_reset_credits.available_count`),
   we just don't read it.
2. **Redeem** one from the panel — a confirm-gated "Use a reset" button that POSTs
   to Codex's consume endpoint and refreshes the card.

Display also lands in the Telegram `/usage` text. The redeem *action* is web-only
for v1.

## Goals

1. Codex card shows "🎟️ Resets banked: N".
2. When N ≥ 1, a "Use a reset" button redeems one and refreshes usage in place.
3. Redeeming is behind an explicit inline confirm (banked resets are scarce + expire).
4. Redeeming is idempotent — a retried request can't double-spend a credit.
5. Telegram `/usage` shows the banked count.
6. All new logic is unit-tested, mirroring the existing `tests/usage.test.ts` style.

## Non-goals

- Redeeming from Telegram (a `/codexreset` command) — easy follow-up, not v1.
- Showing reset *history*, referral state, or how to earn more resets.
- Any change to Claude/Cursor/opencode cards.
- Persisting/auto-refreshing usage in the background.

## Verified API contract

Confirmed against the open-source `openai/codex` Rust client
(`codex-rs/backend-client/src/client/rate_limit_resets.rs` + `types.rs` +
`app-server/.../account_processor/rate_limit_resets.rs`). Auth is the ChatGPT
access token from `~/.codex/auth.json` → `tokens.access_token` (what
`fetchCodexUsage` already uses).

**Read available resets** — same endpoint we already GET:

```
GET https://chatgpt.com/backend-api/wham/usage
→ { ..., rate_limit_reset_credits: { available_count: <int> } }
```

`rate_limit_reset_credits` may be absent → treat as 0.

**Redeem one reset:**

```
POST https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume
headers: Authorization: Bearer <token>, Content-Type: application/json
body:    { "redeem_request_id": "<non-empty idempotency key>" }
→ { "code": <enum>, "windows_reset": <int> }
```

`code` ∈ `reset` | `nothing_to_reset` | `no_credit` | `already_redeemed`.
`windows_reset` = number of windows cleared (e.g. 2 = 5h + weekly).
`redeem_request_id` is an idempotency key — the same key on retry won't
double-spend (we generate a fresh UUID per redeem attempt).

## Backend — `src/core/usage/index.ts`

### Type change

Add one field to `CodexUsage`:

```ts
export interface CodexUsage {
  plan: string
  primaryWindow: UsageWindow
  secondaryWindow: UsageWindow
  credits: { hasCredits: boolean; balance: string } | null
  limitReached: boolean
  resetCredits: number   // banked rate-limit resets available
}
```

In `fetchCodexUsage`, parse it:

```ts
resetCredits: data.rate_limit_reset_credits?.available_count ?? 0,
```

### New redeem function

```ts
// Known backend codes, for documentation + tests. `code` is typed as string on
// the result so an unrecognized future code passes through instead of crashing.
export type CodexResetCode = "reset" | "nothing_to_reset" | "no_credit" | "already_redeemed"
export interface CodexResetResult { code: string; windowsReset: number }

export async function redeemCodexReset(
  authPath: string = CODEX_AUTH,
  idempotencyKey: string = globalThis.crypto.randomUUID(),  // fresh UUID per call (Bun Web Crypto)
): Promise<CodexResetResult>
```

- Reads the token exactly like `fetchCodexUsage`. If the auth file or token is
  missing, throw (so the endpoint reports it) — mirrors the fetch helpers'
  error discipline rather than returning null, because redeem is an explicit action.
- POSTs `{ redeem_request_id: idempotencyKey }` to the consume URL with the same
  10s `AbortSignal.timeout`.
- On `!res.ok`, throw `Codex reset API ${status}: ${body}` (matches `fetchCodexUsage`).
- Returns `{ code, windowsReset: data.windows_reset ?? 0 }`. An unrecognized
  `code` string is passed through as-is (typed loosely) so a new backend code
  doesn't crash us; the UI has a default message.
- `idempotencyKey` is a parameter (defaulting to a fresh UUID) purely so tests
  can assert the exact body.

## Backend — `src/channels/web/index.ts`

New route directly after the `GET /usage` handler:

```ts
if (method === "POST" && path === "/usage/codex/reset") {
  const { redeemCodexReset, fetchCodexUsage } = await import("../../core/usage/index")
  try {
    const result = await redeemCodexReset()
    const codex = await fetchCodexUsage().catch(() => null)  // best-effort refresh
    return this.json({ ...result, codex })
  } catch (err: any) {
    return this.json({ error: err?.message ?? String(err) }, 502)
  }
}
```

- Same cookie auth as the rest of the panel (no extra gating).
- Returns `{ code, windowsReset, codex }` so the client updates the card from one
  round-trip. If the post-redeem refetch fails, `codex` is `null` and the client
  falls back to its existing `refresh()`.

## Frontend — `src/web-app/src/api/client.ts`

```ts
redeemCodexReset: () =>
  request("POST", "/usage/codex/reset", {}) as Promise<{
    code: string; windowsReset: number; codex: CodexUsage | null
  }>,
```

## Frontend — `src/web-app/src/views/UsageView.vue`

`CodexUsage` interface (the local copy at the top of the file) gains
`resetCredits: number`.

### Pure helper (unit-tested) — `src/web-app/src/lib/codex-reset.ts`

Keep the message mapping out of the component so it's testable without a Vue harness:

```ts
export function codexResetNote(code: string, windowsReset: number): string {
  switch (code) {
    case "reset":            return `✓ Reset — cleared ${windowsReset} window${windowsReset === 1 ? "" : "s"}`
    case "nothing_to_reset": return "Nothing to reset right now — your windows aren't capped"
    case "no_credit":        return "No banked resets left"
    case "already_redeemed": return "That reset was already redeemed"
    default:                 return "Reset request completed"
  }
}
```

### Card UI (inside the existing Codex `<template v-if="codex">`)

A block after Credits:

- Always (codex present): a line "🎟️ Resets banked: {{ codex.resetCredits }}".
- When `codex.resetCredits > 0` and not currently confirming: a **"Use a reset"**
  button.
- When confirming: replace it with two buttons — **"Confirm · spends 1 of N"**
  (primary) and **"Cancel"**.
- While the request is in flight: disable + show a spinner (reuse the `RefreshCw`
  `animate-spin` pattern already imported).
- After completion: show `resetNote` as a small muted line; it clears on the next
  manual `refresh()`.

### Component state + handler

```ts
const confirmingReset = ref(false)
const redeeming = ref(false)
const resetNote = ref<string | null>(null)

async function useReset() {
  redeeming.value = true
  resetNote.value = null
  try {
    const res = await api.redeemCodexReset()
    if (res.codex && data.value) data.value.codex = res.codex   // update card in place
    else await refresh()                                        // server couldn't refetch — pull fresh
    resetNote.value = codexResetNote(res.code, res.windowsReset)
  } catch (e: any) {
    resetNote.value = e?.message ?? "Reset failed"
  } finally {
    redeeming.value = false
    confirmingReset.value = false
  }
}
```

Styling mirrors existing card elements (text-xs, `border-t border-border`,
`bg-muted`, rounded). No new icon library — reuse `RefreshCw`.

## Telegram — `src/core/usage/format.ts`

In `fmtCodex`, after the credits line:

```ts
if (c.resetCredits > 0) lines.push(`  Resets banked: ${c.resetCredits}`)
```

Display only; no redeem action over Telegram in v1.

## Resolved UX decisions

- **Confirm = inline two-step button**, not a modal — matches the card's minimal style.
- **Button shows whenever `resetCredits > 0`.** If the user isn't actually capped,
  the backend returns `nothing_to_reset` and (per the Codex client contract) does
  **not** spend a credit, so an unnecessary tap is safe; we show the "nothing to
  reset" note. (Alternative considered: only show when a window is maxed — rejected
  as it relies on threshold guessing and hides a harmless action.)

## Testing

`tests/usage.test.ts` (extend, same `globalThis.fetch` stub + temp-dir pattern):

- `fetchCodexUsage` parses `resetCredits` from `rate_limit_reset_credits.available_count`;
  defaults to 0 when the object is absent (extend the existing two Codex tests).
- `redeemCodexReset`:
  - POSTs to `https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume`
    with `Authorization: Bearer <token>`, `Content-Type: application/json`, and body
    `{ redeem_request_id: <passed key> }` (pass a fixed key to assert).
  - Maps each `code` (`reset` with `windows_reset` → `windowsReset`,
    `nothing_to_reset`, `no_credit`, `already_redeemed`).
  - Throws on `!res.ok`; throws when the auth file/token is missing.

`tests/usage-format.test.ts` (extend):

- `fmtCodex` includes `Resets banked: 3` when `resetCredits: 3`; omits the line when 0.

`src/web-app/src/lib/codex-reset.test.ts` (new, bun:test, mirrors
`archived-projects.test.ts` style):

- `codexResetNote` for each code + singular/plural window wording + unknown-code default.

Existing Codex tests must keep passing — they construct `CodexUsage` via the fetch
path, so adding `resetCredits` with a default keeps them green; update any explicit
object literals if present.

Manual verification: with a real `~/.codex/auth.json`, `GET /usage` returns
`codex.resetCredits`; the card shows the count; "Use a reset" → confirm → POST →
card refreshes and the note renders; Telegram `/usage` shows the banked line.

## Implementation order

1. `core/usage/index.ts`: `resetCredits` on type + parse; `redeemCodexReset` + tests.
2. `core/usage/format.ts`: banked line + test.
3. `channels/web/index.ts`: `POST /usage/codex/reset`.
4. `web-app/src/lib/codex-reset.ts` + test.
5. `web-app/src/api/client.ts`: `redeemCodexReset`.
6. `web-app/src/views/UsageView.vue`: `resetCredits` on interface, banked line,
   button + confirm + handler.
7. Green: `bun test`, `tsc --noEmit`, web-app build.

Backend (1–3) and frontend (4–6) touch disjoint files and can be built in parallel
against this fixed contract.
