# vendor/zmx — the supermux session-contract patch

A pinned source patch to upstream [zmx](https://github.com/neurosnap/zmx) that
gives supermux the session guarantees the stock CLI does not provide. Nothing
here is vendored source: `patches/` holds a diff, `upstream.lock.json` holds
the pin, and `scripts/build-zmx.sh` fetches, verifies, patches, builds and
tests into the gitignored `build/zmx/` cache. **No system zmx binary is ever
installed or replaced.**

| | |
|---|---|
| upstream | `neurosnap/zmx@8bab1f0173b07e79835ea372d749af3dbf0d0842` (v0.8.1, MIT) |
| ghostty (zmx's own pin) | `8af6897c0afc63037a8a3efee4162a380e3a4572` |
| zig | 0.16.0 (reuses `~/.local/zig/0.16.0`, provisioned by `apps/terminal-core/native/build.sh`) |
| optimize | `ReleaseSafe` (`MUX_ZIG_OPTIMIZE` overrides). **Not optional** — see `VERIFICATION.md` §1 |
| patch | `patches/0001-supermux-session-contract.patch` |
| touches | `src/ipc.zig`, `src/loop.zig`, `src/util.zig` — and nothing else |

`VERIFICATION.md` records the one run where all of this was exercised against
real daemons and real shells, and the four defects it found.

```sh
scripts/build-zmx.sh --check-patches   # verify the pin + patch (CI gate)
scripts/build-zmx.sh --stock-test      # unpatched baseline
scripts/build-zmx.sh                   # fetch + patch + build + test (zmx AND helper)
scripts/build-zmx.sh --target linux-x64 --test
scripts/build-zmx.sh --helper-only     # just the broker helper
```

The **broker helper** (`src/core/terminal/zmx/helper`) is built by the same
script, against this tree's own `src/ipc.zig`, and lands beside `zmx` in
`build/zmx/out/bin/` with a `manifest.json` naming the commit, the patch
sha256, the helper ABI and both binaries' digests. `--target` cross-compiles
(`linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`); the host target is
left unnamed on purpose so a native build cannot accidentally resolve a
different libc and recompile ghostty from scratch.

---

## 1. What zmx actually is

Read before touching anything. The plan that produced this task was written
from a source read; two of its assumptions were wrong, and this section is the
correction.

### One daemon, one socket, one session

`socket.zig:createSocket(sesh)` binds `getSocketPath(cfg.socket_dir,
session_name)` — literally `<socket_dir>/<session_name>`. Each session is a
separate double-forked daemon process owning one PTY and one listening socket,
and `loop.zig`'s own doc comment says why:

> Instead of a single daemon for all sessions, we create a daemon for every
> session. […] The ipc communication between session clients and the daemon
> doesn't need to be tagged with the session name. If a daemon crashes for one
> session won't crash all the other sessions.

**There is no upstream primitive where one socket dispatches to many named
targets.** `zmx list` enumerates the socket directory and connects to each
socket in turn (`ipc.probeSession` → `Info` + `LabelData`).

zmx enforces the `sun_path` budget itself: `socket.zig:max_socket_path_len` is
derived from `sockaddr_un.path`, `getSocketPath` returns `error.NameTooLong`,
and `maxSessionNameLen` reports the per-directory limit. It has its own tests
for all three. So the limit `src/core/terminal/zmx/names.ts` measured is real —
it is just already enforced one layer down.

### IPC framing

`ipc.Header` is `packed struct { tag: u8, len: u32 }` — a `u40` backing
integer, so `@sizeOf` is **8**, not 5, and the three tail bytes are padding on
the wire. Payload follows, `len` bytes, native endian (AF_UNIX, same machine).
`SocketBuffer.next()` re-frames a stream into messages. `Tag` is deliberately
non-exhaustive with a compile-time assertion to keep it that way, so an old
daemon ignores a tag it does not know instead of dying — which is what makes an
additive patch like this one safe against a partially-upgraded deployment.

Wire compatibility is treated as sacred upstream; `ipc.zig` carries a "WIRE
PROTOCOL FREEZE" block and tests pinning `@sizeOf(Info) == 552`,
`@sizeOf(Header) == 8` and every tag integer. This patch adds to those tests
rather than editing them.

### Who owns resize today

`Daemon.leader_client_fd`. It is set:

* on the first `.Init` when there is no leader (`handleInit`),
* on a `.Resize` when there is no leader (`handleResize`),
* and — the problem — **by any non-leader client whose `.Input` payload looks
  like typing**: `handleInput` calls `util.isUserInput(payload)`, which parses
  the bytes with a ghostty VT parser and promotes the sender on a printable
  character, CR/LF/tab/backspace, or a modified key.

Only the leader's `.Resize` reaches `TIOCSWINSZ`. On leader disconnect
`closeClient` clears the field and the next client to type claims it.

For a tty that heuristic is reasonable. For supermux it is not: a background
browser tab that sends one keystroke silently takes the geometry of the focused
device with it, and `isUserInput` cannot tell a keystroke from a terminal query
answer the client's own emulator produced — both arrive as `.Input`.

### The restore path

`handleInit` → if `has_pty_output and has_had_client` → `util.serializeTerminalState`:

* ghostty's `TerminalFormatter` in two phases (scrollback with `extra = .none`,
  then `\x1b[2J\x1b[H\x1b[0m`, then the viewport with modes / scrolling region /
  keyboard / screen transitions), plus colour overrides, OSC 7 pwd and OSC 2
  title appended by hand;
* formatted into a `std.Io.Writer.Allocating`, `dupe`d, and appended to the
  client's write buffer as **one `.Output` message**.

Two consequences. There is no marker: the client cannot tell the restore from
the program writing. And the buffer is unbounded — a 50k-line scrollback is
several MiB built into a `dupe` and then into a per-client `ArrayList` with no
ceiling. `zmx run`'s `.History` path has the same shape.

### What the daemon says when the shell dies

Nothing. PTY EOF sets `daemon.running = false`; the loop exits, the socket is
unlinked, clients see a closed connection. `waitpid` is called only in the
teardown `defer`, and its status is discarded. A closed socket is also what a
killed daemon, a crashed daemon and a dropped connection look like — four
different facts with one symptom. (`TaskComplete` exists but is `zmx run`-only:
it comes from scanning PTY output for an injected `ZMX_TASK_COMPLETED:` marker,
not from the process table.)

---

## 2. The decision: short opaque socket names, real names in the protocol

This is the open architectural question the reviewer flagged, and it is
**settled here as option (a), with the durability problem removed.**

A supermux key is `{ scope, terminalId }` — `"w:<uuid>"` plus a terminal id.
Hex-encoded it reaches ~93 characters, and `/run/user/1000/supermux/zmx/<name>`
is 121 bytes against a 103-byte portable budget. It does not fit, and
truncating would map two workspaces onto one shell — the one outcome we refuse.
A multiplexing front-end (option (b)) would mean re-tagging every message,
re-homing the PTY, and giving up zmx's crash isolation, for a problem zmx
already solves.

So:

* **The socket basename is short and opaque.** The helper mints it; it does not
  have to be derivable from the key, and it is not.
* **The real key travels in the protocol**, in `BrokerHello`'s optional name
  field, and the daemon stores it as the **label `mux.target`**.

Labels are the part that makes this work. They already exist upstream
(`LabelSet` / `LabelGet` / `LabelData`), they already live in the daemon, and
`zmx list` **already reads them from every socket it probes**. Discovery after
a broker restart is therefore a property of the running daemons, not of any
file the broker has to keep in sync with them — exactly what the
`WorkspaceTerminalBackend` contract demands ("the source of truth is the
running targets, not in-process state"). There is nothing to persist, nothing
to reconcile after a crash, and nothing that can go stale.

Verified live against the patched binary:

```
$ zmx list
  name=brk1  pid=3355436  clients=0  created=…  mux.target=muxterm_77_6d61696e
```

`list(scope)` is then: enumerate the socket directory, probe each socket, read
`mux.target`, `decodeName` it, keep the ones whose scope matches.

Label values are restricted upstream to `[A-Za-z0-9._-]`. Our encoded name is
`muxterm_<hex>_<hex>` — inside that alphabet by construction. A name that is
not is **refused, never mangled** (`handleBrokerHello` → `label.assertLabel`),
because a mangled name is two workspaces sharing one shell.

### What this means for `src/core/terminal/zmx/names.ts`

That file was written assuming one short server socket with target names in the
protocol. Half of that is right (names in the protocol) and half is wrong (one
socket). It has been corrected in the same change as this patch:

* `encodeName` / `decodeName` are unchanged, but they now encode/decode the
  **label value**, not the socket basename.
* `socketBasename(key)` is new: `mx` + 20 hex of SHA-256 over
  `scope \0 terminalId` — 22 characters, derived so `ensure` is idempotent
  across broker restarts without any stored mapping.
* `SERVER_SOCKET_BASENAME` / `serverSocketPath` are gone; `targetSocketPath`
  now returns `<dir>/<socketBasename(key)>`, and still validates both the label
  length and the `sun_path` budget *before* anything is created.
* `assertTargetMatches(key, label)` is new — see the collision note below.
* `keysFromNames` / `namesInScope` / `belongsToScope` operate on label values
  (what `list` reads back), not on socket names.

A hash can in principle collide. It is **detected, not merged**:
`assertTargetMatches` compares the `mux.target` label that came back against
`encodeName(key)` and throws `protocol` rather than handing two workspaces one
shell. Task 5 must call it on every attach — the socket name is a hash and
proves nothing by itself.

---

## 3. What the patch adds

All of it is opt-in. A connection is an ordinary zmx client until it sends
`BrokerHello`; nothing in the stock CLI sends it and no stock code path reads
these tags. `src/main.zig` is untouched, so there is no new subcommand and no
new flag — the helper (Task 4) connects to the session socket directly.

### Messages

Tags 22–33. Upstream 0–21 keep their meanings; a test asserts every upstream
tag is below the broker range so a future rebase cannot land one on top of ours.

| tag | dir | payload | meaning |
|---|---|---|---|
| 22 `BrokerHello` | C→D | `BrokerHello`(12B) + optional name | opt in; negotiate version; register `mux.target` |
| 23 `BrokerWelcome` | D→C | `BrokerWelcome`(24B) | accepted; carries `lease_gen` watermark, `pending_max`, `snapshot_max` |
| 24 `BrokerFocus` | C→D | `BrokerFocus`(12B) | claim (`active=1`) or release (`0`) the focus lease, with geometry |
| 25 `BrokerLease` | D→C | `BrokerLease`(16B) | the generation allocated, and the geometry actually applied |
| 26 `BrokerResize` | C→D | `BrokerResize`(16B) | geometry under a generation; stale ⇒ dropped |
| 27 `BrokerInput` | C→D | raw bytes | user keystrokes; always reach the PTY; never move the lease |
| 28 `BrokerReply` | C→D | `[u64 gen][bytes]` | a terminal query answer; owner-only |
| 29 `BrokerReplayStart` | D→C | `BrokerReplay`(16B) | restore opens; `epoch`, snapshot size |
| 30 `BrokerReplayEnd` | D→C | `BrokerReplay`(16B) | restore closes; same `epoch`, bytes sent |
| 31 `BrokerExit` | D→C | `BrokerExit`(8B) | **the target process ended**; at most once |
| 32 `BrokerFailure` | D→C | `[u16 code][u16 len][msg]` | handshake/protocol failure |
| 33 `BrokerDetach` | D→C | `[u16 code][u16 len][msg]` | this viewer is being dropped, and why |

Restore chunks between the two boundaries are ordinary `.Output` — they *are*
output, and reusing the tag keeps one output path in the helper.

### Version negotiation

`ipc.parseBrokerHello` reads magic and version **from the first six bytes and
nothing else**, and rejects an unsupported version there — before any later
field is touched, so a future larger payload is never reinterpreted through
today's struct. Only then is `@sizeOf(BrokerHello)` required, and `struct_len`
must match it exactly: a sender claiming v1 with a different struct size is not
speaking v1. A rejected peer gets `BrokerFailure{unsupported_version}` and is
hung up on — never downgraded, and nothing it says afterwards is acted on.

### Focus leases

Owner (`broker_owner_fd`) and generation (`broker_owner_gen`) live in the
**daemon**, with a monotonic `broker_lease_gen` that is never reset.

* An explicit `BrokerFocus` claim allocates a **fresh** generation — even for
  the viewer that already held it, because re-claiming is how a viewer says
  "everything I sent before now is void".
* `BrokerResize` / `BrokerReply` take effect only if *both* the fd is the owner
  *and* the generation is the outstanding one. Knowing the number is not owning
  the lease; holding the lease does not revive an old generation of your own.
* A broker that restarts reads the watermark out of `BrokerWelcome` and claims
  through the daemon. It cannot restart a counter of its own at a value still
  in flight.
* `BrokerInput` never moves the lease and an **empty input packet is an empty
  write** — not a claim, not a keep-alive. Claims are `BrokerFocus` and nothing
  else.
* Keystrokes (`BrokerInput`) and query answers (`BrokerReply`) are separate
  tags, so the daemon never has to guess from the byte pattern the way
  `isUserInput` must.
* `setLeader` refuses to move while a lease is held, so a stock `zmx attach`
  sharing the session can type — its text still reaches the PTY — without
  taking the focused device's geometry. Inert when no broker viewer exists.

On the owner leaving (detach, explicit release, or being dropped for overflow)
the lease goes to the most recently attached usable broker viewer, with a
**new** generation and a `BrokerLease` carrying the geometry the shell
currently believes. With no successor the session is simply unowned: replies
are discarded, the daemon answers device-attribute queries itself again, and
upstream's policy resumes.

### Atomic restoration boundaries

Every broker attachment opens one, including the first with nothing to restore
— an empty restore is a boundary, not a missing one. The snapshot is taken
while the daemon is processing client messages, which is always an
output-sequence boundary: the loop reads the PTY, feeds every byte to the
terminal and broadcasts it, and only then reads client messages. So there is no
half-consumed escape sequence in the terminal and no broadcast byte the viewer
has neither in its snapshot nor in its queue.

Live output arriving while a restore is still streaming is **staged**, not
appended, and spliced in after `BrokerReplayEnd` in its original order:

```
BrokerReplayStart(epoch) → Output* (snapshot)
  → BrokerReplayEnd(epoch) → Output* (what happened during the replay, in order)
  → Output* (everything after)
```

Content is ghostty's own formatter, unchanged: style, modes, cursor,
scrolling region, keyboard mode, screen transitions, scrollback, pwd, title.

### Bounded restore serialization

`util.serializeTerminalState` was split into `writeTerminalState(writer, term)`
— the same body, taking any `std.Io.Writer` — plus the original wrapper, which
still behaves exactly as before for every existing caller and test.

`serializeTerminalStateBounded` is the new path: a **bounded serialization
cursor** over `std.Io.Writer.fixed`, starting at 256 KiB and doubling to a 4 MiB
ceiling, where the formatter's `error.WriteFailed` *is* the cap being hit. It
returns `error.SnapshotOverflow` rather than a truncated success, so a partial
snapshot cannot be shipped by accident. The retry re-runs the formatter rather
than resuming it (`format` has no resumable cursor); doubling caps that at five
passes over the shipped range. Tested with 4000 lines of wide CJK + emoji.

The snapshot is built **outside** the client's write buffer and moved in 64 KiB
chunks as the socket drains, so a slow viewer applies backpressure instead of
growing the daemon.

### Scrollback that survives the restore

The two-phase snapshot ends phase 1 with **`rows` linefeeds** before the
`\x1b[2J\x1b[H\x1b[0m` separator. Upstream emitted the erase alone, and `ED 2`
clears the visible rows *in place*: every scrollback line phase 1 had just
drawn and that was still on screen was wiped instead of becoming the client's
history, so a client kept `sent - rows + 1` lines and a terminal with less than
one screenful of scrollback kept **none**. The linefeeds push exactly those rows
out of the viewport and into the scrollback, which is where they belong, and
leave the same blank viewport phase 2 needs. The erase stays behind them: a
no-op at equal geometry, and still the thing that clears residue on a client
taller than the daemon. Measured end to end in `VERIFICATION.md` §6 — 99 lines
sent, 99 retained — and pinned in `src/util.zig`'s own tests.

One upstream behaviour changed, deliberately: the synchronized-output
(DECSET 2026) save/restore is now a `defer`. Upstream skips the restore on its
formatter-error path; with a bounded writer "ran out of room" is an *ordinary*
outcome, and leaving DECSET 2026 cleared on the daemon's own terminal would
corrupt every later snapshot. There is a test for it.

### Queue limits and lifecycle

* Pending output per **broker** viewer is capped at 1 MiB (`BROKER_PENDING_MAX`),
  and staged output during a restore at the same. Past it the viewer is detached
  with `BrokerDetach{resync_required}` — queue dropped on purpose, since those
  are exactly the bytes it must not render on top of the next epoch. The shell
  and every other viewer keep running. **Stock clients keep the upstream
  unbounded path**, so the CLI is unchanged under load.
* A snapshot over the ceiling aborts *that viewer* with
  `BrokerDetach{snapshot_overflow}` and emits no `Output` at all.
* `BrokerExit` is sent **once**, on PTY EOF, after `waitpid`. `known=0` when no
  status could be reaped — a stopped child (`WIFSTOPPED`) and an unreaped one
  are reported as unknown rather than as exit code 0. **A lost socket is never
  an exit**, and a helper must not synthesise one from EOF: "your program
  ended" closes a tab, "I cannot see your program" retries.

  PTY EOF and the child becoming reapable **race** — the slave fd is released
  as the process dies — so a single `WNOHANG` lands early often enough to
  matter, and there is no second chance at the status: the message is
  once-only and the teardown `defer`'s `waitpid` status is discarded.
  `reportTargetExit` therefore retries `WNOHANG` for a bounded 200 ms
  (`EXIT_REAP_BUDGET_MS`, 5 ms steps) before giving up and saying `known=0`.
  The daemon is on its way out at that point and nothing else waits on the
  loop. Verified end to end: `exit 7` in the shell arrives at the helper as
  `{known:true, code:7, signal:null}`.
* Daemon shutdown is a *different* message (`BrokerDetach{daemon_shutdown}`),
  suppressed if an exit was already reported so the two cannot contradict each
  other. Both are followed by a bounded (300 ms) best-effort flush, because
  `shutdown()` closes the sockets.

---

## 4. Tests

Added tests live inside the patch, beside the code they cover: `src/ipc.zig`
(wire sizes, tag freeze, version negotiation), `src/util.zig` (bounded
serialization), `src/loop.zig` (the daemon state machine, driven through the
real handlers with real pipe fds as client sockets).

The upstream test target was discovered from `build.zig`:
`b.step("test", "Run unit tests")` over `src/test.zig`.

```sh
# baseline, pristine tree
cd build/zmx/upstream && zig build test --summary all
#   Build Summary: 41/41 steps succeeded; 95/95 tests passed

# patched
cd build/zmx/upstream && zig build test --summary all
#   Build Summary: 41/41 steps succeeded; 146/146 tests passed
```

(51 added: 95 upstream + 51 — the last two are "a dropped connection never
announces an exit" and "a viewer that leaves mid-replay takes its replay with
it", which pin by construction that only the two intended call sites can emit
`BrokerExit` and that a viewer's snapshot + staged output die with it. Both
runs on x86_64-linux, Zig 0.16.0, 2026-09-23; the patched number was 143
before those two, and 145 before "writeTerminalState scrolls the scrollback off
instead of erasing it".) `scripts/build-zmx.sh` wraps both — `--stock-test` and the default
— and passes `ZIG_GLOBAL_CACHE_DIR`/`--cache-dir` into `build/zmx/`.

A patch that applies is not validation, so there are two live checks. The
helper's own end-to-end smoke is
`src/core/terminal/zmx/helper/helper_smoke.py` (see §5); below is the
lower-level one that speaks the protocol to a real daemon socket with no
helper in the middle:

```sh
zmx run brk1 'sleep 120'
python3 vendor/zmx/tools/broker_smoke.py /run/user/1000/zmx/brk1     # PASS broker-wire
python3 vendor/zmx/tools/broker_smoke.py /run/user/1000/zmx/brk1 99  # PASS version-negotiation
```

It asserts, on the wire: the framing; `BrokerWelcome` with the right version,
struct size and watermark; a replay boundary with matching epochs and byte
counts; a focus lease taken with **no keystroke sent**, at the requested
geometry; input reaching the shell and echoing back as `Output`; a resize under
a stale generation being ignored (`tput cols` still reports the focused size);
and an unsupported version refused with `unsupported_version` and no
`BrokerWelcome`.

`scripts/build-zmx.sh --check-patches` was exercised against all four states:
clean pin + verified patch (pass), an unrelated edit in the upstream cache
(fail), a lockfile pinning a different commit (fail), and a tampered patch file
(fail, with the sha mismatch named).

---

## 5. Known gaps and things the next tasks must know

* ~~`reply()` semantics contradict the TS doc comment.~~ **Fixed in Task 4.**
  The Zig behaviour (owner-only; a non-owner's reply is discarded) is the
  intended one — several viewers render the same DA1 query and each answers
  it, and every answer after the first is read by the shell as typed input —
  and `src/core/terminal/workspace-backend.ts` now says so. The helper drops a
  non-owner's reply on its own side too, so nothing pretends it landed.
* ~~**`--check-patches` does not run in CI yet.**~~ **Wired up in Task 6.** The
  `zmx` job in `.github/workflows/ci.yml` runs it before it builds anything,
  then builds both binaries and the reference wasm engine and runs
  `tests/integration/zmx-workspace.test.ts` with `MUX_ZMX_INTEGRATION=1`, which
  turns a missing artifact from a skip into a failure.
* **The helper reads this patch now.** `src/core/terminal/zmx/helper` is built
  by `scripts/build-zmx.sh` against this tree's own `src/ipc.zig`, and
  `src/core/terminal/zmx/helper/helper_smoke.py` drives it against a real
  daemon. Nothing is installed system-wide: `build/zmx/out/` holds both
  binaries and the manifest the TypeScript side verifies.
* ~~**A viewer is never told it LOST the lease.**~~ **Decided in Task 5: no
  new message.** The daemon still sends `BrokerLease` to the winner only, and
  the broker DERIVES the loser from it — every broker viewer of a target is a
  helper of one process, so a lease landing on viewer X is proof that whoever
  the broker last saw own that target has lost it.
  `ZmxWorkspaceBackend.noteLease` is that deduction, and it is where the
  contract's `owner:false` comes from; `owner:true` is never derived, it is
  always the wire's own `lease`. A non-owner's `resize()` is not sent at all
  and its `reply()` returns false, so nothing pretends a dropped message
  landed. The one case this cannot see is a lease granted to a viewer in
  ANOTHER broker process; there is none, because the socket lives in our
  private 0700 directory and `setLeader` refuses to move a held lease.

* **`kill` is identity-checked now** (Task 5). It was the one path that could
  destroy a session without reading its `mux.target` label — the TS command
  type had no `name` field, so the helper's check could never fire from the
  broker — which made a socket-basename collision able to take another
  workspace's shell with it. `name` is required on both sides and an
  unverifiable session is left alone.
* **The two ghostty pins differ** (client `22391ed…`, zmx `8af6897…`). Harmless
  today because nothing shares VT state across them, but the snapshot the
  daemon produces is parsed by the client's emulator, so the pair needs a joint
  VT-behaviour check before this ships.
* **`zmx run` prints `unexpected errno: 6` (ENXIO)** and a stack trace from
  `posix.zig:1248` when the task's foreground process is probed. It is in
  `src/main.zig`, which this patch does not touch; see §4 for the
  stock-vs-patched comparison.
* ~~**Not exercised end to end**: the queue-overflow detach and the exit path.~~
  **Done in Task 6** — `tests/integration/zmx-workspace.test.ts`, written up in
  `VERIFICATION.md`. It found four things worth knowing before this ships:

  1. **The build was Debug** (`b.standardOptimizeOption` defaults to it and the
     script never passed `-Doptimize`), which ran the pty at ~43 KiB/s against a
     plain pty's ~28 MiB/s. Fixed: `ReleaseSafe`, 113× faster end to end, and the
     mode is now recorded in the manifest. §1.
  2. ~~**A slow `emit` is not backpressure.**~~ **Fixed.** Bun drains a
     subprocess pipe eagerly, so the daemon's 1 MiB cap never fired for a viewer
     whose callback was slow — 12 MiB piled up inside the broker instead.
     `ZmxViewer` now applies the same 1 MiB bound to what is queued behind
     `emit`, and drops the viewer with the same recoverable `resync_required`.
     §5.
  3. ~~**The restore ships scrollback and then erases it.**~~ **Fixed.**
     `ESC[2J` after the scrollback phase wiped it in place rather than scrolling
     it off, so the client kept `sent - rows + 1` lines and a terminal with less
     than one screen of scrollback kept none. The phase now ends with `rows`
     linefeeds and the erase behind them; re-measured 99 sent / 99 retained /
     0 lost. §6.
  4. **A viewer dropped during its restore is not told why** — the
     `BrokerDetach` is queued on the socket it stopped reading, and it sees a
     closed connection instead. **Left in the daemon** (`abortBrokerViewer`
     already drops the queue, queues only the detach and holds the socket open
     until it is written, which is enough for a viewer stalled OUTSIDE a
     restore; inside one it is not, and chasing it means another patch re-pin
     for a distinction both ends already treat as `recoverable: true`).
     The broker now annotates the failure with the one thing it does know —
     that the restore was still streaming — so the two cases are told apart in
     a log. §5.

  Two smaller things were fixed on the way: `cmdAttach` now answers
  `target-not-found` (not a recoverable `backend-unavailable`) when nothing is
  listening at the socket path, and `ZmxHelper` exposes its `pid`.
* **`test/*.bats`** is upstream's integration suite; `bats` is not installed on
  this host, so it was not run. It exercises the stock CLI only.
