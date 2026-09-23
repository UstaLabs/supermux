# terminal benchmark — 2026-09-23

Measured numbers for `dev.supermux.terminal:terminal-core` + `terminal-compose`, taken with
[`:terminal-sample:benchmark`](../README.md#the-benchmark).

> **Read the environment section first.** These numbers come from a shared, heavily loaded
> mini PC rendering in SOFTWARE, and the frame-time target is missed **before the terminal draws
> anything**. The control run is in here for exactly that reason: `ui − blank` is the terminal's own
> cost, and it is the only part of a frame time this machine can say anything honest about.

---

## 1. The machine, and what it was doing

| | |
|---|---|
| host | **GMKtec mini PC**, AMD Ryzen 5 3500U (4 cores / 8 threads, 1.4–2.1 GHz), Radeon Vega Mobile |
| OS | Ubuntu 26.04 LTS, kernel 7.0.0-31-generic |
| RAM | 27 GiB total — **~16 GiB in use and 7.2 of 8 GiB of swap already consumed** by other work on this box |
| JDK | OpenJDK 17.0.20 |
| display | **none.** Every desktop run is under `xvfb-run -s "-screen 0 1600x1200x24"` |
| renderer | **`SKIKO_RENDER_API=SOFTWARE`.** Skiko tries GL first and fails (`Cannot create Linux GL context` — Xvfb here has no usable GLX), so it falls back to the CPU rasteriser. Forcing it explicitly only makes the fallback deterministic |
| load average during the runs | **9.3 – 16.6** on 8 threads |

**This box is a shared build host, not a test device.** Other agent sessions compile, run Gradle and
run browsers on it throughout; the load averages above were measured immediately before each run and
are the reason every table below carries a wide max. Nothing here should be quoted as "the terminal
package performs like this" — they are this machine's numbers, on this day, under this load, with a
software rasteriser.

**What is NOT measured anywhere in this file:** a GPU-backed run, a 60 Hz phone, a Mac, an iOS
device, and Windows. See §7.

---

## 2. What each run does

Four terminals are mounted on one process; exactly one is **visible** (`active = true`, holding a
renderer lease) and **focused** (`session.focus(true)`, and it receives synthetic keys through the
same `TerminalAccessoryState` sink a hardware key takes). The other three stay composed and laid out
at the same 120x40 size with `active = false`, so they hold no lease and cost no frames while their
sessions keep parsing.

Two phases alternate until the clock runs out:

1. **stream** — the fixed 10 MiB fixture (generated once from a seed, replayed byte for byte) is fed
   into **every** session at the configured rate.
2. **scroll + stream** — the visible surface is scrolled through history at 8 rows per frame through
   its real `ScrollController` (the one `Modifier.scrollable` drives from a finger), up to the oldest
   retained row and back down, **while the stream keeps running**.

Frame time is the interval between consecutive `withFrameNanos` callbacks in a loop that re-arms
itself, so the host produces frames continuously and each interval is "how long did a frame take",
not "how long until something changed".

---

## 3. The control: what a frame costs here with **no terminal at all**

`--mode=blank --minutes=1 --warmup=5`: the same window, the same frame probe, nothing mounted.

| phase | frames | p50 | p95 | p99 | max | >16.7 ms | >33.3 ms | >100 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| blank control | 3565 | **15.03** | **28.71** | 55.66 | 145.5 | 756 (21%) | 124 | 1 |

p50 of 15.03 ms is the ~60 Hz frame clock doing its job. **p95 of 28.71 ms is not**: one frame in
five already misses the 16.7 ms budget with an empty window, and one frame in the minute took over
100 ms. That is this machine and its load, and it is the floor every number in §5 sits on.

---

## 4. The parse profile: what a frame costs **before anything is drawn**

`--mode=parse`: no window, no Compose. One session at 120x40 with a renderer that acknowledges as
fast as it can, fed 30 MiB of the fixture unthrottled. Both fixtures, so the cost of the escape
sequences themselves is visible.

| metric | `--fixture=ansi` | `--fixture=plain` |
|---|---|---|
| bytes fed | 30.00 MiB in **3149 ms** | 30.00 MiB in **1649 ms** |
| **throughput (parser + codec + session)** | **9.53 MiB/s** | **18.19 MiB/s** |
| frames published | 119 (all full), 264 346 B/frame | 117 (all full), 268 865 B/frame |
| **`ViewportModel.apply` p50** | **0.123 ms** | **0.143 ms** |
| `ViewportModel.apply` p95 | 0.217 ms | 0.312 ms |
| `ViewportModel.apply` max | 1.442 ms | 3.060 ms |
| engine history at end | 31 234 rows | 32 048 rows |
| RSS | 129 MiB → 263 MiB | 118 MiB → 264 MiB |

**Escape sequences cost about half the throughput**: the same 30 MiB of plain UTF-8 goes through at
18.19 MiB/s and the SGR/cursor/erase-laden version at 9.53 MiB/s. The row model does not care —
`apply` is within noise of itself either way, because what it copies is decoded cells, not bytes.

Two things this settles:

- **The snapshot copy is not the problem.** Folding a published frame into the row model costs
  ~0.12 ms at p50 and never more than 1.5 ms — under 1% of a 16.7 ms budget. Whatever a frame costs
  in §5, it is not this.
- **Every frame is `full`, and that is correct.** At 264 KB between frames, a 120-column screen has
  scrolled many times over, so *every* row really is dirty. Partial frames are what the engine
  produces when a program touches part of the screen; a firehose makes them meaningless by
  definition, not by bug. (The alternate-screen fixture in the sample, which repaints by cursor
  addressing, is where partial frames are the common case.)

---

## 5. The 10-minute run

```
--minutes=10 --warmup=15 --terminals=4 --fixture=ansi --rate=1048576
--stream-seconds=60 --scroll-lines=50000
```

Four terminals, 1 MiB/s each (**4 MiB/s aggregate**, a firehose by shell standards), one visible and
focused. The pane converged on the target: **120x40 cells, cell 8x22 px**.

### Frame times

| phase | frames | p50 | p95 | p99 | max | >16.7 ms | >33.3 ms | >100 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| stream | 1925 | **55.06** | **142.78** | 219.67 | 959.3 | 1886 | 1401 | 261 |
| scroll + stream | 8654 | **55.24** | **130.74** | 212.94 | 978.8 | 8534 | 6537 | 949 |
| warm-up (excluded) | 106 | 106.30 | 613.31 | 872.09 | 1223.9 | 106 | 96 | 57 |
| *(control: blank window, §3)* | *3565* | *15.03* | *28.71* | *55.66* | *145.5* | *756* | *124* | *1* |

**Scrolling costs nothing extra.** p50 55.24 ms while scrolling versus 55.06 ms while merely
streaming, and a *lower* p95 — the local-scroll path is arithmetic plus a paint offset, and it asks
the engine for a row only when a boundary is crossed. On a machine that could hold 60 Hz at all this
would be the headline result; here it is visible only as the absence of a difference.

### Throughput, queues, effects

| metric | value | against its bound |
|---|---|---|
| output fed | **2.55 GiB** (2 732 695 293 B) in 10 min | the requested rate, exactly: 242.10 MiB / 60.5 s and 242.03 MiB / 60.5 s per stream phase = **4.00 MiB/s** across four terminals, both times |
| input bytes (engine-encoded) | 578 | synthetic keys through the focused surface's accessory sink |
| **output queue high water** | **16 826 B in flight** | cap is **1 048 576 B per session** — 1.6% |
| backpressure | 14 718 waits, **45.6 s total** | across 4 producers over 600 s = **1.9% of producer time**. Applied, and rarely |
| **refused enqueues** | **0** | nothing was dropped |
| frames published | 5785 (5711 full) | see §4 on why "full" dominates under a firehose |
| observer gaps | 0 | the passive tap never fell behind |
| engine history | 30 925 rows, peak 31 307 | limits are 50 000 lines / 32 MiB — **the BYTE budget binds first**, and the line limit is never reached at 120 columns of this fixture |

### Memory over ten minutes

| t (s) | phase | RSS | heap | history rows | output so far |
|---:|---|---:|---:|---:|---:|
| 1 | stream | 700.52 MiB | 232.50 MiB | 31 207 | 2.26 MiB |
| 21 | stream | 1.07 GiB | 65.36 MiB | 31 071 | 84.14 MiB |
| 52 | stream | 1.11 GiB | 172.24 MiB | 31 295 | 208.82 MiB |
| 354 | scroll | 1.13 GiB | 78.07 MiB | 31 089 | 1.38 GiB |
| 405 | stream | 1.14 GiB | 119.56 MiB | 31 302 | 1.58 GiB |
| 652 | scroll | 1.15 GiB | 268.13 MiB | 30 925 | 2.54 GiB |
| 652 | after closing all four sessions | **1.04 GiB** | 268.64 MiB | 30 925 | 2.55 GiB |

RSS 700 MiB → 1.04 GiB, peak 1.15 GiB; heap peak 336 MiB (of a fixed `-Xmx2g`).

The shape is what matters: RSS climbs to ~1.07 GiB in the first **21 seconds** — that is four
scrollbacks filling to their 32 MiB byte budget plus the JVM taking its heap — and then adds
**~80 MiB over the remaining 10.5 minutes**, at a decaying rate (+40 MiB in the first 31 s of that,
+40 MiB over the next 600 s) while **2.5 GiB** of output flowed through. Closing the four sessions
returned 110 MiB immediately. History rows stay pinned at ~31 000 throughout, so the engine's
eviction is doing its job and nothing is accumulating per byte fed.

Ten minutes is not a leak test, and this does not prove there is no slow leak. What it does show is
that memory is **not** a function of bytes processed: 2.5 GiB of output moved the resident set by
under 8% after the initial fill.

### Phase log

```
repeat 1 stream: 242.10 MiB in 60524 ms (4.00 MiB/s across 4 terminals)
repeat 1 scroll: 62045 of 50000 lines at 8.0 rows/frame
repeat 2 stream: 242.03 MiB in 60508 ms (4.00 MiB/s across 4 terminals)
repeat 2 scroll: 24649 of 50000 lines at 8.0 rows/frame  (cut off by the run's 10-minute deadline)
```

A scroll phase sweeps to the oldest retained row and rides back down, so "62 045 lines" is two full
passes over everything the engine still had. The second phase was cut short by the clock, which the
harness reports rather than hiding.

### A saturation run, for contrast

The same shape at **4 MiB/s per terminal** (16 MiB/s aggregate, 2 min, 2026-09-23) is past this
machine's capacity — §4 puts one session's parse ceiling at 9.53 MiB/s — and it degrades the way it
should: p50 76.3 ms, p95 281.4 ms, **2.09 GiB accepted with 0 refused enqueues**, a 4 386 B queue
high-water, and 219 s of backpressure across four producers. The bound that gave way was
*throughput*, applied as backpressure onto the producer. Nothing was dropped and nothing grew
without limit.

---

## 6. Against the spec's targets

| target | result | verdict |
|---|---|---|
| **steady local scrolling p95 ≤ 16.7 ms on a 60 Hz test device** | scroll p95 **130.74 ms** here — but the blank control's p95 is already **28.71 ms**, and 21% of *empty-window* frames miss the budget | **NOT MET, and not measurable here.** There is no 60 Hz test device on this host; a software rasteriser on a loaded 4-core box cannot stand in for one. What the run does establish is that **scrolling adds nothing over streaming** (p50 55.24 vs 55.06 ms), which is the property the local-scroll design is supposed to have |
| **no >100 ms UI stall during the stream** | **261 frames over 100 ms** in 1925 stream frames (13.6%) | **NOT MET here.** The ladder: 1 in 3565 with an empty window (0.03%), 58 in 3640 with four terminals mounted and a static screen (1.6%), 261 in 1925 under the stream (13.6%). So it is the STREAM that produces the stalls, not merely mounting a terminal — and on a box where the empty window already stalls at all, how much of that survives a GPU is not knowable from here |
| **queues stay within configured byte caps** | high water **16 826 B** against a **1 048 576 B** cap (1.6%); **0 refused enqueues**; backpressure applied for 1.9% of producer time. Under 4x saturation: still 0 refusals | **MET, with room** |
| **no unbounded RSS growth over a 10-minute repeat** | +80 MiB after the first 21 s while 2.5 GiB flowed through, at a decaying rate; history pinned at ~31 000 rows; 110 MiB returned on close | **MET as far as ten minutes can show it.** Memory is not a function of bytes processed |

### Where the frame time goes

Profiled before optimising, as the task requires. Three runs of the same harness differing in exactly one thing each, so the ladder is measured
rather than inferred. All p50, same 120x40 grid, same four mounted terminals:

| run | p50 | delta | what the delta is |
|---|---:|---:|---|
| `--mode=blank` — empty window | **15.03 ms** | — | the compositor and the software rasteriser on this box |
| `--rate=1024` — four terminals mounted, screen essentially static (29 frames published in 2 min) | **28.38 ms** | **+13.35 ms** | **text layout + draw** of a 120x40 grid, repeated every frame |
| `--rate=1048576` — the 10-minute run, 4 MiB/s aggregate | **55.06 ms** | **+26.68 ms** | everything streaming adds: parsing on other threads competing for the same 4 cores, new text runs, layout-cache misses, and a screen that actually changes |

And, from `--mode=parse` (§4), the two stages that happen before a frame is drawn at all:

| stage | cost |
|---|---|
| parser + codec + session | **9.53 MiB/s** (ANSI) / **18.19 MiB/s** (plain), single session, unthrottled |
| snapshot copy (`ViewportModel.apply`) | **p50 0.123 ms**, p95 0.217 ms, max 1.44 ms |

**What not to optimise.** The snapshot copy is 0.12 ms — under 1% of a 16.7 ms budget and under
0.3% of what a frame actually costs here. The parser is not blocking anything either: the 10-minute
run sustained its requested 4 MiB/s exactly, with 0 refused enqueues and backpressure for 1.9% of
producer time. Neither is where the frame went.

**Where it did go**, in order: 15 ms of host floor, 13 ms of drawing a full grid, 27 ms of
everything the stream adds on a CPU-bound 4-core box at load 15. Two of those three are this
machine. Optimising the package against them would be optimising against llvmpipe and against the
other agents' Gradle daemons.

**So nothing was optimised in this task, deliberately.** The one number that is clearly the
package's own — +13.35 ms to lay out and draw 4 800 cells — is measured through a software
rasteriser, which is precisely the workload a GPU exists to remove. The next honest step is a
GPU-backed run and a 60 Hz device, not a code change.

---

## 7. What could not be measured, and why

| target | status |
|---|---|
| **Windows JVM sample** | **NOT POSSIBLE HERE.** There is no Windows machine on this host or reachable from it. The Linux JVM run is *not* substituted for it: the two differ in the skiko backend, the font stack and the JVM's own scheduling, and calling one the other would be a false claim. `:terminal-sample:run` / `runRelease` are plain `JavaExec` tasks with no OS-specific wiring, so they *should* work there, and that is a prediction, not a measurement |
| **iOS** | **NOT RUN.** The Mac (`ahmets-macbook-air`, 100.121.185.86) was offline on the tailnet for the whole task — `ssh` timed out and `tailscale status` reported `offline, last seen 3m ago` on every attempt. The iOS check is `:terminal-sample:linkDebugFrameworkIosSimulatorArm64` with `JAVA_HOME=/opt/homebrew/opt/openjdk@17`; it is written down in the sample README and has not been executed |
| **Android on a device** | **NOT RUN.** `:terminal-sample:assembleDebug` builds an APK that carries `libsupermux_terminal_jni.so` for `arm64-v8a` (1.9 MB) and `x86_64` (2.1 MB), which is a packaging check. Running it needs the Fold with wireless debugging enabled, which was not available |
| **Browser** | `:terminal-sample:wasmJsBrowserTest` runs the common suite (12 tests) in headless Chrome 148 — a compile-and-behave check, not a measurement. No frame times were taken in the browser |
| **GPU rendering** | **NOT MEASURED.** Skiko cannot create a GL context under this host's Xvfb, so every desktop number here is CPU rasterisation |
| **A 60 Hz test device** | **NOT AVAILABLE.** The spec's p95 ≤ 16.7 ms target is written against one; this box cannot stand in for it (§3) |
| **Screen readers** | TalkBack / VoiceOver were not run. The semantics tree is asserted headlessly by `:terminal-compose:jvmTest`; a real screen-reader pass needs a device |

---

## 8. Reproducing this

```sh
cd apps

# control — the host's own frame floor
SKIKO_RENDER_API=SOFTWARE xvfb-run -a -s "-screen 0 1600x1200x24" \
  ./gradlew :terminal-sample:benchmark -Pbenchmark.args="--mode=blank --minutes=1 --warmup=5"

# the middle rung — four terminals mounted, screen essentially static
SKIKO_RENDER_API=SOFTWARE xvfb-run -a -s "-screen 0 1600x1200x24" \
  ./gradlew :terminal-sample:benchmark \
  -Pbenchmark.args="--minutes=2 --warmup=8 --terminals=4 --rate=1024 --stream-seconds=120 --scroll-lines=1"

# parse profile — engine + codec + row model, no window (run it for both fixtures)
./gradlew :terminal-sample:benchmark -Pbenchmark.args="--mode=parse --fixture=ansi --minutes=1"
./gradlew :terminal-sample:benchmark -Pbenchmark.args="--mode=parse --fixture=plain --minutes=1"

# the 10-minute run
SKIKO_RENDER_API=SOFTWARE xvfb-run -a -s "-screen 0 1600x1200x24" \
  ./gradlew :terminal-sample:benchmark \
  -Pbenchmark.args="--minutes=10 --warmup=15 --terminals=4 --fixture=ansi --rate=1048576 \
                    --stream-seconds=60 --scroll-lines=50000 --out=run.md"
```

Swap `--fixture=ansi` for `--fixture=plain` for the escape-free stream, and `--rate=max` to find the
saturation point.
