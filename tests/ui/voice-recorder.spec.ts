/**
 * UI smoke for the voice composer.
 *
 * Headless Chrome has no real mic. We use Chrome's --use-fake-device-for-media-stream
 * flag (plus --use-fake-ui-for-media-stream to bypass the permission prompt) to
 * synthesize a mic stream. The VoiceRecorder + MediaRecorder flow then runs
 * end-to-end exactly as it would in a real session.
 *
 * Run: bun tests/ui/voice-recorder.spec.ts
 */

import { chromium, type Browser } from "playwright"
import { DeviceStore } from "../../src/channels/web/device-store"
import { existsSync } from "fs"
import { join } from "path"

declare const document: {
  querySelector(selector: string): { disabled?: boolean } | null
}

const STATE_DIR = process.env.MUX_STATE_DIR ?? join(process.env.HOME ?? "", ".mux/state")
const MUX_WEB_PORT = parseInt(process.env.MUX_WEB_PORT ?? "9898", 10)
const APP_URL = `http://127.0.0.1:${MUX_WEB_PORT}`
const CHROME_BIN = "/usr/bin/google-chrome"

async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI smoke; set MUX_RUN_UI_SMOKE=1 to run")
    return
  }

  const res = await fetch(`${APP_URL}/sessions`).catch(() => null)
  if (!res) throw new Error(`broker not reachable at ${APP_URL}`)

  const ds = new DeviceStore(join(STATE_DIR, "devices.json"))
  const minted = ds.mint("voice-test-temp")
  const token = minted.token

  let browser: Browser | null = null
  try {
    if (!existsSync(CHROME_BIN)) throw new Error(`chrome not found at ${CHROME_BIN}`)
    browser = await chromium.launch({
      executablePath: CHROME_BIN,
      headless: true,
      args: [
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--use-fake-ui-for-media-stream",
        "--use-fake-device-for-media-stream",
      ],
    })
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    page.on("console", (msg) => console.log(`[console.${msg.type()}] ${msg.text()}`))
    page.on("pageerror", (err) => console.log(`[pageerror] ${err.message}`))

    await page.goto(`${APP_URL}/pair?t=${encodeURIComponent(token)}`, { waitUntil: "networkidle" })
    await page.waitForSelector("text=ana", { timeout: 10_000 })
    await page.click("text=ana")
    await page.waitForSelector('textarea[placeholder*="Message"]', { timeout: 10_000 })

    // Tap mic button
    const micBtn = page.locator('button[aria-label="Record voice message"]')
    await micBtn.waitFor({ state: "visible", timeout: 5_000 })
    await micBtn.click()
    console.log("mic clicked")

    // Recording bar should appear (the cancel + stop buttons)
    const stopBtn = page.locator('button[aria-label="Stop recording"]')
    await stopBtn.waitFor({ state: "visible", timeout: 10_000 })
    console.log("recording bar visible")

    // Let some audio capture happen
    await page.waitForTimeout(1500)

    // Stop the recording
    await stopBtn.click()
    console.log("stop clicked")

    // Voice chip should appear in composer (mic icon + duration)
    const voiceChip = page.locator('button[aria-label="Play voice memo"]').first()
    await voiceChip.waitFor({ state: "visible", timeout: 5_000 })
    console.log("voice chip appeared")

    // Tap send
    const submitBtn = page.locator('button[aria-label="Submit"]')
    await submitBtn.waitFor({ state: "visible" })
    await page.waitForFunction(
      () => !document.querySelector('button[aria-label="Submit"]')?.disabled,
      { timeout: 5_000 },
    )
    await submitBtn.click()
    console.log("submit clicked")

    // Wait for upload to settle (toast cycle)
    await page.waitForTimeout(2500)

    // Verify broker received a voice attachment in ana's recent messages
    const msgs = await fetch(`${APP_URL}/sessions/ana/messages`, {
      headers: { Cookie: `cmux_token=${token}` },
    }).then((r) => r.json())
    const found = (msgs as any[]).some((m) =>
      m.direction === "inbound" &&
      m.channel === "web" &&
      Array.isArray(m.attachments) &&
      m.attachments.some((a: any) => a.kind === "voice"),
    )
    if (found) console.log("broker recorded an inbound voice attachment")
    else throw new Error("no inbound voice attachment found in ana messages")

    console.log("\n=== TEST PASSED ===")
  } finally {
    if (browser) await browser.close()
    new DeviceStore(join(STATE_DIR, "devices.json")).revoke("voice-test-temp")
  }
}

main().catch((e) => {
  console.error("TEST FAILED:", e instanceof Error ? e.stack ?? e.message : String(e))
  process.exit(1)
})
