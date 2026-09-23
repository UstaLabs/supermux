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
fast as it can, fed 30 MiB of the ANSI fixture unthrottled.

| metric | value |
|---|---|
| fixture | `ansi`, 10 MiB × 3 |
| bytes fed | 30.00 MiB in **3149 ms** |
| **throughput (parser + codec + session)** | **9.53 MiB/s** |
| frames published | 119 (all full — see below), 264 346 bytes per frame |
| **`ViewportModel.apply` p50** | **0.123 ms** |
| `ViewportModel.apply` p95 | 0.217 ms |
| `ViewportModel.apply` max | 1.442 ms |
| engine history at end | 31 234 rows |
| RSS | 129 MiB → 263 MiB |

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

<!-- HEADLINE-RUN -->

---

## 6. Against the spec's targets

<!-- TARGETS -->

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

# parse profile — engine + codec + row model, no window
./gradlew :terminal-sample:benchmark -Pbenchmark.args="--mode=parse --fixture=ansi --minutes=1"

# the 10-minute run
SKIKO_RENDER_API=SOFTWARE xvfb-run -a -s "-screen 0 1600x1200x24" \
  ./gradlew :terminal-sample:benchmark \
  -Pbenchmark.args="--minutes=10 --warmup=15 --terminals=4 --fixture=ansi --rate=1048576 \
                    --stream-seconds=60 --scroll-lines=50000 --out=run.md"
```

Swap `--fixture=ansi` for `--fixture=plain` for the escape-free stream, and `--rate=max` to find the
saturation point.
