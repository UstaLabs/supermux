import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { LIMIT_RESET_BACKGROUND_SUFFIX, LIMIT_RESET_CONTINUE_PROMPT, LimitWaits, RESUME_MARGIN_MS, STAGGER_MS, SPACING_MS, UNKNOWN_RESET_RETRY_MS, resumeAfterLimit, type LimitWait, type ResumeDeps } from "./limit-wait"
import { parseAccountsSettings } from "./broker-accounts"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

const T0 = Date.parse("2026-11-08T16:49:00Z")
const MIN = 60_000

function harness(options: { file?: string; enabled?: boolean; now?: number } = {}) {
  const dir = mkdtempSync(join(tmpdir(), "limit-wait-"))
  dirs.push(dir)
  const file = options.file ?? join(dir, "limit-waits.json")
  const clock = { now: options.now ?? T0 }
  const timers: Array<{ fn: () => void; ms: number; cleared: boolean }> = []
  const resumed: LimitWait[] = []
  const changes: Array<[string, LimitWait | undefined]> = []
  const waits = new LimitWaits({
    file,
    now: () => clock.now,
    setTimer: (fn, ms) => { const t = { fn, ms, cleared: false }; timers.push(t); return t },
    clearTimer: (t) => { (t as { cleared: boolean }).cleared = true },
    resume: async (wait) => { resumed.push(wait) },
    changed: (id, wait) => { changes.push([id, wait]) },
    enabled: () => options.enabled ?? true,
  })
  /** Moves the clock on and fires every live timer: each one checks the clock itself. */
  const advance = async (ms: number) => {
    clock.now += ms
    for (const t of [...timers]) {
      if (t.cleared) continue
      t.cleared = true
      t.fn()
    }
    await new Promise((r) => setTimeout(r, 0))
  }
  return { waits, file, clock, timers, resumed, changes, advance }
}

test("a wait resumes at the reset + the margin + this session's stagger, and is saved", () => {
  const h = harness()
  const resetsAt = new Date(T0 + 81 * MIN)
  const wait = h.waits.wait("s1", { account: "work", resetsAt, background: false })!
  const offset = Date.parse(wait.resumeAt) - resetsAt.getTime()
  expect(offset).toBeGreaterThanOrEqual(RESUME_MARGIN_MS)
  expect(offset).toBeLessThan(RESUME_MARGIN_MS + STAGGER_MS)
  expect(wait).toMatchObject({ sessionId: "s1", account: "work", resetsAt: resetsAt.toISOString(), background: false })
  expect(h.waits.get("s1")).toEqual(wait)
  expect(h.changes).toEqual([["s1", wait]])
  expect(JSON.parse(readFileSync(h.file, "utf8"))).toEqual({ version: 1, waits: [wait] })
  // The stagger is the session's own: the same session lands on the same moment again.
  expect(harness().waits.wait("s1", { account: "work", resetsAt, background: false })!.resumeAt).toBe(wait.resumeAt)
})

test("no reset time known: check again after UNKNOWN_RESET_RETRY_MS", () => {
  const h = harness()
  const wait = h.waits.wait("s1", { account: "work", background: false })!
  expect(wait.resetsAt).toBeUndefined()
  const offset = Date.parse(wait.resumeAt) - T0
  expect(offset).toBeGreaterThanOrEqual(UNKNOWN_RESET_RETRY_MS)
  expect(offset).toBeLessThan(UNKNOWN_RESET_RETRY_MS + RESUME_MARGIN_MS + STAGGER_MS)
})

test("no thundering herd: waits on the same account resume at least SPACING_MS apart", () => {
  const h = harness()
  const resetsAt = new Date(T0 + 60 * MIN)
  const times = Array.from({ length: 30 }, (_, i) => Date.parse(h.waits.wait(`s${i}`, { account: "work", resetsAt, background: false })!.resumeAt)).sort((a, b) => a - b)
  for (let i = 1; i < times.length; i++) expect(times[i]! - times[i - 1]!).toBeGreaterThanOrEqual(SPACING_MS)
  // Another account's waits are not spaced against these.
  const other = h.waits.wait("x", { account: "personal", resetsAt, background: false })!
  expect(Date.parse(other.resumeAt)).toBeLessThan(resetsAt.getTime() + RESUME_MARGIN_MS + STAGGER_MS)
})

test("the timer resumes the session once and forgets the wait", async () => {
  const h = harness()
  const wait = h.waits.wait("s1", { account: "work", resetsAt: new Date(T0 + 5 * MIN), background: true })!
  const armed = h.timers.at(-1)!
  expect(armed.ms).toBe(Date.parse(wait.resumeAt) - T0)
  await h.advance(armed.ms)
  expect(h.resumed).toEqual([wait])
  expect(h.waits.get("s1")).toBeUndefined()
  expect(h.changes.at(-1)).toEqual(["s1", undefined])
  expect(JSON.parse(readFileSync(h.file, "utf8")).waits).toEqual([])
})

test("a timer that fires early (clamped or drifted) re-arms instead of resuming", async () => {
  const h = harness()
  h.waits.wait("s1", { account: "work", resetsAt: new Date(T0 + 30 * 86_400_000), background: false })
  expect(h.timers.at(-1)!.ms).toBeLessThanOrEqual(2 ** 31 - 1)
  await h.advance(h.timers.at(-1)!.ms)
  expect(h.resumed).toEqual([])
  expect(h.waits.get("s1")).toBeDefined()
  expect(h.timers.filter((t) => !t.cleared)).toHaveLength(1)
})

test("a newer wait for the same session replaces the old one", () => {
  const h = harness()
  h.waits.wait("s1", { account: "work", resetsAt: new Date(T0 + 5 * MIN), background: false })
  const second = h.waits.wait("s1", { account: "personal", resetsAt: new Date(T0 + 50 * MIN), background: false })!
  expect(h.waits.list()).toEqual([second])
  expect(h.timers.filter((t) => !t.cleared)).toHaveLength(1)
})

test("cancel drops the wait: nothing resumes", async () => {
  const h = harness()
  h.waits.wait("s1", { account: "work", resetsAt: new Date(T0 + 5 * MIN), background: false })
  expect(h.waits.cancel("s1")).toBe(true)
  expect(h.waits.cancel("s1")).toBe(false)
  await h.advance(10 * MIN)
  expect(h.resumed).toEqual([])
  expect(h.changes.at(-1)).toEqual(["s1", undefined])
  expect(JSON.parse(readFileSync(h.file, "utf8")).waits).toEqual([])
})

test("resumeNow resumes at once", async () => {
  const h = harness()
  const wait = h.waits.wait("s1", { account: "work", resetsAt: new Date(T0 + 50 * MIN), background: false })!
  expect(await h.waits.resumeNow("s1")).toBe(true)
  expect(h.resumed).toEqual([wait])
  expect(h.waits.get("s1")).toBeUndefined()
  expect(await h.waits.resumeNow("s1")).toBe(false)
})

test("dropAccount drops every wait on a removed account", () => {
  const h = harness()
  h.waits.wait("a", { account: "gone", resetsAt: new Date(T0 + 5 * MIN), background: false })
  h.waits.wait("b", { account: "gone", resetsAt: new Date(T0 + 5 * MIN), background: false })
  const kept = h.waits.wait("c", { account: "work", resetsAt: new Date(T0 + 5 * MIN), background: false })!
  expect(h.waits.dropAccount("gone").sort()).toEqual(["a", "b"])
  expect(h.waits.list()).toEqual([kept])
})

test("turned off: no wait is made", () => {
  const h = harness({ enabled: false })
  expect(h.waits.wait("s1", { account: "work", resetsAt: new Date(T0 + 5 * MIN), background: false })).toBeUndefined()
  expect(h.waits.list()).toEqual([])
  expect(h.timers).toHaveLength(0)
})

test("a restart re-arms saved waits; ones already due are spread out, not fired together", async () => {
  const first = harness()
  const resetsAt = new Date(T0 + 5 * MIN)
  for (const id of ["a", "b", "c"]) first.waits.wait(id, { account: "work", resetsAt, background: false })
  const future = first.waits.wait("later", { account: "work", resetsAt: new Date(T0 + 300 * MIN), background: false })!
  // The broker was down past the reset.
  const after = harness({ file: first.file, now: T0 + 60 * MIN })
  expect(after.waits.load()).toEqual({ armed: 1, due: 3 })
  expect(after.waits.list().map((w) => w.sessionId).sort()).toEqual(["a", "b", "c", "later"])
  const due = ["a", "b", "c"].map((id) => Date.parse(after.waits.get(id)!.resumeAt)).sort((x, y) => x - y)
  expect(due[0]).toBeGreaterThanOrEqual(T0 + 60 * MIN)
  for (let i = 1; i < due.length; i++) expect(due[i]! - due[i - 1]!).toBeGreaterThanOrEqual(SPACING_MS)
  expect(after.waits.get("later")!.resumeAt).toBe(future.resumeAt)
  expect(after.timers.filter((t) => !t.cleared)).toHaveLength(4)
})

test("an unreadable file loads as no waits", () => {
  const h = harness()
  writeFileSync(h.file, "{not json")
  h.waits.load()
  expect(h.waits.list()).toEqual([])
})

// ── resumeAfterLimit: what the broker does when a wait is due ──

function resumeHarness(over: Partial<ResumeDeps> = {}) {
  const log: string[] = []
  const deps: ResumeDeps = {
    exists: () => true,
    busy: () => false,
    account: () => "personal",
    registered: () => true,
    switchAccount: async (id, account) => { log.push(`switch ${id} ${account}`) },
    label: (account) => account.toUpperCase(),
    notify: async (id, text) => { log.push(`notify ${id} ${text}`) },
    deliver: async (id, text) => { log.push(`deliver ${id} ${text}`) },
    ...over,
  }
  return { deps, log }
}
const due: LimitWait = { sessionId: "s1", account: "work", resetsAt: "2026-11-08T18:10:00.000Z", resumeAt: "2026-11-08T18:13:00.000Z", background: false }

test("resumeAfterLimit: moves the session to the account that reset, says so, and continues the turn", async () => {
  const { deps, log } = resumeHarness()
  await resumeAfterLimit(due, deps)
  expect(log).toEqual([
    "switch s1 work",
    "notify s1 Resumed on WORK — the usage limit reset",
    `deliver s1 ${LIMIT_RESET_CONTINUE_PROMPT}`,
  ])
})

test("resumeAfterLimit: already on that account, no switch; background work gets the subagent hint", async () => {
  const { deps, log } = resumeHarness({ account: () => "work" })
  await resumeAfterLimit({ ...due, background: true }, deps)
  expect(log).toEqual([
    "notify s1 Resumed on WORK — the usage limit reset",
    `deliver s1 ${LIMIT_RESET_CONTINUE_PROMPT} ${LIMIT_RESET_BACKGROUND_SUFFIX}`,
  ])
})

test("resumeAfterLimit: reset time unknown → it says it is trying again", async () => {
  const { deps, log } = resumeHarness({ account: () => "work" })
  const { resetsAt: _, ...unknown } = due
  await resumeAfterLimit(unknown, deps)
  expect(log[0]).toBe("notify s1 Trying again on WORK — the usage limit may have reset")
})

test("resumeAfterLimit: a removed account or a failed switch goes on where the session is", async () => {
  {
    const { deps, log } = resumeHarness({ registered: () => false })
    await resumeAfterLimit(due, deps)
    expect(log).toEqual(["notify s1 Resumed on PERSONAL — the usage limit reset", `deliver s1 ${LIMIT_RESET_CONTINUE_PROMPT}`])
  }
  {
    const { deps, log } = resumeHarness({ switchAccount: async () => { throw new Error("session_busy") } })
    await resumeAfterLimit(due, deps)
    expect(log).toEqual(["notify s1 Resumed on PERSONAL — the usage limit reset", `deliver s1 ${LIMIT_RESET_CONTINUE_PROMPT}`])
  }
})

test("resumeAfterLimit: a session that is gone, or already working again, is left alone", async () => {
  for (const over of [{ exists: () => false }, { busy: () => true }]) {
    const { deps, log } = resumeHarness(over)
    await resumeAfterLimit(due, deps)
    expect(log).toEqual([])
  }
})

test("the setting: auto-resume is on unless turned off; auto-switch stays off unless turned on", () => {
  expect(parseAccountsSettings(undefined)).toEqual({ autoSwitch: false, autoResume: true })
  expect(parseAccountsSettings({ autoResume: false, autoSwitch: true })).toEqual({ autoSwitch: true, autoResume: false })
  expect(parseAccountsSettings({ autoResume: "no" })).toEqual({ autoSwitch: false, autoResume: true })
})
