/**
 * Black-box journey: pair a fresh browser, open the seeded session, send a
 * message through the PWA, receive the fake agent's reply through the real shim
 * socket, and verify both messages persisted in the broker.
 *
 * Run: scripts/test-broker.sh bun tests/ui/core-journey.spec.ts
 */
import { launchBrowser, uiFixture } from "./fixture-env"

async function main(): Promise<void> {
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

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, {
      waitUntil: "networkidle",
    })

    const list = page.locator('[data-testid="session-list"]')
    await list.waitFor({ state: "visible", timeout: 15_000 })
    const row = page.locator(
      `[data-testid="session-row"][data-session-id="${fixture.sessionId}"]`,
    )
    await row.waitFor({ state: "visible", timeout: 10_000 })
    await row.click()

    await page.locator('[data-testid="chat-view"]').waitFor({ state: "visible" })
    const composer = page.locator('[data-testid="composer-input"]')
    await composer.waitFor({ state: "visible" })

    const prompt = `journey-${Date.now()}`
    await composer.fill(prompt)
    await page.locator('[data-testid="composer-submit"]').click()

    await page.locator(
      `[data-testid="chat-message"][data-message-direction="inbound"]`,
      { hasText: prompt },
    ).waitFor({ state: "visible", timeout: 10_000 })
    await page.locator(
      `[data-testid="chat-message"][data-message-direction="outbound"]`,
      { hasText: `Fixture reply: ${prompt}` },
    ).waitFor({ state: "visible", timeout: 10_000 })

    const persisted = await page.evaluate(async (sessionId) => {
      const response = await fetch(`/sessions/${encodeURIComponent(sessionId)}/messages`)
      if (!response.ok) throw new Error(`messages endpoint returned ${response.status}`)
      return await response.json() as Array<{ direction?: string; text?: string }>
    }, fixture.sessionId)
    if (!persisted.some((entry) => entry.direction === "inbound" && entry.text === prompt)) {
      throw new Error("user message rendered but was not persisted")
    }
    if (!persisted.some((entry) => entry.direction === "outbound" && entry.text === `Fixture reply: ${prompt}`)) {
      throw new Error("agent reply rendered but was not persisted")
    }

    console.log("UI JOURNEY PASS: pair → open session → converse → persist")
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error("UI JOURNEY FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
  process.exit(1)
})
