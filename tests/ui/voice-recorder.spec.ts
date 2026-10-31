/**
 * UI smoke for the voice composer on the Kotlin/Wasm client.
 *
 * Headless Chrome has no microphone, so `--use-fake-device-for-media-stream`
 * synthesizes one and `--use-fake-ui-for-media-stream` grants it without a
 * prompt; `WebMic`'s Web Audio capture then runs exactly as in a real session.
 * The fake device emits a tone, not speech, so the transcribe endpoint is
 * stubbed: what is under test is the dictation round trip (mic → recording bar →
 * stop → POST → draft) and that the upload is the 16 kHz WAV the broker reads
 * without ffmpeg, not the ASR.
 *
 * Run: scripts/test-broker.sh bun tests/ui/voice-recorder.spec.ts
 */

import type { Browser } from "playwright"
import { launchBrowser, uiFixture } from "./fixture-env"
import { byTag, openSeededSession, tap, waitForTextWithin } from "./compose-dom"
import { forceVoiceStreaming } from "./dictation-stream.spec"

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
    // `/sessions/<id>/transcribe` the composer actually calls, with or without
    // `?partial=1` (Stop's final pass). An unstubbed partial reaches the fixture
    // broker, which really transcribes when the host has an STT login.
    // This journey covers the upload path, which every app falls back to (and which a broker
    // without `voiceStreaming` gets); the streamed path is dictation-stream.spec.ts.
    await forceVoiceStreaming(page, false)
    const uploads: Buffer[] = []
    await page.route((url) => url.pathname.endsWith("/transcribe"), (route) => {
      const body = route.request().postDataBuffer()
      if (body) uploads.push(body)
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ text: TRANSCRIPT }),
      })
    })

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

    // Let the capture collect some audio; the fixture mic is a generated tone.
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

    // The multipart "audio" part must be a 16 kHz mono 16-bit WAV with sound in it.
    const last = uploads.at(-1)
    if (!last) throw new Error("no /transcribe upload was captured")
    const riff = last.indexOf("RIFF")
    if (riff < 0 || last.toString("latin1", riff + 8, riff + 12) !== "WAVE") throw new Error("upload is not a WAV file")
    const channels = last.readUInt16LE(riff + 22)
    const rate = last.readUInt32LE(riff + 24)
    const bits = last.readUInt16LE(riff + 34)
    const dataBytes = last.readUInt32LE(riff + 40)
    if (channels !== 1 || rate !== 16000 || bits !== 16) throw new Error(`upload is ${channels}ch ${rate} Hz ${bits}-bit, want 1ch 16000 Hz 16-bit`)
    if (dataBytes < 16000) throw new Error(`upload holds only ${dataBytes} bytes of audio (< 0.5 s)`)
    console.log(`upload: WAV 16 kHz mono, ${dataBytes} bytes of PCM`)

    console.log("VOICE UI PASS: mic → record → stop → WAV upload → transcript in the draft")
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
