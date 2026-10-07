/**
 * Visual harness for the question / permission request card: injects a three-question
 * `request_open`, walks it (select, next, type free text, submit), closes it into a receipt, then
 * opens a permission card — screenshotting each state at phone (390) and desktop (900) widths in
 * dark and light themes. It also asserts the answer that reaches the broker, so it doubles as the
 * question-card journey.
 *
 * Run: scripts/test-broker.sh bun tests/ui/question-card-shots.spec.ts
 * Out: $QUESTION_SHOTS_DIR (default /tmp/claude-1000/question-card-shots/<QUESTION_SHOTS_LABEL|after>)
 */
import { mkdirSync } from "node:fs"
import type { Browser, Page } from "playwright"
import { launchBrowser, uiFixture, injectServerFrame, lastClientFrames } from "./fixture-env"
import { byTag, openSeededSession, tap } from "./compose-dom"

const LABEL = process.env.QUESTION_SHOTS_LABEL || "after"
const OUT = process.env.QUESTION_SHOTS_DIR || `/tmp/claude-1000/question-card-shots/${LABEL}`

const QUESTIONS = [
  {
    id: "q1",
    header: "Goal",
    prompt: "What would you like to work on in this deneme project?",
    multiSelect: false,
    allowFreeText: false,
    options: [
      { id: "o1", label: "Test supermux features", description: "Exercise the channel end to end: prompts, attachments and replies." },
      { id: "o2", label: "Build something small", description: "A tiny throwaway app so there is real code to review." },
      { id: "o3", label: "Tidy the untracked files" },
    ],
  },
  {
    id: "q2",
    header: "Features",
    prompt: "Which channel features should I exercise? This question is deliberately long so the prompt has to wrap across more than one line on a phone.",
    multiSelect: true,
    allowFreeText: false,
    options: [
      { id: "o1", label: "Multiple choice" },
      { id: "o2", label: "Multi-select", description: "Several answers at once." },
      { id: "o3", label: "File attachments and a deliberately long option label that must wrap cleanly onto a second line" },
    ],
  },
  {
    id: "q3",
    header: "Branch name",
    prompt: "What should the working branch be called?",
    multiSelect: false,
    allowFreeText: true,
    options: [
      { id: "o1", label: "deneme/explore" },
      { id: "o2", label: "main" },
    ],
  },
]

type Variant = { width: number; height: number; theme: "dark" | "light" }
const VARIANTS: Variant[] = [
  { width: 390, height: 844, theme: "dark" },
  { width: 390, height: 844, theme: "light" },
  { width: 900, height: 900, theme: "dark" },
  { width: 900, height: 900, theme: "light" },
]

async function exists(page: Page, id: string): Promise<boolean> {
  return (await page.locator(`[id="${id}"]`).count()) > 0
}

async function settle(page: Page, ms = 700): Promise<void> {
  await page.waitForTimeout(ms)
}

/** The card's box from the a11y mirror, padded; the composer dock when the card is gone. */
async function shot(page: Page, v: Variant, name: string, tag: string): Promise<void> {
  const file = `${OUT}/${v.width}-${v.theme}-${name}.png`
  const target = page.locator(`[id="${tag}"]`).first()
  const box = await target.boundingBox().catch(() => null)
  if (box && box.width > 0 && box.height > 0) {
    const pad = 16
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

/**
 * Wait for the answer frame. [retap] re-presses once after 5 s: the a11y mirror is rebuilt on a
 * debounce, and a tap dispatched while it is mid-rebuild occasionally lands on a stale node. A
 * second press is harmless — the card goes inert after the first one that registers.
 */
async function waitRespond(page: Page, requestId: string, retap?: () => Promise<void>): Promise<Record<string, unknown>> {
  const deadline = Date.now() + 15_000
  const retapAt = Date.now() + 5_000
  let retapped = false
  while (Date.now() < deadline) {
    if (retap && !retapped && Date.now() > retapAt) {
      retapped = true
      await retap().catch(() => {})
    }
    const frames = await lastClientFrames(page)
    const hit = frames.find((f) => {
      const row = f as { type?: string; requestId?: string }
      return row.type === "request_respond" && row.requestId === requestId
    })
    if (hit) return hit as Record<string, unknown>
    await page.waitForTimeout(200)
  }
  throw new Error(`request_respond for ${requestId} never reached the broker`)
}

async function runVariant(browser: Browser, storage: Awaited<ReturnType<import("playwright").BrowserContext["storageState"]>>, v: Variant, sessionId: string, baseUrl: string): Promise<void> {
  const context = await browser.newContext({
    viewport: { width: v.width, height: v.height },
    colorScheme: v.theme,
    storageState: storage,
    deviceScaleFactor: 2,
  })
  await context.addInitScript((mode) => {
    try { localStorage.setItem("supermux:appearance:mode", mode) } catch {}
  }, v.theme === "dark" ? "DARK" : "LIGHT")
  const page = await context.newPage()
  page.on("pageerror", (error) => console.error(`[pageerror] ${error.message}`))
  try {
    await page.goto(baseUrl, { waitUntil: "domcontentloaded" })
    await openSeededSession(page, sessionId)

    const rid = `q-shots-${v.width}-${v.theme}`
    await injectServerFrame(page, {
      type: "request_open",
      session: sessionId,
      request: {
        requestId: rid,
        kind: "question",
        title: "Goal",
        body: JSON.stringify(QUESTIONS),
        options: QUESTIONS[0]!.options.map((o) => ({ id: o.id, label: o.label })),
        allowFreeText: true,
        blocking: true,
      },
    })
    const card = `request-card:${rid}`
    await byTag(page, card).waitFor({ state: "attached", timeout: 30_000 })
    await settle(page, 1200)
    await shot(page, v, "01-open", card)
    await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-01-open-full.png` })

    // Question 1: pick the first option.
    await tap(byTag(page, "request-option:o1"))
    await settle(page)
    await shot(page, v, "02-q1-selected", card)

    // Question 2 (paged flow → Next; stacked → options are already on screen).
    if (await exists(page, "request-next")) {
      await tap(byTag(page, "request-next"))
      await settle(page)
    }
    await tap(byTag(page, "request-option:o1"))
    await tap(byTag(page, "request-option:o3"))
    await settle(page)
    await shot(page, v, "03-q2-multi", card)

    // Question 3: free text.
    if (await exists(page, "request-next")) {
      await tap(byTag(page, "request-next"))
      await settle(page)
    }
    if (await exists(page, "request-freetext")) {
      await tap(byTag(page, "request-freetext"))
      await page.keyboard.type("deneme/question-card", { delay: 10 })
      await settle(page)
    }
    await shot(page, v, "04-q3-freetext", card)

    if (await exists(page, "request-send")) {
      await tap(byTag(page, "request-send"))
      const respond = await waitRespond(page, rid, () => tap(byTag(page, "request-send")))
      console.log(`[${v.width}-${v.theme}] respond ${JSON.stringify(respond)}`)
      if (LABEL === "after") {
        const answers = (respond.answer as { answers?: Record<string, unknown> })?.answers
        const expected = { q1: "o1", q2: ["o1", "o3"], q3: "deneme/question-card" }
        if (JSON.stringify(answers) !== JSON.stringify(expected)) {
          throw new Error(`unexpected answers ${JSON.stringify(answers)}; wanted ${JSON.stringify(expected)}`)
        }
      }
      await settle(page, 300)
      await shot(page, v, "05-sending", card)
    }

    await injectServerFrame(page, {
      type: "request_closed",
      session: sessionId,
      requestId: rid,
      outcome: "answered",
      answerLabel: "Test supermux features, Multiple choice, File attachments and a deliberately long option label that must wrap cleanly onto a second line, deneme/question-card",
    })
    await page.locator(`[id="${card}"]`).first().waitFor({ state: "detached", timeout: 15_000 })
    await settle(page)
    await shot(page, v, "06-receipt", `request-receipt:${rid}`)

    // A declined one, for the receipt's other face.
    const rid2 = `q-decline-${v.width}-${v.theme}`
    await injectServerFrame(page, {
      type: "request_open",
      session: sessionId,
      request: {
        requestId: rid2, kind: "question", title: "Deploy",
        body: JSON.stringify([{ id: "q1", header: "Deploy", prompt: "Ship it to production now?", options: [{ id: "o1", label: "Yes" }, { id: "o2", label: "Not yet" }] }]),
        options: [{ id: "o1", label: "Yes" }, { id: "o2", label: "Not yet" }], allowFreeText: false, blocking: true,
      },
    })
    await byTag(page, `request-card:${rid2}`).waitFor({ state: "attached", timeout: 15_000 })
    await settle(page)
    await shot(page, v, "07-single-question", `request-card:${rid2}`)
    await tap(byTag(page, "request-decline"))
    await waitRespond(page, rid2)
    await injectServerFrame(page, { type: "request_closed", session: sessionId, requestId: rid2, outcome: "answered", answerLabel: "Declined" })
    await page.locator(`[id="request-card:${rid2}"]`).first().waitFor({ state: "detached", timeout: 15_000 })

    // Permission cards: a short command with two options, a long one with three, then an MCP
    // tool (arguments as rows). The broker prefixes the body with the tool name ("Bash <cmd>"),
    // which the card must not repeat under its "Allow Bash?" header.
    const perms = [
      {
        name: "08-permission-short-2opt", title: "Bash", body: "Bash git status --short",
        options: [
          { id: "allow_once", label: "Allow once", kind: "allow_once" },
          { id: "reject_once", label: "Reject once", kind: "reject_once" },
        ],
      },
      {
        name: "09-permission-long-3opt", title: "Bash",
        body: "Bash printf 'hello from deneme-3\\nwritten at %s\\n' \"$(date)\" > bashtest.md && cat bashtest.md && find apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat -name '*.kt' -newer bashtest.md | xargs wc -l | sort -n | tail -20",
        options: [
          { id: "allow_once", label: "Allow once", kind: "allow_once" },
          { id: "allow_always", label: "Always allow", kind: "allow_always" },
          { id: "reject_once", label: "Reject", kind: "reject_once" },
        ],
      },
      {
        name: "10-permission-mcp", title: "mcp__mux-shim__rename_session",
        body: '{"name":"question-card-redesign","session":"ahmet"}',
        options: [
          { id: "allow_once", label: "Allow once", kind: "allow_once" },
          { id: "allow_always", label: "Always allow", kind: "allow_always" },
          { id: "reject_once", label: "Reject", kind: "reject_once" },
        ],
      },
    ]
    for (const perm of perms) {
      const rid3 = `perm-${perm.name}-${v.width}-${v.theme}`
      await injectServerFrame(page, {
        type: "request_open",
        session: sessionId,
        request: { requestId: rid3, kind: "permission", title: perm.title, body: perm.body, options: perm.options, allowFreeText: false, blocking: true },
      })
      await byTag(page, `request-card:${rid3}`).waitFor({ state: "attached", timeout: 15_000 })
      await settle(page)
      await shot(page, v, perm.name, `request-card:${rid3}`)
      if (perm.name.startsWith("09")) {
        await page.screenshot({ path: `${OUT}/${v.width}-${v.theme}-${perm.name}-full.png` })
        console.log(`shot ${OUT}/${v.width}-${v.theme}-${perm.name}-full.png`)
        // First Reject tap opens the optional note.
        await tap(byTag(page, "request-option:reject_once"))
        await settle(page)
        await shot(page, v, "11-permission-reject-note", `request-card:${rid3}`)
      }
      await tap(byTag(page, perm.name.startsWith("09") ? "request-option:reject_once" : "request-option:allow_once"))
      await waitRespond(page, rid3)
      await injectServerFrame(page, { type: "request_closed", session: sessionId, requestId: rid3, outcome: "answered", answerLabel: "Allow once" })
      await page.locator(`[id="request-card:${rid3}"]`).first().waitFor({ state: "detached", timeout: 15_000 })
    }
  } finally {
    await context.close()
  }
}

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping question-card shots; run through scripts/test-broker.sh")
    return
  }
  mkdirSync(OUT, { recursive: true })
  const fixture = uiFixture()
  const browser = await launchBrowser()
  try {
    // Pair once, then reuse the cookie jar for every width/theme context.
    const pairing = await browser.newContext()
    const pairPage = await pairing.newPage()
    await pairPage.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, { waitUntil: "domcontentloaded" })
    await openSeededSession(pairPage, fixture.sessionId)
    const storage = await pairing.storageState()
    await pairing.close()

    const only = process.env.QUESTION_SHOTS_ONLY // e.g. "390-dark"
    for (const v of VARIANTS) {
      if (only && `${v.width}-${v.theme}` !== only) continue
      await runVariant(browser, storage, v, fixture.sessionId, fixture.baseUrl)
    }
    console.log(`QUESTION CARD SHOTS PASS → ${OUT}`)
  } finally {
    await browser.close()
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error("QUESTION CARD SHOTS FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
