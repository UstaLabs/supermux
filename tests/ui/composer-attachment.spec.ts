/**
 * Black-box attachment smoke against the Kotlin/Wasm client: pick a file through
 * the composer's attach button, send it, and verify the broker persisted an
 * inbound message carrying the attachment.
 *
 * On a pointer-sized window `composer-attach` opens the OS file chooser directly
 * (no attach menu, and Compose emits no `menuitem` role anywhere), so the journey
 * arms `page.waitForEvent("filechooser")` before tapping.
 *
 * Run: scripts/test-broker.sh bun tests/ui/composer-attachment.spec.ts
 */
import type { Browser } from "playwright"
import { rmSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { launchBrowser, uiFixture } from "./fixture-env"
import { byTag, openSeededSession, tap } from "./compose-dom"

const PIXEL_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAICAQCk/EwiAAAAAElFTkSuQmCC",
  "base64",
)

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI smoke; run through scripts/test-broker.sh")
    return
  }

  const fixture = uiFixture()
  const tmpFile = join(tmpdir(), `playwright-pixel-${Date.now()}.png`)
  writeFileSync(tmpFile, PIXEL_PNG)
  const filename = tmpFile.split("/").pop()!

  let browser: Browser | null = null
  try {
    browser = await launchBrowser()
    const context = await browser.newContext()
    const page = await context.newPage()
    page.on("pageerror", (error) => console.error(`[pageerror] ${error.message}`))

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, {
      waitUntil: "domcontentloaded",
    })
    await openSeededSession(page, fixture.sessionId)

    const chooserPromise = page.waitForEvent("filechooser", { timeout: 30_000 })
    await tap(byTag(page, "composer-attach"))
    const chooser = await chooserPromise
    await chooser.setFiles(tmpFile)

    // The chip an ATTACHED chat composer renders is `composer-chip` (it tracks a
    // real upload); `composer_staged_<name>` is the pre-spawn launcher's chip, for
    // files staged before a session exists. Accept either so the journey does not
    // encode which screen the fixture happens to open on.
    await page.locator('[id="composer-chip"], [id^="composer_staged_"]').first()
      .waitFor({ state: "attached", timeout: 30_000 })

    // Send is enabled by the staged attachment even with an empty draft, but the
    // enabling is a recomposition away from the chip appearing — retry rather than
    // dispatching one click into a not-yet-clickable button.
    const send = byTag(page, "composer-send")
    for (let i = 0; i < 10; i++) {
      await tap(send)
      const sent = await page.locator('[id="composer-chip"], [id^="composer_staged_"]').count()
      if (sent === 0) break
      await page.waitForTimeout(1000)
    }

    // The real assertion: the broker persisted an inbound web message whose
    // attachment list names the file we picked. Rendering a chip proves nothing
    // about the upload having landed.
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
      { timeout: 30_000 },
    )

    console.log("ATTACHMENT UI PASS: choose → upload → send → persist")
  } finally {
    await browser?.close()
    rmSync(tmpFile, { force: true })
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error("ATTACHMENT UI FAILED:", error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
