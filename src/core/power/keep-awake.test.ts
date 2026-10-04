import { describe, expect, test } from "bun:test"
import {
  CAFFEINATE, ES_CONTINUOUS_SYSTEM_REQUIRED, HINT_DENIED, KeepAwake, LINUX_BOUND_SH, LINUX_WAIT_SH, PROBE_MS,
  REASON_DENIED, REASON_GAVE_UP, REASON_NO_LINUX_INHIBITOR, REASON_ON_BATTERY, RESPAWN_MAX_PER_HOUR,
  inhibitorPlan, isDenied, linuxOnBatteryFrom, parsePmsetBatt, parseWin32BatteryStatus, sessionBusEnv,
  spawnInhibitor, windowsKeepAwakeScript,
  type InhibitorChild, type InhibitorExit, type KeepAwakeClock, type KeepAwakeState,
} from "./keep-awake"

// ── a fake clock and spawner: nothing real is ever started ─────────────────────────────────────

class FakeClock implements KeepAwakeClock {
  t = 1_000_000
  private seq = 0
  timers = new Map<number, { at: number; fn: () => void; every?: number }>()
  now() { return this.t }
  setTimeout(fn: () => void, ms: number) { const id = ++this.seq; this.timers.set(id, { at: this.t + ms, fn }); return id }
  clearTimeout(h: unknown) { this.timers.delete(h as number) }
  setInterval(fn: () => void, ms: number) { const id = ++this.seq; this.timers.set(id, { at: this.t + ms, fn, every: ms }); return id }
  clearInterval(h: unknown) { this.timers.delete(h as number) }
  get pending() { return [...this.timers.values()] }
  /** Advance to [ms] later, firing due timers in order. */
  async advance(ms: number) {
    const end = this.t + ms
    for (;;) {
      const due = [...this.timers.entries()].filter(([, v]) => v.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
      if (!due) break
      const [id, v] = due
      this.t = v.at
      if (v.every) v.at += v.every
      else this.timers.delete(id)
      v.fn()
      await flush()
    }
    this.t = end
    await flush()
  }
}

const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }

class FakeChild implements InhibitorChild {
  killed = false
  private resolve!: (e: InhibitorExit) => void
  exited = new Promise<InhibitorExit>((r) => { this.resolve = r })
  constructor(readonly argv: string[], readonly env?: Record<string, string>) {}
  kill() { this.killed = true; this.resolve({ signal: "SIGTERM" }) }
  die(e: InhibitorExit = { code: 1 }) { this.resolve(e) }
}

const LINUX_ALL = (b: string) => `/usr/bin/${b}`

function harness(opts: {
  platform?: NodeJS.Platform; enabled?: boolean; onBatterySetting?: boolean; which?: (b: string) => string | null
  spawnThrows?: boolean; env?: Record<string, string | undefined>
} = {}) {
  const clock = new FakeClock()
  const children: FakeChild[] = []
  let battery: boolean | undefined = false
  const states: KeepAwakeState[] = []
  const ka = new KeepAwake({
    platform: opts.platform ?? "darwin",
    pid: 4242,
    clock,
    which: opts.which ?? LINUX_ALL,
    spawn: (argv, o) => {
      if (opts.spawnThrows) throw new Error("boom")
      const c = new FakeChild(argv, o?.env)
      children.push(c)
      return c
    },
    onBattery: async () => battery,
    env: opts.env ?? { DBUS_SESSION_BUS_ADDRESS: "unix:path=/run/user/1000/bus" },
    uid: 1000,
    exists: () => true,
  }, { enabled: opts.enabled ?? true, onBattery: opts.onBatterySetting ?? true })
  ka.onChange((s) => states.push(s))
  return { ka, clock, children, states, setBattery: (b: boolean | undefined) => { battery = b } }
}

const live = (cs: FakeChild[]) => cs.filter((c) => !c.killed)
const ON = { enabled: true, onBattery: true, active: true, supported: true }

// ── per-OS argv ───────────────────────────────────────────────────────────────────────────────

describe("inhibitorPlan", () => {
  test("macOS: caffeinate -is -w <pid>", () => {
    expect(inhibitorPlan("darwin", 4242, () => null)).toEqual({ candidates: [{ name: "caffeinate", argv: [CAFFEINATE, "-is", "-w", "4242"] }] })
  })

  test("Linux: systemd-inhibit (sleep only), then gnome-session-inhibit, then kde-inhibit, all broker-bound", () => {
    const bus = { DBUS_SESSION_BUS_ADDRESS: "unix:path=/run/user/1000/bus" }
    const r = inhibitorPlan("linux", 4242, LINUX_ALL, bus)
    expect(r).toEqual({
      candidates: [
        {
          name: "systemd-inhibit",
          argv: [
            "/usr/bin/systemd-inhibit", "--what=sleep", "--who=supermux", "--why=Hosting agents", "--mode=block",
            "/bin/sh", "-c", LINUX_WAIT_SH, "mux-keep-awake", "4242",
          ],
        },
        {
          name: "gnome-session-inhibit",
          argv: ["/bin/sh", "-c", LINUX_BOUND_SH, "mux-keep-awake", "4242", "/usr/bin/gnome-session-inhibit", "--inhibit", "suspend", "--inhibit-only"],
          env: bus,
        },
        { name: "kde-inhibit", argv: ["/usr/bin/kde-inhibit", "--power", "/bin/sh", "-c", LINUX_WAIT_SH, "mux-keep-awake", "4242"], env: bus },
      ],
    })
    expect(LINUX_WAIT_SH).toContain('kill -0 "$broker"')
    expect(LINUX_WAIT_SH).toContain('kill -0 "$parent"')
  })

  test("Linux: only the inhibitors that are installed; none at all is unsupported", () => {
    const r = inhibitorPlan("linux", 1, (b) => (b === "kde-inhibit" ? "/usr/bin/kde-inhibit" : null)) as { candidates: Array<{ name: string }> }
    expect(r.candidates.map((c) => c.name)).toEqual(["kde-inhibit"])
    expect(inhibitorPlan("linux", 1, () => null)).toEqual({ unsupported: REASON_NO_LINUX_INHIBITOR })
  })

  test("Windows: a hidden, non-interactive PowerShell with a fixed script (no shell)", () => {
    const r = inhibitorPlan("win32", 4242, () => null) as { candidates: Array<{ argv: string[] }> }
    const argv = r.candidates[0]!.argv
    expect(argv.slice(0, 8)).toEqual([
      "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
    ])
    expect(Buffer.from(argv[8]!, "base64").toString("utf16le")).toBe(windowsKeepAwakeScript(4242))
    const script = windowsKeepAwakeScript(4242)
    expect(script).toContain('[DllImport("kernel32.dll")]')
    expect(script).toContain("SetThreadExecutionState")
    expect(script).toContain(`[uint32]${ES_CONTINUOUS_SYSTEM_REQUIRED}`)
    expect(ES_CONTINUOUS_SYSTEM_REQUIRED).toBe(2147483649)
    expect(script.endsWith("Wait-Process -Id 4242")).toBe(true)
  })

  test("other platforms are unsupported", () => {
    expect("unsupported" in inhibitorPlan("freebsd", 1, () => null)).toBe(true)
  })
})

describe("session bus", () => {
  test("kept when set; else the user bus when its socket exists; else nothing", () => {
    expect(sessionBusEnv({ DBUS_SESSION_BUS_ADDRESS: "unix:path=/x" }, 1000, () => true)).toEqual({})
    expect(sessionBusEnv({}, 1000, (p) => p === "/run/user/1000/bus")).toEqual({ DBUS_SESSION_BUS_ADDRESS: "unix:path=/run/user/1000/bus" })
    expect(sessionBusEnv({}, 1000, () => false)).toEqual({})
    expect(sessionBusEnv({}, undefined, () => true)).toEqual({})
  })

  test("the session inhibitors get the fallback bus address; systemd-inhibit does not need it", () => {
    const h = harness({ platform: "linux", env: {} })
    h.ka.start()
    expect(h.children[0]!.env).toBeUndefined()
    h.children[0]!.die({ code: 1, stderr: "Failed to inhibit: Access denied" })
    return flush().then(() => {
      expect(h.children[1]!.env).toEqual({ DBUS_SESSION_BUS_ADDRESS: "unix:path=/run/user/1000/bus" })
    })
  })

  test("isDenied", () => {
    expect(isDenied("Failed to inhibit: Access denied")).toBe(true)
    expect(isDenied("Error: Not authorized to perform operation")).toBe(true)
    expect(isDenied("NOT AUTHORISED")).toBe(true)
    expect(isDenied("Failed to connect to bus")).toBe(false)
    expect(isDenied(undefined)).toBe(false)
  })
})

// ── the real spawner never crashes the broker ─────────────────────────────────────────────────

test("spawnInhibitor: a missing binary resolves exited with the error instead of throwing", async () => {
  const c = spawnInhibitor(["/nonexistent/mux-keep-awake-test-binary"])
  const e = await c.exited
  expect(e.error).toContain("ENOENT")
  c.kill() // harmless after the fact
})

test.skipIf(process.platform === "win32")("spawnInhibitor captures stderr (a refusal is recognisable)", async () => {
  const c = spawnInhibitor(["/bin/sh", "-c", 'echo "Failed to inhibit: Access denied" >&2; exit 1'])
  const e = await c.exited
  expect(e.code).toBe(1)
  expect(isDenied(e.stderr)).toBe(true)
})

test.skipIf(process.platform === "win32")("LINUX_BOUND_SH kills its inhibitor once the watched broker pid is gone", async () => {
  const broker = Bun.spawn(["sleep", "30"], { stdout: "ignore", stderr: "ignore" })
  const bound = Bun.spawn(["/bin/sh", "-c", LINUX_BOUND_SH, "mux-keep-awake", String(broker.pid), "sleep", "60"], { stdout: "ignore", stderr: "ignore" })
  await Bun.sleep(100)
  expect(bound.exitCode).toBeNull()
  broker.kill()
  await broker.exited
  const code = await Promise.race([bound.exited, Bun.sleep(6_000).then(() => "timeout")])
  expect(code).toBe(0)
}, 10_000)

test.skipIf(process.platform === "win32")("LINUX_BOUND_SH passes on its inhibitor's exit status when that ends first", async () => {
  const bound = Bun.spawn(["/bin/sh", "-c", LINUX_BOUND_SH, "mux-keep-awake", String(process.pid), "sh", "-c", "exit 3"], { stdout: "ignore", stderr: "ignore" })
  expect(await Promise.race([bound.exited, Bun.sleep(6_000).then(() => "timeout")])).toBe(3)
}, 10_000)

// ── enable / disable ──────────────────────────────────────────────────────────────────────────

describe("KeepAwake", () => {
  test("enabled: start spawns one inhibitor and reports active", () => {
    const h = harness()
    const s = h.ka.start()
    expect(h.children).toHaveLength(1)
    expect(h.children[0]!.argv).toEqual([CAFFEINATE, "-is", "-w", "4242"])
    expect(s).toEqual(ON)
  })

  test("disabled: nothing spawns until enabled; disabling kills it and emits", () => {
    const h = harness({ enabled: false })
    expect(h.ka.start().active).toBe(false)
    expect(h.children).toHaveLength(0)
    h.ka.update({ enabled: true, onBattery: true })
    expect(h.children).toHaveLength(1)
    expect(h.states.at(-1)!.active).toBe(true)
    h.ka.update({ enabled: false, onBattery: true })
    expect(h.children[0]!.killed).toBe(true)
    expect(h.states.at(-1)).toEqual({ enabled: false, onBattery: true, active: false, supported: true })
  })

  test("stop releases without re-spawning", async () => {
    const h = harness()
    h.ka.start()
    h.ka.stop()
    await h.clock.advance(120_000)
    expect(h.children).toHaveLength(1)
    expect(h.children[0]!.killed).toBe(true)
    expect(h.clock.pending).toHaveLength(0)
  })

  test("unsupported Linux reports supported:false with the reason and spawns nothing", () => {
    const h = harness({ platform: "linux", which: () => null })
    expect(h.ka.start()).toEqual({
      enabled: true, onBattery: true, active: false, supported: false, reason: REASON_NO_LINUX_INHIBITOR, reasonCode: "unsupported",
    })
    expect(h.children).toHaveLength(0)
  })

  // ── Linux: refusals fall over, never retried ────────────────────────────────────────────────

  test("'Access denied' from systemd-inhibit: no retry, gnome then kde are tried, then denied with a hint", async () => {
    const h = harness({ platform: "linux" })
    h.ka.start()
    expect(h.children.map((c) => c.argv[0])).toEqual(["/usr/bin/systemd-inhibit"])
    h.children[0]!.die({ code: 1, stderr: "Failed to inhibit: Access denied\n" })
    await flush()
    expect(h.children).toHaveLength(2)
    expect(h.children[1]!.argv).toContain("/usr/bin/gnome-session-inhibit")
    expect(h.clock.pending).toHaveLength(0) // no backoff timer: nothing is retried
    h.children[1]!.die({ code: 1, stderr: "Not authorized" })
    await flush()
    expect(h.children[2]!.argv[0]).toBe("/usr/bin/kde-inhibit")
    h.children[2]!.die({ code: 1, stderr: "access denied" })
    await flush()
    await h.clock.advance(3_600_000)
    expect(h.children).toHaveLength(3)
    const final: KeepAwakeState = { enabled: true, onBattery: true, active: false, supported: true, reason: REASON_DENIED, reasonCode: "denied", hint: HINT_DENIED }
    expect(h.ka.state()).toEqual(final)
    expect(h.states.at(-1)).toEqual(final)
    expect(HINT_DENIED).toContain("org.freedesktop.login1.inhibit-block-sleep")
    // Toggling retries from the first inhibitor.
    h.ka.update({ enabled: true, onBattery: true })
    expect(h.children).toHaveLength(4)
    expect(h.children[3]!.argv[0]).toBe("/usr/bin/systemd-inhibit")
  })

  test("a fallback that holds stays: its later exits get the normal backoff", async () => {
    const h = harness({ platform: "linux" })
    h.ka.start()
    h.children[0]!.die({ code: 1, stderr: "Failed to inhibit: Access denied" })
    await flush()
    expect(h.ka.state().active).toBe(true)
    await h.clock.advance(PROBE_MS + 10_000)
    h.children[1]!.die({ code: 1 })
    await flush()
    const timer = h.clock.pending.find((t) => !t.every)!
    expect(timer.at - h.clock.t).toBe(1_000)
    await h.clock.advance(1_000)
    expect(h.children).toHaveLength(3)
    expect(h.children[2]!.argv).toContain("/usr/bin/gnome-session-inhibit")
  })

  test("a fallback that can't hold at all (no session bus) moves on at once; the last one failing too is denied", async () => {
    const h = harness({ platform: "linux", which: (b) => (b === "kde-inhibit" ? null : `/usr/bin/${b}`) })
    h.ka.start()
    h.children[0]!.die({ code: 1, stderr: "Failed to inhibit: Access denied" })
    await flush()
    h.children[1]!.die({ code: 1, stderr: "Failed to connect to the session manager" })
    await flush()
    expect(h.children).toHaveLength(2)
    expect(h.ka.state().reasonCode).toBe("denied")
  })

  // ── respawn and backoff ─────────────────────────────────────────────────────────────────────

  test("an unexpected exit re-spawns with backoff 1 s, 2 s, 4 s … capped at 60 s", async () => {
    const h = harness()
    h.ka.start()
    const delays: number[] = []
    for (let i = 0; i < 8; i++) {
      h.children.at(-1)!.die()
      await flush()
      expect(h.ka.state().active).toBe(false)
      const timer = h.clock.pending.find((t) => !t.every)!
      delays.push(timer.at - h.clock.t)
      await h.clock.advance(timer.at - h.clock.t)
      expect(h.ka.state().active).toBe(true)
    }
    expect(delays).toEqual([1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000])
  })

  test("after 10 re-spawn tries in an hour it gives up: active:false with a reason", async () => {
    const h = harness()
    h.ka.start()
    for (let i = 0; i < RESPAWN_MAX_PER_HOUR; i++) {
      h.children.at(-1)!.die({ code: 3 })
      await flush()
      await h.clock.advance(60_000)
    }
    expect(h.children).toHaveLength(RESPAWN_MAX_PER_HOUR + 1)
    h.children.at(-1)!.die({ code: 3 })
    await flush()
    await h.clock.advance(3_600_000)
    expect(h.children).toHaveLength(RESPAWN_MAX_PER_HOUR + 1) // no 12th
    const s = h.ka.state()
    expect(s.active).toBe(false)
    expect(s.reason).toStartWith(REASON_GAVE_UP)
    expect(s.reasonCode).toBe("gave_up")
    expect(s.reason).toContain("exit 3")
    expect(h.states.at(-1)).toEqual(s)
    // Toggling is the retry.
    h.ka.update({ enabled: true, onBattery: true })
    expect(h.children).toHaveLength(RESPAWN_MAX_PER_HOUR + 2)
    expect(h.ka.state()).toEqual(ON)
  })

  test("update() drops a pending re-spawn and acquires at once", async () => {
    const h = harness()
    h.ka.start()
    h.children[0]!.die()
    await flush()
    expect(h.clock.pending.filter((t) => !t.every)).toHaveLength(1)
    h.ka.update({ enabled: true, onBattery: true })
    expect(h.children).toHaveLength(2)
    expect(h.clock.pending.filter((t) => !t.every)).toHaveLength(0)
    expect(h.ka.state().active).toBe(true)
  })

  test("a spawner that throws counts as a failed try and never throws out", async () => {
    const h = harness({ spawnThrows: true })
    expect(() => h.ka.start()).not.toThrow()
    expect(h.ka.state().active).toBe(false)
    await h.clock.advance(10 * 60_000)
    expect(h.ka.state().reason).toStartWith(REASON_GAVE_UP)
  })

  test("a release on purpose is not an unexpected exit", async () => {
    const h = harness()
    h.ka.start()
    h.ka.update({ enabled: false, onBattery: true })
    await h.clock.advance(120_000)
    expect(h.children).toHaveLength(1)
  })

  // ── battery ─────────────────────────────────────────────────────────────────────────────────

  test("'Also on battery' off: released on battery, re-acquired on AC (30 s poll)", async () => {
    const h = harness({ onBatterySetting: false })
    h.ka.start()
    await flush()
    expect(live(h.children)).toHaveLength(1)
    h.setBattery(true)
    await h.clock.advance(30_000)
    expect(live(h.children)).toHaveLength(0)
    expect(h.ka.state()).toEqual({
      enabled: true, onBattery: false, active: false, supported: true, reason: REASON_ON_BATTERY, reasonCode: "on_battery",
    })
    h.setBattery(false)
    await h.clock.advance(30_000)
    expect(live(h.children)).toHaveLength(1)
    expect(h.ka.state().active).toBe(true)
    expect(h.states.map((s) => s.active)).toEqual([true, false, true]) // first check → held, battery → released, AC → held
  })

  test("'Also on battery' off: the first battery check runs before anything is acquired", async () => {
    const h = harness({ onBatterySetting: false })
    h.setBattery(true)
    h.ka.start()
    expect(h.children).toHaveLength(0) // not yet: the check is pending
    await flush()
    expect(h.children).toHaveLength(0) // on battery: never held
    expect(h.ka.state().reasonCode).toBe("on_battery")
  })

  test("an undeterminable power source counts as AC", async () => {
    const h = harness({ onBatterySetting: false })
    h.setBattery(undefined)
    h.ka.start()
    await h.clock.advance(30_000)
    expect(h.ka.state().active).toBe(true)
  })

  test("'Also on battery' on (default): the battery is not even polled", async () => {
    const h = harness({ onBatterySetting: true })
    h.setBattery(true)
    h.ka.start()
    await h.clock.advance(60_000)
    expect(h.clock.pending.filter((t) => t.every)).toHaveLength(0)
    expect(h.ka.state().active).toBe(true)
  })

  test("refresh() re-reads the battery at once (after a wake)", async () => {
    const h = harness({ onBatterySetting: false })
    h.ka.start()
    await flush()
    h.setBattery(true)
    await h.ka.refresh()
    expect(h.ka.state().active).toBe(false)
    h.setBattery(false)
    await h.ka.refresh()
    expect(h.ka.state().active).toBe(true)
  })
})

// ── battery parsers ───────────────────────────────────────────────────────────────────────────

describe("battery parsing", () => {
  test("pmset -g batt", () => {
    expect(parsePmsetBatt("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t80%; discharging")).toBe(true)
    expect(parsePmsetBatt("Now drawing from 'AC Power'\n -InternalBattery-0\t100%; charged")).toBe(false)
    expect(parsePmsetBatt("Now drawing from 'AC Power'\n")).toBe(false) // a Mac mini
    expect(parsePmsetBatt("")).toBeUndefined()
  })

  test("Linux power_supply", () => {
    expect(linuxOnBatteryFrom([{ type: "Mains", online: "1" }, { type: "Battery", status: "Charging" }])).toBe(false)
    expect(linuxOnBatteryFrom([{ type: "Mains", online: "0" }, { type: "Battery", status: "Discharging" }])).toBe(true)
    expect(linuxOnBatteryFrom([{ type: "Battery", status: "Discharging" }])).toBe(true)
    expect(linuxOnBatteryFrom([{ type: "Battery", status: "Full" }])).toBe(false)
    expect(linuxOnBatteryFrom([{ type: "Mains", online: "1" }])).toBe(false) // a desktop
    expect(linuxOnBatteryFrom([])).toBeUndefined()
    expect(linuxOnBatteryFrom([{ type: "USB", online: "0" }])).toBeUndefined()
  })

  test("Linux power_supply: device batteries are ignored; an online USB / USB-C supply is AC", () => {
    // A wireless mouse discharging must not make a desktop look like it's on battery.
    expect(linuxOnBatteryFrom([{ type: "Battery", scope: "Device", status: "Discharging" }, { type: "Mains", online: "1" }])).toBe(false)
    expect(linuxOnBatteryFrom([{ type: "Battery", scope: "Device", status: "Discharging" }])).toBeUndefined()
    expect(linuxOnBatteryFrom([{ type: "USB_C", online: "1" }, { type: "Battery", status: "Discharging" }])).toBe(false)
    expect(linuxOnBatteryFrom([{ type: "USB", online: "1" }, { type: "Battery", status: "Discharging" }])).toBe(false)
    expect(linuxOnBatteryFrom([{ type: "USB_C", online: "0" }, { type: "Mains", online: "0" }, { type: "Battery", status: "Discharging" }])).toBe(true)
  })

  test("Win32_Battery BatteryStatus", () => {
    expect(parseWin32BatteryStatus("1\r\n")).toBe(true)
    expect(parseWin32BatteryStatus("2\r\n")).toBe(false)
    for (const c of [4, 5, 11]) expect(parseWin32BatteryStatus(`${c}\r\n`)).toBe(true)
    for (const c of [3, 6, 7, 8, 9]) expect(parseWin32BatteryStatus(`${c}\r\n`)).toBe(false)
    expect(parseWin32BatteryStatus("")).toBe(false) // no battery: a desktop
  })
})

// The Linux wait itself (plain /bin/sh, no systemd-inhibit): it ends when the broker it watches ends.
test.skipIf(process.platform === "win32")("LINUX_WAIT_SH exits once the watched broker pid is gone", async () => {
  const broker = Bun.spawn(["sleep", "30"], { stdout: "ignore", stderr: "ignore" })
  const wait = Bun.spawn(["/bin/sh", "-c", LINUX_WAIT_SH, "mux-keep-awake", String(broker.pid)], { stdout: "ignore", stderr: "ignore" })
  await Bun.sleep(100)
  expect(wait.exitCode).toBeNull()
  broker.kill()
  await broker.exited
  const code = await Promise.race([wait.exited, Bun.sleep(6_000).then(() => "timeout")])
  expect(code).toBe(0)
}, 10_000)
