# terminal-compose — the shared terminal surface

`dev.supermux.terminal:terminal-compose` is **one Compose Multiplatform composable** that draws a
[`terminal-core`](../terminal-core/native/README.md) session on a plain `Canvas`, for Android, the
desktop JVM, iOS and the browser. There is no DOM node, no Swing widget and no `UITextView`
anywhere in the rendering path: the only text drawing is `DrawScope.drawText` inside
`TerminalPainter`.

It depends on `terminal-core` and Compose and **nothing else** — no app theme, no host framework, no
design tokens. A separate build proves it: [`consumer-smoke/`](consumer-smoke/README.md) resolves
the published coordinates and draws a terminal with them, with no supermux source on its classpath.

```kotlin
implementation("dev.supermux.terminal:terminal-compose:0.1.0-dev.1")   // terminal-core comes with it
```

A running example of everything below: [`apps/terminal-sample`](../terminal-sample/README.md).

---

## Status — what has actually been run, and where

**Everything below this section is written in the present tense, and on one platform that is
earned.** This package has been exercised by 123 automated tests on the **desktop JVM, on Linux,
under `xvfb-run`, with software rasterisation**. It has **never run on a device**: not an Android
phone or tablet, not an iPhone or iPad, not a browser, not Windows, not on any GPU. Where a
behaviour below is platform-specific, read this table first.

| platform | what has run | what has not |
|---|---|---|
| desktop JVM (Linux) | `:terminal-compose:jvmTest` — 123 tests (114 `jvmTest`, 9 `commonTest`), driving the composable against a real `terminal-core` engine: input routing, scrolling, selection, IME, the semantics tree | a GPU-backed run; this host's Xvfb cannot create a GL context, so every frame is CPU-rasterised |
| Android | compiles and publishes; `:terminal-sample:assembleDebug` packages the JNI engine for `arm64-v8a` and `x86_64` | **every runtime behaviour**. No emulator, no device. The soft-keyboard IME path in §6 has run only against the *desktop* JVM's key/commit events |
| browser (wasmJs) | the `terminal-core` common suite runs in headless Chrome | this composable in a browser, at all. No Safari/WebKit run of anything (Safari automation is off on this host) |
| iOS / iPadOS | nothing — the Apple compile and link tasks are disabled on Linux | the framework link, a simulator run, a device run, and every touch behaviour below |
| Windows | nothing. There is no Windows machine here, and Linux is not substituted for it | everything |

**`:terminal-compose:jvmTest` is FLAKY on this host.** Four runs on 2026-09-23: two green at 123/123,
two failing **one test each, a different one each time** —
`InputPolicyTest.anApplicationWheelIsEncodedByTheEngineAndNeverScrollsHistory` (*"the program's
wheel also scrolled the surface", expected row=188 got row=0*) and
`InputPolicyTest.aLongPressIsNeverAButtonTheProgramSees` (*"the release was not reported"*). Both
read a value that a fed escape sequence has not necessarily been applied to yet, so they are races
in the tests, not findings about the surface — but a suite that fails one in two runs cannot
distinguish itself from one that found something, and it is not fixed here.

Three specific claims made below as fact are the ones to treat as **design intent pending a device**:

- **§6, composed text (IME).** `TerminalImeState` and `TerminalTextGate` are covered by 19 JVM
  tests, driven by synthetic key and commit events. A real soft keyboard — Gboard, the iOS
  keyboard, a CJK IME, dictation, swipe — has never reached this code. The "one keystroke, one
  character" gate in particular is tuned against the *desktop* duplicate-event pattern.
- **Touch selection handles.** The two-handle long-press selection described under *What the
  surface does for you* has been driven only by synthesized pointer events. No finger has touched
  it, and handle ergonomics are exactly the kind of thing that only a device shows.
- **The accessibility tree.** `TerminalAccessibilityTest` (8 tests) asserts the semantics nodes and
  their actions headlessly. **TalkBack and VoiceOver have not been run**, so what a screen reader
  actually announces from this tree is unverified.

None of this is a reason not to use the package. It is the difference between "tested" and "tested
on the desktop JVM", which the rest of this document was not making.

---

## 1. Initialization

The **host owns the session**: it opens it, feeds it from its transport, and closes it. The
composable owns only what it draws.

```kotlin
@Composable
fun MyTerminalPane(transport: Transport) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }

    DisposableEffect(transport) {
        val job = scope.launch {
            session = try {
                TerminalSession.open(
                    // The surface resizes this to whatever actually fits as soon as it is
                    // measured, so the initial grid only has to be plausible.
                    size = TerminalSize(columns = 80, rows = 24, cellWidthPx = 8, cellHeightPx = 16),
                    limits = TerminalLimits(historyLines = 50_000, historyBytes = 32L * 1024 * 1024),
                    colors = MyTerminalTheme.engineColors(),
                    effects = { effect -> transport.onEffect(effect) },   // see §4
                )
            } catch (error: TerminalEngineUnavailableException) {
                failure = error                                           // see §2
                null
            }
        }
        onDispose {
            job.cancel()
            val open = session
            session = null
            scope.launch { open?.close() }                                // see §5
        }
    }

    val open = session
    when {
        failure != null -> TerminalUnavailable(failure!!)
        open == null -> TerminalStarting()
        else -> Terminal(session = open, theme = MyTerminalTheme, modifier = Modifier.fillMaxSize())
    }
}
```

**In the browser, and only there**, the engine is a WebAssembly module that must be fetched and
compiled first. Await it once at app start; on Android, the JVM and iOS it is a no-op, so shared
code may call it unconditionally:

```kotlin
suspend fun bootstrap() {
    TerminalRuntime.initialize()   // no-op off the browser; throws the typed error below on failure
}
```

A browser host must also re-export two files as its own `wasmJs` resources — the Kotlin/Wasm
toolchain does not copy a dependency klib's resources next to the consumer's module, and bundling
fails with `Can't resolve './terminal-loader.mjs'` otherwise. The recipe is in
[`terminal-core/consumer-smoke/README.md`](../terminal-core/consumer-smoke/README.md); a worked copy
is in [`terminal-sample/build.gradle.kts`](../terminal-sample/build.gradle.kts).

## 2. Errors

There are exactly two kinds, and they arrive in two different places.

**The engine never started.** `TerminalSession.open` (really `createTerminalEngine`) throws
`TerminalEngineUnavailableException`, whose `reason` says why — `NOT_LINKED`,
`UNSUPPORTED_PLATFORM`, `MISSING_BINARY`, `CORRUPT_BINARY`, `ABI_MISMATCH`,
`INITIALIZATION_FAILED`, or `NOT_INITIALIZED` (browser: `TerminalRuntime.initialize()` has not
completed). On Android, the JVM and iOS this is sticky for the process — the native library is
loaded once — so retrying does not help. In the browser a failed `initialize` is **not** cached and
may be retried.

```kotlin
@Composable
fun TerminalUnavailable(error: Throwable) {
    val reason = (error as? TerminalEngineUnavailableException)?.reason
    Text(
        when (reason) {
            TerminalEngineUnavailableException.Reason.NOT_INITIALIZED ->
                "The terminal engine is still loading."
            TerminalEngineUnavailableException.Reason.UNSUPPORTED_PLATFORM,
            TerminalEngineUnavailableException.Reason.MISSING_BINARY ->
                "This build has no terminal engine for ${hostPlatform()}."
            else -> "The terminal engine could not start: ${error.message}"
        },
    )
}
```

**The session died while running.** The surface reports it through `onFailure`, at most once, and
**keeps showing the last frame it drew** — a frozen screen with an explanation beats a blank one.
This callback exists because a renderer only ever uses the non-blocking calls, so without it a dead
terminal is just a screen that stopped changing:

```kotlin
Terminal(
    session = session,
    onFailure = { error -> tab.markDead(error) },
    overlay = { if (tab.dead) DeadTerminalBanner(onRetry = ::reopen) },
)
```

The same error is also on `session.failure` (a `StateFlow`), `session.close()` rethrows it, and
every suspending call throws it. *Recoverable* engine errors — a dropped effect, an allocation
failure — go to `open`'s `onEngineError` instead and the session carries on.

## 3. Reset

`TerminalSession.reset()` is a full reset (RIS): the engine keeps its size, colours and limits and
throws away the screen and the scrollback. The surface needs nothing from you — every row changes,
so the next frame carries the whole screen.

```kotlin
scope.launch { session.reset() }
```

Re-theming is a different operation and does **not** need a reset. The engine resolved palette
indices and OSC colours itself, so a theme change has to reach it before the next paint or half the
screen keeps the old palette — the composable does that for you whenever `theme` changes, but a
host that changes colours without changing the `TerminalTheme` object must call
`session.colors(...)` itself.

If you bind a surface to a *different* session, just pass the new one: everything the composable
remembers is keyed on `session`, so the old model, scroll anchor, selection and IME state go with
it.

## 4. Effects

The host creates the session, so **the composable never sees effects on its own**.
`TerminalEffect.Response` and `TerminalEffect.Input` bytes are the host's to write to its
transport; `Title`, `Bell` and `ClipboardRequest` are information.

To make the surface's `onTitle` / `onClipboard` callbacks fire, install a relay:

```kotlin
val relay = remember { TerminalEffectRelay() }
val session = remember {
    TerminalSession.open(
        size = size,
        effects = relay.wrap { effect ->
            when (effect) {
                is TerminalEffect.Response -> transport.write(effect.bytes)
                is TerminalEffect.Input -> transport.write(effect.bytes)
                is TerminalEffect.Bell -> host.bell()
                else -> Unit
            }
        },
    )
}

CompositionLocalProvider(LocalTerminalEffects provides relay) {
    Terminal(
        session = session,
        onTitle = { tab.title = it },
        // An OSC 8 hyperlink the USER activated. The surface never opens anything itself — what a
        // URI means is the host's decision, and a link that arrived in a build log is not a reason
        // to open anything.
        onLink = { uri -> if (host.trusts(uri)) host.open(uri) },
        // An OSC 52 request from the PROGRAM: reported and nothing more, DEFAULT DENY. The surface
        // never puts a program's text on the clipboard. Reads are informational — the engine
        // already denied them synchronously, because it cannot wait for an embedder to answer.
        onClipboard = { request -> if (request.write) host.confirmCopy(request.text) },
    )
}
```

Without a relay, `onTitle` and `onClipboard` simply never fire; the surface reports only what it is
actually told. `wrap` keeps your own consumer in the chain and never changes ordering — effects
still run on the session's owner coroutine, in queue order.

## 5. Lifecycle

**`active`, not unmounting, is the off-screen switch.** A background tab passes `active = false`:
the surface releases its renderer lease, so it stops asking the session for frames, cancels any
fling, and takes no focus and no input. The session keeps parsing output and delivering effects, and
any *other* surface on the same session keeps receiving frames. It is not a close and it never
resizes the session.

```kotlin
Terminal(session = session, active = tab.isSelected)
```

**Several surfaces may share one session** — a split view, a detached window. Each takes its own
`RendererLease`, so one going away (or inactive) never stops the frames the others are watching.
Frames are published to a conflated `StateFlow`, so a slow surface can be handed a partial frame
whose base it never saw; `TerminalViewport.sequence` is how it notices, and it asks for a full frame
itself. Nothing is required of the host.

**Unmounting is safe and cheap.** Leaving the composition unsubscribes and stops asking for frames;
it never closes or resizes the session. Remounting asks for a full frame and shows the up-to-date
screen. `apps/terminal-sample`'s *hide* / *show* controls do exactly this.

**Closing is the host's job, and the only one that must happen**: `session.close()` releases native
memory. It rethrows a fatal engine error, so a host that wants a quiet shutdown wraps it:

```kotlin
onDispose {
    scope.launch { runCatching { session.close() } }
}
```

`close` says nothing about the remote program: the package owns no transport, opens no socket and
emits no "exit" event of its own.

---

## 6. Composed text (IME)

Nothing is required of the host here — the surface owns its own text input — but two behaviours are
worth knowing about, because both are visible to the user.

**A preedit byte is never sent.** Turkish dead keys, CJK candidates, emoji pickers, dictation and a
software keyboard's own rewriting all show the user characters *before* they are final. The surface
holds the composing text apart from the terminal's content and draws it at the cursor itself; the
program hears exactly one thing, the committed string. See `TerminalImeState`.

**One keystroke, one character.** A hardware key press can arrive twice — once as a key event and
once as an IME commit of the same character (Android does this, and so does a desktop's key-typed
event). `TerminalTextGate` drops the second copy: a press records the text it submitted, and a
commit of exactly that text is its echo. The record is a small bounded queue, not a single slot,
because an echo can lag several presses behind; and suppression is deliberately conservative — a
commit is dropped only when a pending echo matches it exactly, and a commit that matches nothing
retires the stale entries behind it. When the two are genuinely indistinguishable the gate errs
towards sending: an extra character is visible and deletable, a swallowed keystroke is not.

**Known limitation — a correction that arrives after the word.** Committed text is on the pty
immediately, so a keyboard that later revises a word it already gave us (autocorrect landing late,
a swipe or dictation rewrite) cannot be applied: the user sees the original followed by the
correction, `teh the` rather than `the`. A terminal cannot un-send bytes, and synthesizing
backspaces would corrupt everything that is not a line editor — a full-screen UI, a password
prompt, a program in raw mode — so the surface does neither. It asks the platform for no
autocorrect, no capitalization and no suggestions, which is what keeps it rare; the behaviour is
pinned by `TerminalImeTest.aRevisionAfterTheCommitIsAppendedBecauseBytesCannotBeUnsent`.

---

## What the surface does for you

Collecting and acknowledging frames, measuring the grid and resizing the session once per distinct
size, smooth local scrolling with a fling (a `scrollTo` reaches the engine only when a **row**
boundary is crossed; everything in between is a paint offset, and nothing reaches the host or the
program), input routing by the terminal's negotiated modes, IME composition without ever sending a
preedit byte, selection with mouse, long-press and two touch handles copied through the *engine* so
wraps and grapheme clusters come out right, clipboard, OSC 8 links, and an accessibility tree with
stable copy / paste / scroll / focus actions and deliberately no live region.

Each of those is documented on the declaration that implements it; start at
[`Terminal.kt`](src/commonMain/kotlin/dev/supermux/terminal/compose/Terminal.kt).

Three of them — the touch handles, the soft-keyboard side of the IME, and the accessibility tree —
have been asserted only on the desktop JVM, headlessly. See **Status** at the top.

## Publishing

```sh
cd apps
./gradlew :terminal-core:publishAllPublicationsToLocalTestRepository
./gradlew :terminal-compose:publishAllPublicationsToLocalTestRepository
./gradlew -p terminal-compose/consumer-smoke jvmTest
```

Nothing is uploaded anywhere: the one repository declared is a git-ignored directory inside
`build/`. The two packages are versioned and released as a **pair** — this module's POM carries a
dependency on `terminal-core:<the same version>` and `-Pterminal.version=` moves both. Two gates
enforce that, and the order above is not a suggestion:

- **`verifyPairedVersion`** fails the publish when the two modules are *configured* at different
  versions. It compares two strings in one Gradle invocation, and that is all it can do.
- **`verifyPairedCoreArtifacts`** fails the publish when `terminal-core` is not actually **in** the
  local test repository at that version — the failure the version check cannot see, and the one you
  get by publishing this module alone. It checks the root coordinate's POM and module metadata and
  then follows every `available-at` redirect in that metadata, which is what a consumer's resolution
  follows, so it adapts to whichever targets the core publish contained (the Apple redirects are
  exempt off a Mac). To see it work, point it at a repository that has no core in it:

  ```sh
  ./gradlew :terminal-compose:publishAllPublicationsToLocalTestRepository -PterminalCoreRepo=/tmp/empty
  ```

Every publish task also runs `terminal-core`'s own native gate first, because a surface whose engine
cannot start is a surface that draws nothing.

A publish made on Linux contains **no Apple publications** (their Kotlin targets are disabled
there); the Apple artifacts come from a Mac-made publish, exactly as for `terminal-core`
([`VERIFICATION.md`](../terminal-core/VERIFICATION.md) §4).
