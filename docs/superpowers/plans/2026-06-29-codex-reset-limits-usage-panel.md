# Codex Rate-Limit Resets — Usage Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show banked Codex rate-limit resets in the usage panel and let the user redeem one via a confirm-gated button.

**Architecture:** The banked count rides on the `wham/usage` response the panel already fetches. A new `redeemCodexReset()` core fn POSTs to Codex's consume endpoint with a UUID idempotency key; a new `POST /usage/codex/reset` broker route redeems + returns refreshed usage. The Vue card gains a banked line + inline two-step confirm button; message text lives in a unit-tested pure helper.

**Tech Stack:** Bun + TypeScript (broker), Vue 3 + Tailwind (web-app), `bun:test`.

**Spec:** `docs/superpowers/specs/2026-06-29-codex-reset-limits-usage-panel-design.md`

**Parallelism:** Tasks 1–4 (backend) and 5–7 (frontend) touch disjoint files and run as two parallel streams against the fixed contract below. Task 8 integrates.

**Fixed contract (both streams depend on this):**
`POST /usage/codex/reset` → `{ code: string; windowsReset: number; codex: CodexUsage | null }`
where `code` ∈ `reset | nothing_to_reset | no_credit | already_redeemed`.

---

## Stream A — Backend (`src/core` + `src/channels/web`)

### Task 1: Parse banked reset count into `CodexUsage`

**Files:**
- Modify: `src/core/usage/index.ts` (CodexUsage interface ~L24-30; fetchCodexUsage ~L149-155)
- Test: `tests/usage.test.ts` (extend the two Codex tests ~L103-164)

- [ ] **Step 1: Add failing assertions.** In `tests/usage.test.ts`, in `"fetchCodexUsage returns usage when auth valid"`, add `rate_limit_reset_credits: { available_count: 3 }` to the mocked JSON response body, and after the existing assertions add:

```ts
  expect(result!.resetCredits).toBe(3)
```

In `"fetchCodexUsage accepts legacy resets_at field"` (whose mock has no such object), add:

```ts
  expect(result!.resetCredits).toBe(0)
```

- [ ] **Step 2: Run, verify fail.** `bun test tests/usage.test.ts` → FAIL (`resetCredits` undefined / property missing).

- [ ] **Step 3: Implement.** In `src/core/usage/index.ts`, add the field to the interface:

```ts
export interface CodexUsage {
  plan: string
  primaryWindow: UsageWindow
  secondaryWindow: UsageWindow
  credits: { hasCredits: boolean; balance: string } | null
  limitReached: boolean
  resetCredits: number
}
```

In `fetchCodexUsage`, add to the returned object (after `limitReached`):

```ts
    resetCredits: data.rate_limit_reset_credits?.available_count ?? 0,
```

- [ ] **Step 4: Run, verify pass.** `bun test tests/usage.test.ts` → PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(usage): parse Codex banked reset count"`

---

### Task 2: `redeemCodexReset()` core function

**Files:**
- Modify: `src/core/usage/index.ts` (add types + fn after `fetchCodexUsage`, ~L156)
- Test: `tests/usage.test.ts`

- [ ] **Step 1: Write failing tests.** Add `redeemCodexReset` to the import at the top of `tests/usage.test.ts`, then append:

```ts
// ── Codex reset redemption ──

test("redeemCodexReset posts idempotency key and maps reset code", async () => {
  const authPath = join(tmpDir, "auth.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "codex-token-xyz" } }))

  globalThis.fetch = (async (url: any, init: any) => {
    expect(url).toBe("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume")
    expect(init?.method).toBe("POST")
    expect(init?.headers.Authorization).toBe("Bearer codex-token-xyz")
    expect(init?.headers["Content-Type"]).toBe("application/json")
    expect(JSON.parse(init.body)).toEqual({ redeem_request_id: "fixed-key-1" })
    return new Response(JSON.stringify({ code: "reset", windows_reset: 2 }))
  }) as typeof fetch

  const result = await redeemCodexReset(authPath, "fixed-key-1")
  expect(result.code).toBe("reset")
  expect(result.windowsReset).toBe(2)
})

test("redeemCodexReset maps no_credit code", async () => {
  const authPath = join(tmpDir, "auth.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "t" } }))
  globalThis.fetch = (async () =>
    new Response(JSON.stringify({ code: "no_credit", windows_reset: 0 }))) as unknown as typeof fetch
  const result = await redeemCodexReset(authPath, "k")
  expect(result.code).toBe("no_credit")
  expect(result.windowsReset).toBe(0)
})

test("redeemCodexReset throws when auth missing", async () => {
  await expect(redeemCodexReset(join(tmpDir, "nope.json"), "k")).rejects.toThrow()
})

test("redeemCodexReset throws on API error", async () => {
  const authPath = join(tmpDir, "auth.json")
  writeFileSync(authPath, JSON.stringify({ tokens: { access_token: "t" } }))
  globalThis.fetch = (async () => new Response("boom", { status: 500 })) as unknown as typeof fetch
  await expect(redeemCodexReset(authPath, "k")).rejects.toThrow()
})
```

- [ ] **Step 2: Run, verify fail.** `bun test tests/usage.test.ts` → FAIL (`redeemCodexReset` not exported).

- [ ] **Step 3: Implement.** In `src/core/usage/index.ts`, add a const near the other Codex code and the function after `fetchCodexUsage`:

```ts
// Known backend codes (documentation + tests). `code` is typed as string on the
// result so an unrecognized future code passes through instead of crashing.
export type CodexResetCode = "reset" | "nothing_to_reset" | "no_credit" | "already_redeemed"
export interface CodexResetResult { code: string; windowsReset: number }

const CODEX_RESET_CONSUME_URL =
  "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume"

export async function redeemCodexReset(
  authPath: string = CODEX_AUTH,
  idempotencyKey: string = globalThis.crypto.randomUUID(),
): Promise<CodexResetResult> {
  if (!existsSync(authPath)) throw new Error("Codex auth not found")
  const raw = JSON.parse(readFileSync(authPath, "utf-8"))
  const token = raw.tokens?.access_token
  if (!token) throw new Error("Codex access token not found")

  const res = await fetch(CODEX_RESET_CONSUME_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ redeem_request_id: idempotencyKey }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
  if (!res.ok) throw new Error(`Codex reset API ${res.status}: ${await res.text()}`)

  const data = (await res.json()) as any
  return { code: String(data.code ?? "unknown"), windowsReset: data.windows_reset ?? 0 }
}
```

- [ ] **Step 4: Run, verify pass.** `bun test tests/usage.test.ts` → PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(usage): redeemCodexReset() consumes a banked reset"`

---

### Task 3: Telegram `/usage` banked line

**Files:**
- Modify: `src/core/usage/format.ts` (`fmtCodex` ~L57-71)
- Test: `tests/usage-format.test.ts`

- [ ] **Step 1: Write failing test.** Open `tests/usage-format.test.ts` to match its existing fixture style (it builds a `UsageResponse` and calls `formatUsageTelegram`). Add a test that builds a codex object with `resetCredits: 3` and asserts the output contains `Resets banked: 3`, plus one with `resetCredits: 0` asserting it does NOT contain `Resets banked`. A full Codex fixture:

```ts
const codexFixture = (resetCredits: number) => ({
  plan: "plus",
  primaryWindow: { used: 10, resetsAt: null },
  secondaryWindow: { used: 5, resetsAt: null },
  credits: null,
  limitReached: false,
  resetCredits,
})

test("formatUsageTelegram shows Codex banked resets when > 0", () => {
  const out = formatUsageTelegram({ claude: null, codex: codexFixture(3), cursor: null, opencode: null, errors: {} } as any)
  expect(out).toContain("Resets banked: 3")
})

test("formatUsageTelegram omits banked resets when 0", () => {
  const out = formatUsageTelegram({ claude: null, codex: codexFixture(0), cursor: null, opencode: null, errors: {} } as any)
  expect(out).not.toContain("Resets banked")
})
```

(If the existing test file already imports `formatUsageTelegram`, reuse that import. Also update any existing Codex fixture in this file to include `resetCredits: 0` so it still type-checks.)

- [ ] **Step 2: Run, verify fail.** `bun test tests/usage-format.test.ts` → FAIL.

- [ ] **Step 3: Implement.** In `src/core/usage/format.ts`, in `fmtCodex`, after the `if (c.credits?.hasCredits) {...}` block and before `return lines.join("\n")`:

```ts
  if (c.resetCredits > 0) lines.push(`  Resets banked: ${c.resetCredits}`)
```

- [ ] **Step 4: Run, verify pass.** `bun test tests/usage-format.test.ts` → PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(usage): show Codex banked resets in Telegram /usage"`

---

### Task 4: `POST /usage/codex/reset` broker route

**Files:**
- Modify: `src/channels/web/index.ts` (immediately after the `GET /usage` handler, ~L1966-1970)

No new unit test (the route is thin glue over Task 2, which is fully tested; route is exercised in manual/verification). Keep it minimal.

- [ ] **Step 1: Implement.** Insert right after the `GET /usage` block:

```ts
    if (method === "POST" && path === "/usage/codex/reset") {
      const { redeemCodexReset, fetchCodexUsage } = await import("../../core/usage/index")
      try {
        const result = await redeemCodexReset()
        const codex = await fetchCodexUsage().catch(() => null) // best-effort refresh
        return this.json({ ...result, codex })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 502)
      }
    }
```

- [ ] **Step 2: Typecheck.** `tsc --noEmit` → no new errors.

- [ ] **Step 3: Commit.** `git add -A && git commit -m "feat(usage): POST /usage/codex/reset route"`

---

## Stream B — Frontend (`src/web-app`)

### Task 5: `codexResetNote` pure helper + test

**Files:**
- Create: `src/web-app/src/lib/codex-reset.ts`
- Test: `src/web-app/src/lib/codex-reset.test.ts`

- [ ] **Step 1: Write failing test.** Create `src/web-app/src/lib/codex-reset.test.ts`:

```ts
import { test, expect } from "bun:test"
import { codexResetNote } from "./codex-reset"

test("codexResetNote: reset uses singular/plural windows", () => {
  expect(codexResetNote("reset", 1)).toBe("✓ Reset — cleared 1 window")
  expect(codexResetNote("reset", 2)).toBe("✓ Reset — cleared 2 windows")
})

test("codexResetNote: known non-reset codes", () => {
  expect(codexResetNote("nothing_to_reset", 0)).toContain("Nothing to reset")
  expect(codexResetNote("no_credit", 0)).toContain("No banked resets")
  expect(codexResetNote("already_redeemed", 0)).toContain("already redeemed")
})

test("codexResetNote: unknown code falls back", () => {
  expect(codexResetNote("future_code", 0)).toBe("Reset request completed")
})
```

- [ ] **Step 2: Run, verify fail.** `bun test src/web-app/src/lib/codex-reset.test.ts` → FAIL (module missing).

- [ ] **Step 3: Implement.** Create `src/web-app/src/lib/codex-reset.ts`:

```ts
// Maps a Codex rate-limit-reset redemption code to a short user-facing note.
// Kept pure + outside the Vue component so it's unit-testable.
export function codexResetNote(code: string, windowsReset: number): string {
  switch (code) {
    case "reset":
      return `✓ Reset — cleared ${windowsReset} window${windowsReset === 1 ? "" : "s"}`
    case "nothing_to_reset":
      return "Nothing to reset right now — your windows aren't capped"
    case "no_credit":
      return "No banked resets left"
    case "already_redeemed":
      return "That reset was already redeemed"
    default:
      return "Reset request completed"
  }
}
```

- [ ] **Step 4: Run, verify pass.** `bun test src/web-app/src/lib/codex-reset.test.ts` → PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(usage): codexResetNote helper"`

---

### Task 6: API client method

**Files:**
- Modify: `src/web-app/src/api/client.ts` (the `api` object; place next to `getUsage`, ~L127)

- [ ] **Step 1: Implement.** Add to the `api` object:

```ts
  redeemCodexReset: () =>
    request("POST", "/usage/codex/reset", {}) as Promise<{
      code: string; windowsReset: number; codex: unknown
    }>,
```

(`codex` typed `unknown` to avoid importing the broker type; the view casts it.)

- [ ] **Step 2: Commit.** `git add -A && git commit -m "feat(usage): api.redeemCodexReset client method"`

---

### Task 7: Codex card — banked line + confirm button

**Files:**
- Modify: `src/web-app/src/views/UsageView.vue` (CodexUsage interface L9; script L1-75; Codex card template L166-207)

- [ ] **Step 1: Add `resetCredits` to the local interface (L9).** Change the `CodexUsage` interface to include it:

```ts
interface CodexUsage { plan: string; primaryWindow: UsageWindow; secondaryWindow: UsageWindow; credits: { hasCredits: boolean; balance: string } | null; limitReached: boolean; resetCredits: number }
```

- [ ] **Step 2: Import the helper + add state + handler.** Add the import near the top (`@/lib/codex-reset`) and, inside `<script setup>`, after the existing refs/computeds:

```ts
import { codexResetNote } from "@/lib/codex-reset"

const confirmingReset = ref(false)
const redeeming = ref(false)
const resetNote = ref<string | null>(null)

async function useReset() {
  redeeming.value = true
  resetNote.value = null
  try {
    const res = await api.redeemCodexReset()
    if (res.codex && data.value) data.value.codex = res.codex as CodexUsage
    else await refresh()
    resetNote.value = codexResetNote(res.code, res.windowsReset)
  } catch (e: any) {
    resetNote.value = e?.message ?? "Reset failed"
  } finally {
    redeeming.value = false
    confirmingReset.value = false
  }
}
```

(`ref` is already imported; `api` is already imported.)

- [ ] **Step 3: Add the card block.** Inside the Codex card's `<template v-if="codex">`, after the Credits `<div v-if="codex.credits && codex.credits.hasCredits">…</div>` block:

```html
            <!-- Banked rate-limit resets -->
            <div class="pt-2 mt-2 border-t border-border">
              <div class="flex items-center justify-between text-xs">
                <span class="text-muted-foreground">🎟️ Resets banked</span>
                <span>{{ codex.resetCredits }}</span>
              </div>
              <div v-if="codex.resetCredits > 0" class="mt-2">
                <button
                  v-if="!confirmingReset"
                  @click="confirmingReset = true"
                  :disabled="redeeming"
                  class="text-xs px-3 py-1.5 rounded-lg border border-border hover:bg-muted transition disabled:opacity-50"
                >Use a reset</button>
                <div v-else class="flex items-center gap-2">
                  <button
                    @click="useReset"
                    :disabled="redeeming"
                    class="text-xs px-3 py-1.5 rounded-lg bg-emerald-600 text-white hover:bg-emerald-500 transition disabled:opacity-50 flex items-center gap-1.5"
                  >
                    <RefreshCw v-if="redeeming" class="size-3.5 animate-spin" />
                    Confirm · spends 1 of {{ codex.resetCredits }}
                  </button>
                  <button
                    @click="confirmingReset = false"
                    :disabled="redeeming"
                    class="text-xs px-3 py-1.5 rounded-lg border border-border hover:bg-muted transition disabled:opacity-50"
                  >Cancel</button>
                </div>
              </div>
              <p v-if="resetNote" class="text-[11px] text-muted-foreground mt-2">{{ resetNote }}</p>
            </div>
```

(`RefreshCw` is already imported at L3.)

- [ ] **Step 4: Build/typecheck.** `cd src/web-app && bun run build` → vue-tsc passes, vite build succeeds.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(usage): Codex banked-reset display + redeem button"`

---

## Task 8: Green — full suite

**Files:** none (verification)

- [ ] **Step 1:** From repo root: `bun test` → all pass (core + web-app lib).
- [ ] **Step 2:** `tsc --noEmit` → clean.
- [ ] **Step 3:** `cd src/web-app && bun run build` → clean (`vue-tsc --noEmit && vite build`).
- [ ] **Step 4:** If all green, no commit needed (work already committed per task).

---

## Self-review (against spec)

- **Spec coverage:** resetCredits parse (T1) ✓ · redeem fn + 4 codes (T2) ✓ · Telegram line (T3) ✓ · broker route (T4) ✓ · note helper (T5) ✓ · client (T6) ✓ · card UI + confirm + handler (T7) ✓ · green (T8) ✓.
- **Types:** `CodexResetResult.code: string`; `redeemCodexReset(authPath, idempotencyKey)`; endpoint returns `{ code, windowsReset, codex }`; client mirrors it; view casts `codex as CodexUsage`. Consistent.
- **No placeholders:** every code/test/command is concrete.
