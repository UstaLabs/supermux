/**
 * Seeds a session, injects request_open, taps Allow once, asserts request_respond
 * reached the broker and the card closed after request_closed.
 *
 * Run: scripts/test-broker.sh bun tests/ui/prompts-journey.spec.ts
 */
import { launchBrowser, uiFixture, injectServerFrame, lastClientFrames } from "./fixture-env"
import { byTag, openSeededSession, tap } from "./compose-dom"

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

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, {
      waitUntil: "domcontentloaded",
    })
    await openSeededSession(page, fixture.sessionId)

    await injectServerFrame(page, {
      type: "request_open",
      session: fixture.sessionId,
      request: {
        requestId: "r-journey",
        kind: "permission",
        title: "Bash",
        body: "execute ls",
        options: [
          { id: "allow_once", label: "Allow once", kind: "allow_once" },
          { id: "reject_once", label: "Reject", kind: "reject_once" },
        ],
        allowFreeText: false,
        blocking: true,
      },
    })

    await tap(byTag(page, "request-option:allow_once"))

    const deadline = Date.now() + 15_000
    let sawRespond = false
    while (Date.now() < deadline) {
      const frames = await lastClientFrames(page)
      sawRespond = frames.some((f) => {
        const row = f as { type?: string; requestId?: string }
        return row.type === "request_respond" && row.requestId === "r-journey"
      })
      if (sawRespond) break
      await page.waitForTimeout(200)
    }
    if (!sawRespond) throw new Error("request_respond never reached the broker")

    await injectServerFrame(page, {
      type: "request_closed",
      session: fixture.sessionId,
      requestId: "r-journey",
      outcome: "answered",
    })

    await page.locator('[id="request-card:r-journey"]').first()
      .waitFor({ state: "detached", timeout: 15_000 })

    console.log("UI JOURNEY PASS: inject request_open → tap option → request_respond → card closed")
  } finally {
    await browser.close()
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error("UI JOURNEY FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
