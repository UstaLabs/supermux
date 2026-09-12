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
 * Sequential on purpose: they share one seeded session, and interleaving their
 * sends would make the transcript assertions ambiguous.
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
  const failures: string[] = []
  for (const [name, journey] of JOURNEYS) {
    const started = Date.now()
    console.log(`\n=== ${name} ===`)
    try {
      await journey()
      console.log(`--- ${name} ok in ${((Date.now() - started) / 1000).toFixed(1)}s`)
    } catch (error) {
      failures.push(name)
      console.error(
        `--- ${name} FAILED after ${((Date.now() - started) / 1000).toFixed(1)}s:`,
        error instanceof Error ? error.stack ?? error.message : String(error),
      )
    }
  }
  if (failures.length > 0) {
    console.error(`\nUI JOURNEYS FAILED: ${failures.join(", ")}`)
    process.exit(1)
  }
  console.log("\nALL UI JOURNEYS PASS")
}

run()
