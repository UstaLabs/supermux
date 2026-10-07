/**
 * UI smoke for the voice composer on the Kotlin/Wasm client.
 *
 * Headless Chrome has no microphone, so `--use-fake-device-for-media-stream`
 * synthesizes one and `--use-fake-ui-for-media-stream` grants it without a
 * prompt; `WebMic` + MediaRecorder then run exactly as in a real session. The
 * fake device emits a tone, not speech, so the transcribe endpoint is stubbed:
 * what is under test is the dictation round trip (mic → recording bar → stop →
 * POST → draft), not the ASR.
 *
 * Run: scripts/test-broker.sh bun tests/ui/voice-recorder.spec.ts
 */

import type { Browser } from "playwright"
import { launchBrowser, uiFixture } from "./fixture-env"
import { byTag, openSeededSession, tap, waitForTextWithin } from "./compose-dom"

const TRANSCRIPT = "hello from transcribe"

export async function main(): Promise<void> {
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
    page.on("pageerror", (err) => console.log(`[pageerror] ${err.message}`))

    // Route BEFORE the app can post: covers `/transcribe` and the per-session
    // `/sessions/<id>/transcribe` the composer actually calls.
    await page.route("**/transcribe", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ text: TRANSCRIPT }),
      }),
    )

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, {
      waitUntil: "domcontentloaded",
    })
    await openSeededSession(page, fixture.sessionId)

    await tap(byTag(page, "composer-mic"))
    console.log("mic tapped")

    // The recording bar takes the whole composer card over in chat; its stop
    // button is what ends the capture and posts the audio.
    const stop = byTag(page, "voice_stop")
    await stop.waitFor({ state: "attached", timeout: 30_000 })
    console.log("recording bar visible")

    // Give MediaRecorder something to flush; the fixture mic is a generated tone.
    await page.waitForTimeout(1500)
    await tap(stop)
    console.log("stop tapped")

    // The transcript lands in the DRAFT, it is not sent. Compose publishes the
    // field's text as the mirror element's innerText, and a Playwright locator
    // pierces the open shadow root that `document.querySelector` cannot — so poll
    // the locator rather than evaluating in the page.
    const input = byTag(page, "composer-input")
    const draft = await waitForTextWithin(input, TRANSCRIPT, 40_000)
    console.log(`composer draft: ${JSON.stringify(draft)}`)

    console.log("VOICE UI PASS: mic → record → stop → transcript in the draft")
  } finally {
    if (browser) await browser.close()
  }
}

if (import.meta.main) {
  main().catch((e) => {
    console.error("VOICE UI FAILED:", e instanceof Error ? e.stack ?? e.message : String(e))
    process.exit(1)
  })
}
