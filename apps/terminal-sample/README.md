# terminal-sample — the standalone sample and the benchmark

A Gradle module that depends on **`:terminal-compose`** (and through it `:terminal-core`) and on
**nothing else of supermux**: no `:shared`, no `:ui`, no broker, no SSH, no pty, no
`ProcessBuilder`. That independence is the check, not tidiness — if the terminal package can only be
used from inside this repository, this module stops building.

Every byte any terminal here ever shows is generated in process
([`SampleFixtures.kt`](src/commonMain/kotlin/dev/supermux/terminal/sample/SampleFixtures.kt)), so a
run is reproducible from a seed and says something about the *engine* and the *surface* rather than
about whatever `/bin/bash` happened to print.

---

## The four fixtures

| fixture | what it is | what it exercises |
|---|---|---|
| **Shell-like output** | prompts, coloured `ls`, a `git status`, a build log with `\r` progress | the ordinary case, and the carriage-return rewrite that a whole-screen renderer gets wrong |
| **Mouse-enabled alternate screen** | a TUI on modes 1049 + 1002 + 1006 + 1007, repainting by cursor addressing | input ROUTING: while it runs the wheel and drags belong to the program, not to the local scrollback |
| **Unicode and styles** | CJK, combining marks, ZWJ emoji, box drawing, every SGR flag, all five underline kinds, the 256-colour cube, a truecolour ramp, OSC 8 + OSC 0 | cell occupancy, wide glyphs, grapheme clusters, style batching, and the two effects the surface reports to its host |
| **Sustained output** | dense log lines at the configured rate | throughput, scrollback eviction, and whether backpressure is actually applied |

Each is deterministic: `SampleFixture.stream(seed)` with the same seed produces byte-identical
output, which is what makes two runs comparable.

## The diagnostics are SAMPLE-ONLY

The panel shows grid size, output and input bytes, frame p50/p95/p99, the worst frame, stalls over
33 ms and 100 ms, published/full frames, engine history rows against the configured limits, the
output-queue cap and its high-water mark, backpressure waits, refused enqueues, effect counts,
heap/RSS and their peaks, and the session lifecycle totals.

**None of it lives in `:terminal-compose`.** A product terminal shows a terminal: it does not show
frame-time percentiles, and a renderer that carried that machinery would make every app that embeds
it pay for it. Everything here is built on the package's public seams:

- the session's **effects consumer** (`TerminalEffect.Input` bytes are, by definition, what a host
  would write back to the pty — the only honest definition of "input bytes" a terminal package can
  have),
- a **passive second collector** on `session.viewports`. It is a conflated `StateFlow`, so a second
  collector neither consumes frames nor acknowledges them — only the surface does that,
- **Compose's own frame clock** (`withFrameNanos`, re-armed in a loop),
- `/proc/self/status` for RSS, which is the only number that can see the *native* side: the engine's
  scrollback lives outside the managed heap, so a heap graph alone cannot see a terminal leak.

## Controls

| control | what it does |
|---|---|
| **reset** | `TerminalSession.reset()` (RIS) + a measurement reset; the producer restarts from the fixture's first byte |
| **hide** | takes the `Terminal` composable **out of the composition entirely**. The session keeps parsing — this is the case that proves the renderer is not load-bearing |
| **show** | puts it back. The remounted surface has no rows to patch, so it asks for a full frame (visible as `full` ticking up) |
| **dispose** | closes the session. The terminal is gone; **open** makes a new one |
| **panel on/off**, **stop/start probe** | the diagnostics panel, and the frame-time probe (which keeps the host producing frames continuously — that is what makes the percentiles meaningful, and why it is a toggle) |
| **1 / 4 terminals**, **#1..#4** | mount one or four terminals and choose which is visible. All four stay composed and laid out at the same size; only the visible one is `active`, so the rest release their renderer leases while their sessions keep parsing |
| rate slider | 4 KiB/s … 64 MiB/s, logarithmic, or unthrottled |

---

## Commands

All from `apps/`.

```sh
# Desktop sample — developer run (assertions on, no optimisation flags)
./gradlew :terminal-sample:run
./gradlew :terminal-sample:run -Psample.terminals=4

# Desktop sample — RELEASE run (see "What `release` means" below)
./gradlew :terminal-sample:runRelease

# Tests, desktop JVM: the fixtures and the measurement arithmetic (common), plus seven Compose
# UI tests that mount this app against a REAL engine and press reset / hide / show / dispose.
./gradlew :terminal-sample:jvmTest

# Android: builds the sample APK (device/emulator needed to RUN it)
./gradlew :terminal-sample:assembleDebug

# Browser: the same commonTest suite in headless Chrome (Karma)
./gradlew :terminal-sample:wasmJsBrowserTest
# …and to look at it:
./gradlew :terminal-sample:wasmJsBrowserDevelopmentRun

# iOS: on a Mac only (this Linux host disables the Apple compile/link tasks)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :terminal-sample:linkDebugFrameworkIosSimulatorArm64
```

On a **headless** host the desktop tasks need a display and, without a GPU, the software renderer:

```sh
SKIKO_RENDER_API=SOFTWARE xvfb-run -a -s "-screen 0 1600x1200x24" \
  ./gradlew :terminal-sample:runRelease
```

### The benchmark

```sh
SKIKO_RENDER_API=SOFTWARE xvfb-run -a -s "-screen 0 1600x1200x24" \
  ./gradlew :terminal-sample:benchmark \
  -Pbenchmark.args="--minutes=10 --terminals=4 --fixture=ansi --rate=1048576 \
                    --stream-seconds=60 --scroll-lines=50000 --out=run.md"
```

| option | meaning |
|---|---|
| `--mode=ui\|blank\|parse` | `ui` is the full harness; `blank` is the same window with **no terminal mounted** — the host's own frame floor on this machine; `parse` is no window at all: engine + codec + `ViewportModel.apply` |
| `--minutes=<n>` | wall-clock minutes to measure (warm-up is excluded) |
| `--fixture=plain\|ansi` | the fixed fixture, generated once and replayed byte for byte |
| `--rate=<bytes/s>` / `--rate=max` | per terminal |
| `--terminals=<1..4>` | all mounted; #1 is the visible one and the focused one |
| `--columns` / `--rows` | target grid (default 120x40); the harness converges its pane on it and the report quotes what it actually got |
| `--stream-seconds=<n>` | stream phase length before each scroll phase |
| `--scroll-lines=<n>` / `--scroll-rows-per-frame=<n>` | the scroll phase's target and its speed |
| `--fixture-bytes=<n>` | fixed fixture size (default 10 MiB) |
| `--warmup=<seconds>` | excluded from the report |
| `--out=<file>` | also write the markdown report there |

**Always run `blank` and `parse` beside a `ui` run.** A p95 frame time on an unknown machine means
nothing on its own; `ui − blank` is the terminal's own cost, and `parse` says how much of a frame is
not drawing at all. Recorded numbers and the environment they came from:
[`benchmarks/2026-09-terminal.md`](benchmarks/2026-09-terminal.md).

### What `release` means here

`:terminal-sample:runRelease` and `:terminal-sample:benchmark` run the same JVM configuration, and
it is spelled out in `build.gradle.kts` so a number can be traced to it:

- **not debuggable** — `debugOptions.enabled = false`, no JDWP agent, so nothing can attach and
  deoptimise a hot method under the measurement;
- **assertions off** — `-da -dsa` (Kotlin's `require`/`check` still run; they are plain code);
- **full tiered JIT** — explicitly *not* `-XX:TieredStopAtLevel=1`, the single biggest difference
  between a debug and a release JVM run;
- **G1 with a fixed heap** (`-Xms512m -Xmx2g`), so GC behaviour does not depend on how much RAM the
  machine happened to have free;
- **nothing instrumented** — no Compose hot reload agent, no tooling agent, no profiler.

There is no ProGuard/R8 step because a desktop JVM app has none: "release" on the JVM means the
runtime is unhobbled, not that the bytecode was rewritten. The window title and the report both say
which build produced them (`-Dsupermux.sample.buildType`).

---

## Platform status

| platform | what is checked here | what is not |
|---|---|---|
| desktop JVM (Linux) | `jvmTest` (19 tests, incl. 7 driving this app against a real engine), `run`, `runRelease`, `benchmark` — all run, all measured | a GPU-backed run (this host falls back to software rendering under Xvfb) |
| Android | `assembleDebug` builds an APK carrying `libsupermux_terminal_jni.so` for `arm64-v8a` and `x86_64` | running it: needs a device or emulator |
| browser (wasmJs) | `wasmJsBrowserTest` runs the common suite (12 tests) in headless Chrome | a measured browser run |
| iOS | nothing on this host — the Apple compile/link tasks are disabled on Linux | the framework link and a simulator run, both Mac-only |
| Windows | **nothing** — there is no Windows machine here. Not substituted with Linux; see `benchmarks/2026-09-terminal.md` | everything |
