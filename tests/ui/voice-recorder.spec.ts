/**
 * UI smoke for the voice composer.
 *
 * Headless Chrome has no real mic. We use Chrome's --use-fake-device-for-media-stream
 * flag (plus --use-fake-ui-for-media-stream to bypass the permission prompt) to
 * synthesize a mic stream. The VoiceRecorder + MediaRecorder flow then runs
 * end-to-end exactly as it would in a real session.
 *
 * Run: scripts/test-broker.sh bun tests/ui/voice-recorder.spec.ts
 */

import type { Browser } from "playwright"
import { launchBrowser, uiFixture } from "./fixture-env"

declare const document: {
  querySelector(selector: string): { disabled?: boolean } | null
}
type PromptInputHook = {
  textInput: { value: string }
  setTextInput(value: string): void
}

async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI smoke; run through scripts/test-broker.sh")
    return
  }

  const fixture = uiFixture()

  let browser: Browser | null = null
  try {
    browser = await launchBrowser({
      args: [
        "--use-fake-ui-for-media-stream",
        "--use-fake-device-for-media-stream",
      ],
    })
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    page.on("console", (msg) => console.log(`[console.${msg.type()}] ${msg.text()}`))
    page.on("pageerror", (err) => console.log(`[pageerror] ${err.message}`))

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, { waitUntil: "networkidle" })
    await page.locator(`[data-testid="session-row"][data-session-id="${fixture.sessionId}"]`).click()
    await page.locator('[data-testid="composer-input"]').waitFor({ state: "visible", timeout: 10_000 })

    // Stub the transcribe endpoint so the assertion is deterministic
    // (the fake mic produces non-speech audio; real whisper won't return useful text)
    await page.route("**/sessions/*/transcribe", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ text: "hello from transcribe" }),
      }),
    )

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

    // Stop the recording — triggers POST to /sessions/:id/transcribe
    await stopBtn.click()
    console.log("stop clicked")

    // After stop, transcribed text should be dropped into the composer (not sent)
    await page.waitForFunction(
      () =>
        (globalThis as typeof globalThis & { __cmuxPromptInput?: PromptInputHook })
          .__cmuxPromptInput?.textInput?.value?.includes("hello from transcribe"),
      { timeout: 10_000 },
    )
    console.log("transcribed text appeared in composer")

    console.log("\n=== TEST PASSED ===")
  } finally {
    if (browser) await browser.close()
  }
}

main().catch((e) => {
  console.error("TEST FAILED:", e instanceof Error ? e.stack ?? e.message : String(e))
  process.exit(1)
})
