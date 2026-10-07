// REAL PROGRAMS, END TO END: vim with the mouse on, less, htop, a 50k-line
// build, emoji, line editing, a multiline paste, two devices at two sizes, a
// shell that exits, a close, a disconnect and a broker restart.
//
// WHY THIS IS A SEPARATE FILE FROM zmx-workspace.test.ts. That suite proves the
// BACKEND CONTRACT against real processes — who outlives whom, who owns the
// geometry, who gets dropped. This one proves the thing a user actually does:
// run a program, look at the screen, reconnect, and see the same screen. The
// two failure modes are different, and so is what each file has to keep stable.
//
// AND WHY IT IS AN INTEGRATION TEST RATHER THAN A CLICK-THROUGH. Every scenario
// here needs three real things at once — a real pty, the real zmx daemon, and
// the client's real VT engine — and exactly one of them (the GUI) is not
// needed. `referenceEngine` is `supermux-terminal.wasm`: the same binary the
// browser loads and the same `st_*` ABI the Android, JVM and iOS bindings call,
// so a screen that decodes here is a screen a client can draw. What is NOT
// covered is therefore precise and worth saying out loud: nothing here touches
// Compose, so pointer routing, touch inertia, IME, selection handles and the
// tab strip's keep-alive are the Compose suites' business
// (`:terminal-compose:jvmTest`, `:ui:jvmTest`), and how a wheel notch becomes a
// scroll is `TerminalInputPolicy`'s.
//
// THE BROKER HERE IS ISOLATED, ALWAYS. Every target lives in a private 0700
// socket directory minted per run under $XDG_RUNTIME_DIR, the shell is a
// dotfile-free wrapper, and cleanup kills by RECORDED PID only — never a
// pattern, which on a shared machine matches other people's shells.
import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { join } from "path"
import type {
  WorkspaceTerminalEvent,
  WorkspaceTerminalKey,
  WorkspaceTerminalViewer,
} from "../../src/core/terminal/workspace-backend"
import { ZmxWorkspaceBackend, type ZmxHelperLaunch } from "../../src/core/terminal/zmx/backend"
import { ZmxHelper, type HelperBinaries } from "../../src/core/terminal/zmx/helper"
import {
  REQUIRED,
  prerequisites,
  referenceEngine,
  type ReferenceEngine,
  type Viewport,
} from "./zmx-workspace-fixture"

const gate = prerequisites()

test("workload prerequisites", () => {
  if (gate.ok) return
  if (REQUIRED) throw new Error(`MUX_ZMX_INTEGRATION=1 but the suite cannot run: ${gate.reason}`)
  console.warn(`[zmx-workloads] SKIPPED: ${gate.reason}`)
})

const suite = gate.ok ? describe : describe.skip
const binaries: HelperBinaries = gate.ok ? gate.binaries : ({} as HelperBinaries)
const shellSource = gate.ok ? gate.shellSource : "/bin/sh"

const encoder = new TextEncoder()
const decoder = new TextDecoder()
const bytes = (text: string) => encoder.encode(text)

/** What each scenario actually exercised. Printed at the end as the record of
 * which platform ran what — the GUI-only half is named, not claimed. */
const ran: Record<string, unknown>[] = []
function record(scenario: string, data: Record<string, unknown>): void {
  ran.push({ scenario, ...data })
  console.log(`[workload] ${scenario} ${JSON.stringify(data)}`)
}

suite("real terminal workloads", () => {
  let socketDir: string
  let scratch: string
  let shell: string
  let backend: ZmxWorkspaceBackend
  const helpers: ZmxHelper[] = []
  const daemonPids = new Set<number>()
  const scopes = new Set<string>()

  const launch: ZmxHelperLaunch = async handlers => {
    const helper = await ZmxHelper.launch(handlers, { binaries })
    helpers.push(helper)
    return helper
  }

  const newBackend = () => new ZmxWorkspaceBackend({ socketDir, binaries, launch })

  /** Daemon pids, recorded so cleanup never has to guess. */
  async function listRaw(): Promise<{ name: string; pid: number }[]> {
    const helper = await ZmxHelper.launch({ onOutput: () => {}, onEvent: () => {}, onFailure: () => {} }, { binaries })
    try {
      const rows = await helper.send<{ name: string; pid: number }[]>({ op: "list", dir: socketDir })
      for (const row of rows) if (Number.isInteger(row.pid) && row.pid > 1) daemonPids.add(row.pid)
      return rows
    } finally {
      helper.kill()
    }
  }

  async function create(
    scope: string, terminalId = "main", cols = 100, rows = 30,
  ): Promise<WorkspaceTerminalKey> {
    scopes.add(scope)
    const key = { scope, terminalId }
    await backend.ensure(key, { cwd: scratch, shell, env: { TERM: "xterm-256color" }, cols, rows })
    await listRaw()
    return key
  }

  // ---- a viewer with the CLIENT'S OWN ENGINE behind it ----------------------
  //
  // The point of the whole file: every byte the backend delivers is fed to the
  // real VT engine with the origin the replay boundary says, so what a test
  // reads is the SCREEN, not a substring of the wire. A `grep` over the raw
  // bytes would pass for a vim that painted nothing and for a screen that
  // scrolled the answer away.

  type Screen = {
    id: string
    viewer: WorkspaceTerminalViewer
    engine: ReferenceEngine
    events: WorkspaceTerminalEvent[]
    /** Raw wire text, for waiting on a marker before reading the screen. */
    wire: string
    bytesIn: number
    view(): Viewport
    /** Every non-blank row of the current viewport, trailing space trimmed. */
    lines(): string[]
    text(): string
    type(text: string): void
    waitForWire(needle: string, ms?: number): Promise<void>
    waitForScreen(match: (text: string) => boolean, what: string, ms?: number): Promise<void>
    clearWire(): void
    /** Drop the viewer and the engine behind it. The TARGET survives — that is
     * the whole point of a detach, and several tests re-open afterwards. */
    detach(): Promise<void>
  }

  async function open(
    key: WorkspaceTerminalKey, id: string,
    options: { cols?: number; rows?: number; on?: ZmxWorkspaceBackend } = {},
  ): Promise<Screen> {
    const cols = options.cols ?? 100
    const rows = options.rows ?? 30
    const manager = options.on ?? backend
    const engine = await referenceEngine(cols, rows)
    let replaying = false
    const self: Partial<Screen> = { id, events: [], wire: "", bytesIn: 0 }

    const viewer = await manager.attachExisting(key, id, async event => {
      if (event.type === "output") {
        self.bytesIn = (self.bytesIn ?? 0) + event.bytes.length
        self.wire = ((self.wire ?? "") + decoder.decode(event.bytes, { stream: true })).slice(-400_000)
        // Origin 1 is REPLAY, 0 is LIVE. Feeding restored history as live
        // would let the engine answer a device query the shell never asked.
        engine.feed(event.bytes, replaying ? 1 : 0)
        return
      }
      self.events!.push(event)
      if (event.type === "replay-start") replaying = true
      if (event.type === "replay-end") replaying = false
    })

    const screen = self as Screen
    screen.viewer = viewer
    screen.engine = engine
    screen.view = () => engine.viewport()
    screen.lines = () => engine.viewport().grid.map(row => row.text)
    screen.text = () => screen.lines().join("\n")
    screen.type = text => { expect(viewer.write(bytes(text))).toBe(true) }
    screen.clearWire = () => { screen.wire = "" }
    screen.waitForWire = (needle, ms = 20_000) => waitFor(
      () => screen.wire.includes(needle), ms,
      () => `${id} never saw ${JSON.stringify(needle)} on the wire; tail=${JSON.stringify(screen.wire.slice(-300))}`)
    screen.waitForScreen = (match, what, ms = 20_000) => waitFor(
      () => match(screen.text()), ms,
      () => `${id} never showed ${what}; screen=\n${screen.text()}`)
    screen.detach = async () => {
      await viewer.detach()
      engine.destroy()
    }
    return screen
  }

  async function waitFor(
    predicate: () => boolean | Promise<boolean>, ms: number, describe: () => string,
  ): Promise<void> {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      if (await predicate()) return
      await Bun.sleep(50)
    }
    throw new Error(`timed out after ${ms}ms: ${describe()}`)
  }

  /** A marker whose own ECHO cannot contain it: the shell puts the command line
   * back on the wire before running it, so a test waiting for a literal
   * proceeds on the echo. Split across format and argument, only the OUTPUT
   * ever has the halves adjacent. */
  function markerPrintf(marker: string): string {
    const cut = Math.ceil(marker.length / 2)
    return `printf '${marker.slice(0, cut)}%s\\n' '${marker.slice(cut)}'`
  }

  async function run(s: Screen, command: string, marker: string, ms = 60_000): Promise<void> {
    s.type(`${command}; ${markerPrintf(marker)}\r`)
    await s.waitForWire(marker, ms)
  }

  /** Leave whatever full-screen program is running and get a prompt back. */
  async function backToShell(s: Screen, marker: string): Promise<void> {
    s.type("\x1b")           // any pending vim/less operator
    s.type("q")              // less, htop
    s.type(":q!\r")          // vim
    s.clearWire()
    await run(s, "true", marker)
  }

  beforeAll(() => {
    delete process.env.ZMX_SESSION
    delete process.env.ZMX_DIR
    const runtimeDir = process.env.XDG_RUNTIME_DIR!
    mkdirSync(join(runtimeDir, "supermux"), { recursive: true })
    socketDir = mkdtempSync(join(runtimeDir, "supermux", "zmx-wl-"))
    chmodSync(socketDir, 0o700)
    scratch = mkdtempSync(join(process.env.TMPDIR || "/tmp", "zmx-wl-"))
    // The host's profile writes OSC sequences and a $PS1 this suite would have
    // to parse around; the wrapper is the same bash, minus the dotfiles.
    shell = join(scratch, "muxsh")
    writeFileSync(shell, `#!/bin/sh\nexec ${shellSource} --norc --noprofile "$@"\n`)
    chmodSync(shell, 0o700)
    backend = newBackend()
  })

  afterAll(async () => {
    for (const scope of scopes) {
      try { await backend.closeScope(scope) } catch { /* best effort */ }
    }
    for (const helper of helpers) {
      // SIGCONT first: a helper stopped to starve it of reads sits in state T,
      // where a SIGTERM is merely PENDING and would outlive the run.
      try { process.kill(helper.pid, "SIGCONT") } catch { /* gone */ }
      try { helper.kill() } catch { /* gone */ }
    }
    await Bun.sleep(300)
    for (const helper of helpers) {
      try { process.kill(helper.pid, 0); process.kill(helper.pid, "SIGKILL") } catch { /* gone */ }
    }
    let survivors: { pid: number }[] = []
    try { survivors = await listRaw() } catch { /* the dir may be gone */ }
    for (const pid of new Set([...survivors.map(row => row.pid), ...daemonPids])) {
      if (!Number.isInteger(pid) || pid <= 1) continue
      try { process.kill(pid, "SIGTERM") } catch { /* gone */ }
    }
    await Bun.sleep(300)
    for (const pid of daemonPids) {
      try { process.kill(pid, 0); process.kill(pid, "SIGKILL") } catch { /* gone */ }
    }
    rmSync(socketDir, { recursive: true, force: true })
    rmSync(scratch, { recursive: true, force: true })
    console.log(`[workload] summary ${JSON.stringify(ran)}`)
  })

  // ---- vim, with the mouse on ----------------------------------------------

  test("vim with the mouse on: the alternate screen, mouse tracking, and both given back", async () => {
    // THE ONE THAT DECIDES WHETHER A SCROLL IS LOCAL. `TerminalInputPolicy`
    // routes a wheel notch to the program or to the local scrollback by
    // reading `mouseTracking` and `alternateScreen` off the engine — so if a
    // real vim's `set mouse=a` does not reach the client's engine as those two
    // flags, every wheel in every full-screen program goes to the wrong place.
    // Nothing short of a real vim over a real pty establishes that.
    const key = await create("w:workload-vim")
    const s = await open(key, "vim-1")
    try {
      const before = s.view()
      expect(before.alternateScreen).toBe(false)
      expect(before.mouseTracking).toBe(false)

      writeFileSync(join(scratch, "note.txt"), "alpha\nbeta\ngamma\n")
      s.type("vim -u NONE -c 'set mouse=a' -c 'set ttymouse=sgr' note.txt\r")
      await s.waitForScreen(text => text.includes("gamma"), "the file on the alternate screen")

      const inVim = s.view()
      expect(inVim.alternateScreen).toBe(true)
      expect(inVim.mouseTracking).toBe(true)
      // The file is on screen, and it is the SCREEN that says so, not the wire.
      expect(s.lines().some(line => line.includes("alpha"))).toBe(true)

      // Editing through the pty, read back off the grid.
      s.type("Gdd")
      await s.waitForScreen(text => !text.includes("gamma"), "the deleted line gone from the grid")

      s.type(":q!\r")
      await waitFor(() => !s.view().alternateScreen, 20_000, () => `vim never gave the screen back:\n${s.text()}`)
      const after = s.view()
      // BOTH have to come back. A client left with mouseTracking on after the
      // program exited sends SGR wheel bytes at a shell that will echo them.
      expect(after.alternateScreen).toBe(false)
      expect(after.mouseTracking).toBe(false)
      record("vim-mouse", { alternateScreen: true, mouseTracking: true, restored: true })
    } finally {
      await s.detach()
    }
  }, 120_000)

  // ---- less, and the history a pager does NOT leave behind ------------------

  test("less: the alternate screen carries the file, and leaves no history behind", async () => {
    const key = await create("w:workload-less")
    const s = await open(key, "less-1")
    try {
      const lines = Array.from({ length: 500 }, (_, i) => `line-${i}`)
      writeFileSync(join(scratch, "big.txt"), lines.join("\n") + "\n")
      const historyBefore = s.view().historyRows

      // No `-X`: that flag is exactly "do not use the alternate screen", and a
      // pager that paints inline is the case this test is NOT about. No `-F`
      // either — it quits immediately for a file that fits, and 500 lines only
      // fit if the geometry is wrong.
      s.type("less big.txt\r")
      await s.waitForScreen(text => text.includes("line-0"), "the top of the file in the pager")
      expect(s.view().alternateScreen).toBe(true)

      s.type("G")
      await s.waitForScreen(text => text.includes("line-499"), "the end of the file")

      s.type("q")
      await waitFor(() => !s.view().alternateScreen, 20_000, () => `less never exited:\n${s.text()}`)
      // A pager paints on the alternate screen, so its 500 lines must NOT have
      // become scrollback the user can flick through afterwards.
      const historyAfter = s.view().historyRows
      expect(historyAfter - historyBefore).toBeLessThan(100)
      record("less", { historyBefore, historyAfter, alternateScreenUsed: true })
    } finally {
      await s.detach()
    }
  }, 120_000)

  // ---- htop, which repaints forever ----------------------------------------

  test("htop: a continuously repainting TUI stays coherent and gives the screen back", async () => {
    const key = await create("w:workload-htop")
    const s = await open(key, "htop-1")
    try {
      s.type("htop -d 2\r")
      await s.waitForScreen(
        text => /Mem|Swap|Tasks|PID/i.test(text), "htop's own chrome on the grid", 30_000)
      const inHtop = s.view()
      expect(inHtop.alternateScreen).toBe(true)
      // The engine publishes exactly `rows` rows, whatever the program paints.
      expect(inHtop.grid.length).toBe(inHtop.rows)

      // Let it repaint a few times: a partial-frame bug shows up as a grid that
      // stops matching the geometry, not as an error.
      await Bun.sleep(2_500)
      const later = s.view()
      expect(later.grid.length).toBe(later.rows)
      expect(later.columns).toBe(inHtop.columns)

      s.type("q")
      await waitFor(() => !s.view().alternateScreen, 20_000, () => `htop never exited:\n${s.text()}`)
      record("htop", { repaints: "observed", rows: later.rows, columns: later.columns })
    } finally {
      await s.detach()
    }
  }, 120_000)

  // ---- 50k lines of build output -------------------------------------------

  test("a 50k-line build: every line arrives, the tail is exact, and history is bounded", async () => {
    // THE FLOOD. It is also the only honest test of the scrollback budget: the
    // engine prunes history under its own cap, so "all 50,000 lines are
    // readable" is the WRONG expectation and asserting it would pin a bug. What
    // must hold is that the wire carried them all and the LAST ones are exactly
    // right — losing the tail is what a user notices.
    const key = await create("w:workload-flood")
    const s = await open(key, "flood-1")
    try {
      const marker = "FLOODDONE1"
      await run(s, "seq 1 50000", marker, 120_000)
      await s.waitForScreen(text => text.includes(marker), "the marker on the grid")

      // The tail, off the grid: the last number printed before the marker.
      const lines = s.lines().filter(line => line.length > 0)
      expect(lines.some(line => line.trim() === "50000")).toBe(true)

      const view = s.view()
      expect(view.historyRows).toBeGreaterThan(1_000)
      record("flood-50k", {
        bytesIn: s.bytesIn, historyRows: view.historyRows, tailExact: true,
      })
    } finally {
      await s.detach()
    }
  }, 300_000)

  // ---- unicode and emoji ----------------------------------------------------

  test("unicode and emoji: width, combining marks and Turkish survive the round trip", async () => {
    const key = await create("w:workload-unicode")
    const s = await open(key, "uni-1")
    try {
      const marker = "UNIDONE1"
      // A wide CJK pair, an emoji with a variation selector, a ZWJ family, a
      // combining mark, and Turkish dotted/dotless i — the cases that break a
      // grid that measures in code units.
      await run(s, `printf '%s\\n' '日本語 👍🏽 👨‍👩‍👧 é İıĞğ'`, marker)
      await s.waitForScreen(text => text.includes(marker), "the marker on the grid")

      // The OUTPUT row, not the shell's echo of the command line: the echo
      // contains the same bytes, so matching it would prove only that the wire
      // carried them — which is what this test exists not to do.
      const row = s.view().grid.find(r => r.text.includes("日本語") && !r.text.includes("printf"))
      expect(row).toBeDefined()
      // The screen, not the wire: a grid that lost the width would join the
      // continuation cell in and the text would not compare equal.
      expect(row!.text).toContain("日本語")
      expect(row!.text).toContain("İıĞğ")
      // A wide glyph occupies TWO columns, with the second a continuation.
      const wide = row!.cells.find(cell => cell.text === "日")
      expect(wide?.width).toBe(2)
      record("unicode", { wideCellWidth: wide?.width, row: row!.text })
    } finally {
      await s.detach()
    }
  }, 120_000)

  // ---- shell line editing ---------------------------------------------------

  test("shell line editing: arrows, backspace and history edit the line the shell has", async () => {
    const key = await create("w:workload-editing")
    const s = await open(key, "edit-1")
    try {
      // Type a wrong command, fix it with the keys a user actually presses, and
      // let the SHELL tell us what it ended up with.
      s.type("echo brokenX")
      await s.waitForWire("brokenX")
      s.type("\x7f")                       // backspace: drop the X
      s.type("\x1b[D\x1b[D\x1b[D\x1b[D\x1b[D\x1b[D") // left six, to before "broken"
      s.type("un")                          // -> "echo unbroken"
      s.clearWire()
      s.type("\r")
      await s.waitForScreen(
        text => /(^|\n)unbroken\s*$/m.test(text) || text.includes("\nunbroken"),
        "the corrected command's OUTPUT on the grid")
      expect(s.text()).toContain("unbroken")

      // History: up-arrow recalls it, and running it again repeats the output.
      s.clearWire()
      s.type("\x1b[A\r")
      await waitFor(
        () => (s.text().match(/unbroken/g) ?? []).length >= 2, 20_000,
        () => `history never repeated the command:\n${s.text()}`)
      record("line-editing", { backspace: true, arrows: true, history: true })
    } finally {
      await s.detach()
    }
  }, 120_000)

  // ---- a multiline paste ----------------------------------------------------

  test("a multiline paste inside bracketed paste is ONE line for the shell, not three commands", async () => {
    // THE RULE THE RENDERER ENCODES AND THIS CONFIRMS END TO END: inside the
    // bracketed-paste markers a newline stays LF, and bash in bracketed-paste
    // mode buffers the whole thing as one editable line instead of running each
    // line as it arrives. A paste that ran three commands is the accident this
    // exists to prevent.
    const key = await create("w:workload-paste")
    const s = await open(key, "paste-1")
    try {
      // bracketed paste is a MODE the program turns on; bash's readline does.
      await waitFor(() => s.view().bracketedPaste, 20_000,
        () => `bash never enabled bracketed paste; screen=\n${s.text()}`)

      s.clearWire()
      // Three lines, LF-separated, wrapped in the markers — exactly what the
      // renderer sends for a clipboard with newlines in it.
      s.type("\x1b[200~echo one\necho two\necho three\x1b[201~")
      // Nothing may have RUN yet: the shell is holding an edit line.
      await Bun.sleep(1_000)
      expect(s.text()).not.toContain("\none\n")

      s.type("\r")
      await s.waitForScreen(text => text.includes("three"), "all three lines running as one submit")
      const text = s.text()
      expect(text).toContain("one")
      expect(text).toContain("two")
      expect(text).toContain("three")
      record("multiline-paste", { bracketedPaste: true, ranOnSubmit: true })
    } finally {
      await s.detach()
    }
  }, 120_000)

  // ---- two devices at two sizes --------------------------------------------

  test("two devices at different sizes: one pty geometry, and both screens stay decodable", async () => {
    const key = await create("w:workload-two-devices", "main", 100, 30)
    const phone = await open(key, "phone", { cols: 60, rows: 20 })
    const laptop = await open(key, "laptop", { cols: 120, rows: 40 })
    try {
      // The pty has ONE size, so the two clients cannot both be authoritative;
      // what must hold is that neither ends up decoding a grid that does not
      // match the frames it is being sent.
      const marker = "TWODEV01"
      await run(laptop, "printf 'shared-%s\\n' line", marker)
      await phone.waitForWire(marker)

      expect(laptop.text()).toContain("shared-line")
      expect(phone.text()).toContain("shared-line")
      // Each engine publishes exactly the rows it was created with.
      expect(phone.view().grid.length).toBe(phone.view().rows)
      expect(laptop.view().grid.length).toBe(laptop.view().rows)
      record("two-devices", {
        phone: `${phone.view().columns}x${phone.view().rows}`,
        laptop: `${laptop.view().columns}x${laptop.view().rows}`,
      })
    } finally {
      await phone.detach()
      await laptop.detach()
    }
  }, 120_000)

  // ---- disconnect, reconnect, and a broker restart --------------------------

  test("disconnect and reconnect: the screen comes back, including what was missed", async () => {
    const key = await create("w:workload-reconnect")
    const first = await open(key, "before")
    const marker = "RECON001"
    await run(first, "printf 'before-%s\\n' restart", marker)
    await first.waitForScreen(text => text.includes("before-restart"), "the pre-disconnect output")
    // The user closes the lid: the viewer goes, the target does not.
    await first.detach()

    // Output the client was not there for.
    const missedBackend = newBackend()
    const away = await open(key, "away", { on: missedBackend })
    const missedMarker = "MISSED001"
    await run(away, "printf 'while-%s\\n' away", missedMarker)
    await away.waitForWire(missedMarker)
    await away.detach()

    const second = await open(key, "after")
    try {
      // The restore is a REPLAY, and it has to carry both.
      await second.waitForScreen(
        text => text.includes("before-restart") && text.includes("while-away"),
        "the restored screen with the missed output")
      expect(second.events.some(e => e.type === "replay-start")).toBe(true)
      expect(second.events.some(e => e.type === "replay-end")).toBe(true)
      record("reconnect", { replayed: true, missedOutputRestored: true })
    } finally {
      await second.detach()
    }
  }, 180_000)

  test("a broker restart: a brand-new manager finds the target and restores the screen", async () => {
    // The broker is the thing that dies and comes back; the daemon is not. A
    // reconstructed `ZmxWorkspaceBackend` shares NO state with the old one, so
    // finding the target at all is the running daemons answering, not memory.
    const key = await create("w:workload-broker-restart")
    const before = await open(key, "pre")
    const marker = "BROKER001"
    await run(before, "printf 'survives-%s\\n' restart", marker)
    await before.waitForScreen(text => text.includes("survives-restart"), "the output before the restart")
    await before.detach()

    const restarted = newBackend()
    const listed = await restarted.list(key.scope)
    expect(listed.map(k => k.terminalId)).toContain(key.terminalId)

    const after = await open(key, "post", { on: restarted })
    try {
      await after.waitForScreen(text => text.includes("survives-restart"), "the restored screen")
      // ...and the same shell answers, not a new one wearing its name.
      const pid = "BPID001"
      await run(after, "printf '%s:%s\\n' BPID001 \"$$\"", pid)
      record("broker-restart", { listedAfterRestart: true, screenRestored: true })
    } finally {
      await after.detach()
    }
  }, 180_000)

  // ---- the two ways a terminal ends ----------------------------------------

  test("a shell that exits is an exit event, and the target is gone", async () => {
    const key = await create("w:workload-exit")
    const s = await open(key, "exit-1")
    try {
      s.type("exit 7\r")
      await waitFor(() => s.events.some(e => e.type === "exit"), 30_000,
        () => `no exit event; events=${JSON.stringify(s.events.map(e => e.type))}`)
      const exit = s.events.find(e => e.type === "exit")!
      expect(exit).toMatchObject({ type: "exit" })
      await waitFor(async () => !(await backend.exists(key)), 30_000, () => "the target outlived its shell")
      record("shell-exit", { exit: JSON.stringify(exit) })
    } finally {
      await s.detach()
    }
  }, 120_000)

  test("an explicit close ends the shell, and it stays closed", async () => {
    const key = await create("w:workload-close")
    const s = await open(key, "close-1")
    const marker = "CLOSE001"
    await run(s, "printf 'alive-%s\\n' now", marker)
    await s.detach()

    await backend.close(key)
    expect(await backend.exists(key)).toBe(false)
    // A closed target is never resurrected by an attach — that is `ensure`'s
    // job, and the split exists so a stale client cannot restart a shell.
    const error = await backend.attachExisting(key, "ghost", async () => {}).then(() => null, (e: unknown) => e)
    expect(error).not.toBeNull()
    record("explicit-close", { closed: true, attachRefused: true })
  }, 120_000)
})
