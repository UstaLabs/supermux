import { describe, expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { SettingsStore } from "../settings/store"
import { TelemetryService } from "./service"

type Call = { url: string; body: any }

function setup(opts: { enabled?: boolean; status?: number; fail?: boolean } = {}) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const settings = new SettingsStore(db)
  const calls: Call[] = []
  let enabled = opts.enabled ?? true
  let now = 1_000_000
  const svc = new TelemetryService({
    db, settings,
    url: "https://t.test/v1/batch",
    isEnabled: () => enabled,
    common: { version: "0.14.0", commit: "abc1234", installMode: "binary", os: "linux", arch: "x64" },
    now: () => now,
    fetchImpl: async (url, init) => {
      calls.push({ url: String(url), body: JSON.parse(String(init?.body)) })
      if (opts.fail) throw new Error("offline")
      return new Response(JSON.stringify({ accepted: 1, rejected: 0 }), { status: opts.status ?? 200 })
    },
    heartbeat: () => ({ sessionCount: 1, workspaceCount: 1, agentsInstalled: ["claude"], channels: { web: true }, activeSessions24h: 1 }),
  })
  return { svc, calls, settings, setEnabled: (v: boolean) => { enabled = v }, advance: (ms: number) => { now += ms } }
}

describe("TelemetryService", () => {
  test("install id is created once and reused", () => {
    const a = setup()
    const id = a.svc.installId()
    expect(id).toMatch(/^[0-9a-f-]{36}$/)
    expect(a.svc.installId()).toBe(id)
    expect(a.settings.get("telemetry.installId")).toBe(id)
  })

  test("record stamps common fields and flush sends them", async () => {
    const { svc, calls } = setup()
    svc.record("turn.requested", { agent: "codex", surface: "ios" })
    await svc.flush()
    expect(calls.length).toBe(1)
    const e = calls[0]!.body.events[0]
    expect(e).toMatchObject({ event: "turn.requested", version: "0.14.0", installMode: "binary", props: { agent: "codex", surface: "ios" } })
    expect(e.installId).toBe(svc.installId())
    expect(svc.recent()[0]!.sent).toBe(true)
  })

  test("an invalid event is never queued", async () => {
    const { svc, calls } = setup()
    svc.record("turn.requested", { agent: "codex", surface: "ios", prompt: "secret" } as any)
    await svc.flush()
    expect(calls.length).toBe(0)
  })

  test("nothing is recorded or sent while disabled", async () => {
    const { svc, calls } = setup({ enabled: false })
    svc.record("turn.requested", { agent: "codex", surface: "ios" })
    await svc.flush()
    expect(calls.length).toBe(0)
    expect(svc.recent()).toEqual([])
  })

  test("disable() clears unsent events", () => {
    const { svc, setEnabled } = setup()
    svc.record("turn.requested", { agent: "codex", surface: "ios" })
    setEnabled(false)
    svc.onDisabled()
    expect(svc.pending()).toBe(0)
  })

  test("a network failure keeps the batch and backs off", async () => {
    const { svc, calls, advance } = setup({ fail: true })
    svc.record("turn.requested", { agent: "codex", surface: "ios" })
    await svc.flush()
    expect(svc.pending()).toBe(1)
    await svc.flushWhenDue()          // inside the backoff window: no send
    expect(calls.length).toBe(1)
    advance(60_000)
    await svc.flushWhenDue()
    expect(calls.length).toBe(2)
  })

  test("a 4xx drops the batch at once", async () => {
    const { svc } = setup({ status: 400 })
    svc.record("turn.requested", { agent: "codex", surface: "ios" })
    await svc.flush()
    expect(svc.pending()).toBe(0)
  })

  test("batches hold at most 100 events", async () => {
    const { svc, calls } = setup()
    // record() also starts a send once 50 are queued; whatever the timing, no batch exceeds 100.
    for (let i = 0; i < 250; i++) svc.record("turn.requested", { agent: "codex", surface: "ios" })
    while (svc.pending() > 0) await svc.flush()
    expect(calls.every((c) => c.body.events.length <= 100)).toBe(true)
    expect(calls.reduce((n, c) => n + c.body.events.length, 0)).toBe(250)
  })

  test("heartbeat records broker.heartbeat", async () => {
    const { svc, calls } = setup()
    svc.recordHeartbeat()
    await svc.flush()
    expect(calls[0]!.body.events[0].event).toBe("broker.heartbeat")
  })
})
