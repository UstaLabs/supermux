# Optional test run on Finish — two-step "Run tests / Skip tests"

**Date:** 2026-06-28
**Status:** Approved (brainstorm) → spec
**Surfaces:** Web PWA, Android, iOS (all three Finish sheets)
**Backend:** one additive readiness field (see §6); finish/skip path already fully plumbed

## 1. Problem

When finishing a worktree session, the Finish sheet's **Merge locally** and
**Open PR** actions always run the project's tests (`.mux/verify.sh`) before
integrating. The only ways to skip are **reactive**:

- *Merge anyway* — offered only after a red run (`tests_failed` outcome).
- *Merge without verifying* — offered only when no `.mux/verify.sh` exists (`no_verify` outcome).

There is no way to decide **up front** to skip tests — e.g. a docs-only change,
a branch the agent already verified, or when the user just wants speed. The user
wants an explicit, up-front **Run tests / Skip tests** choice.

## 2. Goal

Tapping **Merge locally** or **Open PR** presents a two-step choice — **Run
tests** (default) or **Skip tests** — before finish starts. Identical behaviour
and look across Web, Android, and iOS.

### Non-goals (YAGNI)

- No persisted preference / "remember my choice" — the user explicitly chose a
  two-step prompt, so it asks every time.
- No global toggle or settings entry.
- No change to verify-command auto-detection or `.mux/verify.sh` generation.
- The existing reactive recovery paths are unchanged.

## 3. Current behaviour (reference)

`FinishSheet.vue` / `FinishSheet.kt` / `FinishSheet.swift` each render the same
three-state machine driven by the live `FinishJob`:

- **Menu** — readiness preflight + action rows: *Merge locally*, *Open PR*,
  *Keep*, *Discard*. Each calls a `run(action, skipVerify?, commitFirst?, …)`.
- **Running** — live `stage`.
- **Outcome** — per-status recovery (incl. the two reactive skip paths above).

All three sheets already implement an inline **"Discard all work?" confirm**
that appears under the *Discard* row (`confirmingDiscard` state) — the model
this feature reuses.

`skipVerify` is already accepted **end-to-end**, verified in code:

| Layer | Location | Evidence |
|---|---|---|
| Web client | `src/web-app/src/api/client.ts:164` | `finish({ skipVerify })` |
| HTTP endpoint | `src/channels/web/index.ts:1645` | `skipVerify: body.skipVerify === true` |
| Finish job | `src/core/worktree/finish-job.ts:25,64` | `opts.skipVerify` → `finishWorktree` |
| Core | `src/core/worktree/finish.ts:79,147` | `if (!opts?.skipVerify)` |
| KMP API | `apps/shared/.../net/BrokerApi.kt:1219` | `finish(skipVerify: Boolean?)` |

Today only the **Outcome** state passes `skipVerify: true`. This feature adds an
up-front path from the **Menu**.

## 4. Proposed behaviour

A **two-step inline confirm** on the two verifying actions (*Merge locally*,
*Open PR*), modelled exactly on the existing "Discard all work?" confirm.

```
Menu:
  Merge locally            ← tap
     ↳ Run tests           (emphasized — primary/teal/green; default)
     ↳ Skip tests          (secondary — amber, matching "Merge anyway")
  Open PR                  ← tap → same two rows
  Keep
  Discard
```

1. User taps **Merge locally** (or **Open PR**).
2. Finish does **not** start. The sheet reveals two inline rows under the tapped
   action:
   - **Run tests** → `run(action, skipVerify: false)`
   - **Skip tests** → `run(action, skipVerify: true)`
3. Tapping the same action again, or any other menu action, collapses the
   choice. (Opening the choice for one action collapses it for the other —
   single pending action at a time.)
4. Finish then runs exactly as today (Running → Outcome), including all reactive
   recovery.

### 4.1 State

One component-local nullable field per sheet, analogous to `confirmingDiscard`:

```
pendingVerify: "merge" | "pr" | null
```

- Tap *Merge locally* → `pendingVerify = "merge"` (or collapse if already "merge").
- Tap *Open PR* → `pendingVerify = "pr"`.
- On choosing Run/Skip, or on any other action, reset to `null`.

### 4.2 Copy

- Parent rows keep their labels (*Merge locally*, *Open PR*).
- Choice rows: **"Run tests"** and **"Skip tests"** (the parent already conveys
  merge-vs-PR, so the choice rows stay terse).

### 4.3 Visual treatment per client (reuse existing primitives)

- **Web (`FinishSheet.vue`)** — render two buttons in an inline block beneath the
  tapped action, same container style as the `confirmingDiscard` block. *Run
  tests* = `bg-emerald-600` (primary); *Skip tests* = amber border (reuse the
  "Merge anyway" `border-amber-500/40 text-amber-400`).
- **Android (`FinishSheet.kt`)** — two `ActionRow`s inserted after the tapped
  action; *Run tests* tinted `cs.primary`, *Skip tests* tinted `cs.tertiary`
  (the amber used by "Merge anyway").
- **iOS (`FinishSheet.swift`)** — two `Button`s in the same `Section`; *Run
  tests* `Theme.teal`, *Skip tests* `.orange` (matching "Merge anyway").

## 5. Rules / decisions (approved)

1. **Scope:** applies to **both** Merge locally and Open PR.
2. **Default emphasis:** *Run tests* is always the first, emphasized option; no
   memory between invocations.
3. **`prRequiresGreen` guard:** when the repo's `.mux/finish.json` sets
   `prRequiresGreen: true`, the **Skip tests** row is **hidden on the Open PR
   path only** — skipping verification would silently defeat a "PR requires
   green" policy (`finish.ts` skips the whole verify block when `skipVerify`,
   so `prRequiresGreen` would never fire). Merge locally is unaffected.

## 6. The one backend addition — expose `prRequiresGreen` on readiness

Rule §5.3 needs the clients to know the repo's `prRequiresGreen`. It is **not**
currently on `FinishReadiness` (only on the finish *request* body). Add it as an
additive, optional field:

- `src/core/worktree/readiness.ts` — add `prRequiresGreen: boolean` to the
  `FinishReadiness` interface and to `ReadinessInput`; set it in
  `computeReadiness`. The readiness caller already loads `loadFinishConfig` (for
  `defaultAction`); thread `prRequiresGreen` through the same way.
- `src/web-app/src/api/client.ts` — add `prRequiresGreen: boolean` to the
  `FinishReadiness` interface (defaults to `false` if absent for back-compat).
- `apps/shared/.../net/BrokerApi.kt:299` — add `val prRequiresGreen: Boolean =
  false` to the `FinishReadiness` data class (default keeps old payloads decoding).

No other backend logic changes. The finish/skip execution path is untouched.

## 7. Components & boundaries

| Unit | Change | Depends on |
|---|---|---|
| `readiness.ts` | add `prRequiresGreen` field + populate | `loadFinishConfig` |
| web `client.ts` type | add `prRequiresGreen` to `FinishReadiness` | readiness payload |
| KMP `FinishReadiness` DTO | add `prRequiresGreen` (default false) | readiness payload |
| `FinishSheet.vue` | `pendingVerify` state + two-step rows + PR guard | web readiness type |
| `FinishSheet.kt` | `pendingVerify` state + two-step rows + PR guard | KMP DTO |
| `FinishSheet.swift` | `pendingVerify` state + two-step rows + PR guard | KMP DTO |

Each unit is independently testable; the three client sheets share no state and
can be implemented in parallel once the readiness field exists.

## 8. Edge cases (all preserved, not re-implemented)

- **Run tests → red:** existing `tests_failed` outcome (Merge anyway / Let the
  agent fix it).
- **Run tests → no `.mux/verify.sh`:** existing `no_verify` outcome (Generate
  verify / Merge without verifying).
- **Skip tests:** goes straight to integrate (merge) or push+PR — no verify card.
- **Uncommitted / sync_conflict / non_ff / nothing_to_do:** unchanged; surface
  exactly as today after the chosen path starts.
- **`nothingToLand`:** the Merge/PR rows aren't shown, so no two-step there.

## 9. Testing strategy

- **Backend (`readiness.test.ts`)** — `computeReadiness` returns
  `prRequiresGreen` reflecting `.mux/finish.json` (true / false / absent→false).
- **Web** — component test (or the existing FinishSheet test harness): tapping
  *Merge locally* reveals Run/Skip and does **not** call `api.finish` until a
  choice; *Run tests* calls `finish({action:"merge", skipVerify:false})`, *Skip
  tests* `skipVerify:true`; *Skip tests* hidden on PR when
  `prRequiresGreen`.
- **Android** — Compose UI test (testTags) mirroring the same assertions.
- **iOS** — `FinishSheet` test mirroring the same assertions (extend existing
  `FinishMessageTests` target).
- **KMP** — extend `BrokerApiSettingsTest` to assert `FinishReadiness` decodes
  `prRequiresGreen` and defaults to `false` when omitted.

## 10. Rollout

Single branch, all three clients together (consistency). Web is verifiable on
this host; Android builds here; iOS builds/tests go through the remote-Mac /
Linux-KMP path. No migration, no config change required of users — default
behaviour (tap → Run tests) matches today's one-decision flow with one extra tap.
