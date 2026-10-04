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
const AFTER = LABEL.startsWith("after")
/** "t3" runs only the thread / truthful-actions states (09–14); anything else runs them all. */
const PART = process.env.SUBAGENT_SHOTS_PART || "all"

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

/**
 * Shoot the FOOT of an expanded card — where its reply box / reasons / Stop live. The cards under
 * test are the last items of the transcript, so wheel the transcript to its end and take the
 * viewport: the foot sits right above the composer dock. (The a11y mirror gives layout-only
 * containers no box, so the foot cannot be located and clipped directly.)
 */
async function shotFoot(page: Page, v: Variant, name: string, _cardTag: string, _bottomTag: string): Promise<void> {
  const vp = page.viewportSize()!
  for (let i = 0; i < 4; i++) {
    await page.mouse.move(vp.width - 40, vp.height * 0.35)
    await page.mouse.wheel(0, 3000)
    await settle(page, 500)
  }
  // No revealChrome() here: its wheel-up would scroll the foot back under the dock. A wheel DOWN
  // never tucks the dock on a pointer host, and the strip is part of the shot anyway.
  const file = `${OUT}/${v.width}-${v.theme}-${name}.png`
  await page.screenshot({ path: file })
  console.log(`shot ${file}`)
}

/** Wheel so [tag]'s bottom edge sits just above the composer dock. */
async function scrollToBottomOf(page: Page, tag: string): Promise<void> {
  const box = await page.locator(`[id="${tag}"]`).first().boundingBox().catch(() => null)
  if (!box) return
  const vp = page.viewportSize()!
  const target = vp.height - 260
  if (box.y + box.height > target) {
    await page.mouse.move(vp.width - 40, vp.height * 0.35)
    await page.mouse.wheel(0, box.y + box.height - target)
    await settle(page, 900)
  }
}

/** Expand a card and wait until its body is there (re-tapping once if the mirror was mid-rebuild). */
async function openCard(page: Page, id: string): Promise<void> {
  for (let i = 0; i < 3; i++) {
    if (await exists(page, `subagent-card-body:${id}`)) return
    await tap(byTag(page, `subagent-card-header:${id}`))
    await page.locator(`[id="subagent-card-body:${id}"]`).first().waitFor({ state: "attached", timeout: 3_000 }).catch(() => {})
  }
  await settle(page, 600)
}

/** Collapse a card (if open) and wait for the body to go. */
async function closeCard(page: Page, id: string): Promise<void> {
  for (let i = 0; i < 3; i++) {
    if (!(await exists(page, `subagent-card-body:${id}`))) return
    await tap(byTag(page, `subagent-card-header:${id}`))
    await page.locator(`[id="subagent-card-body:${id}"]`).first().waitFor({ state: "detached", timeout: 3_000 }).catch(() => {})
  }
}

/** Wheel the transcript to its end (LazyColumn only composes what is near the viewport). */
async function toEnd(page: Page): Promise<void> {
  const vp = page.viewportSize()!
  for (let i = 0; i < 4; i++) {
    await page.mouse.move(vp.width - 40, vp.height * 0.35)
    await page.mouse.wheel(0, 3000)
    await settle(page, 400)
  }
}

async function textOf(page: Page, tag: string): Promise<string> {
  return (await page.locator(`[id="${tag}"]`).first().innerText().catch(() => "")) ?? ""
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
    if (PART === "t3") {
      await threadStates(page, v, frame, now)
      return
    }

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
      for (let i = 0; i < 3 && !(await exists(page, `subagent-card-body:${fx.ids.a}`)); i++) {
        await revealChrome(page)
        await tap(byTag(page, `subagent-strip-row:${fx.ids.a}`))
        await page.locator(`[id="subagent-card-body:${fx.ids.a}"]`).first().waitFor({ state: "attached", timeout: 4_000 }).catch(() => {})
      }
      await settle(page, 800)
      if (!(await exists(page, `subagent-card-body:${fx.ids.a}`))) throw new Error("strip tap did not expand the card")
      await shot(page, v, "06b-strip-jump", `subagent-card:${fx.ids.a}`)

      // 07 — message field open, relay hint; then a real send (the broker 404s an injected id,
      // which must come back as an inline error).
      await scrollToTop(page, `subagent-card:${fx.ids.a}`)
      // T3: the reply box is simply there while the agent can take a message.
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
    await threadStates(page, v, frame, Date.now())
  } finally {
    await context.close()
  }
}

type Frame = (f: Record<string, unknown>) => Promise<void>

/**
 * T3 — the card as a thread, truthful actions, the main-chat marker:
 *   09 a conversation with 3 exchanges        12 stopped by you (Claude): reason, no Message
 *   10 collapsed card with "2 new replies"    13 Cursor running: Message, no Stop + reason
 *   11 closed by the main agent: reason        14 the "↪ to <name>" marker, and tapping it
 */
async function threadStates(page: Page, v: Variant, frame: Frame, now: number): Promise<void> {
  const sfx = `${v.width}${v.theme[0]}`
  const act = (event: Record<string, unknown>) => frame({ type: "activity_append", event })
  const upd = (subagent: Record<string, unknown>) => frame({ type: "subagent_update", subagent })
  const A = `01a10304-${sfx}`
  // The thread spans the last ~100 s, so in a full run (after states 01–08) its cards sort last.
  const t = (sec: number) => iso(now - 100_000 + sec * 1000)
  const msg = (sec: number, direction: "to" | "from", text: string, sender?: "user" | "parent") =>
    act({ ts: t(sec), kind: "subagent_message", title: text.split("\n")[0], text, direction, ...(sender ? { sender } : {}), subagentId: A })
  const tool = (sec: number, n: number, name: string, detail: string) => [
    act({ ts: t(sec), kind: "tool", tool: name, title: `${name}: ${detail}`, detail, phase: "started", callId: `t3-${sfx}-${n}`, subagentId: A }),
    act({ ts: t(sec + 1), kind: "tool_result", title: "done", phase: "completed", callId: `t3-${sfx}-${n}`, detail: "ok", subagentId: A }),
  ]
  const userLine = (sec: number, id: string, text: string) => frame({
    type: "message_append",
    entry: { id: `in:web:${id}-${sfx}`, ts: t(sec), direction: "inbound", channel: "web", text: `↪ to Anscombe: ${text}`, subagent_id: A },
  })
  const base = {
    id: A, name: "Anscombe", description: "Check the failing migration", background: false,
    prompt: "Find why the users migration fails on a fresh database and report back with the exact cause.",
    messaging: "direct", actionsSource: "native", model: "gpt-5.6-luna", parentCallId: `collab-${sfx}`,
    startedAt: now - 100_000, lastActivityAt: now, stats: { toolCalls: 4, tokens: 8210 },
  }

  await frame({ type: "subagents_cleared" })
  await frame({ type: "message_append", entry: { id: `t3-in-${sfx}`, ts: t(-3), direction: "inbound", text: "The users migration fails on a fresh DB. Can you get someone on it?" } })
  await frame({ type: "message_append", entry: { id: `t3-out-${sfx}`, ts: t(-2), direction: "outbound", text: "I've asked Anscombe to dig into it. You can talk to it directly from its card." } })
  await act({ ts: t(-1), kind: "tool", tool: "spawn_agent", title: "spawn_agent", description: "Check the failing migration", phase: "started", callId: `collab-${sfx}` })
  await msg(0, "to", "Find why the users migration fails on a fresh database and report back with the exact cause. Start with db/migrate.sql and the schema snapshot; do not change any files.", "parent")
  for (const p of tool(2, 1, "Read", "db/migrate.sql")) await p
  for (const p of tool(5, 2, "Grep", "NOT NULL")) await p
  await msg(9, "from", "Found it: the migration adds `users.plan` as **NOT NULL without a default**, so every existing row violates it.")
  await userLine(20, "u1", "Is it only the users table?")
  await msg(20, "to", "Is it only the users table?", "user")
  for (const p of tool(22, 3, "Grep", "ADD COLUMN .* NOT NULL")) await p
  await msg(26, "from", "Only `users`. `orgs.plan` was added the same way but with `DEFAULT 'free'`, which is why it never failed.")
  await userLine(40, "u2", "What would the fix be?")
  await msg(40, "to", "What would the fix be?", "user")
  await msg(44, "from", "Add the default in the migration:\n\n```sql\nALTER TABLE users ADD COLUMN plan text NOT NULL DEFAULT 'free';\n```\n\nThen backfill nothing — the default covers existing rows.")
  await userLine(60, "u3", "Great, write that up for the PR description.")
  await msg(60, "to", "Great, write that up for the PR description.", "user")
  for (const p of tool(62, 4, "Read", "db/schema.sql")) await p
  await msg(70, "from", "**Fix users migration on fresh databases**\n\n`users.plan` was added as NOT NULL without a default, so the migration failed on any database with existing rows. It now defaults to `'free'`, matching `orgs.plan`.")
  await upd({ ...base, status: "running", activity: "Waiting for your next message", canMessage: true, canStop: true, replies: 4 })
  await settle(page, 1500)
  await toEnd(page)
  await shot(page, v, "09-before-open")
  const card = `subagent-card:${A}`
  const header = `subagent-card-header:${A}`
  if (await exists(page, header)) {
    await openCard(page, A)
    await settle(page, 600)
    await scrollToTop(page, card)
    await shot(page, v, "09-thread", card)
    await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-09-thread-full.png` })
    await shotFoot(page, v, "09-thread-foot", card, `subagent-actions:${A}`)
    // 10 — collapse, two more replies arrive → "2 new replies".
    await closeCard(page, A)
    await settle(page)
  }
  await msg(80, "from", "I also checked the down migration — it drops the column cleanly, nothing to change there.")
  await msg(85, "from", "Done. Anything else?")
  await upd({ ...base, status: "running", activity: "Waiting for your next message", canMessage: true, canStop: true, replies: 6 })
  await settle(page, 1000)
  await revealChrome(page)
  await shot(page, v, "10-new-replies", AFTER ? card : undefined)
  // The header merges its children for accessibility, so the badge is read as the header's text.
  if (AFTER && !(await page.locator(`[id="${header}"]`).first().innerText()).includes("2 new replies")) throw new Error("no new-replies badge")
  await shot(page, v, "10-new-replies-full")

  // 11 — closed by the main agent: no Message, the reason instead.
  await upd({ ...base, status: "cancelled", endedBy: "parent", endedAt: now - 5_000, canMessage: false, canStop: false,
    cannotMessageReason: "Closed by the main agent — it can't take messages any more", cannotStopReason: "It isn't running", replies: 6 })
  await settle(page, 900)
  if (await exists(page, header)) {
    await openCard(page, A)
    if (AFTER && await exists(page, `subagent-message-field:${A}`)) throw new Error("closed agent still offers Message")
    if (AFTER && !(await textOf(page, card)).includes("Closed by the main agent")) throw new Error("closed agent shows no reason")
    await shotFoot(page, v, "11-closed-by-parent", card, `subagent-actions:${A}`)
    await closeCard(page, A)
    await settle(page)
  } else {
    await shot(page, v, "11-closed-by-parent")
  }

  // 12 — Claude, stopped by you: can't resume, so no Message.
  const C = `claude-${sfx}`
  await act({ ts: t(90), kind: "tool", tool: "Agent", title: "Agent: Benchmark the WS snapshot", description: "Benchmark the WS snapshot", phase: "started", callId: `p-${C}` })
  await act({ ts: t(91), kind: "subagent_message", title: "Measure", text: "Measure the WS snapshot size and encode time for 50 sessions and report the numbers.", direction: "to", sender: "parent", subagentId: C })
  await upd({ id: C, name: "general-purpose", description: "Benchmark the WS snapshot", status: "cancelled", endedBy: "client",
    messaging: "relay", canMessage: false, canStop: false, actionsSource: "derived", parentCallId: `p-${C}`,
    cannotMessageReason: "Stopped by you — Claude can't resume it", startedAt: now - 210_000, endedAt: now - 150_000, stats: { toolCalls: 2 } })
  // 13 — Cursor, running: Message yes, Stop no (with why).
  const K = `cursor-${sfx}`
  await act({ ts: t(95), kind: "tool", tool: "Task", title: "Task: Audit dependency licences", description: "Audit dependency licences", phase: "started", callId: `p-${K}` })
  await act({ ts: t(96), kind: "subagent_message", title: "List", text: "List every dependency whose licence is not MIT/Apache/BSD.", direction: "to", sender: "parent", subagentId: K })
  await act({ ts: t(97), kind: "tool", tool: "Read", title: "Read: package.json", detail: "package.json", phase: "started", callId: `k-${sfx}-1`, subagentId: K })
  await upd({ id: K, name: "Licence auditor", description: "Audit dependency licences", status: "running", activity: "Read package.json",
    messaging: "relay", canMessage: true, canStop: false, actionsSource: "derived", parentCallId: `p-${K}`,
    cannotStopReason: "Cursor can't stop subagents", startedAt: now - 30_000, stats: { toolCalls: 1 } })
  await settle(page, 1200)
  for (const [id, name, foot] of [[C, "12-stopped-by-you", `subagent-cannot-message:${C}`], [K, "13-cursor-running", `subagent-cannot-stop:${K}`]] as const) {
    const h = `subagent-card-header:${id}`
    if (await exists(page, h)) {
      await openCard(page, id)
      const reason = id === C ? "Claude can't resume it" : "Cursor can't stop subagents"
      if (AFTER && !(await textOf(page, `subagent-card:${id}`)).includes(reason)) throw new Error(`${name}: reason line missing`)
      if (AFTER && id === K && (await exists(page, `subagent-stop:${K}`) || !(await exists(page, `subagent-message-field:${K}`)))) throw new Error("cursor: wrong actions")
      await shotFoot(page, v, name, `subagent-card:${id}`, `subagent-actions:${id}`)
      await closeCard(page, id)
      await settle(page)
    } else {
      await shot(page, v, name)
    }
  }

  // 14 — the "↪ to Anscombe" marker in the main chat, and tapping it.
  const marker = `subagent-marker:in:web:u2-${sfx}`
  if (await exists(page, marker)) {
    await scrollToTop(page, marker)
    await revealChrome(page)
    await shot(page, v, "14-marker", marker, 60)
    await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-14-marker-full.png` })
    await tap(byTag(page, marker))
    await settle(page, 1200)
    if (!(await exists(page, `subagent-card-body:${A}`))) throw new Error("marker tap did not open the card")
    await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-14b-marker-jump-full.png` })
  } else {
    await page.mouse.move(v.width - 40, v.height * 0.4)
    await page.mouse.wheel(0, -600)
    await settle(page)
    await shot(page, v, "14-marker")
  }
  await frame({ type: "subagents_cleared" })
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
