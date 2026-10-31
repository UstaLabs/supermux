/**
 * The streamed dictation on the Kotlin/Wasm client: the app sends its mic as binary frames over
 * `/transcribe/stream` and writes whatever text the broker sends into the composer.
 *
 * By default the broker's side of the socket is played by the test (Playwright's routeWebSocket),
 * so what is under test is the app: it streams 16 kHz s16le PCM, puts each `text` event in the
 * field while recording, and on Stop sends `stop` and shows the `final` text in the draft.
 *
 * MUX_UI_LIVE_STT=1 runs it for real instead: no stand-in, the fixture broker's Silero + Smart
 * Turn + STT engine (the host's ChatGPT login) hear a spoken recording (MUX_UI_SPEECH_WAV), and
 * the draft must hold its words (MUX_UI_EXPECT="a|b"). The recording reaches the app as its
 * microphone through Web Audio (see [speakInto]): Chrome's own `--use-file-for-fake-audio-capture`
 * came out unintelligible on a loaded box (2026-10-31), for the upload path as much as this one.
 *
 * Run: MUX_TEST_SKIP_WEB_BUILD=1 scripts/test-broker.sh bun tests/ui/dictation-stream.spec.ts
 */

import type { Browser, Page } from "playwright"
import { launchBrowser, uiFixture } from "./fixture-env"
import { byTag, openSeededSession, tap, waitForTextWithin } from "./compose-dom"

/** Make the app see (or not see) a broker that takes streamed dictation. */
export async function forceVoiceStreaming(page: Page, on: boolean): Promise<void> {
  await page.route((url) => url.pathname === "/settings/config", async (route) => {
    const res = await route.fetch()
    const body = (await res.json()) as Record<string, unknown>
    await route.fulfill({ response: res, json: { ...body, voiceStreaming: on } })
  })
}

/**
 * Make [wav] the page's microphone: getUserMedia answers with a stream that plays the file through
 * Web Audio, starting when the app opens the mic.
 */
async function speakInto(page: Page, wav: string): Promise<void> {
  const bytes = await Bun.file(wav).arrayBuffer()
  await page.route((url) => url.pathname === "/__test-speech.wav", (route) =>
    route.fulfill({ status: 200, contentType: "audio/wav", body: Buffer.from(bytes) }))
  await page.addInitScript(() => {
    // In the page; the test's own tsconfig has no DOM types.
    const g = globalThis as any
    g.navigator.mediaDevices.getUserMedia = async () => {
      const ctx = new g.AudioContext()
      const buf = await ctx.decodeAudioData(await (await fetch("/__test-speech.wav")).arrayBuffer())
      const src = ctx.createBufferSource()
      src.buffer = buf
      const dest = ctx.createMediaStreamDestination()
      src.connect(dest)
      src.start()
      return dest.stream
    }
  })
}

export async function main(): Promise<void> {
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.log("skipping UI smoke; run through scripts/test-broker.sh")
    return
  }
  const live = process.env.MUX_UI_LIVE_STT === "1"
  const speech = process.env.MUX_UI_SPEECH_WAV
  if (live && !speech) throw new Error("MUX_UI_LIVE_STT=1 needs MUX_UI_SPEECH_WAV=<16 kHz WAV of speech>")
  // Debugging the audio itself: MUX_UI_DUMP_PCM=<path.wav> saves what the app streamed (stand-in mode).
  const dump = process.env.MUX_UI_DUMP_PCM
  const frames: Buffer[] = []
  const fixture = uiFixture()

  let browser: Browser | null = null
  try {
    browser = await launchBrowser({
      args: [
        "--use-fake-ui-for-media-stream",
        "--use-fake-device-for-media-stream",
      ],
    })
    const page = await (await browser.newContext()).newPage()
    page.on("pageerror", (err) => console.log(`[pageerror] ${err.message}`))
    await forceVoiceStreaming(page, true)
    if (speech) await speakInto(page, speech)

    // The broker's side of the socket, unless this is the live run.
    let audioBytes = 0
    let oddFrame = false
    let streamUrl = ""
    const control: unknown[] = []
    let sentText = false
    if (!live) {
      await page.routeWebSocket((url) => url.pathname === "/transcribe/stream", (ws) => {
        streamUrl = ws.url()
        ws.onMessage((msg) => {
          if (typeof msg !== "string") {
            audioBytes += msg.length
            if (dump) frames.push(Buffer.from(msg))
            if (msg.length % 2 === 1) oddFrame = true
            // Half a second of audio in: say something, as a pause would.
            if (!sentText && audioBytes >= 16_000) {
              sentText = true
              ws.send(JSON.stringify({ type: "text", text: "streamed words" }))
            }
            return
          }
          const m = JSON.parse(msg) as { type: string }
          control.push(m)
          if (m.type === "stop") {
            ws.send(JSON.stringify({ type: "final", text: "streamed words, final." }))
            ws.send(JSON.stringify({ type: "done" }))
            ws.close()
          }
        })
      })
    }

    await page.goto(`${fixture.baseUrl}/pair?t=${encodeURIComponent(fixture.token)}`, { waitUntil: "domcontentloaded" })
    await openSeededSession(page, fixture.sessionId)

    await tap(byTag(page, "composer-mic"))
    const stop = byTag(page, "voice_stop")
    await stop.waitFor({ state: "attached", timeout: 30_000 })
    console.log("recording")

    const input = byTag(page, "composer-input")
    if (live) {
      // Let the whole recording play, plus a moment for the last pause.
      await page.waitForTimeout(Number(process.env.MUX_UI_SPEECH_MS ?? "12000"))
    } else {
      const during = await waitForTextWithin(input, "streamed words", 30_000)
      console.log(`live text while recording: ${JSON.stringify(during)}`)
    }
    await tap(stop)
    console.log("stop tapped")

    if (live) {
      const want = (process.env.MUX_UI_EXPECT ?? "").split("|").filter(Boolean)
      const draft = await waitForTextWithin(input, want[0] ?? "", 30_000)
      for (const w of want) if (!draft.toLowerCase().includes(w.toLowerCase())) throw new Error(`draft lacks "${w}": ${draft}`)
      console.log(`DICTATION STREAM LIVE PASS: ${JSON.stringify(draft)}`)
      return
    }

    if (dump) {
      const pcm = Buffer.concat(frames)
      const head = Buffer.alloc(44)
      head.write("RIFF", 0); head.writeUInt32LE(36 + pcm.length, 4); head.write("WAVEfmt ", 8)
      head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22); head.writeUInt32LE(16_000, 24)
      head.writeUInt32LE(32_000, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34); head.write("data", 36); head.writeUInt32LE(pcm.length, 40)
      await Bun.write(dump, Buffer.concat([head, pcm]))
      console.log(`dumped ${pcm.length} bytes of streamed PCM to ${dump}`)
    }
    const draft = await waitForTextWithin(input, "streamed words, final.", 30_000)
    console.log(`draft after stop: ${JSON.stringify(draft)}`)
    if (!/[?&]dictationId=[^&]+/.test(streamUrl)) throw new Error(`stream opened without a dictationId: ${streamUrl}`)
    if (audioBytes < 16_000) throw new Error(`only ${audioBytes} bytes of audio were streamed`)
    if (oddFrame) throw new Error("a frame split a 16-bit sample")
    if (!control.some((m) => (m as { type: string }).type === "stop")) throw new Error(`no stop was sent: ${JSON.stringify(control)}`)
    console.log(`streamed ${audioBytes} bytes of PCM; control: ${JSON.stringify(control)}`)
    console.log("DICTATION STREAM UI PASS: mic → PCM frames → live text in the field → stop → final in the draft")
  } finally {
    if (browser) await browser.close()
  }
}

if (import.meta.main) {
  main().catch((e) => {
    console.error("DICTATION STREAM UI FAILED:", e instanceof Error ? e.stack ?? e.message : String(e))
    process.exit(1)
  })
}
