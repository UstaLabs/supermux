// "Keep this computer awake while hosting" (spec 2026-09-30-desktop-hosting-lifecycle-design, the
// section approved 2026-10-04). The broker holds a sleep inhibitor through a child process that is
// bound to the broker's lifetime, so a crash or SIGKILL of the broker never leaves the computer
// unable to sleep:
//   - macOS: `caffeinate -is -w <brokerPid>` (exits by itself when the broker dies)
//   - Linux: `systemd-inhibit --what=sleep:idle … sh -c <wait for the broker>` (the lock lives as
//     long as the wait does; the wait ends with the broker or with systemd-inhibit)
//   - Windows: a hidden PowerShell that calls `SetThreadExecutionState(ES_CONTINUOUS |
//     ES_SYSTEM_REQUIRED)` and then `Wait-Process -Id <brokerPid>`
// "Also on battery" off releases the inhibitor while the computer runs on battery (polled every
// 30 s; unknown counts as AC). An inhibitor that exits while it should be held is re-spawned with
// backoff (1 s → 60 s), at most 10 times an hour; after that the state says why it is not active.
import { execFile, spawn as nodeSpawn } from "child_process"
import { readFile, readdir } from "fs/promises"
import { join } from "path"
import type { KeepAwakeSettings } from "../settings/keep-awake-config"

export interface KeepAwakeState {
  enabled: boolean
  /** The "Also on battery" setting (not whether the computer is on battery right now). */
  onBattery: boolean
  /** The inhibitor is held right now. */
  active: boolean
  /** This computer has a way to hold one. */
  supported: boolean
  /** Why it is not active although enabled (unsupported, released on battery, gave up). */
  reason?: string
}

/** How the inhibitor ended: a normal exit (code/signal) or a failure to start (error). */
export interface InhibitorExit {
  code?: number | null
  signal?: string | null
  error?: string
}

export interface InhibitorChild {
  /** Resolves once, when the child exits or fails to start. Never rejects. */
  exited: Promise<InhibitorExit>
  kill(): void
}

/** Starts [argv] with no shell. Must never throw and never leave an unhandled `error` event. */
export type InhibitorSpawner = (argv: string[]) => InhibitorChild

export interface KeepAwakeClock {
  now(): number
  setTimeout(fn: () => void, ms: number): unknown
  clearTimeout(handle: unknown): void
  setInterval(fn: () => void, ms: number): unknown
  clearInterval(handle: unknown): void
}

export interface KeepAwakeDeps {
  platform: NodeJS.Platform
  spawn: InhibitorSpawner
  /** The absolute path of [bin] on PATH, or null. */
  which: (bin: string) => string | null
  /** Is the computer on battery right now? undefined when it can't tell (treated as AC). */
  onBattery: () => Promise<boolean | undefined>
  clock?: KeepAwakeClock
  /** The broker's pid (the inhibitor waits on it). Defaults to process.pid. */
  pid?: number
  log?: (event: string, data?: Record<string, unknown>) => void
  batteryPollMs?: number
}

export const BATTERY_POLL_MS = 30_000
export const RESPAWN_MIN_MS = 1_000
export const RESPAWN_MAX_MS = 60_000
export const RESPAWN_MAX_PER_HOUR = 10
const HOUR_MS = 60 * 60_000
/** A child that lived at least this long resets the backoff to [RESPAWN_MIN_MS]. */
const HEALTHY_RUN_MS = 60_000

export const REASON_ON_BATTERY = "Released while on battery"
export const REASON_GAVE_UP = "The keep-awake helper kept exiting; it was stopped after 10 tries in an hour"
export const REASON_NO_SYSTEMD_INHIBIT = "systemd-inhibit was not found, so this computer can't be kept awake"
export const reasonUnsupportedPlatform = (p: string) => `Keeping the computer awake isn't supported on ${p}`

// ── per-OS commands ───────────────────────────────────────────────────────────────────────────

export const CAFFEINATE = "/usr/bin/caffeinate"

/**
 * The Linux wait under systemd-inhibit. Same parent-bound idea as frpc's wrapper
 * (`parentBoundFrpcCommand` in core/relay/frp-provider): poll with `kill -0` and end as soon as
 * either the broker ($1) or our own parent (systemd-inhibit, $PPID) is gone, so neither a broker
 * SIGKILL nor a kill of systemd-inhibit leaves the lock or an orphan behind.
 */
export const LINUX_WAIT_SH = [
  'broker="$1"',
  "parent=$PPID",
  "trap 'exit 0' TERM INT HUP",
  'while kill -0 "$broker" 2>/dev/null && kill -0 "$parent" 2>/dev/null; do sleep 2; done',
].join("\n")

/** ES_CONTINUOUS | ES_SYSTEM_REQUIRED */
export const ES_CONTINUOUS_SYSTEM_REQUIRED = 0x80000001

export function windowsKeepAwakeScript(pid: number): string {
  return [
    "$ErrorActionPreference = 'Stop'",
    "Add-Type -Namespace MuxKeepAwake -Name Power -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint esFlags);'",
    `[void][MuxKeepAwake.Power]::SetThreadExecutionState([uint32]${ES_CONTINUOUS_SYSTEM_REQUIRED})`,
    `Wait-Process -Id ${Math.trunc(pid)}`,
  ].join("; ")
}

/**
 * PowerShell's `-EncodedCommand` form of [script] (base64 of UTF-16LE). Windows' command-line
 * quoting mangles the embedded double quotes of a DllImport under `-Command`; the encoded form
 * carries the script byte-for-byte.
 */
export function encodePowerShell(script: string): string {
  return Buffer.from(script, "utf16le").toString("base64")
}

export type InhibitorCommand = { argv: string[] } | { unsupported: string }

/** The fixed argv that holds the inhibitor on [platform] while [pid] lives. */
export function inhibitorCommand(platform: NodeJS.Platform, pid: number, which: (bin: string) => string | null): InhibitorCommand {
  const p = String(Math.trunc(pid))
  if (platform === "darwin") return { argv: [CAFFEINATE, "-is", "-w", p] }
  if (platform === "linux") {
    const inhibit = which("systemd-inhibit")
    if (!inhibit) return { unsupported: REASON_NO_SYSTEMD_INHIBIT }
    return {
      argv: [
        inhibit, "--what=sleep:idle", "--who=supermux", "--why=Hosting agents", "--mode=block",
        "/bin/sh", "-c", LINUX_WAIT_SH, "mux-keep-awake", p,
      ],
    }
  }
  if (platform === "win32") {
    return {
      argv: [
        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodePowerShell(windowsKeepAwakeScript(pid)),
      ],
    }
  }
  return { unsupported: reasonUnsupportedPlatform(platform) }
}

// ── the real spawner and battery probes ───────────────────────────────────────────────────────

/**
 * The real spawner: no shell, no window, stdio ignored. A missing binary arrives as an async
 * `error` event (ENOENT) that would crash the broker if unhandled, so the handler is attached
 * synchronously and the whole spawn is wrapped.
 */
export function spawnInhibitor(argv: string[]): InhibitorChild {
  let resolveExit!: (e: InhibitorExit) => void
  let settled = false
  const exited = new Promise<InhibitorExit>((r) => { resolveExit = r })
  const finish = (e: InhibitorExit) => {
    if (settled) return
    settled = true
    resolveExit(e)
  }
  let child: ReturnType<typeof nodeSpawn> | undefined
  try {
    child = nodeSpawn(argv[0]!, argv.slice(1), { stdio: "ignore", windowsHide: true, shell: false })
    child.on("error", (err) => finish({ error: String(err) }))
    child.on("exit", (code, signal) => finish({ code, signal }))
  } catch (err) {
    finish({ error: String(err) })
  }
  return {
    exited,
    kill: () => {
      try { child?.kill() } catch { /* already gone */ }
    },
  }
}

/** `pmset -g batt` → true on battery, false on AC, undefined when it doesn't say. */
export function parsePmsetBatt(out: string): boolean | undefined {
  if (/'Battery Power'/.test(out)) return true
  if (/'AC Power'/.test(out)) return false
  return undefined
}

/**
 * Linux `/sys/class/power_supply/*` entries → on battery? A Mains supply that is online means AC;
 * Mains supplies that are all offline (with a battery present) mean battery. With no Mains entry,
 * a battery that reports Discharging means battery. Anything else: undefined.
 */
export function linuxOnBatteryFrom(supplies: Array<{ type?: string; online?: string; status?: string }>): boolean | undefined {
  const mains = supplies.filter((s) => s.type?.trim() === "Mains")
  const batteries = supplies.filter((s) => s.type?.trim() === "Battery")
  const onlines = mains.map((s) => s.online?.trim()).filter((v) => v === "0" || v === "1")
  if (onlines.includes("1")) return false
  if (onlines.length > 0 && batteries.length > 0) return true
  if (batteries.some((b) => b.status?.trim() === "Discharging")) return true
  if (batteries.length > 0 && batteries.every((b) => ["Charging", "Full", "Not charging"].includes(b.status?.trim() ?? ""))) return false
  return undefined
}

/** `Get-CimInstance Win32_Battery` BatteryStatus values → on battery? (1 = discharging). */
export function parseWin32BatteryStatus(out: string): boolean | undefined {
  const codes = out.split(/\r?\n/).map((l) => l.trim()).filter((l) => /^\d+$/.test(l)).map(Number)
  if (codes.length === 0) return false // no battery: a desktop on mains
  if (codes.includes(1)) return true
  return false
}

function execText(cmd: string, args: string[], timeoutMs = 5_000): Promise<string | undefined> {
  return new Promise((resolve) => {
    try {
      execFile(cmd, args, { timeout: timeoutMs, windowsHide: true }, (err, stdout) => {
        resolve(err ? undefined : String(stdout))
      })
    } catch {
      resolve(undefined)
    }
  })
}

async function readTrim(path: string): Promise<string | undefined> {
  try {
    return (await readFile(path, "utf8")).trim()
  } catch {
    return undefined
  }
}

const POWER_SUPPLY_DIR = "/sys/class/power_supply"

/** The real, async battery probe for [platform]; undefined (→ AC) on any failure. */
export function batteryProbe(platform: NodeJS.Platform): () => Promise<boolean | undefined> {
  if (platform === "darwin") {
    return async () => {
      const out = await execText("pmset", ["-g", "batt"])
      return out === undefined ? undefined : parsePmsetBatt(out)
    }
  }
  if (platform === "linux") {
    return async () => {
      try {
        const names = await readdir(POWER_SUPPLY_DIR)
        const supplies = await Promise.all(names.map(async (n) => ({
          type: await readTrim(join(POWER_SUPPLY_DIR, n, "type")),
          online: await readTrim(join(POWER_SUPPLY_DIR, n, "online")),
          status: await readTrim(join(POWER_SUPPLY_DIR, n, "status")),
        })))
        return linuxOnBatteryFrom(supplies)
      } catch {
        return undefined
      }
    }
  }
  if (platform === "win32") {
    return async () => {
      const out = await execText("powershell.exe", [
        "-NoProfile", "-NonInteractive", "-Command",
        "Get-CimInstance Win32_Battery | ForEach-Object { $_.BatteryStatus }",
      ], 10_000)
      return out === undefined ? undefined : parseWin32BatteryStatus(out)
    }
  }
  return async () => undefined
}

export const realClock: KeepAwakeClock = {
  now: () => Date.now(),
  setTimeout: (fn, ms) => {
    const h = setTimeout(fn, ms)
    ;(h as { unref?: () => void }).unref?.()
    return h
  },
  clearTimeout: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
  setInterval: (fn, ms) => {
    const h = setInterval(fn, ms)
    ;(h as { unref?: () => void }).unref?.()
    return h
  },
  clearInterval: (h) => clearInterval(h as ReturnType<typeof setInterval>),
}

// ── the manager ───────────────────────────────────────────────────────────────────────────────

export class KeepAwake {
  private settings: KeepAwakeSettings
  private readonly clock: KeepAwakeClock
  private readonly pid: number
  private command: InhibitorCommand | undefined
  private child: InhibitorChild | undefined
  private childStartedAt = 0
  private respawnTimer: unknown = undefined
  private pollTimer: unknown = undefined
  private backoffMs = RESPAWN_MIN_MS
  /** Times of unexpected exits (each one is a re-spawn try) in the last hour. */
  private failures: number[] = []
  private gaveUp = false
  private lastError: string | undefined
  /** The computer is on battery right now (unknown = AC). */
  private batteryNow = false
  private started = false
  private stopped = false
  private lastEmitted = ""
  private readonly listeners = new Set<(s: KeepAwakeState) => void>()

  constructor(private readonly deps: KeepAwakeDeps, initial: KeepAwakeSettings) {
    this.settings = { ...initial }
    this.clock = deps.clock ?? realClock
    this.pid = deps.pid ?? process.pid
  }

  private log(event: string, data?: Record<string, unknown>) {
    try { this.deps.log?.(event, data) } catch { /* logging never breaks keep-awake */ }
  }

  private cmd(): InhibitorCommand {
    if (!this.command) this.command = inhibitorCommand(this.deps.platform, this.pid, this.deps.which)
    return this.command
  }

  get supported(): boolean {
    return "argv" in this.cmd()
  }

  state(): KeepAwakeState {
    const cmd = this.cmd()
    const s: KeepAwakeState = {
      enabled: this.settings.enabled,
      onBattery: this.settings.onBattery,
      active: this.child !== undefined,
      supported: "argv" in cmd,
    }
    if ("unsupported" in cmd) s.reason = cmd.unsupported
    else if (this.settings.enabled && this.gaveUp) s.reason = this.lastError ? `${REASON_GAVE_UP} (${this.lastError})` : REASON_GAVE_UP
    else if (this.settings.enabled && this.releasedForBattery()) s.reason = REASON_ON_BATTERY
    return s
  }

  onChange(fn: (s: KeepAwakeState) => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  /** Begin: hold the inhibitor if enabled, and watch the battery if that matters. */
  start(): KeepAwakeState {
    if (this.started) return this.state()
    this.started = true
    this.stopped = false
    this.apply()
    this.lastEmitted = JSON.stringify(this.state())
    if (this.needsBattery()) void this.pollBattery()
    return this.state()
  }

  /** A new user choice. Clears a previous give-up, so toggling is the way to retry. */
  update(next: KeepAwakeSettings): KeepAwakeState {
    const wasWatching = this.needsBattery()
    this.settings = { ...next }
    this.gaveUp = false
    this.failures = []
    this.backoffMs = RESPAWN_MIN_MS
    this.lastError = undefined
    if (this.started) {
      this.apply()
      this.emitIfChanged()
      if (!wasWatching && this.needsBattery()) void this.pollBattery()
    }
    return this.state()
  }

  /** Re-check the battery now and re-apply (after a wake: the power source may have changed). */
  async refresh(): Promise<void> {
    if (!this.started || this.stopped) return
    if (this.needsBattery()) await this.pollBattery()
    else {
      this.apply()
      this.emitIfChanged()
    }
  }

  /** Release and stop all timers (broker shutdown). */
  stop(): void {
    this.stopped = true
    this.started = false
    this.clearRespawn()
    this.stopPoll()
    this.release()
  }

  private needsBattery(): boolean {
    return this.settings.enabled && !this.settings.onBattery && this.supported
  }

  private releasedForBattery(): boolean {
    return !this.settings.onBattery && this.batteryNow
  }

  private shouldHold(): boolean {
    return this.started && !this.stopped && this.settings.enabled && this.supported && !this.gaveUp && !this.releasedForBattery()
  }

  /** Converge the child (and the battery poll) to the settings. */
  private apply(): void {
    if (this.needsBattery() && this.started) this.startPoll()
    else this.stopPoll()
    if (this.shouldHold()) {
      if (!this.child && this.respawnTimer === undefined) this.acquire()
    } else {
      this.clearRespawn()
      this.release()
    }
  }

  private acquire(): void {
    const cmd = this.cmd()
    if (!("argv" in cmd)) return
    let child: InhibitorChild
    try {
      child = this.deps.spawn(cmd.argv)
    } catch (err) {
      // A spawner should never throw; if it does, it is one failed try like any other.
      this.log("keep_awake_spawn_failed", { err: String(err) })
      this.onUnexpectedExit({ error: String(err) })
      return
    }
    this.child = child
    this.childStartedAt = this.clock.now()
    this.log("keep_awake_acquired", { platform: this.deps.platform })
    const onExit = (e: InhibitorExit) => {
      if (this.child !== child) return // released on purpose, or replaced
      this.child = undefined
      this.onUnexpectedExit(e ?? {}, this.clock.now() - this.childStartedAt)
    }
    void child.exited.then(onExit, (err) => onExit({ error: String(err) }))
  }

  private release(): void {
    const child = this.child
    if (!child) return
    this.child = undefined
    try { child.kill() } catch { /* already gone */ }
    this.log("keep_awake_released", {})
  }

  private onUnexpectedExit(e: InhibitorExit, livedMs = 0): void {
    const now = this.clock.now()
    this.lastError = e.error ?? (e.code != null ? `exit ${e.code}` : e.signal ? `signal ${e.signal}` : undefined)
    this.log("keep_awake_child_exited", { ...e, livedMs })
    if (livedMs >= HEALTHY_RUN_MS) this.backoffMs = RESPAWN_MIN_MS
    this.failures = this.failures.filter((t) => now - t < HOUR_MS)
    this.failures.push(now)
    if (!this.shouldHold()) {
      this.emitIfChanged()
      return
    }
    if (this.failures.length > RESPAWN_MAX_PER_HOUR) {
      this.gaveUp = true
      this.log("keep_awake_gave_up", { tries: RESPAWN_MAX_PER_HOUR, lastError: this.lastError })
      this.emitIfChanged()
      return
    }
    const delay = this.backoffMs
    this.backoffMs = Math.min(RESPAWN_MAX_MS, this.backoffMs * 2)
    this.respawnTimer = this.clock.setTimeout(() => {
      this.respawnTimer = undefined
      if (this.shouldHold() && !this.child) {
        this.acquire()
        this.emitIfChanged()
      }
    }, delay)
    this.emitIfChanged()
  }

  private clearRespawn(): void {
    if (this.respawnTimer === undefined) return
    this.clock.clearTimeout(this.respawnTimer)
    this.respawnTimer = undefined
  }

  private startPoll(): void {
    if (this.pollTimer !== undefined) return
    this.pollTimer = this.clock.setInterval(() => { void this.pollBattery() }, this.deps.batteryPollMs ?? BATTERY_POLL_MS)
  }

  private stopPoll(): void {
    if (this.pollTimer === undefined) return
    this.clock.clearInterval(this.pollTimer)
    this.pollTimer = undefined
  }

  private async pollBattery(): Promise<void> {
    let on: boolean | undefined
    try {
      on = await this.deps.onBattery()
    } catch {
      on = undefined
    }
    if (this.stopped || !this.started) return
    const next = on === true // unknown → AC
    if (next !== this.batteryNow) {
      this.batteryNow = next
      this.log("keep_awake_power_source", { onBattery: next })
    }
    this.apply()
    this.emitIfChanged()
  }

  private emitIfChanged(): void {
    const s = this.state()
    const key = JSON.stringify(s)
    if (key === this.lastEmitted) return
    this.lastEmitted = key
    for (const fn of this.listeners) {
      try { fn({ ...s }) } catch (err) { this.log("keep_awake_listener_failed", { err: String(err) }) }
    }
  }
}
