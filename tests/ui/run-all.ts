/**
 * Runs all four UI journeys against ONE fixture broker.
 *
 * Four separate `scripts/test-broker.sh` invocations would pay for four Gradle
 * up-to-date checks, four broker boots and four fake-agent handshakes to test
 * four independent flows against the same seed — about a minute of pure setup.
 * The pairing token the fixture mints is a durable device token rather than a
 * one-shot code, so every spec can pair its own browser against the same broker,
 * and each spec already owns its browser (the push journey needs a persistent
 * context for the Push API; the voice journey needs Chrome's fake-media flags),
 * so sharing a fixture costs nothing in isolation.
 *
 * Sequential on purpose, and it STOPS at the first failure: they share one seeded
 * session, so a journey that fails part-way leaves the transcript, the composer or
 * the device list in a state the next journey never expected — its failure would be
 * noise about the first one, not a second finding. The first stack trace is the one
 * worth reading.
 *
 * Run: scripts/test-broker.sh bun tests/ui/run-all.ts
 */
import { main as coreJourney } from "./core-journey.spec"
import { main as composerAttachment } from "./composer-attachment.spec"
import { main as voiceRecorder } from "./voice-recorder.spec"
import { main as pushBanner } from "./push-banner.spec"

const JOURNEYS: Array<[string, () => Promise<void>]> = [
  ["core-journey", coreJourney],
  ["composer-attachment", composerAttachment],
  ["voice-recorder", voiceRecorder],
  ["push-banner", pushBanner],
]

async function run(): Promise<void> {
  // Each spec skips itself when this is unset, so a bare `bun tests/ui/run-all.ts`
  // would run four no-ops and print ALL UI JOURNEYS PASS — a green line for a suite
  // that never opened a browser. Refuse instead: outside the fixture there is no
  // broker, no seeded session and nothing to assert.
  if (process.env.MUX_RUN_UI_SMOKE !== "1") {
    console.error("run through scripts/test-broker.sh (MUX_RUN_UI_SMOKE unset)")
    process.exit(1)
  }

  for (const [name, journey] of JOURNEYS) {
    const started = Date.now()
    console.log(`\n=== ${name} ===`)
    try {
      await journey()
      console.log(`--- ${name} ok in ${((Date.now() - started) / 1000).toFixed(1)}s`)
    } catch (error) {
      console.error(
        `--- ${name} FAILED after ${((Date.now() - started) / 1000).toFixed(1)}s:`,
        error instanceof Error ? error.stack ?? error.message : String(error),
      )
      console.error(`\nUI JOURNEYS FAILED: ${name} (later journeys not run — they share its session)`)
      process.exit(1)
    }
  }
  console.log("\nALL UI JOURNEYS PASS")
}

run()
