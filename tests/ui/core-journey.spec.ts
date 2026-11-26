/**
 * Black-box journey against the Kotlin/Wasm client: pair a fresh browser, open
 * the seeded session, send a message, receive the fake agent's reply through the
 * real shim socket, and verify both messages persisted in the broker.
 *
 * Everything the browser can see of this app is Compose's accessibility mirror —
 * see `compose-dom.ts` for why taps go through `dispatchEvent` and why nothing is
 * ever `click()`ed.
 *
 * Run: scripts/test-broker.sh bun tests/ui/core-journey.spec.ts
 */
import { writeFileSync } from "node:fs"
import { join } from "node:path"
import type { Page } from "playwright"
import { launchBrowser, uiFixture, freshPairLink } from "./fixture-env"
import { byTag, byTagPrefix, innerTextOf, openSeededSession, tap, typeInto, waitForTextWithin } from "./compose-dom"

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI journey; run through scripts/test-broker.sh")
    return
  }

  const fixture = uiFixture()
  const browser = await launchBrowser()
  try {
    const context = await browser.newContext()
    const page = await context.newPage()
    page.on("pageerror", (error) => console.error(`[pageerror] ${error.message}`))
    // The page's own console, kept for a failure report: the broker's log alone can't say why a reply it sent
    // never showed (#166).
    const consoleLines: string[] = []
    page.on("console", (message) => { consoleLines.push(`${new Date().toISOString()} [${message.type()}] ${message.text()}`) })

    await page.goto((await freshPairLink(fixture)).url, {
      waitUntil: "domcontentloaded",
    })

    await openSeededSession(page, fixture.sessionId)

    const prompt = `journey-${Date.now()}`
    await typeInto(page, "composer-input", prompt)
    // Wait for the draft to reach Compose before pressing send. The send button
    // only carries a DOM click listener while it is ENABLED, and it is enabled
    // only once the composer has a non-blank draft — tapping between the last
    // keystroke and that recomposition dispatches a click into a dead element and
    // the journey then waits 30 s for a reply that was never asked for.
    await waitForTextWithin(byTag(page, "composer-input"), prompt, 15_000)
    await tap(byTag(page, "composer-send"))

    // The reply is asserted twice, because the two assertions fail for different
    // reasons: the TEXT proves the fake agent's round trip through the shim
    // socket, the per-row ID proves the transcript actually rendered it as an
    // outbound message row rather than echoing it somewhere else on screen.
    const replyText = `Fixture reply: ${prompt}`
    try {
      await page.getByText(replyText, { exact: false }).first()
        .waitFor({ state: "attached", timeout: 30_000 })
    } catch (error) {
      await reportMissingReply(page, fixture.sessionId, consoleLines)
      throw error
    }

    const outbound = byTagPrefix(page, "chat-message:outbound:")
    await outbound.last().waitFor({ state: "attached", timeout: 30_000 })
    const rows = await outbound.all()
    const texts = await Promise.all(rows.map(innerTextOf))
    if (!texts.some((t) => t.includes(replyText))) {
      throw new Error(`no chat-message:outbound: row carries the reply; rows=${JSON.stringify(texts)}`)
    }
    const inbound = byTagPrefix(page, "chat-message:inbound:")
    const inboundTexts = await Promise.all((await inbound.all()).map(innerTextOf))
    if (!inboundTexts.some((t) => t.includes(prompt))) {
      throw new Error(`no chat-message:inbound: row carries the prompt; rows=${JSON.stringify(inboundTexts)}`)
    }

    const persisted = await page.evaluate(async (sessionId) => {
      const response = await fetch(`/sessions/${encodeURIComponent(sessionId)}/messages`)
      if (!response.ok) throw new Error(`messages endpoint returned ${response.status}`)
      return await response.json() as Array<{ direction?: string; text?: string }>
    }, fixture.sessionId)
    if (!persisted.some((entry) => entry.direction === "inbound" && entry.text === prompt)) {
      throw new Error("user message rendered but was not persisted")
    }
    if (!persisted.some((entry) => entry.direction === "outbound" && entry.text === replyText)) {
      throw new Error("agent reply rendered but was not persisted")
    }

    console.log("UI JOURNEY PASS: pair → open session → converse → persist")
  } finally {
    await browser.close()
  }
}

/**
 * What the page showed when the reply did not appear, next to the broker's log (MUX_HOME is the fixture directory CI
 * uploads on failure): the chat's rows, what the broker persisted, the page's console and a screenshot.
 */
async function reportMissingReply(page: Page, sessionId: string, consoleLines: string[]): Promise<void> {
  const dir = process.env.MUX_HOME
  if (!dir) return
  const rows = await page.locator('[id^="chat-message:"]').evaluateAll((nodes) =>
    nodes.map((node) => `${node.id}: ${(node.textContent ?? "").trim().slice(0, 200)}`)).catch((e) => [`<rows: ${e}>`])
  const persisted = await page.evaluate(async (id) => {
    const response = await fetch(`/sessions/${encodeURIComponent(id)}/messages`)
    return response.ok ? JSON.stringify(await response.json()) : `messages endpoint ${response.status}`
  }, sessionId).catch((e) => `<messages: ${e}>`)
  const chat = await page.locator('[id="view_chat"]').first().innerText().catch((e) => `<view_chat: ${e}>`)
  writeFileSync(join(dir, "journey-failure.log"), [
    `at ${new Date().toISOString()}`,
    "--- chat rows", ...rows,
    "--- view_chat text", chat,
    "--- persisted messages", persisted,
    "--- page console", ...consoleLines,
  ].join("\n"))
  await page.screenshot({ path: join(dir, "journey-failure.png") }).catch(() => {})
}

if (import.meta.main) {
  main().catch((error) => {
    console.error("UI JOURNEY FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
