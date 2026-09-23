# zmx workspace backend — what was actually run, and what it showed

Everything else about this backend is tested against a fake helper. This file
records the one run where the patched daemon, the Zig helper, the TypeScript
backend, a real shell and the client's own terminal engine were all the real
thing at once: `tests/integration/zmx-workspace.test.ts`.

Three of the findings below are **defects, not test artefacts**. They are listed
under §5 and §6 with the measurement that found them, and each is pinned by an
assertion so the fix shows up as a change to this suite.

---

## 0. The run

| | |
|---|---|
| host | x86_64 Linux 7.0.0-31-generic, 27 GiB RAM (shared, loaded: 15 GiB in use, swap full) |
| date | 2026-09-23 |
| bun | 1.3.14 |
| zig | 0.16.0 |
| zmx | `8bab1f0173b07e79835ea372d749af3dbf0d0842` + `patches/0001-supermux-session-contract.patch` (`6160737b…`) |
| helper | ABI 1, `fcae0efd…` |
| optimize | **ReleaseSafe** (see §1) |
| suite | `bun test tests/integration/zmx-workspace.test.ts` → **12 pass, 0 fail, 114 expect(), 23.04 s** |
| typecheck | `bun run typecheck` → clean except one pre-existing, unrelated error in `src/channels/web/project-routes.test.ts:258` |

macOS and Windows were **not** run: this host has neither. The suite gates
itself on `process.platform` and on the artifacts, so it skips there rather than
failing (Windows workspace terminals are `sessiond-term.ts`, tested separately).

### Prerequisite gating

The suite decides *before* `describe` whether it can run at all, and says so in a
test that always runs:

* missing `build/zmx/out/{bin,manifest.json}`, a manifest that does not describe
  the binaries on disk, a missing `supermux-terminal.wasm`, no `/bin/bash`, no
  `XDG_RUNTIME_DIR`, or a non-POSIX platform ⇒ an **unmet prerequisite**. Locally
  that is a skip with the reason printed.
* `MUX_ZMX_INTEGRATION=1` (set by the `zmx` CI job) turns every one of those into
  a **failure**. A build step that silently stopped producing the helper would
  otherwise leave a green job that tested nothing.

The CI job (`.github/workflows/ci.yml`, job `zmx`) runs
`scripts/build-zmx.sh --check-patches` (the gate `README.md` §5 said was wired up
nowhere), then builds zmx + the helper, then the reference wasm engine, then the
suite with `MUX_ZMX_INTEGRATION=1`.

### Isolation

Every target lives in a `mkdtemp` directory under `$XDG_RUNTIME_DIR/supermux/`,
`chmod 0700`, one per run. `ZMX_SESSION` and `ZMX_DIR` are deleted from the
environment before the first helper starts. Cleanup kills **only pids read back
out of that directory** — no `pkill`, no name matching. After the recorded run,
`/run/user/1000/supermux/` was empty and no `mux-zmx-helper` process remained.

---

## 1. The build was Debug, and that was a product defect

`scripts/build-zmx.sh` never passed `-Doptimize`, and `b.standardOptimizeOption`
defaults to **Debug**. Upstream's own README tells people to build
`-Doptimize=ReleaseSafe`. Measured on this host, writing to the pty from inside
the shell (`TIMEFORMAT`/`time` around the generator, so the number is the
shell's own blocking time, not the broker's):

| path | 1 MiB of shell output | effective rate |
|---|---|---|
| a plain pty (`script -qec … /dev/null`) | 0.152 s (4.2 MiB) | ~28 MiB/s |
| zmx **Debug**, one stock `zmx attach` client, no broker viewer | 24.23 s | ~43 KiB/s |
| zmx **Debug**, through the broker backend | 22.95 s (shell) / 23.27 s (broker) | ~45 KiB/s |
| zmx **ReleaseSafe**, through the broker backend | **0.080 s** (shell) / **0.206 s** (broker) | ~5.1 MiB/s |

287× at the shell, 113× end to end. A `make`, a `cat` of a log or a test run
would have taken minutes to draw. The no-client row is the important one: it
rules out the patch, the helper and the broker, and points at the optimize mode.

Fixed in this change: `scripts/build-zmx.sh` passes `-Doptimize=$ZIG_OPTIMIZE`
(default `ReleaseSafe`, override `MUX_ZIG_OPTIMIZE`) for **both** binaries, and
the manifest now records `"optimize"` — two binaries differing only in optimize
mode are otherwise indistinguishable to everything downstream. The zig **test**
steps stay in Debug, which is where a Zig test suite belongs; the 145/145 result
in `README.md` §4 is unaffected (no zmx source changed).

The 12 MiB flood in §5 completes in **1.692 s** on the ReleaseSafe build.

---

## 2. A target outlives its viewers; a closed one is never resurrected

`bash --norc --noprofile` behind a non-login-named wrapper, 100×30.

* created, session pid `3556523`, `/proc/3556523` present.
* attach → focus → `echo` → detach, **twice**. After each detach the socket
  directory still listed exactly one daemon, the same pid, with no viewers.
  Traces: `reset(1) replay-start(1) replay-end(1) owner(true)` then
  `reset(2) replay-start(2) replay-end(2) owner(true)` — a fresh epoch per
  attachment, never a reused one.
* the manager was then **reconstructed** (a new `ZmxWorkspaceBackend`, nothing
  carried over). `list("w:survival-0001")` found the target from the running
  daemon's `mux.target` label, and the shell's own `$$` came back **3556523** —
  the same process, not a new shell wearing the name.
* `close()` → `exists()` false, socket file gone.
* re-attach after the close ⇒ **`target-not-found`, `recoverable: false`**, and
  `list` stayed empty. The target was not recreated.

That last line needed a fix. `ipc.connectSession` (upstream) collapses every
connect failure except `ECONNREFUSED` into `error.Unexpected`, so a target closed
a moment earlier — the case `workspace-backend.ts` explicitly calls out, "closed
while the attach was in flight" — reached the broker as a **recoverable
`backend-unavailable`**, and a client told to retry a terminal that no longer
exists retries for ever. `cmdAttach` now also asks whether anything is still
listening at that path, and answers `target-not-found` when nothing is. (The
`message` is still the raw `Unexpected`; the *code* is what callers branch on.)

## 3. The focused viewer owns the geometry

Two viewers on one target; the pty's real size read back with `stty size` inside
the shell, which is the only authority on what reached `TIOCSWINSZ`.

| step | `stty size` |
|---|---|
| A focused at 120×40 | `40x120` |
| B (background) resizes to 60×20 **and types** | `40x120` — unchanged |
| B focused at 60×20, **no keystroke** | `20x60` |
| A (now stale) resizes to 200×50 | `20x60` — unchanged |
| A types `echo …` | delivered; `20x60` — unchanged |

B never received `owner(true)` while it was in the background, so upstream's
`isUserInput` promotion is genuinely gone: a background tab's keystroke no longer
takes the focused device's geometry with it. A's stale `reply()` returned
**false** (not delivered, and said so); B's `reply()` as owner returned true.
Traces: A `… owner(true) owner(false)`, B `… owner(true)`.

## 4. Restoration, decoded by the real client engine

The **reference engine is `apps/terminal-core/build/wasm/supermux-terminal.wasm`**
— the same module the browser client loads — driven from Bun through the
loader's exported `TerminalRuntime`. `loadRuntime()` itself is skipped because it
fetches over http(s) by design; the module is compiled from disk and handed to
the same binding. That is the cheapest reliable route: the Kotlin/Native and JVM
libraries would have meant a Gradle run per assertion, for the same VT engine.

History of 120 bold-red lines of `RED-ÜNÏÇØDE-日本語-🎉` (wide CJK + astral
emoji), then the alternate screen, then output generated **while nobody was
attached**.

**In the alternate screen** (replay 179 bytes):

* `alternateScreen: true`, `bracketedPaste: true` (the shell's own mode, carried
  across), cursor `(0, 5)`, visible.
* row 0 is `ALT-SCREEN-ÄÖÜ-🎈`, bold (`flags & 1`).
* `DETACHED-MARKER-A7`, printed 2 s after the last viewer left, is on screen.
* `historyRows: 0` — the alternate screen has no scrollback and the snapshot does
  not invent one.
* event order `reset(2) → replay-start(2) → replay-end(2)`, one epoch, and **no
  `output` before the boundary closed**.

**Back on the primary screen** (replay 7,937 bytes): `alternateScreen: false`,
top retained row `HIST-001 RED-ÜNÏÇØDE-日本語-🎉`, bold with fg `0xcc6666ff`
against `0x100000000` (DEFAULT) on an unstyled cell. Style, width and astral
codepoints all survive the round trip.

**Replay query responses never enter the pty.** The engine produced **no**
`Response` effects from either snapshot — ghostty's formatter emits state, not
the queries that produced it — and every `reply()` offered during the open
boundary, and every response offered from the engine, returned **false**. (A
re-attaching viewer is not the owner, so the owner rule is what fires first here;
the `replayClosed` rule itself is pinned in
`src/core/terminal/zmx/backend.test.ts:424`. See §6 for why it cannot be reached
end to end.)

## 5. Backpressure

12 MiB generated with `dd … | tr | fold` (bounded by `count`, never `yes`).

**A viewer that stops reading is dropped, recoverably.** `SIGSTOP` on the helper
process — the daemon's view of a wedged client:

```
flood 12,582,912 B generated, shell finished in 1,692 ms
reader viewer        12,709,061 B delivered
stalled viewer               44 B during the flood, 49,307 B in total
daemon log: broker viewer detached fd=10 reason=1 msg=pending output over cap
broker event: failure(backend-unavailable, recoverable=true)
              "zmx detached this viewer: resync_required (pending output over cap)"
no exit event
```

Peak queue: the cap is `ipc.BROKER_PENDING_MAX` = 1 MiB per broker viewer; the
stalled viewer ultimately received 49,307 bytes of the 12.7 MB — its queue was
**dropped**, not delivered late, which is the point (those bytes must not be
drawn over the next epoch). The shell and the reading viewer never noticed:
`echo STILL-ALIVE-42` came back immediately afterwards.

The detach **arrives late, and must**: the daemon states the reason on the same
socket the viewer had stopped reading, so the news only lands when the process
runs again. A stalled client cannot be told anything.

**Resume through a fresh restore**: a new attachment's replay showed
`STILL-ALIVE-42`, i.e. the latest state, with 3,836 history rows.

**The shell's own end**: `exit 7` produced exactly **one** `exit(known=true,
code=7, signal=null)` on the reading viewer, and `exists()` went false.
Separately, `SIGKILL` on the shell produced `exit(known=true, code=null,
signal=9)` — a signalled end is a signal, never exit code 0.

### DEFECT: a slow `emit` is not backpressure

`HelperHandlers.onOutput` is documented as *"Called in order; awaiting here is
the backpressure path."* It is not. Bun drains a subprocess pipe eagerly, so a
viewer whose callback never returns does not slow the helper at all:

```
flood                    12,582,912 B
delivered while stalled         157 B   (the callback was suspended)
delivered after resume   12,708,926 B   (every byte, late)
reader                   12,708,926 B
detach events                     0
helper RSS during the stall   2.5 MB    (flat — the helper never backed up)
```

The daemon's 1 MiB cap never fires, because nothing downstream of the helper is
slow *to the helper*. The bytes do not vanish — they accumulate **inside the
broker**, where nothing bounds them. That is precisely the cost the cap exists to
avoid, relocated to the process with the least to spare. A slow web socket on one
tab can therefore grow the broker without limit.

The fix belongs in `ZmxViewer`/`ZmxHelper`, not in the daemon: count the bytes
queued behind `emit` and drop the viewer with the same recoverable
`resync_required` failure once it passes a cap of our own. Pinned by
*"a slow EMIT is not backpressure: the broker buffers the whole flood for it"* so
the change is visible here.

### DEFECT: a viewer dropped during its restore is not told why

A viewer that attaches into a running flood and stops reading before its replay
boundary closes **is** dropped, recoverably — but what reaches the broker is

```
failure(backend-unavailable, recoverable=true) "daemon closed the connection"
```

not `resync_required`. The daemon logs the detach and queues `BrokerDetach` on
that viewer's socket, and by the time the viewer reads again the connection is
closed, so the stated reason does not survive the case it exists for. Both
outcomes are recoverable by re-attaching, so nothing is broken for a user today;
what is lost is the distinction the protocol went to some trouble to make. The
assertion accepts either string and records which one happened.

### Observed, benign: output can arrive before the first boundary

Under load the stalled-in-replay viewer's trace began

```
reset(pre-stalled-in-replay#12) replay-start(…) replay-end(…) reset(2) replay-start(2) …
```

The daemon broadcasts ordinary `.Output` to every connected client, including one
that has connected but not yet sent `BrokerHello`, so bytes really can precede
the first `BrokerReplayStart`. `ZmxViewer.#onOutput`'s synthetic empty epoch —
described in `backend.ts` as "a belt on a brace" — is load-bearing after all.
The contract holds (`output` never precedes a `reset`), at the cost of the client
drawing a few bytes it then discards on the real epoch.

## 6. Failure distinctions

| what was done | what the viewer got | what survived |
|---|---|---|
| `SIGKILL` the **helper** (pid 3564730) | `failure(backend-unavailable, recoverable=true)`, **no** `exit` | the target, and its neighbour in the same scope; re-attaching found the same shell |
| `SIGKILL` the **daemon** (pid 3564906 — the *parent* of the session pid `3564907`) | `failure(backend-unavailable, recoverable=true)`, **no** `exit` | the neighbouring target, whose viewer saw nothing at all |
| `SIGKILL` the **shell** (the session pid) | `exit(known=true, code=null, signal=9)`, exactly one | nothing — correctly |
| a manifest describing a different helper | `backend-unavailable`, **not** recoverable, naming both digests; nothing was exec'd | the running target, still answering commands |
| `close()` racing an in-flight `attachExisting` | `target-not-found` | the neighbouring target in the same scope |
| `closeScope()` while 695,449 bytes were draining | **no** `failure` and no `exit` — a close we asked for is not a loss | the other scope's target, exactly, and it still ran commands |

`zmx list` reports the **session** pid, which is the shell. The daemon is its
parent. Killing what `list` reports is the third row, not the second — a
distinction the first version of this suite got wrong, and the reason
`daemonPidOf()` reads `/proc/<pid>/status`.

### FIXED: the restore shipped scrollback and then erased it

The snapshot is *scrollback*, then a phase separator, then the *viewport*. That
separator was `ESC[2J ESC[H ESC[0m` alone (upstream's `serializeTerminalState`,
which the first version of this patch did not change), and `ED 2` erases the
screen **in place** instead of scrolling it off, so every scrollback line still
on screen when the separator arrived was wiped rather than becoming the client's
history. Measured on a 100×30 terminal:

```
history lines on the wire   98
history rows in the client  69      (= 98 − 30 + 1)
lost                        29
```

Exactly one screenful, every time — and a terminal whose scrollback was
**shorter than one screen came back with no history at all** (measured
separately: 14 lines sent, 0 retained). The bytes were paid for and thrown away.

`util.writeTerminalState` now ends the scrollback phase with **`rows`
linefeeds**, which push exactly those rows out of the viewport and into the
client's scrollback, and keeps the erase behind them, where it is a no-op at
equal geometry and still clears residue on a client taller than the daemon. The
same run, re-measured (2026-09-23, patch `705fd022…`, zig suite 146/146):

```
history lines on the wire   99
history rows in the client  99
lost                         0
top retained row            HIST-001 RED-ÜNÏÇØDE-日本語-🎉
```

Pinned as an equality (`historyRows === historySent`) plus the identity of the
oldest line, so a regression cannot pass by shipping a different number.

---

## 7. What was not tested, and why

* **macOS and Windows.** No host. The suite skips on Windows by design and would
  skip on macOS for want of the artifacts; `scripts/build-zmx.sh --target
  macos-arm64` cross-compiles but nothing here can run the result.
* **The staged-output cap in isolation.** Reaching it requires a snapshot larger
  than a socket buffer (~200 KiB); a realistic 120-line scrollback serializes to
  ~8 KiB, so the boundary closes before a stalled viewer can be caught inside it.
  What §5 exercises instead is a viewer stalled *around* a restore under load,
  which is the reachable version of the same case. The cap itself is covered by
  the daemon's own tests in `loop.zig`.
* **The `replayClosed` reply guard in isolation.** A viewer cannot be the focus
  owner during its own first replay: the `BrokerLease` that would make it the
  owner travels on the same stream, behind the replay bytes. So the owner rule
  always fires first end to end, and the replay rule is only reachable on a
  re-sync epoch delivered to a viewer that already owns the lease — which this
  daemon never issues. It stays pinned at the unit level
  (`backend.test.ts:424`).
* **A broker restart against live daemons.** Covered as far as the backend goes
  (§2 reconstructs the manager and re-discovers through the labels); restarting
  the actual broker process is a deployment operation this suite must not do.
* **`--stock-test` and the 145/145 zig run** were not re-run: no zmx source
  changed in this task, only the optimize mode the binaries are built with.
