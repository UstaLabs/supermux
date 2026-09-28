/**
 * Visual harness + journey for subagents in the chat UI (slice S3): injects `subagent_update`,
 * `activity_append` (spawn row + the subagent's own rows), `bg_tasks`, `agent_state` and a
 * subagent `request_open`, then screenshots every state the brief lists at phone (390) and
 * desktop (900) widths in dark and light:
 *
 *   01 one running subagent with live activity       05 the composer strip with 4 running (+1 bg)
 *   02 three parallel (2 running, 1 done)            06 … the strip expanded
 *   03 expanded card: nested tool rows + long result 07 the message field open (relay hint)
 *   04 failed + stopped                              08 a subagent's permission request
 *
 * On the "after" build it also asserts the strip → card jump and that Stop/Message reach the
 * broker (whose 404 for an injected subagent must surface inline, not crash).
 *
 * Run: scripts/test-broker.sh bun tests/ui/subagent-shots.spec.ts
 * Out: $SUBAGENT_SHOTS_DIR (default /tmp/claude-1000/subagent-shots/<SUBAGENT_SHOTS_LABEL|after>)
 */
import { mkdirSync } from "node:fs"
import type { Browser, BrowserContext, Page } from "playwright"
import { launchBrowser, uiFixture, injectServerFrame } from "./fixture-env"
import { byTag, openSeededSession, tap, typeInto } from "./compose-dom"

const LABEL = process.env.SUBAGENT_SHOTS_LABEL || "after"
const OUT = process.env.SUBAGENT_SHOTS_DIR || `/tmp/claude-1000/subagent-shots/${LABEL}`
const AFTER = LABEL === "after"

type Variant = { width: number; height: number; theme: "dark" | "light" }
const VARIANTS: Variant[] = [
  { width: 390, height: 844, theme: "dark" },
  { width: 390, height: 844, theme: "light" },
  { width: 900, height: 900, theme: "dark" },
  { width: 900, height: 900, theme: "light" },
]

const settle = (page: Page, ms = 700) => page.waitForTimeout(ms)
const iso = (ms: number) => new Date(ms).toISOString()

async function exists(page: Page, id: string): Promise<boolean> {
  return (await page.locator(`[id="${id}"]`).count()) > 0
}

/** Wheel the transcript so [tag]'s top sits near the top of the viewport (Compose owns scrolling). */
async function scrollToTop(page: Page, tag: string): Promise<void> {
  const box = await page.locator(`[id="${tag}"]`).first().boundingBox().catch(() => null)
  if (!box) return
  const vp = page.viewportSize()!
  await page.mouse.move(vp.width - 40, vp.height * 0.35)
  await page.mouse.wheel(0, box.y - 150)
  await settle(page, 900)
}

/**
 * Bring the composer dock back: on a narrow chat pane, scrolling the transcript down tucks the
 * chrome (and with it the running strip) away; a small wheel-up restores it.
 */
async function revealChrome(page: Page): Promise<void> {
  const vp = page.viewportSize()!
  await page.mouse.move(vp.width - 40, vp.height * 0.4)
  await page.mouse.wheel(0, -120)
  await settle(page, 600)
  // At the very top a wheel-up has nothing to scroll; a tap on the transcript's gutter is the
  // documented way back (ChatChromeAutoHide: "a tap while tucked away brings it back").
  if (!(await page.locator('[id="subagent-strip"]').first().boundingBox().catch(() => null))?.height) {
    await page.mouse.click(8, vp.height * 0.3)
    await settle(page, 900)
  }
}

async function shot(page: Page, v: Variant, name: string, tag?: string, pad = 16): Promise<void> {
  const file = `${OUT}/${v.width}-${v.theme}-${name}.png`
  const box = tag ? await page.locator(`[id="${tag}"]`).first().boundingBox().catch(() => null) : null
  if (box && box.width > 0 && box.height > 0) {
    const vp = page.viewportSize()!
    const x = Math.max(0, box.x - pad)
    const y = Math.max(0, box.y - pad)
    await page.screenshot({
      path: file,
      clip: { x, y, width: Math.min(vp.width - x, box.width + pad * 2), height: Math.min(vp.height - y, box.height + pad * 2) },
    })
  } else {
    await page.screenshot({ path: file })
  }
  console.log(`shot ${file}`)
}

/** Rows the harness feeds; every id is suffixed per variant so contexts never collide. */
function fixtures(sfx: string, now: number) {
  const spawn = (callId: string, description: string, ts: number) => ({
    ts: iso(ts), kind: "tool", tool: "Agent", title: `Agent: ${description}`, description,
    phase: "started", callId,
  })
  const child = (sid: string, n: number, tool: string, detail: string, ts: number, done = true) => {
    const callId = `c-${sid}-${n}`
    const rows: Record<string, unknown>[] = [{
      ts: iso(ts), kind: "tool", tool, title: `${tool}: ${detail}`, detail, phase: "started", callId, subagentId: sid,
    }]
    if (done) rows.push({ ts: iso(ts + 400), kind: "tool_result", title: "done", phase: "completed", callId, detail: "ok", subagentId: sid })
    return rows
  }
  const sub = (id: string, extra: Record<string, unknown>) => ({
    id, name: "general-purpose", messaging: "relay", background: false, status: "running",
    stats: {}, startedAt: now - 60_000, lastActivityAt: now, ...extra,
  })
  const a = `sa-auth-${sfx}`, b = `sa-tests-${sfx}`, c = `sa-docs-${sfx}`
  const d = `sa-fail-${sfx}`, e = `sa-stop-${sfx}`, f = `sa-lint-${sfx}`, g = `sa-deps-${sfx}`
  const longResult = [
    "## Auth flow summary",
    "",
    "Sessions are created in `src/auth/session.ts` and persisted through `SessionStore`. Three things stand out:",
    "",
    "1. **Token refresh** happens lazily on the first 401, so two parallel requests can both refresh.",
    "2. The cookie is `SameSite=Lax`, which is fine for the PWA but not for the embedded preview.",
    "3. `logout()` clears the store but never revokes the refresh token server-side.",
    "",
    "Suggested fix: guard refresh with a single in-flight promise and call `/auth/revoke` on logout.",
    "",
    "Files read: `src/auth/session.ts`, `src/auth/store.ts`, `src/channels/web/index.ts`, `src/auth/cookies.ts`.",
  ].join("\n")
  return {
    ids: { a, b, c, d, e, f, g },
    first: {
      spawn: spawn(`p-${a}`, "Explore the auth flow", now - 60_000),
      children: [
        ...child(a, 1, "Glob", "src/auth/**/*.ts", now - 58_000),
        ...child(a, 2, "Read", "src/auth/session.ts", now - 50_000),
        ...child(a, 3, "Grep", "refreshToken", now - 30_000, false),
      ],
      sub: sub(a, {
        name: "Explore", description: "Explore the auth flow", parentCallId: `p-${a}`,
        activity: "Grep: refreshToken in src/", prompt: "Map how sessions are created, refreshed and revoked. Read src/auth/** and the web channel. Report the flow and anything risky, with file references.",
        stats: { toolCalls: 3, tokens: 18_400 },
      }),
    },
    parallel: [
      { spawn: spawn(`p-${b}`, "Run the test suite", now - 45_000), sub: sub(b, {
        description: "Run the test suite", parentCallId: `p-${b}`, startedAt: now - 45_000,
        activity: "Bash: bun test src/core --timeout 20000", stats: { toolCalls: 5, tokens: 9_100 },
      }), children: child(b, 1, "Bash", "bun test src/core --timeout 20000", now - 40_000, false) },
      { spawn: spawn(`p-${c}`, "Summarize the docs folder", now - 44_000), sub: sub(c, {
        name: "Explore", description: "Summarize the docs folder", parentCallId: `p-${c}`, status: "completed",
        startedAt: now - 44_000, endedAt: now - 10_000, activity: "Read docs/core-design/README.md",
        stats: { toolCalls: 14, tokens: 23_000, durationMs: 34_000 },
        result: longResult, resultClipped: true,
        prompt: "Read every markdown file under docs/ and summarize what each design doc decides, in under 200 words.",
      }), children: [
        ...child(c, 1, "Glob", "docs/**/*.md", now - 43_000),
        ...child(c, 2, "Read", "docs/core-design/README.md", now - 40_000),
        ...child(c, 3, "Read", "docs/core-design/subagents-probe-report.md", now - 35_000),
        ...child(c, 4, "Grep", "decision:", now - 20_000),
      ] },
    ],
    endings: [
      { spawn: spawn(`p-${d}`, "Migrate the settings schema", now - 30_000), sub: sub(d, {
        description: "Migrate the settings schema", parentCallId: `p-${d}`, status: "failed",
        startedAt: now - 30_000, endedAt: now - 18_000, stats: { toolCalls: 2 },
        result: "Error: migration 035 conflicts with 034_message_subagent.sql (duplicate column subagent_id).",
      }) },
      { spawn: spawn(`p-${e}`, "Benchmark the WS snapshot", now - 25_000), sub: sub(e, {
        description: "Benchmark the WS snapshot", parentCallId: `p-${e}`, status: "cancelled", background: true,
        startedAt: now - 25_000, endedAt: now - 12_000, stats: { toolCalls: 1 }, messaging: "none",
      }) },
    ],
    more: [
      sub(f, { description: "Lint the Kotlin sources", name: "general-purpose", startedAt: now - 20_000, activity: "Bash: ./gradlew ktlintCheck", background: true, messaging: "direct" }),
      sub(g, { description: "Audit dependency licences", name: "Explore", startedAt: now - 8_000, activity: "Read package.json" }),
    ],
  }
}

async function runVariant(browser: Browser, storage: Awaited<ReturnType<BrowserContext["storageState"]>>, v: Variant, sessionId: string, baseUrl: string): Promise<void> {
  const context = await browser.newContext({ viewport: { width: v.width, height: v.height }, colorScheme: v.theme, storageState: storage, deviceScaleFactor: 2 })
  await context.addInitScript((mode) => { try { localStorage.setItem("supermux:appearance:mode", mode) } catch {} }, v.theme === "dark" ? "DARK" : "LIGHT")
  const page = await context.newPage()
  page.on("pageerror", (error) => console.error(`[pageerror] ${error.message}`))
  const frame = (f: Record<string, unknown>) => injectServerFrame(page, { session: sessionId, ...f })
  const act = (event: Record<string, unknown>) => frame({ type: "activity_append", event })
  const upd = (subagent: Record<string, unknown>) => frame({ type: "subagent_update", subagent })
  try {
    await page.goto(baseUrl, { waitUntil: "domcontentloaded" })
    await openSeededSession(page, sessionId)
    const now = Date.now()
    const fx = fixtures(`${v.width}${v.theme[0]}`, now)

    await frame({ type: "subagents_cleared" })
    await frame({ type: "bg_tasks", tasks: [] })
    await frame({ type: "message_append", entry: { id: `in-${fx.ids.a}`, ts: iso(now - 62_000), direction: "inbound", text: "Look into how auth works and run the tests while you're at it." } })
    await frame({ type: "message_append", entry: { id: `out-${fx.ids.a}`, ts: iso(now - 61_000), direction: "outbound", text: "I'll split this up: one agent explores the auth flow while others run the suite and read the docs." } })
    await frame({ type: "agent_state", phase: "running", state: "working", working: true, detail: "running", tool: "Agent", workingSince: now - 61_000 })

    // 01 — one running subagent with live activity.
    await act(fx.first.spawn)
    for (const r of fx.first.children) await act(r)
    await upd(fx.first.sub)
    await settle(page, 1500)
    await shot(page, v, "01-one-running")
    if (AFTER) await shot(page, v, "01-one-running-card", `subagent-card:${fx.ids.a}`)

    // 02 — three in parallel: 2 running, 1 done.
    for (const p of fx.parallel) {
      await act(p.spawn)
      for (const r of p.children) await act(r)
      await upd(p.sub)
    }
    await settle(page, 1200)
    await shot(page, v, "02-parallel")

    // 03 — expand the finished one: nested tool rows + long markdown result.
    const doneId = fx.ids.c
    if (await exists(page, `subagent-card-header:${doneId}`)) {
      await tap(byTag(page, `subagent-card-header:${doneId}`))
      await settle(page, 900)
      await scrollToTop(page, `subagent-card:${doneId}`)
      await shot(page, v, "03-expanded", `subagent-card:${doneId}`)
      await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-03-expanded-full.png` })
      await tap(byTag(page, `subagent-card-header:${doneId}`))
      await settle(page)
    } else {
      await shot(page, v, "03-expanded")
    }

    // 04 — failed + stopped.
    for (const p of fx.endings) { await act(p.spawn); await upd(p.sub) }
    await settle(page, 1200)
    await revealChrome(page)
    await shot(page, v, "04-failed-stopped")
    if (AFTER) {
      await shot(page, v, "04-failed-card", `subagent-card:${fx.ids.d}`)
      await shot(page, v, "04-stopped-card", `subagent-card:${fx.ids.e}`)
    }

    // 05/06 — the strip with 4 running subagents and one background shell.
    for (const s of fx.more) await upd(s)
    await frame({ type: "bg_tasks", tasks: [{ id: `bg-${fx.ids.a}`, kind: "shell", label: "bun run dev --port 5173", startedAt: now - 90_000, status: "running" }] })
    await settle(page, 1200)
    await revealChrome(page)
    await shot(page, v, "05-strip-many", AFTER ? "subagent-strip" : undefined)
    await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-05-strip-many-full.png` })
    if (await exists(page, "subagent-strip-more")) {
      await tap(byTag(page, "subagent-strip-more"))
      await settle(page, 900)
      await revealChrome(page)
      await shot(page, v, "06-strip-expanded", "subagent-strip")
      await tap(byTag(page, "subagent-strip-more"))
      await settle(page)
    }

    // The strip → card jump (after only): tap the auth agent's strip row, its card expands.
    if (AFTER) {
      await tap(byTag(page, `subagent-strip-row:${fx.ids.a}`))
      await settle(page, 1200)
      if (!(await exists(page, `subagent-card-body:${fx.ids.a}`))) throw new Error("strip tap did not expand the card")
      await shot(page, v, "06b-strip-jump", `subagent-card:${fx.ids.a}`)

      // 07 — message field open, relay hint; then a real send (the broker 404s an injected id,
      // which must come back as an inline error).
      await scrollToTop(page, `subagent-card:${fx.ids.a}`)
      await tap(byTag(page, `subagent-message:${fx.ids.a}`))
      await settle(page)
      await typeInto(page, `subagent-message-field:${fx.ids.a}`, "Also check the logout path")
      await settle(page)
      await shot(page, v, "07-message-open", `subagent-card:${fx.ids.a}`)
    }
    // A real send reaches the fixture agent, whose echo reply then sits in the session's log for
    // every later variant — so only the LAST variant sends.
    if (AFTER && v === VARIANTS[VARIANTS.length - 1]) {
      await tap(byTag(page, `subagent-message-send:${fx.ids.a}`))
      // The fixture's Claude adapter either relays (note) or refuses an injected id (error);
      // both must land inline on the card.
      await page.locator(`[id="subagent-error:${fx.ids.a}"], [id="subagent-note:${fx.ids.a}"]`).first()
        .waitFor({ state: "attached", timeout: 20_000 })
      await settle(page)
      await shot(page, v, "07b-message-result", `subagent-card:${fx.ids.a}`)
    }
    if (AFTER) {
      await tap(byTag(page, `subagent-card-header:${fx.ids.a}`))
      await settle(page)
    }

    // 08 — a subagent asks for permission.
    const rid = `perm-${fx.ids.b}`
    await frame({
      type: "request_open",
      request: {
        requestId: rid, kind: "permission", title: "Bash", body: "Bash bun test src/core --timeout 20000",
        options: [
          { id: "allow_once", label: "Allow once", kind: "allow_once" },
          { id: "allow_always", label: "Always allow", kind: "allow_always" },
          { id: "reject_once", label: "Reject", kind: "reject_once" },
        ],
        allowFreeText: false, blocking: true,
        subagentId: fx.ids.b, subagentName: "general-purpose", subagentDescription: "Run the test suite",
      },
    })
    await byTag(page, `request-card:${rid}`).waitFor({ state: "attached", timeout: 15_000 })
    await settle(page)
    await revealChrome(page)
    await shot(page, v, "08-subagent-permission", `request-card:${rid}`)
    await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-08-subagent-permission-full.png` })
    await frame({ type: "request_closed", requestId: rid, outcome: "cancelled" })
    await frame({ type: "subagents_cleared" })
    await frame({ type: "bg_tasks", tasks: [] })
    await frame({ type: "agent_state", phase: "idle", state: "idle", working: false })
  } finally {
    await context.close()
  }
}

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping subagent shots; run through scripts/test-broker.sh")
    return
  }
  mkdirSync(OUT, { recursive: true })
  const fixture = uiFixture()
  const browser = await launchBrowser()
  try {
    const pairing = await browser.newContext()
    const pairPage = await pairing.newPage()
    await pairPage.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, { waitUntil: "domcontentloaded" })
    await openSeededSession(pairPage, fixture.sessionId)
    const storage = await pairing.storageState()
    await pairing.close()
    const only = process.env.SUBAGENT_SHOTS_ONLY
    for (const v of VARIANTS) {
      if (only && `${v.width}-${v.theme}` !== only) continue
      await runVariant(browser, storage, v, fixture.sessionId, fixture.baseUrl)
    }
    console.log(`SUBAGENT SHOTS PASS → ${OUT}`)
  } finally {
    await browser.close()
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error("SUBAGENT SHOTS FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
