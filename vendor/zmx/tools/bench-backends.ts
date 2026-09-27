// Before/after for the WORKSPACE TERMINAL BACKEND only.
//
// "Before" is the tmux path the workspace terminals actually used: pty-helper
// exec'ing `tmux -L <sock> -f <conf> new-session -A -s <name> -x -y -c`, with
// the server config the broker used to generate (history-limit 50000, mouse on,
// window-size latest, tmux-256color + Tc). That is reconstructed here verbatim
// from git history (src/core/terminal/tmux-term.ts @ d75a472f^, MUXTERM_CONF @
// 9220fe09) because the module itself has been deleted.
//
// "After" is the real ZmxWorkspaceBackend + the pinned helper + the patched
// daemon, exactly as tests/integration/zmx-workspace.test.ts drives them.
//
// The two arms are NOT the same contract. tmux redraws on attach; zmx replays a
// serialized snapshot inside an explicit reset/replay-start/replay-end
// boundary. Delivered byte counts are therefore a fact about each path, not a
// like-for-like efficiency ratio. What IS comparable: how long the shell blocks
// while writing a fixed payload, how long a reconnect takes to settle, what the
// backend's resident set is afterwards, and which viewer's geometry reaches the
// pty.
//
// SAFETY: private tmux socket under a per-run mkdtemp, private zmx socket dir
// under $XDG_RUNTIME_DIR/supermux, mode 0700. Every kill is by a pid this
// script recorded itself. No pattern matching, no pkill, no killall.
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, statSync } from "fs"
import { join } from "path"
import { ZmxWorkspaceBackend, type ZmxHelperLaunch } from "../../../src/core/terminal/zmx/backend"
import { ZmxHelper } from "../../../src/core/terminal/zmx/helper"
import type { WorkspaceTerminalEvent, WorkspaceTerminalViewer } from "../../../src/core/terminal/workspace-backend"
import { prerequisites } from "../../../tests/integration/zmx-workspace-fixture"

const REPO = join(import.meta.dirname, "..", "..", "..")
const PTY_HELPER = join(REPO, "src", "core", "terminal", "pty-helper")

// Exactly the config the broker wrote for the dedicated workspace tmux server.
const MUXTERM_CONF = `# supermux web-terminal tmux server — managed, do not edit
set -g history-limit 50000
set -g mouse on
set -g status off
set -g destroy-unattached off
set -g window-size latest
set -g default-terminal "tmux-256color"
set -sa terminal-overrides ",*:Tc"
`

const OUT_DIR = process.env.BENCH_OUT ?? join(REPO, "build", "bench")
mkdirSync(OUT_DIR, { recursive: true })

const COLS = 120, ROWS = 40
const PAYLOAD_MIB = Number(process.env.BENCH_MIB ?? 12)
const enc = new TextEncoder()
const dec = new TextDecoder()
const out: Record<string, unknown> = {}

function note(k: string, v: unknown) { out[k] = v; console.log(`[bench] ${k} = ${JSON.stringify(v)}`) }
const now = () => Number(Bun.nanoseconds()) / 1e6

async function waitFor(p: () => boolean | Promise<boolean>, ms: number, what: string) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) { if (await p()) return; await Bun.sleep(25) }
  throw new Error(`timed out after ${ms}ms: ${what}`)
}

function rssKb(pid: number): number | null {
  try {
    const status = readFileSync(`/proc/${pid}/status`, "utf8")
    const m = /VmRSS:\s+(\d+) kB/.exec(status)
    return m ? Number(m[1]) : null
  } catch { return null }
}

// ---------------------------------------------------------------- the payload
// One fixture, generated once, byte-identical for both arms. 80-byte lines.
const scratch = mkdtempSync(join(process.env.TMPDIR || "/tmp", "termbench-"))
function makePayload(kind: "plain" | "ansi"): string {
  const path = join(scratch, `payload-${kind}.txt`)
  const target = PAYLOAD_MIB * 1024 * 1024
  const parts: string[] = []
  let size = 0, i = 0
  while (size < target) {
    const n = String(i++).padStart(7, "0")
    const line = kind === "plain"
      ? `LINE-${n} ` + "X".repeat(80 - 14) + "\n"
      : `\x1b[3${i % 8}m\x1b[1mLINE-${n}\x1b[0m ` + "X".repeat(40) + "\n"
    parts.push(line); size += line.length
    if (parts.length > 20000) { Bun.write(path, parts.join(""), {}); break }
  }
  writeFileSync(path, parts.join(""))
  // Grow to the target by repeating the block, so both fixtures are ~equal size.
  const block = readFileSync(path)
  const reps = Math.max(1, Math.ceil(target / block.length))
  writeFileSync(path, Buffer.concat(Array(reps).fill(block)).subarray(0, target))
  return path
}
const PAYLOAD_PLAIN = makePayload("plain")
const PAYLOAD_ANSI = makePayload("ansi")
note("fixture", {
  plain: { path: PAYLOAD_PLAIN, bytes: statSync(PAYLOAD_PLAIN).size },
  ansi: { path: PAYLOAD_ANSI, bytes: statSync(PAYLOAD_ANSI).size },
})
note("host", {
  loadavg: readFileSync("/proc/loadavg", "utf8").trim(),
  tmux: (await Bun.$`tmux -V`.text()).trim(),
  bun: Bun.version,
})

// =========================================================== ARM 1: tmux (old)
type TmuxViewer = {
  proc: Bun.Subprocess
  bytes: number
  lastByteAt: number
  text: string
  write(s: string): void
  resize(cols: number, rows: number): void
  kill(sig?: number): void
}

const tmuxSock = join(scratch, "sock")   // -L takes a NAME, so use a socket dir instead
const tmuxSocketDir = mkdtempSync(join(process.env.TMPDIR || "/tmp", "termbench-tmux-"))
chmodSync(tmuxSocketDir, 0o700)
const confPath = join(scratch, "muxterm.conf")
writeFileSync(confPath, MUXTERM_CONF)
const SOCKETS = new Set<string>()
function socketFor(label: string): string { const n = `muxbench${process.pid}${label}`; SOCKETS.add(n); return n }

function tmuxArgv(name: string, socket: string): string[] {
  return [
    PTY_HELPER, String(COLS), String(ROWS), scratch,
    "tmux", "-L", socket, "-f", confPath,
    "new-session", "-A", "-s", name,
    "-x", String(COLS), "-y", String(ROWS), "-c", scratch,
    "bash", "--norc", "--noprofile",
  ]
}

function spawnTmuxViewer(name: string, socket: string, cols = COLS, rows = ROWS): TmuxViewer {
  const argv = tmuxArgv(name, socket)
  argv[1] = String(cols); argv[2] = String(rows)
  const idx = argv.indexOf("-x"); argv[idx + 1] = String(cols); argv[idx + 3] = String(rows)
  const env = { ...process.env, TMUX: undefined as unknown as string, TERM: "xterm-256color" }
  delete (env as Record<string, unknown>).TMUX
  const proc = Bun.spawn(argv, { stdin: "pipe", stdout: "pipe", stderr: "pipe", env, cwd: scratch })
  const v: TmuxViewer = {
    proc, bytes: 0, lastByteAt: now(), text: "",
    write(s) { (proc.stdin as { write(d: Uint8Array): void }).write(enc.encode(s)) },
    resize(c, r) { (proc.stdin as { write(d: Uint8Array): void }).write(enc.encode(`\0R${c}:${r}\n`)) },
    kill(sig = 15) { try { proc.kill(sig) } catch { /* gone */ } },
  }
  ;(async () => {
    for await (const chunk of proc.stdout as ReadableStream<Uint8Array>) {
      v.bytes += chunk.length; v.lastByteAt = now()
      v.text = (v.text + dec.decode(chunk, { stream: true })).slice(-200_000)
    }
  })()
  return v
}

async function quiesce(v: { lastByteAt: number }, idleMs = 700, max = 180_000): Promise<number> {
  const deadline = Date.now() + max
  while (Date.now() < deadline) {
    if (now() - v.lastByteAt > idleMs) return v.lastByteAt
    await Bun.sleep(50)
  }
  throw new Error("stream never went idle")
}

async function runTmuxArm(fixture: string, label: string) {
  // One tmux SERVER per arm: `kill-server` at the end of the previous arm races a
  // client connecting to the same socket name, and the new server exits under it.
  const socket = socketFor(label)
  const name = `muxterm_bench_${label}`
  const a = spawnTmuxViewer(name, socket)
  const t0 = now()
  await waitFor(() => a.bytes > 0, 20_000, "tmux viewer A produced no bytes")
  const firstByte = a.lastByteAt - t0
  // Settle the shell and turn echo off so the fixture is the only thing measured.
  a.write("stty -echo; PS1=''; unset PROMPT_COMMAND\r")
  await Bun.sleep(1200)
  await quiesce(a, 500)
  await waitFor(async () => (await Bun.$`tmux -L ${socket} has-session -t ${name}`.nothrow().quiet()).exitCode === 0,
    20_000, "the tmux server never came up")
  const daemonPid = Number((await Bun.$`tmux -L ${socket} display-message -p '#{pid}'`.text()).trim())
  const beforeRss = rssKb(daemonPid)
  const bytesBefore = a.bytes

  // --- throughput: the shell's own blocking time, and the delivery that follows
  const timing = join(scratch, `timing-${label}`)
  const done = join(scratch, `done-${label}`)
  rmSync(done, { force: true })
  const sendAt = now()
  a.write(`TIMEFORMAT=%R; { time cat ${fixture}; } 2> ${timing}; : > ${done}\r`)
  await waitFor(() => existsSync(done), 300_000, "the shell never finished the cat")
  const shellDoneAt = now()
  const lastAt = await quiesce(a, 700)
  const shellSeconds = Number(readFileSync(timing, "utf8").trim())
  const delivered = a.bytes - bytesBefore
  const afterRss = rssKb(daemonPid)

  // --- two viewers: whose geometry reaches the pty.
  // A is the foreground viewer (it has just been typed into). B attaches in the
  // BACKGROUND at 60x20 and TYPES. The question both backends are asked is the
  // same: does a background viewer's keystroke take the focused device's
  // geometry with it? The answer is read out of a FILE, so that asking does not
  // itself promote a client.
  const sizeFile = join(scratch, `size-${label}`)
  rmSync(sizeFile, { force: true })
  const b = spawnTmuxViewer(name, socket, 60, 20)
  await waitFor(() => b.bytes > 0, 20_000, "tmux viewer B produced no bytes")
  await Bun.sleep(1500)
  b.write(`stty size > ${sizeFile}\r`)
  await waitFor(() => existsSync(sizeFile) && readFileSync(sizeFile, "utf8").trim().length > 0,
    20_000, "the shell never wrote its size")
  const size = readFileSync(sizeFile, "utf8").trim().split(/\s+/).reverse().join("x")

  // --- reconnect: the viewer is lost (SIGKILL), a new one attaches
  b.kill(9)
  await Bun.sleep(300)
  a.kill(9)
  await Bun.sleep(700)
  const c0 = now()
  const c = spawnTmuxViewer(name, socket)
  await waitFor(() => c.bytes > 0, 30_000, "reconnecting tmux viewer produced no bytes")
  const reconnectFirstByte = c.lastByteAt - c0
  const restoreLast = await quiesce(c, 700)
  const reconnect = { firstByteMs: reconnectFirstByte, settledMs: restoreLast - c0, restoreBytes: c.bytes }

  c.kill(9)
  await Bun.sleep(200)
  try { await Bun.$`tmux -L ${socket} kill-server`.nothrow().quiet() } catch { /* already gone */ }

  return {
    fixture: fixture.endsWith("ansi.txt") ? "ansi" : "plain",
    attachFirstByteMs: firstByte,
    shellBlockedSeconds: shellSeconds,
    shellThroughputMiBs: (PAYLOAD_MIB) / shellSeconds,
    deliverySettledMs: lastAt - sendAt,
    shellFinishedMs: shellDoneAt - sendAt,
    deliveredBytes: delivered,
    deliveredRatio: delivered / statSync(fixture).size,
    tmuxServerPid: daemonPid,
    tmuxServerRssKb: { before: beforeRss, after: afterRss },
    ptySizeWithBackgroundViewer: size,
    reconnect,
  }
}

async function ptySizeTmux(v: TmuxViewer): Promise<string> {
  v.text = ""
  const marker = `SZ${Math.random().toString(36).slice(2, 8)}`
  const cut = Math.ceil(marker.length / 2)
  v.write(`printf '${marker.slice(0, cut)}%s:%s\\n' '${marker.slice(cut)}' "$(stty size | tr ' ' x)"\r`)
  const re = new RegExp(`${marker}:(\\d+x\\d+)`)
  await waitFor(() => re.test(v.text), 20_000, `no stty answer; tail=${JSON.stringify(v.text.slice(-200))}`)
  return re.exec(v.text)![1]!
}

// ============================================================ ARM 2: zmx (new)
const gate = prerequisites()
if (!gate.ok) { console.error(`[bench] zmx prerequisites unmet: ${gate.reason}`); process.exit(2) }

const runtimeDir = process.env.XDG_RUNTIME_DIR!
mkdirSync(join(runtimeDir, "supermux"), { recursive: true })
const zmxSocketDir = mkdtempSync(join(runtimeDir, "supermux", "bench-"))
chmodSync(zmxSocketDir, 0o700)
delete process.env.ZMX_SESSION
delete process.env.ZMX_DIR
const shellWrapper = join(scratch, "muxsh")
writeFileSync(shellWrapper, `#!/bin/sh\nexec ${gate.shellSource} --norc --noprofile "$@"\n`)
chmodSync(shellWrapper, 0o700)

const helpers: ZmxHelper[] = []
const launch: ZmxHelperLaunch = async handlers => {
  const h = await ZmxHelper.launch(handlers, { binaries: gate.binaries })
  helpers.push(h); return h
}
const backend = new ZmxWorkspaceBackend({ socketDir: zmxSocketDir, binaries: gate.binaries, launch })

type ZRec = {
  viewer: WorkspaceTerminalViewer
  bytes: number
  replayBytes: number
  lastByteAt: number
  text: string
  trace: string[]
  replayEndAt: number | null
  attachedAt: number
}

async function zAttach(key: { scope: string; terminalId: string }, id: string): Promise<ZRec> {
  let inReplay = false
  const rec: ZRec = {
    viewer: null as unknown as WorkspaceTerminalViewer,
    bytes: 0, replayBytes: 0, lastByteAt: now(), text: "", trace: [], replayEndAt: null, attachedAt: now(),
  }
  rec.viewer = await backend.attachExisting(key, id, async event => {
    if (event.type === "output") {
      rec.bytes += event.bytes.length
      if (inReplay) rec.replayBytes += event.bytes.length
      rec.lastByteAt = now()
      rec.text = (rec.text + dec.decode(event.bytes, { stream: true })).slice(-200_000)
      return
    }
    rec.trace.push(event.type === "owner" ? `owner(${event.enabled})` : `${event.type}`)
    if (event.type === "replay-start") inReplay = true
    if (event.type === "replay-end") { inReplay = false; rec.replayEndAt = now() }
  })
  return rec
}

async function zPtySize(rec: ZRec): Promise<string> {
  rec.text = ""
  const marker = `SZ${Math.random().toString(36).slice(2, 8)}`
  const cut = Math.ceil(marker.length / 2)
  rec.viewer.write(enc.encode(
    `printf '${marker.slice(0, cut)}%s:%s\\n' '${marker.slice(cut)}' "$(stty size | tr ' ' x)"\r`))
  const re = new RegExp(`${marker}:(\\d+x\\d+)`)
  await waitFor(() => re.test(rec.text), 20_000, `no stty answer; tail=${JSON.stringify(rec.text.slice(-200))}`)
  return re.exec(rec.text)![1]!
}

async function daemonPidOf(scope: string): Promise<number | null> {
  const h = await ZmxHelper.launch({ onOutput: () => {}, onEvent: () => {}, onFailure: () => {} },
    { binaries: gate.binaries })
  try {
    const rows = await h.send<{ socket: string; name: string; pid: number }[]>({ op: "list", dir: zmxSocketDir })
    const row = rows[0]
    if (!row) return null
    // `list` reports the SESSION pid (the shell); the daemon is its parent.
    const status = readFileSync(`/proc/${row.pid}/status`, "utf8")
    const ppid = /PPid:\s+(\d+)/.exec(status)
    return ppid ? Number(ppid[1]) : null
  } finally { h.kill() }
}

const zmxScopes = new Set<string>()
async function runZmxArm(fixture: string, label: string) {
  const key = { scope: `w:bench-${label}`, terminalId: "main" }
  zmxScopes.add(key.scope)
  await backend.ensure(key, { cwd: scratch, shell: shellWrapper, env: {}, cols: COLS, rows: ROWS })
  const a0 = now()
  const a = await zAttach(key, "A")
  await waitFor(() => a.replayEndAt !== null, 20_000, "no replay-end on first attach")
  const firstAttachMs = a.replayEndAt! - a0
  await a.viewer.focus(true, COLS, ROWS)
  a.viewer.write(enc.encode("stty -echo; PS1=''; unset PROMPT_COMMAND\r"))
  await Bun.sleep(1200)
  await quiesce(a, 500)
  const dPid = await daemonPidOf(key.scope)
  const beforeRss = dPid ? rssKb(dPid) : null
  const bytesBefore = a.bytes

  const timing = join(scratch, `ztiming-${label}`)
  const done = join(scratch, `zdone-${label}`)
  rmSync(done, { force: true })
  const sendAt = now()
  a.viewer.write(enc.encode(`TIMEFORMAT=%R; { time cat ${fixture}; } 2> ${timing}; : > ${done}\r`))
  await waitFor(() => existsSync(done), 300_000, "the shell never finished the cat")
  const shellDoneAt = now()
  const lastAt = await quiesce(a, 700)
  const shellSeconds = Number(readFileSync(timing, "utf8").trim())
  const delivered = a.bytes - bytesBefore
  const afterRss = dPid ? rssKb(dPid) : null

  // two viewers: B attaches in the BACKGROUND at 60x20 and TYPES. Same question,
  // same read-out-of-a-file answer as the tmux arm.
  const sizeFile = join(scratch, `zsize-${label}`)
  rmSync(sizeFile, { force: true })
  const b = await zAttach(key, "B")
  await waitFor(() => b.replayEndAt !== null, 30_000, "no replay-end on B")
  await b.viewer.resize(60, 20)
  b.viewer.write(enc.encode(`stty size > ${sizeFile}\r`))
  await waitFor(() => existsSync(sizeFile) && readFileSync(sizeFile, "utf8").trim().length > 0,
    20_000, "the shell never wrote its size")
  const size = readFileSync(sizeFile, "utf8").trim().split(/\s+/).reverse().join("x")

  // reconnect: both viewers lost, a new one attaches
  await b.viewer.detach()
  await a.viewer.detach()
  await Bun.sleep(500)
  const c0 = now()
  const c = await zAttach(key, "C")
  await waitFor(() => c.replayEndAt !== null, 60_000, "no replay-end on reconnect")
  const reconnectToReplayEnd = c.replayEndAt! - c0
  const settled = await quiesce(c, 700)
  const reconnect = {
    toReplayEndMs: reconnectToReplayEnd,
    settledMs: settled - c0,
    replayBytes: c.replayBytes,
    trace: c.trace.slice(0, 6),
  }
  await c.viewer.detach()

  return {
    fixture: fixture.endsWith("ansi.txt") ? "ansi" : "plain",
    firstAttachToReplayEndMs: firstAttachMs,
    shellBlockedSeconds: shellSeconds,
    shellThroughputMiBs: PAYLOAD_MIB / shellSeconds,
    deliverySettledMs: lastAt - sendAt,
    shellFinishedMs: shellDoneAt - sendAt,
    deliveredBytes: delivered,
    deliveredRatio: delivered / statSync(fixture).size,
    zmxDaemonPid: dPid,
    zmxDaemonRssKb: { before: beforeRss, after: afterRss },
    ptySizeWithBackgroundViewer: size,
    reconnect,
  }
}


// ====================================================== ARM 0: a bare pty (control)
// No backend at all: pty-helper running the same shell, reading the same file.
// This is the floor both backends sit on — the cost of the pty and of `cat`.
async function runPtyControl(fixture: string, label: string) {
  const proc = Bun.spawn(
    [PTY_HELPER, String(COLS), String(ROWS), scratch, "bash", "--norc", "--noprofile"],
    { stdin: "pipe", stdout: "pipe", stderr: "pipe", cwd: scratch })
  const v = { bytes: 0, lastByteAt: now() }
  ;(async () => {
    for await (const chunk of proc.stdout as ReadableStream<Uint8Array>) {
      v.bytes += chunk.length; v.lastByteAt = now()
    }
  })()
  const write = (s: string) => (proc.stdin as { write(d: Uint8Array): void }).write(enc.encode(s))
  write("stty -echo; PS1=''; unset PROMPT_COMMAND\r")
  await Bun.sleep(800)
  await quiesce(v, 400)
  const before = v.bytes
  const timing = join(scratch, `ptiming-${label}`)
  const done = join(scratch, `pdone-${label}`)
  rmSync(done, { force: true })
  const sendAt = now()
  write(`TIMEFORMAT=%R; { time cat ${fixture}; } 2> ${timing}; : > ${done}\r`)
  await waitFor(() => existsSync(done), 300_000, "the control shell never finished the cat")
  const lastAt = await quiesce(v, 700)
  const shellSeconds = Number(readFileSync(timing, "utf8").trim())
  try { proc.kill(9) } catch { /* gone */ }
  return {
    fixture: fixture.endsWith("ansi.txt") ? "ansi" : "plain",
    shellBlockedSeconds: shellSeconds,
    shellThroughputMiBs: PAYLOAD_MIB / shellSeconds,
    deliverySettledMs: lastAt - sendAt,
    deliveredBytes: v.bytes - before,
    deliveredRatio: (v.bytes - before) / statSync(fixture).size,
  }
}

// ============================================================ run and clean up
try {
  note("pty-control/plain", await runPtyControl(PAYLOAD_PLAIN, "plain"))
  note("pty-control/ansi", await runPtyControl(PAYLOAD_ANSI, "ansi"))
  note("tmux/plain", await runTmuxArm(PAYLOAD_PLAIN, "plain"))
  note("tmux/ansi", await runTmuxArm(PAYLOAD_ANSI, "ansi"))
  note("zmx/plain", await runZmxArm(PAYLOAD_PLAIN, "plain"))
  note("zmx/ansi", await runZmxArm(PAYLOAD_ANSI, "ansi"))
  note("host-after", { loadavg: readFileSync("/proc/loadavg", "utf8").trim() })
  writeFileSync(join(OUT_DIR, "backends.json"), JSON.stringify(out, null, 2))
  console.log(`[bench] wrote ${join(OUT_DIR, "backends.json")}`)
} catch (error) {
  console.error("[bench] FAILED", error)
  out.error = String(error)
  writeFileSync(join(OUT_DIR, "backends.json"), JSON.stringify(out, null, 2))
} finally {
  for (const scope of zmxScopes) { try { await backend.closeScope(scope) } catch { /* best effort */ } }
  for (const h of helpers) { try { process.kill(h.pid, "SIGCONT") } catch {} ; try { h.kill() } catch {} }
  await Bun.sleep(400)
  for (const h of helpers) { try { process.kill(h.pid, 0); process.kill(h.pid, "SIGKILL") } catch {} }
  for (const socket of SOCKETS) { try { await Bun.$`tmux -L ${socket} kill-server`.nothrow().quiet() } catch {} }
  rmSync(zmxSocketDir, { recursive: true, force: true })
  rmSync(tmuxSocketDir, { recursive: true, force: true })
  console.log("[bench] cleanup done")
}
