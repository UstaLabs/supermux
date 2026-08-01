/**
 * Black-box attachment smoke: choose a file through the visible composer menu,
 * send it, and verify the broker persisted an inbound message with an
 * attachment.
 *
 * Run: scripts/test-broker.sh bun tests/ui/composer-attachment.spec.ts
 */
import type { Browser } from "playwright"
import { rmSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { launchBrowser, uiFixture } from "./fixture-env"

const PIXEL_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAICAQCk/EwiAAAAAElFTkSuQmCC",
  "base64",
)

async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI smoke; run through scripts/test-broker.sh")
    return
  }

  const fixture = uiFixture()
  const tmpFile = join(tmpdir(), `playwright-pixel-${Date.now()}.png`)
  writeFileSync(tmpFile, PIXEL_PNG)

  let browser: Browser | null = null
  try {
    browser = await launchBrowser()
    const context = await browser.newContext()
    const page = await context.newPage()
    page.on("pageerror", (error) => console.error(`[pageerror] ${error.message}`))

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, {
      waitUntil: "networkidle",
    })
    await page.locator(
      `[data-testid="session-row"][data-session-id="${fixture.sessionId}"]`,
    ).click()
    await page.locator('[data-testid="composer-input"]').waitFor({ state: "visible" })

    const chooserPromise = page.waitForEvent("filechooser")
    await page.locator('[data-testid="attachment-menu"]').click()
    await page.getByRole("menuitem", { name: "Files" }).click()
    const chooser = await chooserPromise
    await chooser.setFiles(tmpFile)

    const filename = tmpFile.split("/").pop()!
    await page.locator(
      `[data-testid="attachment-chip"][data-filename="${filename}"]`,
    ).waitFor({ state: "visible", timeout: 10_000 })
    await page.locator('[data-testid="composer-submit"]').click()

    await page.waitForFunction(
      async ({ sessionId, expectedName }) => {
        const response = await fetch(`/sessions/${encodeURIComponent(sessionId)}/messages`)
        if (!response.ok) return false
        const messages = await response.json() as Array<{
          direction?: string
          channel?: string
          attachments?: Array<{ name?: string }>
        }>
        return messages.some((message) =>
          message.direction === "inbound"
          && message.channel === "web"
          && message.attachments?.some((attachment) => attachment.name === expectedName),
        )
      },
      { sessionId: fixture.sessionId, expectedName: filename },
      { timeout: 15_000 },
    )

    console.log("ATTACHMENT UI PASS: choose → upload → send → persist")
  } finally {
    await browser?.close()
    rmSync(tmpFile, { force: true })
  }
}

main().catch((error) => {
  console.error("ATTACHMENT UI FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
  process.exit(1)
})
