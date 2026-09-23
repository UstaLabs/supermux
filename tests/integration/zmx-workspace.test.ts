// REAL zmx, real daemons, real shells. Everything else about this backend is
// unit-tested against a fake helper; this file is the only place where the
// patched daemon, the Zig helper, the TypeScript backend and the client's own
// terminal engine are all the real thing at once.
//
// WHAT THIS EXISTS TO CATCH. Three of the contract's promises cannot be tested
// against a double, because a double is written from the same reading of the
// daemon that the implementation is:
//
//   1. A TARGET OUTLIVES ITS VIEWERS, AND A CLOSED ONE STAYS CLOSED. tmux's
//      `new-session -A` resurrects a dead session with a fresh shell; the
//      contract splits `ensure` from `attachExisting` so it cannot. Only a real
//      pid, recorded across a reconstructed backend, proves the shell is the
//      same process and not a new one wearing its name.
//   2. THE FOCUSED DEVICE OWNS THE GEOMETRY. `stty size` inside the real shell
//      is the only authority on what reached TIOCSWINSZ.
//   3. A SLOW VIEWER IS DROPPED, NOT ACCOMMODATED. Two caps, in two processes:
//      the daemon's, against a socket that is genuinely not draining, and the
//      broker's, against an `emit` that is slow while the pipe drains fine. A
//      unit test can only assert each state machine agrees with itself; only a
//      real flood shows which one fires.
//
// SAFETY. Every target in this file lives in a private socket directory minted
// per run under $XDG_RUNTIME_DIR, mode 0700, and nothing here ever enumerates,
// attaches to or kills anything outside it. `ZMX_SESSION` and `ZMX_DIR` are
// unset explicitly before the first helper starts: an inherited `ZMX_SESSION`
// would make the helper's `zmx attach` switch into a session we do not own.
// Cleanup kills by RECORDED PID only — never a pattern match, which on a shared
// machine matches other people's shells and this test runner's own.
import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "fs"
import { join } from "path"
import {
  isWorkspaceTerminalError,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
  type WorkspaceTerminalViewer,
} from "../../src/core/terminal/workspace-backend"
import { VIEWER_PENDING_MAX, ZmxWorkspaceBackend, type ZmxHelperLaunch } from "../../src/core/terminal/zmx/backend"
import { ZmxHelper, type HelperBinaries } from "../../src/core/terminal/zmx/helper"
import { decodeName, encodeName, socketBasename } from "../../src/core/terminal/zmx/names"
import {
  CELL_BOLD,
  REQUIRED,
  errorText,
  prerequisites,
  referenceEngine,
  type Viewport,
} from "./zmx-workspace-fixture"

const gate = prerequisites()

// A locally missing artifact is an unmet prerequisite; in CI it is a failure.
// This test runs either way, so "the suite was skipped" is never silent.
test("native prerequisites", () => {
  if (gate.ok) return
  if (REQUIRED) {
    throw new Error(
      `MUX_ZMX_INTEGRATION=1 demands the real backend, but: ${gate.reason}\n` +
      "The CI job builds build/zmx/out before running this suite; if that step is gone, restore it.",
    )
  }
  console.warn(`[zmx-integration] SKIPPED: ${gate.reason}`)
})

const suite = gate.ok ? describe : describe.skip
const binaries: HelperBinaries = gate.ok ? gate.binaries : ({} as HelperBinaries)
const shellSource = gate.ok ? gate.shellSource : "/bin/sh"

const encoder = new TextEncoder()
const decoder = new TextDecoder()
const bytes = (text: string) => encoder.encode(text)

/** Evidence for vendor/zmx/VERIFICATION.md. Printed, never asserted on. */
const evidence: Record<string, unknown>[] = []
function record(scenario: string, data: Record<string, unknown>): void {
  evidence.push({ scenario, ...data })
  console.log(`[zmx-evidence] ${scenario} ${JSON.stringify(data)}`)
}

suite("zmx workspace backend, against real processes", () => {
  let socketDir: string
  let scratch: string
  let shell: string
  let backend: ZmxWorkspaceBackend
  /** Every helper we launched, so a test can kill one by pid and cleanup can
   * stop the rest without matching on a name. */
  let helpers: ZmxHelper[] = []
  /** Daemon pids seen through our OWN socket directory. Cleanup kills these
   * and nothing else. */
  const daemonPids = new Set<number>()
  const scopes = new Set<string>()

  // The real launcher, with the handle kept: a test that has to kill a helper
  // the way the OS would needs its pid, and `kill()` is us stopping it.
  const launch: ZmxHelperLaunch = async handlers => {
    const helper = await ZmxHelper.launch(handlers, { binaries })
    helpers.push(helper)
    return helper
  }

  function newBackend(): ZmxWorkspaceBackend {
    // Deliberately NOT sharing state with `backend`: several tests reconstruct
    // the manager to prove discovery comes from the running daemons.
    return new ZmxWorkspaceBackend({ socketDir, binaries, launch })
  }

  /** `list` with the daemon pids the contract's `list` deliberately hides. */
  async function listRaw(): Promise<{ socket: string; name: string; pid: number; clients: number }[]> {
    const helper = await ZmxHelper.launch({ onOutput: () => {}, onEvent: () => {}, onFailure: () => {} }, { binaries })
    try {
      const rows = await helper.send<{ socket: string; name: string; pid: number; clients: number }[]>(
        { op: "list", dir: socketDir })
      for (const row of rows) if (Number.isInteger(row.pid) && row.pid > 1) daemonPids.add(row.pid)
      return rows
    } finally {
      helper.kill()
    }
  }

  async function create(scope: string, terminalId: string, cols = 100, rows = 30): Promise<WorkspaceTerminalKey> {
    scopes.add(scope)
    const key = { scope, terminalId }
    await backend.ensure(key, { cwd: scratch, shell, env: {}, cols, rows })
    await listRaw() // records the daemon pid for cleanup, whatever the test does next
    return key
  }

  // ---- a viewer, recorded ---------------------------------------------------

  type Recorder = {
    id: string
    viewer: WorkspaceTerminalViewer
    helper: ZmxHelper
    /** Non-output events in arrival order: the contract's trace. */
    trace: string[]
    events: WorkspaceTerminalEvent[]
    text: string
    outputBytes: number
    replay: Uint8Array[]
    /** Bytes delivered after the replay boundary closed. */
    live: Uint8Array[]
    /** What `reply()` answered while the replay boundary was open. */
    replyInsideReplay: boolean[]
    /** Suspend delivery: the emit callback stops returning. */
    stall(): void
    resume(): void
    waitForText(needle: string, ms?: number): Promise<void>
    waitForEvent(match: (event: WorkspaceTerminalEvent) => boolean, ms?: number): Promise<WorkspaceTerminalEvent>
    clear(): void
  }

  async function attach(
    key: WorkspaceTerminalKey,
    id: string,
    options: { on?: ZmxWorkspaceBackend } = {},
  ): Promise<Recorder> {
    const manager = options.on ?? backend
    let release: (() => void) | null = null
    let held: Promise<void> | null = null
    let inReplay = false
    let armed = false

    const rec: Partial<Recorder> & { trace: string[]; events: WorkspaceTerminalEvent[] } = {
      id, trace: [], events: [], text: "", outputBytes: 0,
      replay: [], live: [], replyInsideReplay: [],
    }
    const self = rec as Recorder
    self.stall = () => { armed = true }
    self.resume = () => { armed = false; release?.(); release = null; held = null }
    // Text only: the trace is the contract's event ORDER, and a test that
    // wiped it would be asserting on half a story.
    self.clear = () => { self.text = "" }

    // The viewer has to be reachable from INSIDE the emit callback: the replay
    // boundary is the one window where `reply()` has to be asked and its answer
    // recorded, and by then this function has not returned yet. The helper
    // writes its `ok` for the attach before it forwards a single daemon frame,
    // so this promise is always already settled when the callback consults it.
    let publish!: (viewer: WorkspaceTerminalViewer | null) => void
    const bound = new Promise<WorkspaceTerminalViewer | null>(resolve => { publish = resolve })

    const before = helpers.length
    const viewer = await manager.attachExisting(key, id, async event => {
      if (event.type === "output") {
        self.outputBytes += event.bytes.length
        self.text = (self.text + decoder.decode(event.bytes, { stream: true })).slice(-200_000)
        ;(inReplay ? self.replay : self.live).push(event.bytes)
        if (armed) {
          if (!held) held = new Promise<void>(resolve => { release = resolve })
          await held
        }
        return
      }
      self.events.push(event)
      self.trace.push(traceOf(event))
      if (event.type === "replay-start") inReplay = true
      if (event.type === "replay-end") inReplay = false
      if (inReplay) {
        // The one moment the contract calls out: bytes the replay produced must
        // not be typed back at the shell. Ask, and remember the answer.
        const live = await Promise.race([bound, Bun.sleep(2000).then(() => null)])
        if (live) self.replyInsideReplay.push(live.reply(bytes("\x1b[?62;c")))
      }
    }).catch(error => { publish(null); throw error })
    self.viewer = viewer
    publish(viewer)
    self.helper = helpers[before]!
    self.waitForText = (needle, ms = 15_000) => waitFor(() => self.text.includes(needle), ms,
      () => `${id} never saw ${JSON.stringify(needle)}; tail=${JSON.stringify(self.text.slice(-300))}`)
    self.waitForEvent = async (match, ms = 15_000) => {
      await waitFor(() => self.events.some(match), ms,
        () => `${id} never saw the event; trace=${JSON.stringify(self.trace)}`)
      return self.events.find(match)!
    }
    return self
  }

  function traceOf(event: WorkspaceTerminalEvent): string {
    switch (event.type) {
      case "reset": case "replay-start": case "replay-end": return `${event.type}(${event.epoch})`
      case "owner": return `owner(${event.enabled})`
      case "exit": return `exit(known=${event.known},code=${event.code},signal=${event.signal})`
      case "failure": return `failure(${event.code},recoverable=${event.recoverable})`
      default: return event.type
    }
  }

  async function waitFor(
    predicate: () => boolean | Promise<boolean>,
    ms: number,
    describe: () => string,
  ): Promise<void> {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      if (await predicate()) return
      await Bun.sleep(50)
    }
    throw new Error(`timed out after ${ms}ms: ${describe()}`)
  }

  /**
   * A `printf` whose OWN ECHO does not contain the marker it prints.
   *
   * The shell echoes the command line back over the pty before it runs it, so
   * `printf 'DONE\n'` puts the string "DONE" on the wire twice — and a test
   * waiting for it proceeds on the echo, before the command has run at all.
   * That is a race against the terminal width (whether a line wrap happens to
   * land inside the literal), and it passed alone and failed under load.
   * Splitting the marker across the format and its argument puts the halves on
   * the wire separately; only the OUTPUT ever has them adjacent.
   */
  function markerPrintf(marker: string): string {
    const cut = Math.ceil(marker.length / 2)
    return `printf '${marker.slice(0, cut)}%s\\n' '${marker.slice(cut)}'`
  }

  /** Run a command in the shell and wait for a marker it prints when done. */
  async function run(rec: Recorder, command: string, marker: string, ms = 20_000): Promise<void> {
    expect(rec.viewer.write(bytes(`${command}; ${markerPrintf(marker)}\r`))).toBe(true)
    await rec.waitForText(marker, ms)
  }

  /** The pty's real geometry, straight out of the shell. */
  async function ptySize(rec: Recorder): Promise<string> {
    rec.clear()
    const marker = `SZ${Math.random().toString(36).slice(2, 8)}`
    const cut = Math.ceil(marker.length / 2)
    expect(rec.viewer.write(bytes(
      `printf '${marker.slice(0, cut)}%s:%s\\n' '${marker.slice(cut)}' "$(stty size | tr ' ' x)"\r`))).toBe(true)
    const answer = /SZ[a-z0-9]{6}:(\d+x\d+)/
    await waitFor(() => answer.test(rec.text), 15_000,
      () => `no stty answer in ${JSON.stringify(rec.text.slice(-300))}`)
    return answer.exec(rec.text)![1]!
  }

  beforeAll(() => {
    // An inherited ZMX_SESSION makes `zmx attach` SWITCH sessions instead of
    // creating one; an inherited ZMX_DIR would point the CLI somewhere real.
    delete process.env.ZMX_SESSION
    delete process.env.ZMX_DIR
    const runtimeDir = process.env.XDG_RUNTIME_DIR!
    mkdirSync(join(runtimeDir, "supermux"), { recursive: true })
    socketDir = mkdtempSync(join(runtimeDir, "supermux", "zmx-it-"))
    chmodSync(socketDir, 0o700)
    scratch = mkdtempSync(join(process.env.TMPDIR || "/tmp", "zmx-it-"))
    // Not /bin/bash directly: `ZmxWorkspaceBackend` gives a login-capable shell
    // `-l`, and a login bash sources the host's profile, which on a developer
    // machine writes OSC sequences and a $PS1 this suite would have to parse
    // around. The wrapper is the same bash, minus the host's dotfiles.
    shell = join(scratch, "muxsh")
    writeFileSync(shell, `#!/bin/sh\nexec ${shellSource} --norc --noprofile "$@"\n`)
    chmodSync(shell, 0o700)
    backend = newBackend()
  })

  afterAll(async () => {
    for (const scope of scopes) {
      try { await backend.closeScope(scope) } catch { /* cleanup is best effort */ }
    }
    for (const helper of helpers) {
      // SIGCONT FIRST. A test that stops a helper to starve it of reads and
      // then fails leaves the process in state T, where the SIGTERM `kill()`
      // sends is merely PENDING — it would sit there after the run, holding a
      // socket, until somebody noticed. (Observed once, which is why this is
      // here.) SIGKILL afterwards for anything that still will not go.
      try { process.kill(helper.pid, "SIGCONT") } catch { /* already gone */ }
      try { helper.kill() } catch { /* already gone */ }
    }
    await Bun.sleep(300)
    for (const helper of helpers) {
      try { process.kill(helper.pid, 0); process.kill(helper.pid, "SIGKILL") } catch { /* gone */ }
    }
    // Anything still alive in OUR directory, killed by the pid we recorded from
    // OUR directory. Never a pattern.
    let survivors: { pid: number }[] = []
    try { survivors = await listRaw() } catch { /* the dir may be gone already */ }
    for (const pid of new Set([...survivors.map(row => row.pid), ...daemonPids])) {
      if (!Number.isInteger(pid) || pid <= 1) continue
      try { process.kill(pid, "SIGTERM") } catch { /* already gone */ }
    }
    await Bun.sleep(300)
    for (const pid of daemonPids) {
      try { process.kill(pid, 0); process.kill(pid, "SIGKILL") } catch { /* already gone */ }
    }
    rmSync(socketDir, { recursive: true, force: true })
    rmSync(scratch, { recursive: true, force: true })
    console.log(`[zmx-evidence] summary ${JSON.stringify(evidence)}`)
  })

  // ---- 2. the target outlives its viewers -----------------------------------

  test("a target outlives its viewers, survives a reconstructed manager, and a closed one is never resurrected", async () => {
    const key = await create("w:survival-0001", "main")
    const [created] = await listRaw()
    expect(created).toBeDefined()
    expect(created!.name).toBe(encodeName(key))
    const pid = created!.pid
    expect(pid).toBeGreaterThan(1)
    expect(existsSync(join("/proc", String(pid)))).toBe(true)

    const trace: string[] = []
    for (const round of [1, 2]) {
      const rec = await attach(key, `cycle-${round}`)
      await rec.viewer.focus(true, 100, 30)
      await rec.waitForEvent(event => event.type === "owner" && event.enabled)
      await run(rec, `echo ROUND-${round}`, `ROUND-${round}-DONE`)
      trace.push(`round${round}:${rec.trace.join(",")}`)
      await rec.viewer.detach()
      await Bun.sleep(200)
      // The daemon is still there with nobody watching it.
      expect((await listRaw()).map(row => row.pid)).toEqual([pid])
    }

    // A NEW manager, with nothing carried over: discovery has to come from the
    // running daemons' own `mux.target` labels, not from in-process state.
    const reborn = newBackend()
    expect(await reborn.list(key.scope)).toEqual([
      expect.objectContaining({ scope: key.scope, terminalId: key.terminalId }),
    ])
    const rec = await attach(key, "after-restart", { on: reborn })
    await rec.viewer.focus(true, 100, 30)
    await rec.waitForEvent(event => event.type === "owner" && event.enabled)
    // Same shell, same pid: `$$` is the shell's own, the daemon reported the
    // session's. They are the same process because zmx's session pid IS the
    // child it forked.
    await run(rec, "echo SHELLPID=$$", "PID-DONE")
    const shellPid = Number(/SHELLPID=(\d+)/.exec(rec.text)?.[1])
    expect(shellPid).toBe(pid)
    const samePid = (await listRaw())[0]!.pid
    expect(samePid).toBe(pid)
    await rec.viewer.detach()

    await reborn.close(key)
    await Bun.sleep(400)
    expect(await reborn.exists(key)).toBe(false)
    expect(existsSync(join(socketDir, socketBasename(key)))).toBe(false)

    // A reconnect must NOT recreate it. This is the tmux `new-session -A`
    // failure the contract exists to prevent: the tab would come back alive and
    // empty, with the exit never reported.
    let attachError: unknown
    try {
      await reborn.attachExisting(key, "ghost", async () => {})
    } catch (error) {
      attachError = error
    }
    expect(isWorkspaceTerminalError(attachError, "target-not-found")).toBe(true)
    expect((attachError as { recoverable: boolean }).recoverable).toBe(false)
    expect(await reborn.exists(key)).toBe(false)
    expect((await listRaw()).length).toBe(0)

    record("target-survival", {
      pid, shellPid, trace,
      attachAfterClose: { code: (attachError as { code: string }).code, message: errorText(attachError) },
    })
  }, 120_000)

  // ---- 3. the focused viewer owns the geometry ------------------------------

  test("the focused viewer owns the pty geometry; a background one never takes it", async () => {
    const key = await create("w:focus-0002", "main")
    const a = await attach(key, "A")
    const b = await attach(key, "B")

    await a.viewer.focus(true, 120, 40)
    await a.waitForEvent(event => event.type === "owner" && event.enabled)
    expect(await ptySize(a)).toBe("40x120")

    // B is in the background and reports a layout change. It also TYPES, which
    // under upstream's `isUserInput` heuristic would have promoted it to leader
    // and taken the geometry with it.
    await b.viewer.resize(60, 20)
    expect(b.viewer.write(bytes("# background tab types\r"))).toBe(true)
    await Bun.sleep(400)
    const afterBackgroundResize = await ptySize(a)
    expect(afterBackgroundResize).toBe("40x120")
    expect(b.events.some(event => event.type === "owner" && event.enabled)).toBe(false)

    // Focus moves with NO keystroke.
    await b.viewer.focus(true, 60, 20)
    await b.waitForEvent(event => event.type === "owner" && event.enabled)
    await a.waitForEvent(event => event.type === "owner" && !event.enabled)
    expect(await ptySize(b)).toBe("20x60")

    // A is now a stale generation. Its resize is not sent at all and its reply
    // is refused — both reported as "not delivered" rather than pretended.
    await a.viewer.resize(200, 50)
    const staleReply = a.viewer.reply(bytes("\x1b[?62;c"))
    expect(staleReply).toBe(false)
    await Bun.sleep(400)
    expect(await ptySize(b)).toBe("20x60")

    // Ordinary user input from the very same stale viewer still reaches the pty.
    b.clear()
    expect(a.viewer.write(bytes("echo STALE-VIEWER-CAN-STILL-TYPE\r"))).toBe(true)
    await b.waitForText("STALE-VIEWER-CAN-STILL-TYPE")
    expect(await ptySize(b)).toBe("20x60")

    // The owner's reply DOES reach the pty: it is typing as far as the shell is
    // concerned, which is exactly why only one viewer may send one.
    b.clear()
    expect(b.viewer.reply(bytes("\x1b[?62;c"))).toBe(true)
    await Bun.sleep(500)

    record("focus-geometry", {
      focusedA: "40x120", backgroundResizeIgnored: afterBackgroundResize, focusedB: "20x60",
      staleResizeIgnored: "20x60", staleReplyAccepted: staleReply,
      traceA: a.trace, traceB: b.trace,
    })
    await a.viewer.detach()
    await b.viewer.detach()
  }, 120_000)

  // ---- 4. restoration -------------------------------------------------------

  test("a re-attach restores styled unicode, the alternate screen, the output it missed and the history", async () => {
    const key = await create("w:restore-0003", "main", 100, 30)
    const a = await attach(key, "writer")
    await a.viewer.focus(true, 100, 30)
    await a.waitForEvent(event => event.type === "owner" && event.enabled)

    // Styled unicode history: bold red, combining-free but wide (CJK) and
    // astral (emoji) — the three things a naive serializer loses.
    await run(a,
      "for i in $(seq 1 120); do printf 'HIST-%03d \\033[1;31mRED-\\303\\234N\\303\\217\\303\\207\\303\\230DE-\\346\\227\\245\\346\\234\\254\\350\\252\\236-\\360\\237\\216\\211\\033[0m\\n' $i; done",
      "HISTORY-DONE")
    // Into the alternate screen, with styling of its own.
    await run(a,
      "printf '\\033[?1049h\\033[H\\033[1;32mALT-SCREEN-\\303\\204\\303\\226\\303\\234-\\360\\237\\216\\210\\033[0m\\n'",
      "ALT-DONE")

    // Arm output that happens while NOBODY is watching, then leave.
    expect(a.viewer.write(bytes("{ sleep 2; printf 'DETACHED-MARKER-A7\\n'; } &\r"))).toBe(true)
    await Bun.sleep(300)
    await a.viewer.detach()
    await Bun.sleep(3500)

    const c = await attach(key, "restorer")
    await c.waitForEvent(event => event.type === "replay-end")
    await Bun.sleep(500)
    // reset -> replay-start -> replay-end, all on ONE epoch, and no `output`
    // before the first reset.
    const epochs = c.events.filter(e => e.type === "reset" || e.type === "replay-start" || e.type === "replay-end")
    expect(epochs.map(e => e.type)).toEqual(["reset", "replay-start", "replay-end"])
    expect(new Set(epochs.map(e => (e as { epoch: string }).epoch)).size).toBe(1)
    expect(c.live.length).toBe(0) // nothing arrived before the boundary closed

    const replayBytes = concat(c.replay)
    const engine = await referenceEngine(100, 30)
    engine.feed(replayBytes, 1)
    const responses = engine.responses()
    const altScreen = engine.viewport()

    expect(altScreen.alternateScreen).toBe(true)
    expect(rowsOf(altScreen)).toContain("ALT-SCREEN-ÄÖÜ-🎈")
    // The OUTPUT line, not the echo of the command that scheduled it: the
    // command line is on screen too, and matching that would prove nothing.
    expect(rowsOf(altScreen).some(row =>
      row.includes("DETACHED-MARKER-A7") && !row.includes("sleep"))).toBe(true)
    expect(altScreen.cursorVisible).toBe(true)
    expect(altScreen.cursorRow).toBeGreaterThanOrEqual(0)
    expect(altScreen.cursorRow).toBeLessThan(altScreen.rows)
    expect(altScreen.bracketedPaste).toBe(true) // the shell's own mode, carried over
    // The alternate screen has no scrollback, and the snapshot does not invent one.
    expect(altScreen.historyRows).toBe(0)
    const altRow = altScreen.grid.find(row => row.text.startsWith("ALT-SCREEN"))!
    expect(altRow.cells[0]!.flags & CELL_BOLD).toBe(CELL_BOLD)

    // Every response the client's own emulator produced from the replay was
    // refused while the boundary was open. (`reply()` answers false for a
    // non-owner too, which a re-attaching viewer always is; the replay guard
    // itself is pinned in src/core/terminal/zmx/backend.test.ts.)
    expect(c.replyInsideReplay.length).toBeGreaterThan(0)
    expect(c.replyInsideReplay.every(accepted => accepted === false)).toBe(true)
    for (const response of responses) {
      expect(c.viewer.reply(bytes(response))).toBe(false)
    }

    // ... and the shell's command line is untouched by any of it.
    await c.viewer.focus(true, 100, 30)
    await c.waitForEvent(event => event.type === "owner" && event.enabled)
    c.clear()
    await run(c, "printf '\\033[?1049l'", "BACK-TO-PRIMARY")
    await c.viewer.detach()
    await Bun.sleep(300)

    const d = await attach(key, "history-reader")
    await d.waitForEvent(event => event.type === "replay-end")
    await Bun.sleep(500)
    const primaryEngine = await referenceEngine(100, 30)
    primaryEngine.feed(concat(d.replay), 1)
    const primaryReplay = concat(d.replay)
    const primary = primaryEngine.viewport()
    expect(primary.alternateScreen).toBe(false)

    // RETAINED HISTORY: every line that is sent survives.
    //
    // The snapshot is scrollback, then the phase separator, then the viewport.
    // That separator used to be `ESC[2J ESC[H ESC[0m` alone, and `ED 2` ERASES
    // THE SCREEN IN PLACE rather than scrolling it off: everything phase 1 had
    // just drawn and that was still on screen was wiped instead of becoming the
    // client's history, so a client kept `sent - rows + 1` lines and a terminal
    // with less than one screenful of scrollback kept NONE. The patch now ends
    // phase 1 with `rows` linefeeds — which push exactly those rows into the
    // client's scrollback — and keeps the erase behind them, where it is a
    // no-op at equal geometry. See vendor/zmx/VERIFICATION.md §6.
    //
    // So the separator on the wire is that whole run, and the equality below is
    // "nothing was lost", not a measurement of how much is.
    const scrollOff = bytes("\r\n".repeat(primary.rows))
    const separator = bytes(`${"\r\n".repeat(primary.rows)}\x1b[2J\x1b[H\x1b[0m`)
    const separatorAt = indexOfBytes(primaryReplay, separator)
    expect(separatorAt).toBeGreaterThan(0)
    // n newlines of content are n+1 lines; the last one has no newline after it.
    const historySent = countNewlines(primaryReplay.subarray(0, separatorAt)) + 1
    expect(historySent).toBeGreaterThan(primary.rows)
    expect(primary.historyRows).toBe(historySent)
    // ...and it is the OLDEST line that used to go first, so name it: the first
    // line on the wire is the first line in the client's scrollback.
    const firstSent = new TextDecoder().decode(primaryReplay.subarray(0, separatorAt))
      .split("\n").map(line => /HIST-\d{3}/.exec(line)?.[0]).find(Boolean)
    expect(firstSent).toBeDefined()

    primaryEngine.scrollTo(0)
    const top = rowsOf(primaryEngine.viewport())
    const styled = top.find(row => row.includes("RED-ÜNÏÇØDE-日本語-🎉"))
    expect(styled).toBeDefined()
    // The OLDEST lines survive, not just the ones that had already scrolled
    // past: the erase used to take the first screenful with it, and the first
    // history line on the wire is now inside the client's retained scrollback.
    expect(top.some(row => row.includes(firstSent!))).toBe(true)
    const styledRow = primaryEngine.viewport().grid.find(row => row.text.includes("RED-ÜNÏÇØDE"))!
    const redCell = styledRow.cells[styledRow.cells.findIndex(cell => cell.text === "R" && cell.flags !== 0)]!
    const plainCell = styledRow.cells[0]!
    expect(redCell.flags & CELL_BOLD).toBe(CELL_BOLD)
    expect(redCell.fg).not.toBe(plainCell.fg)

    record("restoration", {
      replayBytes: replayBytes.length,
      altScreen: {
        alternateScreen: altScreen.alternateScreen, cursor: [altScreen.cursorColumn, altScreen.cursorRow],
        historyRows: altScreen.historyRows, bracketedPaste: altScreen.bracketedPaste,
        row0: altScreen.grid[0]!.text,
      },
      primary: {
        replayBytes: primaryReplay.length, alternateScreen: primary.alternateScreen,
        historyLinesSent: historySent, historyRows: primary.historyRows,
        historyLost: historySent - primary.historyRows,
        scrollOffBytes: scrollOff.length, topRow: styled, firstSent,
        boldRedFg: `0x${redCell.fg.toString(16)}`, plainFg: `0x${plainCell.fg.toString(16)}`,
      },
      replayResponses: responses,
      replyInsideReplay: c.replyInsideReplay,
      traceRestorer: c.trace,
    })
    engine.destroy()
    primaryEngine.destroy()
    await d.viewer.detach()
  }, 180_000)

  // ---- 5. backpressure ------------------------------------------------------

  /** 12 MiB, bounded by `count` — never an unbounded `yes`. */
  const FLOOD_BYTES = 12 * 1024 * 1024
  const FLOOD = `dd if=/dev/zero bs=1024 count=${FLOOD_BYTES / 1024} 2>/dev/null | tr '\\0' 'A' | fold -w 200`

  test("a viewer that stops reading is detached recoverably; the shell and the other viewer keep going", async () => {
    const key = await create("w:flood-0004", "main", 100, 30)
    const a = await attach(key, "stalled")
    const b = await attach(key, "reader")
    await b.viewer.focus(true, 100, 30)
    await b.waitForEvent(event => event.type === "owner" && event.enabled)
    await a.waitForEvent(event => event.type === "replay-end")

    // STOPPING THE PROCESS, not the callback. A viewer whose `emit` never
    // returns does NOT stop the bytes: Bun drains a subprocess pipe eagerly and
    // queues the chunks in the broker, so the helper keeps reading the daemon
    // and the cap never fires (measured: the whole 12 MiB arrives once the
    // callback resumes — see the test below, and VERIFICATION.md §5). SIGSTOP
    // is the real thing: the process stops reading its socket, which is what a
    // wedged viewer actually looks like to the daemon.
    const stalledPid = a.helper.pid
    process.kill(stalledPid, "SIGSTOP")

    const started = Date.now()
    expect(b.viewer.write(bytes(`${FLOOD}; ${markerPrintf("FLOOD-DONE")}\r`))).toBe(true)
    await b.waitForText("FLOOD-DONE", 240_000)
    const floodMs = Date.now() - started
    const stalledDuringFlood = a.outputBytes

    // The shell never noticed, and neither did the viewer that kept reading.
    b.clear()
    await run(b, "echo STILL-ALIVE-$((6*7))", "ALIVE-DONE")
    expect(b.text).toContain("STILL-ALIVE-42")

    // THE DETACH IS BEHIND THE BACKLOG. The daemon said "resync_required" on
    // the very socket this viewer had stopped reading, so the news only arrives
    // once the process runs again. A stalled client cannot be told anything.
    process.kill(stalledPid, "SIGCONT")
    const detach = await a.waitForEvent(event => event.type === "failure", 60_000)
    expect(detach).toMatchObject({ type: "failure", code: "backend-unavailable", recoverable: true })
    expect((detach as { message: string }).message).toContain("resync_required")
    // Its queue was DROPPED, not delivered late: those bytes must not be drawn
    // on top of the next epoch.
    expect(a.outputBytes).toBeLessThan(b.outputBytes / 2)
    expect(a.events.some(event => event.type === "exit")).toBe(false)

    // Resume A through a FRESH restore, and check it shows the latest state.
    const a2 = await attach(key, "resumed")
    await a2.waitForEvent(event => event.type === "replay-end")
    await Bun.sleep(400)
    const resumedEngine = await referenceEngine(100, 30)
    resumedEngine.feed(concat(a2.replay), 1)
    const resumed = resumedEngine.viewport()
    expect(rowsOf(resumed).some(row => row.includes("STILL-ALIVE-42"))).toBe(true)

    // Repeat AROUND A RESTORE. A viewer that attaches into a running flood and
    // stops reading before its boundary closes is dropped the same way — the
    // daemon stages live output behind an open snapshot under a cap of its own.
    // A fresh target, so a snapshot over the 4 MiB serialization ceiling (a
    // different, UNRECOVERABLE detach) cannot be what fires instead.
    const second = await create(key.scope, "replay-victim", 100, 30)
    const driver = await attach(second, "replay-driver")
    await driver.viewer.focus(true, 100, 30)
    await driver.waitForEvent(event => event.type === "owner" && event.enabled)
    expect(driver.viewer.write(bytes(`${FLOOD}; ${markerPrintf("FLOOD2-DONE")}\r`))).toBe(true)
    await waitFor(() => driver.outputBytes > 256 * 1024, 120_000, () => "the second flood never started")
    const c = await attach(second, "stalled-in-replay")
    process.kill(c.helper.pid, "SIGSTOP")
    await driver.waitForText("FLOOD2-DONE", 240_000)
    process.kill(c.helper.pid, "SIGCONT")
    const replayDetach = await c.waitForEvent(event => event.type === "failure", 120_000)
    expect(replayDetach).toMatchObject({ type: "failure", code: "backend-unavailable", recoverable: true })
    // NOT `resync_required` — and that is the finding, not a loose assertion.
    // The daemon queues the BrokerDetach carrying that reason on the viewer's
    // own socket, which is the socket the viewer had stopped reading; by the
    // time it reads again the connection is closed, so what arrives is a LOST
    // SOCKET. Recoverable either way (both re-attach), but the stated reason
    // does not survive the case it exists for.
    //
    // What the broker CAN say, and now does, is WHEN it happened: a viewer
    // dropped with its restore still streaming is told so, which is the
    // difference between "the backend died" and "I was too slow to be given
    // my snapshot". See vendor/zmx/VERIFICATION.md §5.
    const replayMessage = (replayDetach as { message: string }).message
    expect(replayMessage).toMatch(/resync_required|daemon closed the connection/)
    if (!replayMessage.includes("resync_required")) {
      expect(replayMessage).toContain("restore was still streaming")
    }
    expect(c.events.some(event => event.type === "exit")).toBe(false)
    // The viewer it was staged for is gone; the target and its driver are not.
    await run(driver, "echo DRIVER-ALIVE", "DRIVER-DONE")
    await driver.viewer.detach()

    // The shell ends on its own: ONE exit event, with a real status behind it.
    expect(b.viewer.write(bytes("exit 7\r"))).toBe(true)
    const exit = await b.waitForEvent(event => event.type === "exit", 30_000)
    expect(exit).toEqual({ type: "exit", known: true, code: 7, signal: null })
    await Bun.sleep(1000)
    expect(b.events.filter(event => event.type === "exit").length).toBe(1)
    expect(a2.events.filter(event => event.type === "exit").length).toBeLessThanOrEqual(1)
    expect(await backend.exists(key)).toBe(false)

    record("backpressure", {
      floodMs, floodBytes: FLOOD_BYTES,
      stalledDuringFlood,
      stalledViewerBytes: a.outputBytes, readerBytes: b.outputBytes,
      detach: (detach as { message: string }).message,
      replayDetach: replayMessage,
      resumedHistoryRows: resumed.historyRows,
      exit: traceOf(exit),
      traceStalled: a.trace, traceReader: b.trace, traceStalledInReplay: c.trace,
    })
    resumedEngine.destroy()
  }, 600_000)

  test("a slow EMIT is bounded by the BROKER: the viewer is dropped, not buffered", async () => {
    // AWAITING `emit` IS NOT BACKPRESSURE. Bun reads a subprocess pipe eagerly,
    // so a viewer whose callback never returns keeps the helper draining the
    // daemon at full speed and the daemon's own 1 MiB cap never fires: the
    // bytes used to pile up inside the BROKER instead, 12.7 MB of them, where
    // nothing bounded them (VERIFICATION.md §5). `ZmxViewer` now counts what is
    // queued behind `emit` and drops the viewer at the same 1 MiB, with the
    // same recoverable `resync_required` the daemon uses — so the cost stays
    // bounded wherever the slow part is.
    const key = await create("w:slowemit-0012", "main", 100, 30)
    const slow = await attach(key, "slow-callback")
    const fast = await attach(key, "reader")
    await fast.viewer.focus(true, 100, 30)
    await fast.waitForEvent(event => event.type === "owner" && event.enabled)
    await slow.waitForEvent(event => event.type === "replay-end")

    slow.stall()
    expect(fast.viewer.write(bytes(`${FLOOD}; ${markerPrintf("SLOW-DONE")}\r`))).toBe(true)
    await fast.waitForText("SLOW-DONE", 240_000)
    const whileStalled = slow.outputBytes
    slow.resume()

    // Dropped, recoverably, and TOLD WHY — this one the broker can say on its
    // own side, so the reason survives (unlike the daemon's, which travels on
    // the socket the viewer stopped reading).
    const failure = await slow.waitForEvent(event => event.type === "failure", 60_000)
    expect(failure).toMatchObject({ type: "failure", code: "backend-unavailable", recoverable: true })
    expect((failure as { message: string }).message).toContain("resync_required")
    await Bun.sleep(500)
    // The queue was dropped, not delivered late: nowhere near the whole flood.
    expect(slow.outputBytes).toBeLessThan(VIEWER_PENDING_MAX * 3)
    expect(slow.outputBytes).toBeLessThan(fast.outputBytes / 2)
    expect(slow.events.some(event => event.type === "exit")).toBe(false)

    // The reader and the shell never noticed, and a fresh attach re-syncs.
    fast.clear()
    await run(fast, "echo SLOW-DROPPED-OK", "SLOWALIVE-DONE")
    expect(fast.text).toContain("SLOW-DROPPED-OK")
    const resumed = await attach(key, "slow-resumed")
    await resumed.waitForEvent(event => event.type === "replay-end")

    record("slow-emit-is-bounded", {
      floodBytes: FLOOD_BYTES, deliveredWhileStalled: whileStalled,
      deliveredBeforeDrop: slow.outputBytes, readerBytes: fast.outputBytes,
      cap: VIEWER_PENDING_MAX, drop: (failure as { message: string }).message,
      traceSlow: slow.trace,
    })
    await resumed.viewer.detach()
    await slow.viewer.detach()
    await fast.viewer.detach()
  }, 600_000)

  // ---- 6. failure distinctions ---------------------------------------------

  test("a killed helper is a lost viewer, not a dead target", async () => {
    const key = await create("w:failures-0005", "main")
    const neighbour = await create("w:failures-0005", "neighbour")
    const rec = await attach(key, "victim")
    await rec.viewer.focus(true, 100, 30)
    await rec.waitForEvent(event => event.type === "owner" && event.enabled)
    await run(rec, "echo HELPER-KILL-SETUP", "SETUP-DONE")

    const helperPid = rec.helper.pid
    expect(existsSync(join("/proc", String(helperPid)))).toBe(true)
    process.kill(helperPid, "SIGKILL")

    const failure = await rec.waitForEvent(event => event.type === "failure", 20_000)
    expect(failure).toMatchObject({ type: "failure", code: "backend-unavailable", recoverable: true })
    // A viewer we lost is NOT the target ending. "Your program ended" closes a
    // tab; "I cannot see your program" retries.
    expect(rec.events.some(event => event.type === "exit")).toBe(false)
    expect(await backend.exists(key)).toBe(true)
    expect(await backend.exists(neighbour)).toBe(true)

    // Re-attaching is the recovery, and it finds the same shell.
    const again = await attach(key, "recovered")
    await again.viewer.focus(true, 100, 30)
    await again.waitForEvent(event => event.type === "owner" && event.enabled)
    await run(again, "echo RECOVERED-AFTER-HELPER-KILL", "RECOVERY-DONE")
    record("helper-killed", { helperPid, failure: traceOf(failure), trace: rec.trace })
    await again.viewer.detach()
  }, 120_000)

  test("a killed daemon is a lost target, never an exit, and takes no neighbour with it", async () => {
    const key = await create("w:failures-0006", "doomed")
    const neighbour = await create("w:failures-0006", "bystander")
    const rec = await attach(key, "watcher")
    const bystander = await attach(neighbour, "bystander-viewer")
    await rec.viewer.focus(true, 100, 30)
    await rec.waitForEvent(event => event.type === "owner" && event.enabled)

    const row = (await listRaw()).find(entry => entry.name === encodeName(key))!
    expect(row.pid).toBeGreaterThan(1)
    // The DAEMON, not the shell. `zmx list` reports the session pid, which is
    // the program; killing that is the `exit` case, which is the opposite fact.
    const daemonPid = daemonPidOf(row.pid)
    expect(daemonPid).not.toBe(row.pid)
    process.kill(daemonPid, "SIGKILL")

    const failure = await rec.waitForEvent(event => event.type === "failure", 20_000)
    expect(failure).toMatchObject({ type: "failure", code: "backend-unavailable", recoverable: true })
    expect(rec.events.some(event => event.type === "exit")).toBe(false)
    expect(await backend.exists(key)).toBe(false)

    // The neighbour is a separate daemon with a separate socket: zmx's crash
    // isolation is the reason this backend is one daemon per target.
    expect(await backend.exists(neighbour)).toBe(true)
    expect(bystander.events.some(event => event.type === "failure" || event.type === "exit")).toBe(false)
    await bystander.viewer.focus(true, 100, 30)
    await bystander.waitForEvent(event => event.type === "owner" && event.enabled)
    await run(bystander, "echo NEIGHBOUR-UNHARMED", "NEIGHBOUR-DONE")

    record("daemon-killed", {
      sessionPid: row.pid, daemonPid, failure: traceOf(failure), trace: rec.trace,
    })
    await bystander.viewer.detach()
  }, 120_000)

  test("a shell killed by a signal is an exit WITH a signal, not exit code 0", async () => {
    // The other half of the distinction above, and the reason `exit` carries a
    // signal at all: a shell that was killed did not finish successfully, and
    // reporting code 0 there would close a tab claiming a clean end nobody saw.
    const key = await create("w:failures-0011", "signalled")
    const rec = await attach(key, "mourner")
    await rec.viewer.focus(true, 100, 30)
    await rec.waitForEvent(event => event.type === "owner" && event.enabled)
    await run(rec, "echo SIGNAL-SETUP", "SIGNAL-SETUP-DONE")

    const row = (await listRaw()).find(entry => entry.name === encodeName(key))!
    process.kill(row.pid, "SIGKILL")

    const exit = await rec.waitForEvent(event => event.type === "exit", 20_000)
    expect(exit).toEqual({ type: "exit", known: true, code: null, signal: 9 })
    expect(rec.events.filter(event => event.type === "exit").length).toBe(1)
    await Bun.sleep(600)
    expect(await backend.exists(key)).toBe(false)
    record("signalled-exit", { sessionPid: row.pid, exit: traceOf(exit), trace: rec.trace })
  }, 120_000)

  test("a binary that does not match the manifest is refused, and nothing is touched", async () => {
    const key = await create("w:failures-0007", "main")
    const rec = await attach(key, "unaffected")
    await rec.viewer.focus(true, 100, 30)
    await rec.waitForEvent(event => event.type === "owner" && event.enabled)

    // A bin dir whose manifest describes a DIFFERENT helper. Symlinks, so the
    // real binaries are never copied or modified.
    const tampered = join(scratch, "tampered")
    mkdirSync(join(tampered, "bin"), { recursive: true })
    symlinkSync(binaries.helper, join(tampered, "bin", "mux-zmx-helper"))
    symlinkSync(binaries.zmx, join(tampered, "bin", "zmx"))
    const real = JSON.parse(await Bun.file(binaries.manifest).text())
    const forged = { ...real, helper: { sha256: "0".repeat(64) } }
    writeFileSync(join(tampered, "manifest.json"), JSON.stringify(forged, null, 2))

    const poisoned = new ZmxWorkspaceBackend({
      socketDir,
      binaries: { dir: tampered, helper: join(tampered, "bin", "mux-zmx-helper"), zmx: join(tampered, "bin", "zmx"), manifest: join(tampered, "manifest.json") },
    })
    let refusal: unknown
    try {
      await poisoned.list(key.scope)
    } catch (error) {
      refusal = error
    }
    expect(isWorkspaceTerminalError(refusal, "backend-unavailable")).toBe(true)
    expect(errorText(refusal)).toContain("0000000000000000000000000000000000000000000000000000000000000000")
    // Not recoverable: retrying the same wrong binary cannot become right.
    expect((refusal as { recoverable: boolean }).recoverable).toBe(false)

    // The refusal execed nothing, so the running target is untouched.
    expect(await backend.exists(key)).toBe(true)
    await run(rec, "echo SURVIVED-TAMPER-CHECK", "TAMPER-DONE")

    record("manifest-mismatch", { message: errorText(refusal), targetStillRunning: true })
    await rec.viewer.detach()
  }, 120_000)

  test("a target closed while an attach is in flight is target-not-found, not a half-attached viewer", async () => {
    const key = await create("w:failures-0008", "revoked")
    const neighbour = await create("w:failures-0008", "keeper")
    const survivor = await attach(neighbour, "keeper-viewer")

    const events: WorkspaceTerminalEvent[] = []
    const attaching = backend.attachExisting(key, "racer", async event => { events.push(event) })
    // Close while that attach is in flight. The contract says the viewer must
    // not outlive the target, and must not be told "I lost your terminal" for
    // something we did on purpose.
    const closing = backend.close(key)
    let raceError: unknown
    try { await attaching } catch (error) { raceError = error }
    await closing

    if (raceError !== undefined) {
      expect(isWorkspaceTerminalError(raceError, "target-not-found")).toBe(true)
    } else {
      // The attach won the race; the close then discarded it silently.
      await Bun.sleep(500)
      expect(events.some(event => event.type === "failure")).toBe(false)
    }
    expect(await backend.exists(key)).toBe(false)
    expect(await backend.exists(neighbour)).toBe(true)
    await survivor.viewer.focus(true, 100, 30)
    await survivor.waitForEvent(event => event.type === "owner" && event.enabled)
    await run(survivor, "echo KEEPER-ALIVE", "KEEPER-DONE")

    record("revoked-while-attaching", {
      outcome: raceError === undefined ? "attach won, viewer discarded silently" : errorText(raceError),
      neighbourAlive: true,
    })
    await survivor.viewer.detach()
  }, 120_000)

  test("deleting a workspace while output drains takes exactly that workspace", async () => {
    const doomedScope = "w:scope-0009"
    const keptScope = "w:scope-0010"
    const doomed = await create(doomedScope, "one")
    const alsoDoomed = await create(doomedScope, "two")
    const kept = await create(keptScope, "survivor")

    const draining = await attach(doomed, "draining")
    const keeper = await attach(kept, "keeper")
    await draining.viewer.focus(true, 100, 30)
    await draining.waitForEvent(event => event.type === "owner" && event.enabled)
    await keeper.viewer.focus(true, 100, 30)
    await keeper.waitForEvent(event => event.type === "owner" && event.enabled)

    // Start a long output and delete the workspace while it is still running.
    expect(draining.viewer.write(bytes(
      "dd if=/dev/zero bs=1024 count=1536 2>/dev/null | tr '\\0' 'B' | fold -w 200\r"))).toBe(true)
    await waitFor(() => draining.outputBytes > 64 * 1024, 120_000, () => "the flood never started")
    await backend.closeScope(doomedScope)
    // BOUNDED WAIT, NOT A FIXED SLEEP. `close` resolves when the helper has
    // DELIVERED the kill (`cmdKill` sends `.Kill` and answers), not when the
    // daemon has finished dying and unlinked its socket — and a daemon that is
    // still draining a megabyte can take a moment over it. A fixed 800 ms was
    // enough about nine times in ten on a loaded box; this asserts the same
    // thing without the tenth being a false failure.
    let doomedRows = await backend.list(doomedScope)
    await waitFor(async () => (doomedRows = await backend.list(doomedScope)).length === 0, 15_000,
      () => `the closed scope still lists ${JSON.stringify(doomedRows.map(row => row.terminalId))}`)

    expect(doomedRows).toEqual([])
    expect(await backend.exists(doomed)).toBe(false)
    expect(await backend.exists(alsoDoomed)).toBe(false)
    // A close we asked for is not a failure a client should see.
    expect(draining.events.some(event => event.type === "failure" || event.type === "exit")).toBe(false)

    // EXACTLY that scope.
    expect((await backend.list(keptScope)).map(entry => entry.terminalId)).toEqual(["survivor"])
    keeper.clear()
    await run(keeper, "echo OTHER-SCOPE-UNHARMED", "SCOPE-DONE")

    record("close-scope-under-load", {
      drainedBytes: draining.outputBytes,
      doomedScopeAfter: await backend.list(doomedScope),
      keptScopeAfter: (await backend.list(keptScope)).map(entry => entry.terminalId),
      traceDraining: draining.trace,
    })
    await keeper.viewer.detach()
  }, 300_000)

  test("a listing too big for one frame comes back WHOLE, not silently short", async () => {
    // WHAT A SHORT LISTING COSTS. `#confirmClosed`/`#waitUnlisted` decide a
    // target is gone because its row is absent, and `closeScope` closes exactly
    // the rows it is handed — so a listing that is short is not a smaller
    // truth, it is a false one, and it ends with `close()` logging success over
    // a shell that is still running.
    //
    // The listing was built as ONE control frame. `OutBuf.frame` refuses a
    // payload over MAX_PAYLOAD, and the refusal was a line on stderr and a
    // dropped frame: the request never got its `ok`, the broker waited out its
    // 20-second send timeout and killed the helper — during a close, where
    // `list` is what decides whether the shell is gone.
    //
    // The budget is lowered rather than the listing raised: spanning frames at
    // the real 63 KiB takes hundreds of live shells, which is not a thing a
    // test stands up. What is under test is the chunking, and 64 bytes makes
    // every one of these rows its own chunk.
    const scope = "w:scope-0011"
    const made: string[] = []
    for (const id of ["alpha", "bravo", "charlie", "delta", "echo"]) {
      await create(scope, id)
      made.push(id)
    }

    const seen: string[] = []
    const chunked = await ZmxHelper.launch(
      { onOutput: () => {}, onEvent: event => { seen.push(event.ev) }, onFailure: () => {} },
      { binaries, listChunkBytes: 64 },
    )
    try {
      const rows = await chunked.send<Array<{ name: string }>>({ op: "list", dir: socketDir })
      // The test is only a test if the listing ACTUALLY spanned frames. Five
      // rows at a 64-byte budget is four `chunk` events and the `ok`; before
      // this existed there was one frame and no `chunk` at all.
      expect(seen.filter(ev => ev === "chunk").length).toBeGreaterThan(0)
      expect(seen.filter(ev => ev === "ok").length).toBe(1)
      // Every target, exactly once, reassembled in order across the frames.
      const ours = rows
        .map(row => decodeName(row.name))
        .filter((key): key is NonNullable<typeof key> => key !== null && key.scope === scope)
        .map(key => key.terminalId)
        .sort()
      expect(ours).toEqual([...made].sort())
    } finally {
      chunked.kill()
    }

    // ...and the same listing through the ordinary single-frame path agrees,
    // so chunking is not producing a different answer.
    expect((await backend.list(scope)).map(entry => entry.terminalId).sort()).toEqual([...made].sort())
    await backend.closeScope(scope)
  }, 180_000)
})

function concat(chunks: Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const out = new Uint8Array(total)
  let at = 0
  for (const chunk of chunks) { out.set(chunk, at); at += chunk.length }
  return out
}

function rowsOf(viewport: Viewport): string[] {
  return viewport.grid.map(row => row.text)
}

function indexOfBytes(haystack: Uint8Array, needle: Uint8Array): number {
  outer: for (let at = 0; at + needle.length <= haystack.length; at++) {
    for (let i = 0; i < needle.length; i++) if (haystack[at + i] !== needle[i]) continue outer
    return at
  }
  return -1
}

function countNewlines(data: Uint8Array): number {
  let n = 0
  for (const byte of data) if (byte === 0x0a) n++
  return n
}

/**
 * The DAEMON behind a session, which is the session pid's PARENT.
 *
 * `zmx list` reports the pid of the shell, not of the daemon that owns the
 * socket — the daemon double-forks and then forks the shell — so killing what
 * `list` reports kills the PROGRAM and produces an exit, which is the opposite
 * of the case under test.
 */
function daemonPidOf(sessionPid: number): number {
  const status = readFileSync(join("/proc", String(sessionPid), "status"), "utf8")
  const parent = Number(/^PPid:\s*(\d+)$/m.exec(status)?.[1])
  if (!Number.isInteger(parent) || parent <= 1) throw new Error(`no parent for session pid ${sessionPid}`)
  const cmdline = readFileSync(join("/proc", String(parent), "cmdline"), "utf8")
  if (!cmdline.includes("zmx")) throw new Error(`pid ${parent} is not a zmx daemon: ${JSON.stringify(cmdline)}`)
  return parent
}
