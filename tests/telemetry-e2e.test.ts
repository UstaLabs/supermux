// A real broker (throwaway state, MUX_TEST_BROKER=1) delivering telemetry to an in-process ingest.
// MUX_TELEMETRY_FORCE=1 lifts the test-broker guard; DO_NOT_TRACK must still win over it.
import { afterAll, expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { IngestStore } from "../src/telemetry-ingest/store"
import { makeIngestHandler } from "../src/telemetry-ingest/server"

const REPO = join(import.meta.dir, "..")
const store = new IngestStore(new Database(":memory:"))
const handler = makeIngestHandler({ store, allow: () => true })
const ingest = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: (req) => handler(req, "127.0.0.1") })
const dirs: string[] = []
afterAll(() => {
  ingest.stop(true)
  if (process.env.MUX_TEST_KEEP_FIXTURE !== "1") for (const d of dirs) rmSync(d, { recursive: true, force: true })
  else console.log("fixtures kept:", dirs.join(" "))
})

function freePort(): number {
  const l = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } })
  const p = l.port
  l.stop(true)
  return p
}

async function runBroker(extraEnv: Record<string, string>, whileUp: (base: string, token: string) => Promise<void>) {
  const dir = mkdtempSync(join(tmpdir(), "mux-telemetry-e2e-"))
  dirs.push(dir)
  const state = join(dir, "state")
  const env: Record<string, string | undefined> = { ...process.env, MUX_HOME: dir, MUX_STATE_DIR: state, MUX_TEST_WORKDIR: join(dir, "workdir") }
  // Same isolation as scripts/test-broker.sh: no real channels, relay or provider keys.
  for (const k of [
    "DO_NOT_TRACK", "MUX_TELEMETRY", "MUX_TELEGRAM_BOT_TOKEN", "MUX_WHATSAPP_GOWA_URL", "MUX_WHATSAPP_GOWA_BASIC_AUTH",
    "MUX_WHATSAPP_GOWA_DEVICE_ID", "MUX_WHATSAPP_WEBHOOK_PORT", "MUX_WHATSAPP_WEBHOOK_SECRET", "MUX_RELAY_DOMAIN",
    "MUX_RELAY_BASE", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "CURSOR_API_KEY",
  ]) delete env[k]
  // `claude` is the library's echo fixture, as in scripts/test-broker.sh, so a turn really runs.
  const stub = join(dir, "stubbin")
  mkdirSync(stub, { recursive: true })
  writeFileSync(join(stub, "claude"), `#!/bin/sh\nMODE=echo EXPECT_DEFAULT_DENY=0 exec bun "${REPO}/packages/supermux-core/tests/fixtures/claude-agent.mjs" "$@"\n`)
  writeFileSync(join(stub, "tmux"), `#!/bin/sh\ncase "$1" in -V) echo "tmux 3.4";; esac\nexit 0\n`)
  chmodSync(join(stub, "claude"), 0o755)
  chmodSync(join(stub, "tmux"), 0o755)
  env.PATH = `${stub}:${env.PATH}`
  const seed = Bun.spawnSync(["bun", "scripts/test-broker-seed.ts"], { cwd: REPO, env: env as Record<string, string> })
  const { token } = JSON.parse(seed.stdout.toString()) as { token: string }
  const port = freePort()
  const base = `http://127.0.0.1:${port}`
  const broker = Bun.spawn(["bun", "src/main.ts"], {
    cwd: REPO,
    stdout: Bun.file(join(dir, "broker.log")),
    stderr: Bun.file(join(dir, "broker.log")),
    env: {
      ...(env as Record<string, string>),
      MUX_TEST_BROKER: "1",
      MUX_SOCKETS_DIR: join(state, "sockets"),
      MUX_WEB_PORT: String(port),
      MUX_WEB_PUBLIC_URL: base,
      MUX_UPDATE_CHECK: "0",
      MUX_CURATOR_ENABLED: "0",
      MUX_TELEMETRY_URL: `http://127.0.0.1:${ingest.port}/v1/batch`,
      ...extraEnv,
    },
  })
  try {
    let ready = false
    for (let i = 0; i < 240 && !ready; i++) {
      const code = await fetch(`${base}/me`).then((r) => r.status).catch(() => 0)
      ready = code === 401
      if (!ready) await Bun.sleep(250)
    }
    expect(ready).toBe(true)
    await whileUp(base, token)
  } finally {
    broker.kill("SIGTERM")   // gracefulShutdown → telemetry.stop() → final flush
    await broker.exited
  }
}

function connectWs(base: string, token: string, query: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const w = new WebSocket(`${base.replace("http", "ws")}/ws${query}`, { headers: { Authorization: `Bearer ${token}` } } as any)
    w.onopen = () => resolve(w)
    w.onerror = (e) => reject(e)
  })
}

const events = () => (store.all() as { event: string; props_json: string }[])

test("a forced test broker delivers broker.started and client.connected", async () => {
  await runBroker({ MUX_TELEMETRY_FORCE: "1" }, async (base, token) => {
    const w = await connectWs(base, token, "?clientSurface=web")
    await Bun.sleep(200)
    w.close()
  })
  const names = events().map((e) => e.event)
  expect(names).toContain("broker.started")
  expect(names).toContain("client.connected")
  const connected = events().find((e) => e.event === "client.connected")!
  expect(JSON.parse(connected.props_json)).toEqual({ surface: "web" })
  // Nothing identifying in any stored value.
  expect(JSON.stringify(store.all())).not.toContain("playwright-fixture")
  expect(JSON.stringify(store.all())).not.toContain("test-journey")
}, 120_000)

test("a chat turn records turn.requested and turn.completed with agent, model and duration", async () => {
  const before = store.count()
  await runBroker({ MUX_TELEMETRY_FORCE: "1" }, async (base, token) => {
    const w = await connectWs(base, token, "?clientSurface=android&clientVersion=0.14.0")
    w.send(JSON.stringify({ type: "send", session: "00000000-0000-4000-8000-000000000001", op: "reply", args: { text: "hello" } }))
    for (let i = 0; i < 200; i++) {
      const r = await fetch(`${base}/settings/telemetry/recent`, { headers: { Authorization: `Bearer ${token}` } }).then((x) => x.json()) as { events: { event: string }[] }
      if (r.events.some((e) => e.event === "turn.completed")) break
      await Bun.sleep(250)
    }
    w.close()
  })
  const fresh = events().slice(before)
  const requested = fresh.find((e) => e.event === "turn.requested")
  const completed = fresh.find((e) => e.event === "turn.completed")
  expect(requested && JSON.parse(requested.props_json)).toEqual({ agent: "claude", surface: "android" })
  const c = JSON.parse(completed!.props_json)
  expect(c).toMatchObject({ agent: "claude", model: "default", status: "completed", fast: false })
  expect(typeof c.durationMs).toBe("number")
  // The prompt never leaves the broker.
  expect(JSON.stringify(store.all())).not.toContain("hello")
}, 120_000)

test("DO_NOT_TRACK=1 sends nothing even when forced", async () => {
  const before = store.count()
  await runBroker({ MUX_TELEMETRY_FORCE: "1", DO_NOT_TRACK: "1" }, async () => {})
  expect(store.count()).toBe(before)
}, 120_000)
